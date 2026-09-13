package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.RegistrationOutcome;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceDetail;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceRegistration;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.EnvironmentEntity;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.ServiceEntity;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.ServiceFields;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.TeamEntity;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestIngestor;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1;
import com.ambitiousconcepts.opsatlas.governance.api.AuditRecorder;
import com.ambitiousconcepts.opsatlas.governance.api.Scorecard;
import com.ambitiousconcepts.opsatlas.governance.api.ScorecardService;
import com.ambitiousconcepts.opsatlas.shared.ConflictException;
import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import com.ambitiousconcepts.opsatlas.shared.PreconditionException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registering and re-registering a service from its manifest.
 *
 * <p>Identity, and why it is what it is. A service is keyed for lookup by
 * {@code (org_id, slug)}, but it is keyed for <em>registration</em> by
 * {@code (org_id, repository, source_path)} - the place the manifest lives. That
 * is what makes a repeated POST from the same repository an update to the same
 * row rather than a second service, and it is the pair the database enforces.
 *
 * <p>Re-registration, in three cases:
 *
 * <ul>
 *   <li>Nothing registered from that location yet - create it.
 *   <li>Something registered, byte-identical manifest - a replay. Return what is
 *       already stored and write nothing. This is what stands in for an
 *       idempotency key on this endpoint (CLAUDE.md section 9).
 *   <li>Something registered, different manifest - a conflict, not a silent
 *       overwrite. POST creates; changing what exists is a PUT carrying the
 *       version the caller read.
 * </ul>
 */
@Service
class ServiceRegistrationService implements ServiceRegistration {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistrationService.class);

    private final ManifestIngestor ingestor;
    private final ServiceRepository services;
    private final TeamRepository teams;
    private final EnvironmentRepository environments;
    private final ServiceDetailAssembler assembler;
    private final ScorecardService scorecards;
    private final AuditRecorder audit;
    private final Clock clock;

    ServiceRegistrationService(
            ManifestIngestor ingestor,
            ServiceRepository services,
            TeamRepository teams,
            EnvironmentRepository environments,
            ServiceDetailAssembler assembler,
            ScorecardService scorecards,
            AuditRecorder audit,
            Clock clock) {
        this.ingestor = ingestor;
        this.services = services;
        this.teams = teams;
        this.environments = environments;
        this.assembler = assembler;
        this.scorecards = scorecards;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RegistrationOutcome register(UUID orgId, String document, String sourcePath, String sourceRef) {
        var ingested = ingestor.ingest(document);
        ManifestV1 manifest = ingested.manifest();
        String repository = manifest.metadata().repository();

        Optional<ServiceEntity> existing =
                services.findByOrgIdAndRepositoryAndSourcePath(orgId, repository, sourcePath);

        if (existing.isPresent()) {
            ServiceEntity service = existing.get();

            if (service.getManifestDigest().equals(ingested.digest())) {
                // A replay. Writing here would bump the version and the
                // updated_at of a row that did not change, which would make a
                // retried request look like an edit in the audit log.
                log.debug(
                        "Registration replay for {}/{} - manifest unchanged, nothing written",
                        repository,
                        sourcePath);
                return new RegistrationOutcome(assembler.assemble(orgId, service), false);
            }

            throw new ConflictException(
                    "A service is already registered from " + repository + "/" + sourcePath
                            + " with a different manifest. Use PUT /api/v1/services/" + service.getSlug()
                            + " with If-Match: \"" + service.getVersion() + "\" to update it.",
                    new Violation(
                            "/metadata/repository",
                            "conflict",
                            "a repository and path not already registered",
                            repository + "/" + sourcePath,
                            "'" + repository + "/" + sourcePath + "' is already registered as the service '"
                                    + service.getSlug() + "'. POST creates; updating an existing service is a PUT"
                                    + " carrying the version you read."));
        }

        rejectIfSlugTaken(orgId, manifest.metadata().name(), null);

        Instant now = clock.instant();
        ServiceEntity service = ServiceEntity.register(orgId, fieldsFrom(orgId, ingested, sourcePath, sourceRef), now);
        services.save(service);
        syncEnvironments(orgId, service.getId(), manifest, now);

        // Both of these run inside this transaction, so the service row, its
        // first scorecard and the audit entry either all exist or none do.
        // CLAUDE.md section 8 requires exactly that.
        Scorecard scorecard = scorecards.evaluateAndStore(orgId, ServiceFactsMapper.from(service.getId(), manifest));
        audit.record(
                orgId,
                "service.registered",
                "service",
                service.getId(),
                auditPayload(service, ingested.digest(), scorecard));

        log.info(
                "Registered service {} from {}/{} at schema {}, scoring {}/{}",
                service.getSlug(),
                repository,
                sourcePath,
                ingested.schemaVersion(),
                scorecard.checksPassed(),
                scorecard.checksApplicable());

        return new RegistrationOutcome(assembler.assemble(orgId, service), true);
    }

    @Override
    @Transactional
    public ServiceDetail update(UUID orgId, String slug, String document, Long ifMatchVersion, String sourceRef) {
        ServiceEntity service = services.findByOrgIdAndSlug(orgId, slug)
                .orElseThrow(() -> new NotFoundException("Service", slug));

        if (ifMatchVersion == null) {
            throw new PreconditionException(
                    PreconditionException.Kind.REQUIRED,
                    "This update requires an If-Match header carrying the version you read. The current version is \""
                            + service.getVersion() + "\". Without it, an update could overwrite a change you have"
                            + " not seen.");
        }
        if (ifMatchVersion != service.getVersion()) {
            throw new PreconditionException(
                    PreconditionException.Kind.FAILED,
                    "The service '" + slug + "' has changed since you read it: you sent If-Match \"" + ifMatchVersion
                            + "\" but the current version is \"" + service.getVersion()
                            + "\". Re-read it, reapply your change, and retry.");
        }

        return applyManifest(orgId, service, document, sourceRef);
    }

    /**
     * The body shared by {@link #update} and {@link #updateFromSource}.
     *
     * <p>Everything after the precondition check is identical for both, and has
     * to stay identical: a manifest applied by a poller must be validated,
     * scored and audited exactly as one applied by a person.
     */
    private ServiceDetail applyManifest(UUID orgId, ServiceEntity service, String document, String sourceRef) {
        String slug = service.getSlug();
        var ingested = ingestor.ingest(document);
        ManifestV1 manifest = ingested.manifest();

        if (!manifest.metadata().name().equals(slug)) {
            // A rename is not an edit. The slug is the URL, the metrics prefix
            // and the name people page each other with; changing it silently
            // would break every one of those without anyone deciding to.
            throw new ConflictException(
                    "This manifest renames the service from '" + slug + "' to '" + manifest.metadata().name() + "'.",
                    new Violation(
                            "/metadata/name",
                            "immutable",
                            "'" + slug + "', the name this service is registered under",
                            manifest.metadata().name(),
                            "Renaming a service is not supported. The name is its URL, its metrics prefix and what"
                                    + " people use to find it, so a rename is a migration rather than an edit."
                                    + " Register the new name and retire the old one."));
        }

        Instant now = clock.instant();
        String previousDigest = service.getManifestDigest();
        service.apply(fieldsFrom(orgId, ingested, service.getSourcePath(), sourceRef), now);
        services.save(service);
        syncEnvironments(orgId, service.getId(), manifest, now);

        // Re-scored on every change. A scorecard computed against a manifest
        // that has since been edited is worse than no scorecard, because it
        // looks current.
        Scorecard scorecard = scorecards.evaluateAndStore(orgId, ServiceFactsMapper.from(service.getId(), manifest));

        Map<String, Object> payload = auditPayload(service, ingested.digest(), scorecard);
        payload.put("previousManifestDigest", previousDigest);
        audit.record(orgId, "service.updated", "service", service.getId(), payload);

        log.info(
                "Updated service {} to manifest digest {}, scoring {}/{}",
                slug,
                ingested.digest(),
                scorecard.checksPassed(),
                scorecard.checksApplicable());

        return assembler.assemble(orgId, service);
    }

    /**
     * What an audit reader needs to reconstruct the change without re-reading
     * the manifest. The digest identifies exactly which document was stored; the
     * score is what it was judged to be at that moment.
     */
    private static Map<String, Object> auditPayload(ServiceEntity service, String digest, Scorecard scorecard) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("slug", service.getSlug());
        payload.put("repository", service.getRepository());
        payload.put("sourcePath", service.getSourcePath());
        payload.put("tier", (int) service.getTier());
        payload.put("manifestDigest", digest);
        payload.put("schemaVersion", service.getSchemaVersion());
        payload.put("policySetVersion", scorecard.policySetVersion());
        payload.put("checksPassed", scorecard.checksPassed());
        payload.put("checksApplicable", scorecard.checksApplicable());
        return payload;
    }

    @Override
    @Transactional
    public ServiceDetail updateFromSource(UUID orgId, UUID serviceId, String document, String sourceRef) {
        ServiceEntity service = services.findByOrgIdAndId(orgId, serviceId)
                .orElseThrow(() -> new NotFoundException("Service", serviceId.toString()));

        // No If-Match. See ServiceRegistration.updateFromSource for why, and for
        // what it costs.
        return applyManifest(orgId, service, document, sourceRef);
    }

    private void rejectIfSlugTaken(UUID orgId, String slug, UUID allowedId) {
        services.findByOrgIdAndSlug(orgId, slug).ifPresent(existing -> {
            if (!existing.getId().equals(allowedId)) {
                throw new ConflictException(
                        "A different service is already registered as '" + slug + "'.",
                        new Violation(
                                "/metadata/name",
                                "conflict",
                                "a service name not already in use",
                                slug,
                                "The name '" + slug + "' is already registered from "
                                        + existing.getRepository() + "/" + existing.getSourcePath()
                                        + ". Service names are unique, because the name is the identity."));
            }
        });
    }

    private ServiceFields fieldsFrom(
            UUID orgId, ManifestIngestor.IngestedManifest ingested, String sourcePath, String sourceRef) {
        ManifestV1 manifest = ingested.manifest();
        UUID teamId = manifest.metadata()
                .owner()
                .map(ownerSlug -> resolveTeam(orgId, ownerSlug).getId())
                .orElse(null);

        return new ServiceFields(
                teamId,
                manifest.metadata().name(),
                manifest.metadata().displayName().orElse(null),
                manifest.metadata().repository(),
                (short) manifest.spec().tier(),
                manifest.spec().runtime().orElse(null),
                manifest.spec().lifecycle(),
                ingested.schemaVersion(),
                ingested.canonicalJson(),
                ingested.digest(),
                sourcePath,
                sourceRef);
    }

    /** Teams are discovered from manifests rather than administered. See {@link TeamEntity}. */
    private TeamEntity resolveTeam(UUID orgId, String slug) {
        return teams.findByOrgIdAndSlug(orgId, slug).orElseGet(() -> {
            log.info("Discovered team '{}' from a manifest; creating it", slug);
            return teams.save(TeamEntity.discovered(orgId, slug, clock.instant()));
        });
    }

    /**
     * Bring the stored environments in line with the manifest, matching by name.
     *
     * <p>Existing environments are updated in place rather than deleted and
     * recreated, so their ids survive a manifest edit. Once observations hang
     * off an environment id, recreating those ids on every edit would discard
     * the history attached to them.
     *
     * <p>{@code spec.health} is declared once for the service and applied to
     * every environment. That is what the manifest says today; per-environment
     * health paths would be a schema change, not an inference to make here.
     */
    private void syncEnvironments(UUID orgId, UUID serviceId, ManifestV1 manifest, Instant now) {
        String readiness = manifest.spec()
                .health()
                .flatMap(ManifestV1.Health::readiness)
                .orElse(null);
        String liveness =
                manifest.spec().health().flatMap(ManifestV1.Health::liveness).orElse(null);

        Map<String, EnvironmentEntity> stored = new HashMap<>();
        environments.findByOrgIdAndServiceIdOrderByNameAsc(orgId, serviceId)
                .forEach(environment -> stored.put(environment.getName(), environment));

        Map<String, EnvironmentEntity> keep = new LinkedHashMap<>();
        for (ManifestV1.Environment declared : manifest.spec().environments()) {
            EnvironmentEntity entity = stored.get(declared.name());
            if (entity == null) {
                entity = EnvironmentEntity.create(
                        orgId, serviceId, declared.name(), declared.url().orElse(null), readiness, liveness, now);
            } else {
                entity.apply(declared.url().orElse(null), readiness, liveness, now);
            }
            keep.put(declared.name(), entity);
        }

        List<EnvironmentEntity> removed = new ArrayList<>();
        stored.forEach((name, entity) -> {
            if (!keep.containsKey(name)) {
                removed.add(entity);
            }
        });

        if (!removed.isEmpty()) {
            log.info(
                    "Removing {} environment(s) no longer declared by service {}: {}",
                    removed.size(),
                    serviceId,
                    removed.stream().map(EnvironmentEntity::getName).toList());
            environments.deleteAll(removed);
        }
        environments.saveAll(keep.values());
    }
}
