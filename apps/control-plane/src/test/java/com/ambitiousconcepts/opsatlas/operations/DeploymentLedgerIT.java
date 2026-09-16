package com.ambitiousconcepts.opsatlas.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ambitiousconcepts.opsatlas.catalog.api.EnvironmentLookup;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceRegistration;
import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalScope;
import com.ambitiousconcepts.opsatlas.operations.api.Deployment;
import com.ambitiousconcepts.opsatlas.operations.api.DeploymentLedger;
import com.ambitiousconcepts.opsatlas.operations.api.DeploymentReport;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Recording what was deployed where.
 *
 * <p>The properties worth pinning are the ones that fail silently. A replayed
 * report becoming a second row does not look broken - it looks like a redeploy,
 * and then like a rollback when the next read takes the wrong row as current.
 * And "current" must be by {@code deployed_at} rather than by insertion order,
 * or a pipeline reporting two environments out of order invents a promotion
 * that never happened.
 */
@SpringBootTest
@Import(DeploymentLedgerIT.FixedClock.class)
class DeploymentLedgerIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");

    static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private DeploymentLedger ledger;

    @Autowired
    private EnvironmentLookup environments;

    @Autowired
    private ServiceRegistration registration;

    @Autowired
    private PrincipalScope principals;

    private UUID serviceId;
    private UUID production;
    private UUID staging;

    @BeforeEach
    void registerAService() throws IOException {
        String manifest = Files.readString(EXAMPLES.resolve("orders-api.yaml"));
        // Called outside a request, so a principal is bound explicitly - the
        // guard that throws when none is bound must stay able to.
        principals.runAs(
                new Principal(ORG, "test", "Test"),
                () -> registration.register(ORG, manifest, "service.yaml", "main"));

        serviceId = environments.serviceIdBySlug(ORG, "orders-api").orElseThrow();
        production = environments.environmentIdByName(ORG, serviceId, "production").orElseThrow();
        staging = environments.environmentIdByName(ORG, serviceId, "staging").orElseThrow();
    }

    @Test
    @DisplayName("a reported deployment becomes the current version for its environment")
    void a_report_becomes_current() {
        ledger.record(ORG, production, "production", report("1.4.2", "abc1234", NOW));

        Map<UUID, Deployment> current = ledger.currentByEnvironment(ORG, serviceId);

        assertThat(current).containsKey(production);
        assertThat(current.get(production).version()).isEqualTo("1.4.2");
        assertThat(current.get(production).commitSha()).isEqualTo("abc1234");
        assertThat(current.get(production).environmentName()).isEqualTo("production");
        // Never reported is absence, not a row with a blank version.
        assertThat(current).doesNotContainKey(staging);
    }

    @Test
    @DisplayName("replaying a report returns the original rather than deploying again")
    void a_replayed_report_is_not_a_second_deployment() {
        DeploymentLedger.Recorded first = ledger.record(ORG, production, "production", report("1.4.2", null, NOW));
        DeploymentLedger.Recorded replay = ledger.record(ORG, production, "production", report("1.4.2", null, NOW));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.deployment().id()).isEqualTo(first.deployment().id());
        // The failure this prevents is not a duplicate row: it is the next read
        // taking the wrong one as current and rendering a rollback.
        assertThat(ledger.history(ORG, serviceId, 50)).hasSize(1);
    }

    @Test
    @DisplayName("current is the latest by deploy time, not by the order reports arrived")
    void current_is_by_deploy_time_not_insertion_order() {
        Instant earlier = NOW.minusSeconds(3600);

        // The newer deploy is recorded first, then a late report of an older
        // one - exactly what a pipeline retrying a failed notification does.
        ledger.record(ORG, production, "production", reportKeyed("2.0.0", NOW, "new"));
        ledger.record(ORG, production, "production", reportKeyed("1.9.0", earlier, "old"));

        assertThat(ledger.currentByEnvironment(ORG, serviceId).get(production).version())
                .isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("history is newest first across every environment")
    void history_is_newest_first() {
        ledger.record(ORG, staging, "staging", reportKeyed("2.1.0-rc1", NOW.minusSeconds(600), "s1"));
        ledger.record(ORG, production, "production", reportKeyed("2.0.0", NOW.minusSeconds(7200), "p1"));

        List<Deployment> history = ledger.history(ORG, serviceId, 50);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).version()).isEqualTo("2.1.0-rc1");
        assertThat(history.get(0).environmentName()).isEqualTo("staging");
        assertThat(history.get(1).version()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("a deploy time the reporter supplies is kept, not replaced with now")
    void a_reported_time_is_not_overwritten() {
        // "In place for" is computed from this. If the ledger stamped its own
        // clock, a pipeline reporting an hour late would make every deploy look
        // like it just happened.
        Instant actuallyDeployed = NOW.minusSeconds(51 * 60);
        ledger.record(ORG, production, "production", report("1.4.2", null, actuallyDeployed));

        assertThat(ledger.currentByEnvironment(ORG, serviceId).get(production).deployedAt())
                .isEqualTo(actuallyDeployed);
    }

    @Test
    @DisplayName("a service with nothing reported has no deployments, rather than an empty version")
    void nothing_reported_is_absence() {
        assertThat(ledger.currentByEnvironment(ORG, serviceId)).isEmpty();
        assertThat(ledger.history(ORG, serviceId, 50)).isEmpty();
    }

    private static DeploymentReport report(String version, String commit, Instant at) {
        return new DeploymentReport(
                version, Optional.ofNullable(commit), Optional.of("ci"), Optional.of(at), version + "@" + at);
    }

    private static DeploymentReport reportKeyed(String version, Instant at, String key) {
        return new DeploymentReport(version, Optional.empty(), Optional.of("ci"), Optional.of(at), key);
    }
}
