package com.ambitiousconcepts.opsatlas.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ambitiousconcepts.opsatlas.catalog.api.ServiceRegistration;
import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalScope;
import com.ambitiousconcepts.opsatlas.operations.api.EnvironmentHealth;
import com.ambitiousconcepts.opsatlas.operations.api.HealthReadModel;
import com.ambitiousconcepts.opsatlas.operations.api.Observation;
import com.ambitiousconcepts.opsatlas.operations.api.ObservationBatch;
import com.ambitiousconcepts.opsatlas.operations.api.ObservationIngest;
import com.ambitiousconcepts.opsatlas.operations.api.ProbeOutcome;
import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Folding probe results into rolled-up state.
 *
 * <p>The properties under test are the ones ADR 0009 depends on and that nothing
 * else can check, because the individual probe results are gone the moment they
 * are counted: the counters must be exactly right, and applying a batch twice
 * must be impossible. A double-count here is silent - nothing looks broken, the
 * numbers are simply wrong.
 */
@SpringBootTest
@Import(ObservationIngestIT.FixedClock.class)
class ObservationIngestIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** Noon UTC, so every timestamp below sits safely in the past. */
    static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    /**
     * Time is held still for this suite.
     *
     * <p>Without it these tests depend on the hour they run at: the ingest
     * clamps observations that claim to be in the future, so a hardcoded
     * timestamp is in the past in the afternoon and clamped in the morning. That
     * is a genuine property worth having, and a test that only passes after
     * midday is not the way to demonstrate it.
     */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private ObservationIngest ingest;

    @Autowired
    private HealthReadModel health;

    @Autowired
    private ServiceRegistration registration;

    @Autowired
    private PrincipalScope principals;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID serviceId;
    private UUID productionId;

    @BeforeEach
    void registerAService() {
        // Registration writes an audit event, and an audit event needs an actor.
        // Called outside a request there is none, so one is bound explicitly -
        // which is what PrincipalScope exists for.
        var registered = principals.runAs(
                new Principal(ORG, "test", "Test"),
                () -> registration.register(ORG, manifest("orders-api.yaml"), "service.yaml", "main"));
        serviceId = registered.service().id();
        productionId = registered.service().environments().stream()
                .filter(environment -> environment.name().equals("production"))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private static String manifest(String name) {
        try {
            return Files.readString(EXAMPLES.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    private ObservationIngest.IngestResult send(String key, Observation... observations) {
        return ingest.accept(ORG, new ObservationBatch(key, "observer-test", List.of(observations)));
    }

    private static Observation healthy(UUID environmentId, Instant at, int responseMs) {
        return new Observation(environmentId, at, ProbeOutcome.HEALTHY, responseMs, 200, null);
    }

    private static Observation failed(UUID environmentId, Instant at, ProbeOutcome outcome, String detail) {
        return new Observation(environmentId, at, outcome, null, null, detail);
    }

    private EnvironmentHealth production() {
        Map<UUID, EnvironmentHealth> byEnvironment = health.forService(ORG, serviceId, 30);
        return byEnvironment.get(productionId);
    }

    // -- State --------------------------------------------------------------

    @Test
    @DisplayName("an environment with no observations has no health at all")
    void unobserved_environments_have_no_health() {
        // Absent, not a default. "Nobody has looked" and "looked and found it
        // healthy" are different facts, and collapsing them would let the
        // console render an unmeasured service as fine.
        assertThat(health.forService(ORG, serviceId, 30)).isEmpty();
        assertThat(health.rollupStatus(ORG, serviceId)).isEmpty();
    }

    @Test
    @DisplayName("a first healthy probe creates state")
    void first_probe_creates_state() {
        Instant now = NOW.minus(Duration.ofHours(2));
        send("batch-000000001", healthy(productionId, now, 42));

        EnvironmentHealth state = production();
        assertThat(state.status()).isEqualTo("HEALTHY");
        assertThat(state.detail()).isNull();
        assertThat(state.responseMs()).isEqualTo(42);
        assertThat(state.lastHealthyAt()).isEqualTo(now);
        assertThat(state.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("one failure is DEGRADED, three is DOWN")
    void a_blip_is_not_an_outage() {
        // Painting the catalog red for a single dropped probe teaches people to
        // ignore the signal.
        Instant start = NOW.minus(Duration.ofHours(2));
        send("batch-000000001", failed(productionId, start, ProbeOutcome.TIMEOUT, "Timed out after 2s."));
        assertThat(production().status()).isEqualTo("DEGRADED");

        send("batch-000000002", failed(productionId, start.plusSeconds(30), ProbeOutcome.TIMEOUT, "Timed out after 2s."));
        assertThat(production().status()).isEqualTo("DEGRADED");

        send("batch-000000003", failed(productionId, start.plusSeconds(60), ProbeOutcome.TIMEOUT, "Timed out after 2s."));
        assertThat(production().status()).isEqualTo("DOWN");
        assertThat(production().consecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("a success resets the failure run and clears the explanation")
    void recovery_resets() {
        Instant start = NOW.minus(Duration.ofHours(2));
        send("batch-000000001", failed(productionId, start, ProbeOutcome.UNREACHABLE, "Connection refused."));
        send("batch-000000002", failed(productionId, start.plusSeconds(30), ProbeOutcome.UNREACHABLE, "Connection refused."));

        send("batch-000000003", healthy(productionId, start.plusSeconds(60), 15));

        EnvironmentHealth state = production();
        assertThat(state.status()).isEqualTo("HEALTHY");
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.detail()).isNull();
    }

    @Test
    @DisplayName("a failing environment keeps its last successful response time")
    void latency_survives_a_failure() {
        Instant start = NOW.minus(Duration.ofHours(2));
        send("batch-000000001", healthy(productionId, start, 40));
        send("batch-000000002", failed(productionId, start.plusSeconds(30), ProbeOutcome.TIMEOUT, "Timed out."));

        // "It last answered in 40ms" stays true and useful while it is failing,
        // and a timeout's duration is not a response time.
        assertThat(production().responseMs()).isEqualTo(40);
        assertThat(production().lastHealthyAt()).isEqualTo(start);
    }

    @Test
    @DisplayName("an out-of-order probe does not overwrite a newer one")
    void stale_results_are_ignored_for_state() {
        // Two observers, or one retrying, can deliver late. Letting a stale
        // probe win would make the catalog flap between two truths.
        Instant now = NOW.minus(Duration.ofHours(2));
        send("batch-000000001", healthy(productionId, now, 20));
        send("batch-000000002", failed(productionId, now.minusSeconds(60), ProbeOutcome.TIMEOUT, "Timed out."));

        assertThat(production().status()).isEqualTo("HEALTHY");
        // It still counts toward the day: a late probe did still happen.
        assertThat(production().days().get(0).probes()).isEqualTo(2);
    }

    // -- Counters -----------------------------------------------------------

    @Nested
    @DisplayName("the daily rollups")
    class Rollups {

        @Test
        @DisplayName("count probes and successes, and never store a row per probe")
        void counters_not_samples() {
            Instant day = NOW.minus(Duration.ofHours(2));
            for (int i = 0; i < 20; i++) {
                Observation observation = i % 5 == 0
                        ? failed(productionId, day.plusSeconds(i * 30L), ProbeOutcome.UNHEALTHY, "503 from readiness.")
                        : healthy(productionId, day.plusSeconds(i * 30L), 30 + i);
                send("batch-%09d".formatted(i), observation);
            }

            // Twenty probes, one row. That is the whole of ADR 0009.
            assertThat(jdbc.queryForObject("select count(*) from environment_day", Integer.class))
                    .isEqualTo(1);

            EnvironmentHealth.DailyAvailability today = production().days().get(0);
            assertThat(today.probes()).isEqualTo(20);
            assertThat(today.successes()).isEqualTo(16);
            assertThat(today.availability()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("split by UTC day, so a run across midnight is two rows")
        void days_are_utc_days() {
            send("batch-000000001", healthy(productionId, Instant.parse("2026-09-12T23:59:00Z"), 10));
            send("batch-000000002", healthy(productionId, Instant.parse("2026-09-13T00:01:00Z"), 10));

            assertThat(production().days()).hasSize(2);
            assertThat(production().days().get(0).day()).isEqualTo("2026-09-12");
            assertThat(production().days().get(1).day()).isEqualTo("2026-09-13");
        }

        @Test
        @DisplayName("accumulate latency only from probes that answered")
        void timeouts_do_not_inflate_latency() {
            Instant day = NOW.minus(Duration.ofHours(2));
            send("batch-000000001", healthy(productionId, day, 100));
            send("batch-000000002", healthy(productionId, day.plusSeconds(30), 200));
            // A timeout has no round trip to report. Folding the timeout value
            // in would silently raise every mean the moment a service went down.
            send("batch-000000003", failed(productionId, day.plusSeconds(60), ProbeOutcome.TIMEOUT, "Timed out."));

            EnvironmentHealth.DailyAvailability today = production().days().get(0);
            assertThat(today.meanResponseMs()).isEqualTo(150);
            assertThat(today.maxResponseMs()).isEqualTo(200);
            assertThat(today.probes()).isEqualTo(3);
            assertThat(today.successes()).isEqualTo(2);
        }

        @Test
        @DisplayName("report probe availability over the window, or null when nothing was probed")
        void availability_is_counted_not_estimated() {
            Instant day = NOW.minus(Duration.ofHours(2));
            send("batch-000000001", healthy(productionId, day, 10));
            send("batch-000000002", healthy(productionId, day.plusSeconds(30), 10));
            send("batch-000000003", failed(productionId, day.plusSeconds(60), ProbeOutcome.UNHEALTHY, "503."));
            send("batch-000000004", failed(productionId, day.plusSeconds(90), ProbeOutcome.UNHEALTHY, "503."));

            assertThat(production().probeAvailability()).isEqualTo(0.5);
        }
    }

    // -- Idempotency --------------------------------------------------------

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("a replayed batch applies nothing and returns the original counts")
        void replay_is_a_no_op() {
            Instant now = NOW.minus(Duration.ofHours(2));
            var first = send("retried-batch-01", healthy(productionId, now, 25));
            assertThat(first.applied()).isEqualTo(1);
            assertThat(first.replayed()).isFalse();

            var second = send("retried-batch-01", healthy(productionId, now, 25));

            assertThat(second.replayed()).isTrue();
            assertThat(second.applied()).isEqualTo(1);
            // The counters are what make this matter: a double-write here is
            // silent, because the probe results themselves were never stored.
            assertThat(production().days().get(0).probes())
                    .as("a retry must not count the same probe twice")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("different keys carrying the same observation both count")
        void distinct_batches_both_apply() {
            // Idempotency is per batch, not per observation: an observer that
            // genuinely probed twice reports two batches.
            Instant now = NOW.minus(Duration.ofHours(2));
            send("batch-aaaaaaaa", healthy(productionId, now, 25));
            send("batch-bbbbbbbb", healthy(productionId, now.plusSeconds(30), 25));

            assertThat(production().days().get(0).probes()).isEqualTo(2);
        }

        @Test
        @DisplayName("a batch with no key is refused, not accepted quietly")
        void a_key_is_required() {
            assertThatThrownBy(() -> send("short", healthy(productionId, NOW, 10)))
                    .isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).violations())
                            .anyMatch(violation -> violation.pointer().equals("/idempotencyKey")));
        }
    }

    // -- Batches that are partly about things we do not know ----------------

    @Test
    @DisplayName("observations for unknown environments are ignored, and the rest still land")
    void unknown_environments_do_not_fail_the_batch() {
        // An observer holding a slightly stale service list is normal. Failing
        // the whole batch over one retired environment would lose every other
        // result in it.
        Instant now = NOW.minus(Duration.ofHours(2));
        var result = send(
                "mixed-batch-01",
                healthy(productionId, now, 30),
                healthy(UUID.randomUUID(), now, 30));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.ignored()).isEqualTo(1);
        assertThat(production().days().get(0).probes()).isEqualTo(1);
    }

    @Test
    @DisplayName("an observation from the future is pulled back to now rather than dropped")
    void clock_skew_is_clamped() {
        // A mildly skewed observer is still reporting something true. Dropping
        // its results would make the catalog quietly blind rather than visibly
        // wrong; writing them would create a counter row for a day that has not
        // happened.
        var result = send("skewed-batch-1", healthy(productionId, NOW.plus(Duration.ofDays(2)), 10));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(production().days()).hasSize(1);
        assertThat(production().days().get(0).day())
                .isEqualTo(NOW.atZone(ZoneOffset.UTC).toLocalDate().toString());
    }

    @Test
    @DisplayName("an empty batch is refused")
    void empty_batches_are_refused() {
        assertThatThrownBy(() -> send("empty-batch-01"))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).violations())
                        .anyMatch(violation -> violation.pointer().equals("/observations")));
    }

    // -- Rollup across environments -----------------------------------------

    @Test
    @DisplayName("a service is as healthy as its worst environment")
    void rollup_takes_the_worst() {
        UUID stagingId = jdbc.queryForObject(
                "select id::text from environment where name = 'staging'", String.class) == null
                ? null
                : UUID.fromString(jdbc.queryForObject(
                        "select id::text from environment where name = 'staging'", String.class));

        Instant now = NOW.minus(Duration.ofHours(2));
        send("batch-000000001", healthy(productionId, now, 20));
        send("batch-000000002", failed(stagingId, now, ProbeOutcome.UNREACHABLE, "Connection refused."));

        // Averaging, or taking the most common status, would call this mostly
        // healthy - true of the environments and false of the service.
        assertThat(health.rollupStatus(ORG, serviceId)).contains("DEGRADED");
    }
}
