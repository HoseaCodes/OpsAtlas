package com.ambitiousconcepts.opsatlas.shared;

/**
 * The requested resource does not exist, or exists in another organization.
 *
 * <p>Those two cases deliberately produce the same response. A 403 for the
 * second would confirm that the resource exists, which is an existence leak
 * across an organization boundary. See
 * {@code docs/adr/0003-org-scoping-stub.md}.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String resourceType;
    private final String identifier;

    public NotFoundException(String resourceType, String identifier) {
        super(resourceType + " '" + identifier + "' was not found.");
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public String resourceType() {
        return resourceType;
    }

    public String identifier() {
        return identifier;
    }
}
