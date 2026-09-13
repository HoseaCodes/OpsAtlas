package com.ambitiousconcepts.opsatlas.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ambitiousconcepts.opsatlas.operations.api.EnvironmentHealth;
import com.ambitiousconcepts.opsatlas.operations.api.HealthReadModel;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The job that makes ADR 0009's bound on storage true.
 *
 * <p>That decision says observations are counters rather than a time series, and
 * that storage is therefore environments × retained days rather than a function
 * of probe frequency. The first half is enforced by the ingest. The second half
 * is enforced by nothing at all unless this job runs and deletes the right rows
 * — {@code environment_day} otherwise accumulates a row per environment per day
 * forever, which is a time series wearing a different hat.
 *
 * <p>It is scheduled, so nothing else in the suite ever executes it. This calls
 * it directly against a fixed clock.
 *
 * <p>The test lives in the {@code internal} package deliberately: the job is not
 * part of any module's public API, and exposing it just to test it would put a
 * scheduled maintenance task in the same contract as the endpoints.
 */
@SpringBootTest
@Import(ObservationRetentionIT.FixedClock.class)
@TestPropertySource(
        properties = {
            // The rest of the suite runs with this off. Only the window sizes
            // are left alone: they come from application.yaml, so this tests the
            // retention the product is actually configured with rather than one
            // invented here. Overriding them would make the ribbon assertion
            // below agree with itself and prove nothing about the deployment.
            "opsatlas.retention.enabled=true",
            // Far enough out that the scheduler never fires during the test.
            // Every prune here is one this test asked for.
            "opsatlas.retention.initial-delay=PT24H"
        })
class ObservationRetentionIT extends PostgresTestBase {

    /**
     * The window the console's ribbon draws, matching {@code HealthController}.
     *
     * <p>A window of N means today and the N-1 days before it, so the oldest day
     * it can draw is {@code today - 29}. The configured retention window is
     * wider on purpose, and the ribbon test below is what holds the two apart.
     */
    private static final int RIBBON_DAYS = 30;

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private ObservationRetentionJob retention;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HealthReadModel health;

    /** Read from the real configuration, not chosen here. */
    @Value("${opsatlas.retention.days}")
    private int retainedDays;

    @Value("${opsatlas.retention.idempotency-key-hours}")
    private int retainKeyHours;

    private UUID serviceId;
    private UUID environmentId;

    @BeforeEach
    void plantAnObservedEnvironment() {
        serviceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into service (
                    id, org_id, slug, repository, tier, lifecycle, schema_version,
                    manifest, manifest_digest, source_path, version, created_at, updated_at)
                values (?, ?, 'orders-api', 'ambitious-concepts/orders-api', 1, 'active',
                    'opsatlas.ambitiousconcepts.io/v1', '{}'::jsonb, ?, 'service.yaml', 0, now(), now())
                """,
                serviceId,
                ORG,
                "c".repeat(64));

        environmentId = UUID.randomUUID();
        jdbc.update(
                """
                insert into environment (id, org_id, service_id, name, url, version, created_at, updated_at)
                values (?, ?, ?, 'production', 'https://orders.example.com', 0, now(), now())
                """,
                environmentId,
                ORG,
                serviceId);
    }

    @Test
    @DisplayName("rollups past the window are deleted, so storage does not grow without bound")
    void old_rollups_are_pruned() {
        plantDay(TODAY.minusDays(retainedDays + 1L));
        plantDay(TODAY.minusDays(400));

        retention.prune();

        assertThat(dayCount())
                .as("a table that only grows is a time series, which is what ADR 0009 refuses to be")
                .isZero();
    }

    @Test
    @DisplayName("everything the ribbon draws is still there after a prune")
    void the_ribbon_is_never_pruned_out_from_under_itself() {
        // The margin between the retention window and the ribbon is the point:
        // without it, a prune landing between two requests would put a gap at
        // the ribbon's oldest edge, and a gap there reads as "never probed"
        // rather than "no longer kept" - two different facts.
        //
        // Asked through the read model the console actually calls, rather than
        // by comparing two constants in this file. If the ribbon is ever
        // widened past the retention window, that is precisely the change this
        // has to fail on, and two local constants would agree with each other
        // while the product was wrong.
        // A window of N covers today and the N-1 days before it, so the
        // oldest day a 30-day ribbon can draw is today minus 29 - not today
        // minus 30, which is one day outside it. The first version of this test
        // planted today-30, got one day back instead of two, and was wrong
        // rather than the code being wrong.
        plantState();
        plantDay(TODAY);
        plantDay(TODAY.minusDays(RIBBON_DAYS - 1L));
        plantDay(TODAY.minusDays(retainedDays));

        assertThat(retainedDays)
                .as("the configured retention window must be wider than the window the console draws")
                .isGreaterThan(RIBBON_DAYS);

        int askedFor = RIBBON_DAYS;
        int beforePrune = daysServed(askedFor);
        retention.prune();

        assertThat(daysServed(askedFor))
                .as("a prune must not change what the console can draw; "
                        + "the retention window has to stay wider than the window the console asks for")
                .isEqualTo(beforePrune);
        assertThat(beforePrune)
                .as("the planted days inside the ribbon must actually be served, "
                        + "or this test would pass on an empty answer")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the boundary is kept, the day before it is not")
    void the_cutoff_is_where_it_is_documented_to_be() {
        // Stated exactly, because "roughly a month" is how an off-by-one lives
        // for a year: the job deletes days strictly older than today minus the
        // window, so the window's own edge survives.
        plantDay(TODAY.minusDays(retainedDays));
        plantDay(TODAY.minusDays(retainedDays + 1L));

        retention.prune();

        assertThat(survivingDays()).containsExactly(TODAY.minusDays(retainedDays));
    }

    @Test
    @DisplayName("current state is never pruned, however long ago it was probed")
    void state_outlives_its_rollups() {
        // An environment nobody has probed lately is still DOWN. Deleting its
        // state row would turn "down for six weeks" into "never observed",
        // which the console renders as an outline rather than a reading - a
        // quiet outage would disappear from the catalog exactly when it had
        // lasted longest.
        jdbc.update(
                """
                insert into environment_state (
                    environment_id, org_id, service_id, status, detail,
                    last_probe_at, consecutive_failures, version, updated_at)
                values (?, ?, ?, 'DOWN', 'Nothing answered at the probe address.', ?, 500, 0, now())
                """,
                environmentId,
                ORG,
                serviceId,
                java.sql.Timestamp.from(NOW.minusSeconds(90L * 24 * 3600)));
        plantDay(TODAY.minusDays(400));

        retention.prune();

        assertThat(jdbc.queryForObject(
                        "select status from environment_state where environment_id = ?", String.class, environmentId))
                .isEqualTo("DOWN");
        assertThat(dayCount()).as("its rollups are gone, but its state is not").isZero();
    }

    @Test
    @DisplayName("an idempotency key outlives the retries of its own request, and not much longer")
    void idempotency_keys_are_kept_briefly_but_long_enough() {
        // Too short and a retry double-counts, silently: nothing looks broken,
        // the numbers are simply wrong. Too long and the table is a log. The
        // configured window has to be far longer than any observer's retry
        // budget and far shorter than the rollups it protects.
        plantBatch("recent-key-000001", NOW.minusSeconds(3600));
        plantBatch("stale-key-0000001", NOW.minusSeconds((retainKeyHours + 1L) * 3600));

        retention.prune();

        assertThat(jdbc.queryForList("select idempotency_key from observation_batch", String.class))
                .containsExactly("recent-key-000001");
    }

    @Test
    @DisplayName("a prune with nothing to remove changes nothing and does not fail")
    void pruning_is_safe_when_there_is_nothing_to_do() {
        plantDay(TODAY);
        plantBatch("fresh-key-0000001", NOW);

        retention.prune();
        retention.prune();

        assertThat(dayCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from observation_batch", Integer.class))
                .isEqualTo(1);
    }

    // -- helpers ------------------------------------------------------------

    private void plantDay(LocalDate day) {
        jdbc.update(
                """
                insert into environment_day (
                    environment_id, day, org_id, service_id, probes, successes,
                    response_ms_sum, updated_at)
                values (?, ?, ?, ?, 10, 10, 1000, now())
                """,
                environmentId,
                java.sql.Date.valueOf(day),
                ORG,
                serviceId);
    }

    private void plantBatch(String key, Instant receivedAt) {
        jdbc.update(
                """
                insert into observation_batch (org_id, idempotency_key, received_at, observations, response)
                values (?, ?, ?, 1, '{"applied":1,"ignored":0,"replayed":false}'::jsonb)
                """,
                ORG,
                key,
                java.sql.Timestamp.from(receivedAt));
    }

    /** How many daily rows the read model serves for a window, as the console asks. */
    private int daysServed(int window) {
        EnvironmentHealth found =
                health.forService(ORG, serviceId, window).get(environmentId);
        return found == null ? 0 : found.days().size();
    }

    private void plantState() {
        jdbc.update(
                """
                insert into environment_state (
                    environment_id, org_id, service_id, status,
                    last_probe_at, consecutive_failures, version, updated_at)
                values (?, ?, ?, 'HEALTHY', ?, 0, 0, now())
                """,
                environmentId,
                ORG,
                serviceId,
                java.sql.Timestamp.from(NOW));
    }

    private Integer dayCount() {
        return jdbc.queryForObject("select count(*) from environment_day", Integer.class);
    }

    private java.util.List<LocalDate> survivingDays() {
        return jdbc.queryForList("select day from environment_day order by day", java.sql.Date.class).stream()
                .map(java.sql.Date::toLocalDate)
                .toList();
    }
}
