package com.ambitiousconcepts.opsatlas.catalog.web;

import com.ambitiousconcepts.opsatlas.catalog.api.RegistrationOutcome;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceCatalog;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceDetail;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceRegistration;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceSummary;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalog API.
 *
 * <p>Registration takes the manifest as the request body with a YAML content
 * type, rather than a JSON envelope with the document as a string field. A
 * service.yaml escaped inside JSON is unreadable in a terminal, in a log and in
 * a bug report, and every newline becomes a chance to corrupt it. This way the
 * request is the file:
 *
 * <pre>
 * curl -X POST localhost:8080/api/v1/services \
 *   -H 'Content-Type: application/yaml' \
 *   --data-binary @examples/services/orders-api.yaml
 * </pre>
 *
 * <p>The repository a service belongs to comes from {@code metadata.repository}
 * inside the manifest, so it is never passed alongside and cannot disagree with
 * the document.
 */
@RestController
@RequestMapping("/api/v1/services")
class ServiceController {

    // No @Validated on this class. That annotation routes validation through a
    // proxy which raises ConstraintViolationException, a type with no HTTP
    // meaning; without it, Spring's built-in method validation raises
    // HandlerMethodValidationException, which carries the parameter name and is
    // what ApiExceptionHandler turns into a 400 naming the parameter.

    /** Deliberately modest. A caller wanting more pages asks for more pages. */
    private static final int DEFAULT_LIMIT = 25;

    private static final String DEFAULT_SOURCE_PATH = "service.yaml";

    private final ServiceCatalog catalog;
    private final ServiceRegistration registration;
    private final CurrentPrincipal currentPrincipal;

    ServiceController(ServiceCatalog catalog, ServiceRegistration registration, CurrentPrincipal currentPrincipal) {
        this.catalog = catalog;
        this.registration = registration;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    PageResponse<ServiceSummary> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(100) int limit) {
        // The organization is read here and passed down explicitly. No layer
        // below this one infers it. See docs/adr/0003-org-scoping-stub.md.
        return catalog.list(currentPrincipal.get().orgId(), cursor, limit);
    }

    @GetMapping("/{slug}")
    ResponseEntity<ServiceDetail> get(@PathVariable String slug) {
        ServiceDetail service = catalog.get(currentPrincipal.get().orgId(), slug);
        return ResponseEntity.ok().eTag(etag(service)).body(service);
    }

    /**
     * Register a service from its manifest.
     *
     * <p>201 for a new service, 200 for a replay of a byte-identical manifest
     * already registered from the same location. A retried request is not an
     * error, and it is not a creation either.
     */
    @PostMapping(consumes = {"application/yaml", "text/yaml", "application/x-yaml", "text/plain"})
    ResponseEntity<ServiceDetail> register(
            @RequestBody String document,
            @RequestParam(defaultValue = DEFAULT_SOURCE_PATH)
                    @Pattern(
                            regexp = "^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$",
                            message = "must be a relative path inside the repository, such as service.yaml")
                    String sourcePath,
            @RequestParam(required = false) String sourceRef) {
        RegistrationOutcome outcome =
                registration.register(currentPrincipal.get().orgId(), document, sourcePath, sourceRef);
        ServiceDetail service = outcome.service();

        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/api/v1/services/" + service.slug()))
                .eTag(etag(service))
                .body(service);
    }

    /**
     * Replace a registered service's manifest.
     *
     * <p>{@code If-Match} is required, not optional. An update with no stated
     * version cannot know what it is overwriting, so it is refused with 428
     * rather than applied.
     */
    @PutMapping(
            path = "/{slug}",
            consumes = {"application/yaml", "text/yaml", "application/x-yaml", "text/plain"})
    ResponseEntity<ServiceDetail> update(
            @PathVariable String slug,
            @RequestBody String document,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestParam(required = false) String sourceRef) {
        ServiceDetail service = registration.update(
                currentPrincipal.get().orgId(), slug, document, parseIfMatch(ifMatch), sourceRef);

        return ResponseEntity.ok().eTag(etag(service)).body(service);
    }

    /**
     * Remove a registered service.
     *
     * <p>{@code If-Match} is required, as it is on update. Deleting something
     * that has changed since it was read is the one mistake in this API that
     * cannot be undone from inside the system, so a delete with no stated
     * version is refused with 428 rather than applied.
     *
     * <p>Answers 204. The audit entry survives the row it describes, because
     * {@code audit_event} declares no foreign key to {@code service} - so "what
     * happened to orders-api" stays answerable after orders-api is gone.
     *
     * <p>Consider {@code spec.lifecycle: retired} instead. It keeps the entry,
     * its scorecard history and its probe record, and is what the catalog is
     * built for; this is for a registration that should not exist.
     */
    // Declared rather than returned through a ResponseEntity, so the generated
    // contract says 204. Springdoc cannot infer a status from ResponseEntity<Void>
    // and documented it as 200, which would have been the contract stating
    // something the endpoint does not do.
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{slug}")
    void delete(
            @PathVariable String slug,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        registration.delete(currentPrincipal.get().orgId(), slug, parseIfMatch(ifMatch));
    }

    private static String etag(ServiceDetail service) {
        return "\"" + service.version() + "\"";
    }

    /**
     * Read the version out of an {@code If-Match} header.
     *
     * @return the version, or null when the header is absent or unusable - both
     *     of which the service layer treats as "no precondition stated", so an
     *     unparseable header can never be mistaken for a matching one
     */
    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
