package com.ambitiousconcepts.opsatlas.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ambitiousconcepts.opsatlas.integrations.api.SourceCatalog;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceView;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.ambitiousconcepts.opsatlas.support.StubGitHub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Syncing a watched repository, against a GitHub that answers on localhost.
 *
 * <p>The property this suite is really about: <strong>a failing sync never
 * erases what is already known.</strong> Every failure case below registers a
 * service first, then breaks the source, then asserts the service is still there
 * and still correct. A catalog that empties itself when an upstream has a bad
 * afternoon is worse than one that is briefly stale.
 */
@SpringBootTest
class SourceSyncIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String REPO = "ambitious-concepts/orders-api";

    private static final StubGitHub GITHUB = new StubGitHub();

    @DynamicPropertySource
    static void pointAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("opsatlas.github.base-url", GITHUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        GITHUB.close();
    }

    @Autowired
    private SourceCatalog sources;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("delete from source");
        jdbc.update("delete from policy_result_check");
        jdbc.update("delete from policy_result");
        jdbc.update("delete from audit_event");
        jdbc.update("delete from environment");
        jdbc.update("delete from service");
        jdbc.update("delete from team");
        GITHUB.reset();
    }

    private static String manifest(String name) {
        try {
            return Files.readString(EXAMPLES.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    private SourceView watch() {
        return sources.watch(ORG, "github", SourceRef.of(REPO, "main", "service.yaml"));
    }

    private SourceView sync(SourceView source) {
        return sources.syncNow(ORG, source.id());
    }

    // -- The happy path -----------------------------------------------------

    @Test
    @DisplayName("a first sync registers the service the repository declares")
    void first_sync_registers() {
        GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));

        SourceView synced = sync(watch());

        assertThat(synced.lastOutcome()).isEqualTo("REGISTERED");
        assertThat(synced.lastDetail()).isNull();
        assertThat(synced.serviceId()).isNotNull();
        assertThat(synced.lastSuccessAt()).isNotNull();
        assertThat(synced.consecutiveFailures()).isZero();

        assertThat(jdbc.queryForObject("select slug from service", String.class)).isEqualTo("orders-api");
    }

    @Test
    @DisplayName("a polled manifest is scored and audited exactly like a pasted one")
    void polled_manifests_are_not_a_second_class_path() {
        // There is no second ingestion path, and this is what proves it: the
        // scorecard and audit entry a poll produces are the same ones a paste
        // produces, written in the same transaction.
        GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));

        sync(watch());

        assertThat(jdbc.queryForObject("select checks_passed from policy_result", Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("select action from audit_event", String.class))
                .isEqualTo("service.registered");
    }

    @Test
    @DisplayName("an unchanged manifest costs a conditional request and nothing else")
    void second_sync_is_conditional() {
        GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
        SourceView source = watch();
        sync(source);

        SourceView second = sync(source);

        assertThat(second.lastOutcome()).isEqualTo("UNCHANGED");
        // The ETag from the first response was replayed, and GitHub answered 304.
        assertThat(GITHUB.ifNoneMatchHeaders()).hasSize(1);
        // Nothing was re-scored, because nothing was re-read.
        assertThat(jdbc.queryForObject("select count(*) from policy_result", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an edited manifest updates the service and re-scores it")
    void changed_manifest_updates() {
        GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
        SourceView source = watch();
        sync(source);

        // The repository is authoritative for its own manifest, so a change
        // there applies without an If-Match round trip (ADR 0008).
        GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml").replace("tier: 1", "tier: 2"));

        SourceView updated = sync(source);

        assertThat(updated.lastOutcome()).isEqualTo("UPDATED");
        assertThat(jdbc.queryForObject("select tier from service", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from policy_result", Integer.class))
                .as("an update re-scores; a scorecard for a manifest that has changed would look current and be wrong")
                .isEqualTo(2);
    }

    // -- Failures, and what survives them -----------------------------------

    @Nested
    @DisplayName("when a sync fails")
    class Failures {

        private SourceView registerThenBreak(StubGitHub.Response breakage) {
            GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
            SourceView source = watch();
            sync(source);
            GITHUB.route(REPO, "service.yaml", breakage);
            return sync(source);
        }

        private void assertServiceSurvived() {
            assertThat(jdbc.queryForObject("select count(*) from service", Integer.class))
                    .as("a failing source is not evidence that the service stopped existing")
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select tier from service", Integer.class))
                    .as("the last known manifest is untouched")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an unreachable provider leaves the catalog stale, not empty")
        void unreachable() {
            GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
            SourceView source = watch();
            sync(source);

            SourceView failed = registerThenBreak(new StubGitHub.Response.ServerError());

            assertThat(failed.lastOutcome()).isEqualTo("UNREACHABLE");
            assertThat(failed.lastDetail()).isNotBlank();
            // The answer to "how current is this", which lastAttemptAt alone
            // cannot give.
            assertThat(failed.lastSuccessAt()).isNotNull().isBefore(failed.lastAttemptAt());
            assertServiceSurvived();
        }

        @Test
        @DisplayName("a deleted manifest is NOT_FOUND, and does not retire the service")
        void not_found() {
            SourceView failed = registerThenBreak(new StubGitHub.Response.NotFound());

            assertThat(failed.lastOutcome()).isEqualTo("NOT_FOUND");
            assertThat(failed.lastDetail()).contains("service.yaml");
            // Deciding a service is gone is not a decision a 404 on one poll
            // should make.
            assertServiceSurvived();
        }

        @Test
        @DisplayName("rejected credentials are reported as such, not as unreachable")
        void unauthorized() {
            SourceView failed = registerThenBreak(new StubGitHub.Response.Unauthorized());

            assertThat(failed.lastOutcome()).isEqualTo("UNAUTHORIZED");
            assertThat(failed.lastDetail()).contains("OPSATLAS_GITHUB_TOKEN");
            assertServiceSurvived();
        }

        @Test
        @DisplayName("a 403 with quota left is a permission problem, not a rate limit")
        void forbidden_is_not_rate_limited() {
            // GitHub uses 403 for both. Conflating them would tell a reader to
            // wait when the answer is to fix a token's scope.
            SourceView failed = registerThenBreak(new StubGitHub.Response.Forbidden());

            assertThat(failed.lastOutcome()).isEqualTo("UNAUTHORIZED");
            assertThat(failed.lastDetail()).contains("permission");
        }

        @Test
        @DisplayName("a 403 with no quota left is a rate limit, and says when it lifts")
        void rate_limited() {
            SourceView failed = registerThenBreak(new StubGitHub.Response.RateLimited());

            assertThat(failed.lastOutcome()).isEqualTo("RATE_LIMITED");
            assertThat(failed.lastDetail()).contains("rate limit").contains("resets");
            assertServiceSurvived();
        }

        @Test
        @DisplayName("a manifest that stops validating is REJECTED, with the violations, and changes nothing")
        void rejected() {
            GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
            SourceView source = watch();
            sync(source);

            GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml").replace("tier: 1", "tier: 9"));
            SourceView failed = sync(source);

            assertThat(failed.lastOutcome()).isEqualTo("REJECTED");
            // The owning team can see what they broke without leaving the catalog.
            assertThat(failed.lastDetail()).contains("/spec/tier");
            assertServiceSurvived();
        }

        @Test
        @DisplayName("a manifest larger than the cap is refused before it is parsed")
        void oversized() {
            SourceView failed = registerThenBreak(new StubGitHub.Response.Oversized(70 * 1024));

            assertThat(failed.lastOutcome()).isEqualTo("UNREACHABLE");
            assertThat(failed.lastDetail()).contains("larger than");
            assertServiceSurvived();
        }

        @Test
        @DisplayName("consecutive failures accumulate, then reset on the next success")
        void failure_count_is_answerable() {
            GITHUB.route(REPO, "service.yaml", new StubGitHub.Response.NotFound());
            SourceView source = watch();

            sync(source);
            SourceView twice = sync(source);
            assertThat(twice.consecutiveFailures()).isEqualTo(2);
            assertThat(twice.lastSuccessAt()).as("it has never worked").isNull();

            GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
            SourceView recovered = sync(source);

            assertThat(recovered.lastOutcome()).isEqualTo("REGISTERED");
            assertThat(recovered.consecutiveFailures()).isZero();
        }
    }

    // -- Watching -----------------------------------------------------------

    @Test
    @DisplayName("watching the same location twice returns the same source")
    void watching_is_idempotent() {
        SourceView first = watch();
        SourceView second = watch();

        // Two sources polling one file would fight over one service row.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("select count(*) from source", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a never-synced source says so rather than pretending to be healthy")
    void unsynced_sources_are_not_healthy() {
        SourceView source = watch();

        assertThat(source.lastOutcome()).isNull();
        assertThat(source.lastAttemptAt()).isNull();
        assertThat(source.healthy()).isFalse();
    }

    @Test
    @DisplayName("unwatching leaves the service registered")
    void unwatching_does_not_retire_the_service() {
        GITHUB.serve(REPO, "service.yaml", manifest("orders-api.yaml"));
        SourceView source = watch();
        sync(source);

        sources.unwatch(ORG, source.id());

        assertThat(jdbc.queryForObject("select count(*) from source", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from service", Integer.class))
                .as("no longer watching a repository is not evidence its service is gone")
                .isEqualTo(1);
    }
}
