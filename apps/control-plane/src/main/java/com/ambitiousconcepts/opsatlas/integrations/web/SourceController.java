package com.ambitiousconcepts.opsatlas.integrations.web;

import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceCatalog;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceView;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repositories OpsAtlas watches.
 *
 * <p>OpsAtlas only ever reads from these. Nothing here, and nothing it calls,
 * can write to a monitored repository — see ADR 0008 for why that is worth
 * giving up webhook latency for.
 */
@RestController
@RequestMapping("/api/v1/sources")
class SourceController {

    private static final int DEFAULT_LIMIT = 25;

    private final SourceCatalog catalog;
    private final CurrentPrincipal currentPrincipal;

    SourceController(SourceCatalog catalog, CurrentPrincipal currentPrincipal) {
        this.catalog = catalog;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * @param provider which reader to use; only {@code github} exists today
     * @param repository {@code owner/name}
     * @param ref a branch, tag or commit. {@code HEAD} means the default branch.
     * @param path where the manifest lives in the repository
     */
    record WatchRequest(String provider, String repository, String ref, String path) {}

    @PostMapping
    ResponseEntity<SourceView> watch(@RequestBody WatchRequest request) {
        SourceRef ref = parse(request);
        SourceView source = catalog.watch(
                currentPrincipal.get().orgId(),
                request.provider() == null || request.provider().isBlank() ? "github" : request.provider(),
                ref);

        return ResponseEntity.created(URI.create("/api/v1/sources/" + source.id())).body(source);
    }

    @GetMapping
    PageResponse<SourceView> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(100) int limit) {
        return catalog.list(currentPrincipal.get().orgId(), cursor, limit);
    }

    @GetMapping("/{id}")
    SourceView get(@PathVariable UUID id) {
        return catalog.get(currentPrincipal.get().orgId(), id);
    }

    /**
     * Sync now rather than waiting for the next scheduled pass.
     *
     * <p>Always 200, even when the sync failed. The failure is a property of the
     * source and is reported in {@code lastOutcome} and {@code lastDetail} — the
     * request itself succeeded in doing what it was asked to do, and returning
     * 502 would make the caller unable to distinguish "the sync ran and GitHub
     * was down" from "this endpoint is broken".
     */
    @PostMapping("/{id}/sync")
    SourceView syncNow(@PathVariable UUID id) {
        return catalog.syncNow(currentPrincipal.get().orgId(), id);
    }

    @PostMapping("/{id}/enable")
    SourceView enable(@PathVariable UUID id) {
        return catalog.setEnabled(currentPrincipal.get().orgId(), id, true);
    }

    @PostMapping("/{id}/disable")
    SourceView disable(@PathVariable UUID id) {
        return catalog.setEnabled(currentPrincipal.get().orgId(), id, false);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> unwatch(@PathVariable UUID id) {
        catalog.unwatch(currentPrincipal.get().orgId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Turns the request into a validated {@link SourceRef}.
     *
     * <p>{@code SourceRef} validates in its constructor and throws
     * {@link IllegalArgumentException}, which is the right behaviour for a domain
     * type and the wrong thing to put on the wire. This converts it into the same
     * located violation shape every other validation failure uses.
     */
    private static SourceRef parse(WatchRequest request) {
        if (request == null || request.repository() == null || request.repository().isBlank()) {
            throw new ValidationFailedException(
                    "A source needs a repository",
                    List.of(new Violation(
                            "/repository",
                            "required",
                            "a repository in owner/name form",
                            null,
                            "No repository was given. A source is a repository, a ref and a path.")));
        }

        try {
            return SourceRef.of(
                    request.repository(),
                    request.ref(),
                    request.path() == null || request.path().isBlank() ? "service.yaml" : request.path());
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(
                    "The source location is not usable",
                    List.of(new Violation(
                            "/repository",
                            "format",
                            "a repository, ref and path GitHub could address",
                            request.repository(),
                            e.getMessage() + ".")));
        }
    }
}
