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
 * The way in to a system that will not let anyone in.
 *
 * <p>Authentication arriving created a bootstrap problem: a token only works if
 * a {@code principal} row exists for it, rows are created by somebody already
 * inside, and on a fresh deployment nobody is. Without this, the first act of
 * every install would be a manual {@code INSERT} against production.
 *
 * <p>So the first principal comes from configuration. Deliberately not from the
 * first caller: "whoever authenticates first becomes the administrator" is a
 * race with the internet, and losing it once is unrecoverable. Setting an
 * environment variable is something only an operator can do, and it says who
 * gets in before anyone tries.
 *
 * <p>Idempotent, and narrow. It provisions exactly the identity it is given, it
 * never creates an organization, and it never grants anything to anybody else.
 * Leaving the variables set is harmless; on every later start it finds the row
 * and does nothing.
 */
@Component
class PrincipalBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PrincipalBootstrap.class);

    private final PrincipalRepository principals;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String issuer;
    private final String subject;
    private final String displayName;

    PrincipalBootstrap(
            PrincipalRepository principals,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${opsatlas.bootstrap.principal.issuer:}") String issuer,
            @Value("${opsatlas.bootstrap.principal.subject:}") String subject,
            @Value("${opsatlas.bootstrap.principal.display-name:}") String displayName) {
        this.principals = principals;
        this.jdbc = jdbc;
        this.clock = clock;
        this.issuer = issuer == null ? "" : issuer.trim();
        this.subject = subject == null ? "" : subject.trim();
        this.displayName = displayName == null ? "" : displayName.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void provisionIfConfigured() {
        if (issuer.isEmpty() && subject.isEmpty()) {
            // Nothing configured. Silent on purpose: a deployment that has
            // already provisioned its people has no reason to set these, and a
            // warning on every start is a warning nobody reads.
            return;
        }
        if (issuer.isEmpty() || subject.isEmpty()) {
            throw new IllegalStateException("Bootstrap needs both opsatlas.bootstrap.principal.issuer and "
                    + ".subject. Half of an identity provisions nobody, and starting anyway would leave a "
                    + "deployment that looks configured and admits no one.");
        }

        if (principals.findByIssuerAndSubject(issuer, subject).isPresent()) {
            log.debug("Bootstrap principal {} at {} already exists", subject, issuer);
            return;
        }

        List<UUID> organizations = jdbc.queryForList("select id from organization order by slug", UUID.class);
        if (organizations.size() != 1) {
            // With none there is nothing to belong to; with several, picking one
            // would be a guess about which tenant gains an administrator.
            throw new IllegalStateException("Bootstrap expects exactly one organization and found "
                    + organizations.size() + ". Provision this principal explicitly instead.");
        }

        principals.save(PrincipalEntity.of(
                organizations.get(0),
                issuer,
                subject,
                displayName.isEmpty() ? subject : displayName,
                clock.instant()));

        // At INFO, and it names what it did: somebody gaining access to a
        // catalog is not a debug-level event.
        log.info("Provisioned bootstrap principal subject={} issuer={} org={}", subject, issuer, organizations.get(0));
    }
}
