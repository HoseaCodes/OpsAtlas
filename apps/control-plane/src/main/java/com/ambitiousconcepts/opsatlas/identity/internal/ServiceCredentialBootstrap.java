package com.ambitiousconcepts.opsatlas.identity.internal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The observer's key, from configuration (ADR 0013).
 *
 * <p>The same shape as {@link PrincipalBootstrap} and for the same reason: a
 * credential has to exist before the machine that needs it starts, and there is
 * no endpoint that mints one. The operator generates a key, gives it to both
 * sides, and this stores its hash.
 *
 * <p>Deliberately not generated here and printed. A key the server invents has
 * to be read out of a log to be useful, which puts it in the log - and then in
 * whatever ships the logs, for as long as they are kept.
 *
 * <p>Changing the configured value rotates: the row keeps its name and takes the
 * new hash, so the old key stops working immediately rather than both being
 * accepted for a while.
 */
@Component
class ServiceCredentialBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialBootstrap.class);

    /** The only machine that needs one today. More would need a way to name them. */
    static final String OBSERVER = "observer";

    private final ServiceCredentialRepository credentials;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String observerKey;

    ServiceCredentialBootstrap(
            ServiceCredentialRepository credentials,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${opsatlas.credentials.observer.key:}") String observerKey) {
        this.credentials = credentials;
        this.jdbc = jdbc;
        this.clock = clock;
        this.observerKey = observerKey == null ? "" : observerKey.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void provisionIfConfigured() {
        if (observerKey.isEmpty()) {
            // Not configured. The observer cannot report, which is a real state
            // a deployment can be in - a control plane with nobody watching -
            // and it is said out loud rather than left to a 401 nobody reads.
            log.info("No observer credential configured (OPSATLAS_OBSERVER_KEY); "
                    + "the observer will not be able to report observations");
            return;
        }

        if (!ServiceCredentials.KEY_SHAPE.matcher(observerKey).matches()) {
            throw new IllegalStateException("OPSATLAS_OBSERVER_KEY must look like opsatlas_sk_<32-128 url-safe "
                    + "characters>. Refusing to start rather than storing something that cannot have come "
                    + "from `make observer-key`, because a key that is nearly right fails later and further away.");
        }

        List<UUID> organizations = jdbc.queryForList("select id from organization order by slug", UUID.class);
        if (organizations.size() != 1) {
            throw new IllegalStateException("A service credential belongs to one organization and there are "
                    + organizations.size() + ". Provision it explicitly instead.");
        }

        String hash = ServiceCredentials.hash(observerKey);
        credentials
                .findByOrgIdAndName(organizations.get(0), OBSERVER)
                .ifPresentOrElse(
                        existing -> {
                            if (existing.hasHash(hash)) {
                                return;
                            }
                            existing.rotateTo(hash);
                            credentials.save(existing);
                            log.info("Rotated the observer credential; the previous key no longer works");
                        },
                        () -> {
                            credentials.save(ServiceCredentialEntity.of(
                                    organizations.get(0), OBSERVER, hash, clock.instant()));
                            log.info("Provisioned the observer credential for org={}", organizations.get(0));
                        });
    }
}
