package com.ambitiousconcepts.opsatlas.integrations.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.RegistrationOutcome;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceRegistration;
import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalScope;
import com.ambitiousconcepts.opsatlas.integrations.api.FetchResult;
import com.ambitiousconcepts.opsatlas.integrations.api.ManifestSourceReader;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceView;
import com.ambitiousconcepts.opsatlas.shared.ConflictException;
import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads a watched source and feeds what it finds into registration.
 *
 * <p>There is no second ingestion path. A polled manifest goes through the same
 * {@link ServiceRegistration} a pasted one does, so validation, scoring, audit
 * and the conflict rules cannot diverge between the two. The only thing added
 * here is recording what happened to the <em>source</em>.
 *
 * <p><strong>This class holds no transaction.</strong> Two reasons, and both
 * were bugs before they were reasons:
 *
 * <ul>
 *   <li>The fetch is a network call. Wrapping the sync in a transaction would
 *       hold a database connection open for the duration of somebody else's
 *       HTTP response.
 *   <li>Registration is transactional, and a rejected manifest marks that
 *       transaction rollback-only. If the source's bookkeeping shared it, the
 *       attempt to record <em>why</em> the sync failed would itself fail, and a
 *       source with a broken manifest would look as though it had never been
 *       tried.
 * </ul>
 *
 * <p>So: fetch outside any transaction, let registration own its own, and record
 * the outcome in a third. {@link SourceStore} holds those boundaries.
 *
 * <p><strong>Every failure leaves the registered service alone.</strong> If
 * GitHub is unreachable, or the manifest has become invalid, the catalog keeps
 * what it last knew and the source says why it is no longer current. A sync
 * failure is not evidence that a service stopped existing.
 */
@Service
class SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncService.class);

    /**
     * Who a polled change is attributed to.
     *
     * <p>An audit reader seeing this learns that a repository poll made the
     * change, not a person - which is the whole point of recording an actor.
     * TODO(auth): when authentication lands this stays exactly as it is. A
     * scheduled job is not a user and should never borrow one's identity.
     */
    private static final String SYNC_SUBJECT = "source-sync";

    private final SourceStore store;
    private final ServiceRegistration registration;
    private final Map<String, ManifestSourceReader> readersByProvider;
    private final PrincipalScope principals;

    SourceSyncService(
            SourceStore store,
            ServiceRegistration registration,
            List<ManifestSourceReader> readers,
            PrincipalScope principals) {
        this.store = store;
        this.registration = registration;
        this.readersByProvider =
                readers.stream().collect(Collectors.toMap(ManifestSourceReader::provider, Function.identity()));
        this.principals = principals;
    }

    SourceView sync(UUID orgId, UUID sourceId) {
        // A scheduled pass has no request and therefore no caller, but
        // registration writes an audit event and an audit event needs an actor.
        // Binding an explicit one here is what keeps CurrentPrincipal able to
        // fail loudly when a *request* loses its principal through a bug.
        return principals.runAs(
                new Principal(orgId, SYNC_SUBJECT, "Repository sync"), () -> doSync(orgId, sourceId));
    }

    private SourceView doSync(UUID orgId, UUID sourceId) {
        SourceEntity source = store.load(orgId, sourceId);
        ManifestSourceReader reader = readersByProvider.get(source.getProvider());

        if (reader == null) {
            // Unreachable through the API, which only accepts providers that
            // have a reader. Possible if a provider is removed while rows remain.
            return SourceMapper.toView(store.recordFailure(
                    orgId,
                    sourceId,
                    "UNREACHABLE",
                    "No reader is configured for provider '" + source.getProvider() + "'."));
        }

        FetchResult result = reader.fetch(source.toRef(), Optional.ofNullable(source.getEtag()));

        SourceEntity updated = switch (result) {
            // GitHub confirmed the file is byte-identical to what we last saw.
            // Nothing is re-validated and nothing is re-scored, which is the
            // whole economy of conditional polling.
            case FetchResult.NotModified ignored -> store.recordSuccess(orgId, sourceId, "UNCHANGED", null, null);
            case FetchResult.Content content -> applyManifest(orgId, source, content);
            case FetchResult.NotFound notFound -> store.recordFailure(orgId, sourceId, "NOT_FOUND", notFound.detail());
            case FetchResult.Unauthorized unauthorized ->
                store.recordFailure(orgId, sourceId, "UNAUTHORIZED", unauthorized.detail());
            case FetchResult.RateLimited rateLimited ->
                store.recordFailure(orgId, sourceId, "RATE_LIMITED", rateLimited.detail());
            case FetchResult.Unreachable unreachable ->
                store.recordFailure(orgId, sourceId, "UNREACHABLE", unreachable.detail());
        };

        return SourceMapper.toView(updated);
    }

    private SourceEntity applyManifest(UUID orgId, SourceEntity source, FetchResult.Content content) {
        String etag = content.etag().orElse(null);
        UUID sourceId = source.getId();

        try {
            RegistrationOutcome outcome =
                    registration.register(orgId, content.document(), source.getPath(), source.getGitRef());

            // "created" distinguishes a first registration from a replay of an
            // unchanged manifest. A source polling an unchanged file reports
            // UNCHANGED even without a 304, because the digest matched.
            String result = outcome.created() ? "REGISTERED" : "UNCHANGED";
            log.info("Synced {}/{}: {}", source.getRepository(), source.getPath(), result);
            return store.recordSuccess(orgId, sourceId, result, outcome.service().id(), etag);

        } catch (ConflictException conflict) {
            // The manifest changed and POST refuses to overwrite (ADR 0007), so
            // the sync applies it as an update - which is what a poller of a
            // repository is entitled to do: the repository is the source of
            // truth for its own manifest.
            return updateExisting(orgId, source, content, etag, conflict);

        } catch (ValidationFailedException invalid) {
            // The repository's manifest has become invalid. The previously
            // registered service stays exactly as it was; the source records
            // every violation so the owning team can see what they broke without
            // leaving the catalog.
            String detail = invalid.getMessage() + " — "
                    + invalid.violations().stream()
                            .map(violation -> violation.pointer() + ": " + violation.message())
                            .collect(Collectors.joining(" "));
            log.warn("Manifest at {}/{} no longer validates", source.getRepository(), source.getPath());
            return store.recordFailure(orgId, sourceId, "REJECTED", detail);
        }
    }

    private SourceEntity updateExisting(
            UUID orgId, SourceEntity source, FetchResult.Content content, String etag, ConflictException conflict) {
        UUID sourceId = source.getId();

        if (source.getServiceId() == null) {
            // A conflict with a service this source did not create - two
            // manifests claiming the same name, or a paste that got there first.
            // Not something a poller should resolve by overwriting.
            return store.recordFailure(orgId, sourceId, "CONFLICT", conflict.getMessage());
        }

        try {
            var updated =
                    registration.updateFromSource(orgId, source.getServiceId(), content.document(), source.getGitRef());
            log.info("Updated {} from {}/{}", updated.slug(), source.getRepository(), source.getPath());
            return store.recordSuccess(orgId, sourceId, "UPDATED", updated.id(), etag);

        } catch (ValidationFailedException invalid) {
            return store.recordFailure(orgId, sourceId, "REJECTED", invalid.getMessage());
        } catch (ConflictException stillConflicting) {
            return store.recordFailure(orgId, sourceId, "CONFLICT", stillConflicting.getMessage());
        }
    }
}
