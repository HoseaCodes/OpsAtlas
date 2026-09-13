package com.ambitiousconcepts.opsatlas.catalog.web;

import com.ambitiousconcepts.opsatlas.catalog.api.ServiceCatalog;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceSummary;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalog read API.
 *
 * <p>Phase 1 serves the collection only, and it is empty until registration
 * lands in phase 2. That is not a placeholder: the pagination contract, the
 * organization scoping and the error shape are all real and tested here, which
 * is what phase 2 then writes into.
 */
@RestController
@RequestMapping("/api/v1/services")
class ServiceController {

    // No @Validated on this class. That annotation routes validation through
    // a proxy which raises ConstraintViolationException, a type with no HTTP
    // meaning; without it, Spring's built-in method validation raises
    // HandlerMethodValidationException, which carries the parameter name and
    // is what ApiExceptionHandler turns into a 400 naming the parameter.

    /** Deliberately modest. A caller wanting more pages asks for more pages. */
    private static final int DEFAULT_LIMIT = 25;

    private final ServiceCatalog catalog;
    private final CurrentPrincipal currentPrincipal;

    ServiceController(ServiceCatalog catalog, CurrentPrincipal currentPrincipal) {
        this.catalog = catalog;
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
}
