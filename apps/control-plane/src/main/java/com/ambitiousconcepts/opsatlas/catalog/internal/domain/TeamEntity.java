package com.ambitiousconcepts.opsatlas.catalog.internal.domain;

import com.ambitiousconcepts.opsatlas.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A team, as discovered from the {@code metadata.owner} of a registered manifest.
 *
 * <p>Teams are not administered through an API in slice one. They appear when a
 * service.yaml names one, which is consistent with the catalog's premise that
 * nothing in it is maintained by hand. The cost is that a typo in
 * {@code metadata.owner} creates a team nobody meant to create - visible in the
 * catalog and fixable by correcting the manifest, but real.
 */
@Entity
@Table(name = "team")
public class TeamEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TeamEntity() {
        // for JPA
    }

    public static TeamEntity discovered(UUID orgId, String slug, Instant now) {
        TeamEntity entity = new TeamEntity();
        entity.id = Ids.newRowId();
        entity.orgId = orgId;
        entity.slug = slug;
        entity.name = humanise(slug);
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /** "platform-tooling" becomes "Platform Tooling", until someone names it properly. */
    private static String humanise(String slug) {
        String[] words = slug.split("-");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.isEmpty() ? slug : name.toString();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }
}
