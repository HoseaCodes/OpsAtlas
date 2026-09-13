package com.ambitiousconcepts.opsatlas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceCatalog;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The test ADR 0003 names as the thing that actually protects the organization
 * boundary.
 *
 * <p>Scoping in this codebase is an explicit {@code orgId} argument on every
 * repository method. Nothing in the type system enforces that, and in a system
 * with one organization a missing filter behaves perfectly - the bug is
 * invisible until a second organization exists, which is exactly when it is
 * most expensive. So a second organization is created here, with a service in
 * it, and every read path is asked whether it leaks.
 *
 * <p>The stub resolver always answers with the seeded organization, so the API
 * under test is permanently "organization one" and the planted rows are
 * permanently someone else's.
 *
 * <p><strong>This test must grow whenever an endpoint is added.</strong> An
 * endpoint it does not cover has unverified isolation, and unverified is what it
 * must be called.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrgIsolationIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    /** The organization the stub resolver returns. Every request runs as this one. */
    private static final UUID OURS = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** A second organization, which no request in this test is ever acting as. */
    private static final UUID THEIRS = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SourceCatalog sources;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private UUID theirServiceId;
    private UUID theirSourceId;
    private UUID theirEnvironmentId;

    /**
     * Endpoints this test asks about another organization's data.
     *
     * <p>Listed rather than discovered, so that adding an endpoint and not
     * covering it here is a build failure instead of something a reviewer has to
     * notice. The rule already existed in {@code CLAUDE.md} and was still missed
     * three times, because nothing failed when it was.
     */
    private static final Set<String> COVERED = Set.of(
            "GET /api/v1/services",
            "POST /api/v1/services",
            "GET /api/v1/services/{slug}",
            "PUT /api/v1/services/{slug}",
            "GET /api/v1/services/{slug}/scorecard",
            "GET /api/v1/services/{slug}/health",
            "GET /api/v1/audit-events",
            "GET /api/v1/health",
            "POST /api/v1/observations",
            "GET /api/v1/sources",
            "POST /api/v1/sources",
            "GET /api/v1/sources/{id}",
            "DELETE /api/v1/sources/{id}",
            "POST /api/v1/sources/{id}/sync",
            "POST /api/v1/sources/{id}/enable",
            "POST /api/v1/sources/{id}/disable");

    /**
     * Endpoints that hold no tenant data, with the reason each one is exempt.
     *
     * <p>An exemption is a claim, so it is written down where it can be argued
     * with rather than left as an absence.
     */
    private static final Set<String> NOT_TENANT_SCOPED = Set.of(
            // The rule catalog is the same ten rules for everybody, computed
            // from PolicyCatalog and never from the database.
            "GET /api/v1/policy/rules");

    @BeforeEach
    void plantAnotherOrganizationsData() {
        jdbc.update("delete from source");
        jdbc.update("delete from environment");
        jdbc.update("delete from service");
        jdbc.update("delete from team");
        jdbc.update("delete from organization where id = ?", THEIRS);

        jdbc.update(
                "insert into organization (id, slug, name) values (?, 'other-tenant', 'Other Tenant')",
                THEIRS);

        theirServiceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into service (
                    id, org_id, slug, repository, tier, lifecycle, schema_version,
                    manifest, manifest_digest, source_path, version, created_at, updated_at)
                values (?, ?, 'their-secret-api', 'other-tenant/their-secret-api', 1, 'active',
                    'opsatlas.ambitiousconcepts.io/v1', '{}'::jsonb, ?, 'service.yaml', 0, now(), now())
                """,
                theirServiceId,
                THEIRS,
                "f".repeat(64));

        theirEnvironmentId = UUID.randomUUID();
        jdbc.update(
                """
                insert into environment (id, org_id, service_id, name, url, version, created_at, updated_at)
                values (?, ?, ?, 'production', 'https://secret.example.com', 0, now(), now())
                """,
                theirEnvironmentId,
                THEIRS,
                theirServiceId);

        // Health they have already been observed to have. A read path that
        // forgets its orgId returns this, and it is the kind of thing a
        // competitor would pay for: whose systems are down, and for how long.
        jdbc.update(
                """
                insert into environment_state (
                    environment_id, org_id, service_id, status, detail,
                    last_probe_at, consecutive_failures, version, updated_at)
                values (?, ?, ?, 'DOWN', 'Nothing answered at the probe address.', now(), 10, 0, now())
                """,
                theirEnvironmentId,
                THEIRS,
                theirServiceId);

        jdbc.update(
                """
                insert into environment_day (
                    environment_id, day, org_id, service_id, probes, successes,
                    response_ms_sum, updated_at)
                values (?, current_date, ?, ?, 10, 0, 0, now())
                """,
                theirEnvironmentId,
                THEIRS,
                theirServiceId);

        jdbc.update(
                """
                insert into audit_event (
                    id, org_id, occurred_at, actor, action, subject_type, subject_id,
                    correlation_id, payload)
                values (?, ?, now(), 'their-operator', 'service.registered', 'service', ?,
                    'their-correlation-id', '{"slug":"their-secret-api"}'::jsonb)
                """,
                UUID.randomUUID(),
                THEIRS,
                theirServiceId);

        theirSourceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into source (id, org_id, provider, repository, git_ref, path, enabled,
                                    consecutive_failures, service_id, version, created_at, updated_at)
                values (?, ?, 'github', 'other-tenant/their-secret-api', 'main', 'service.yaml', true,
                        0, ?, 0, now(), now())
                """,
                theirSourceId,
                THEIRS,
                theirServiceId);
    }

    /**
     * Symmetrical with the reset above, deliberately.
     *
     * <p>Several tests here register a service of our own - a list that excludes
     * their rows proves nothing if it is empty, so there has to be something in
     * it. Those rows would otherwise outlive this class and reach whichever test
     * runs next, which is how a suite acquires an ordering dependency that
     * nobody can see.
     */
    @AfterEach
    void removeAnotherOrganizationsData() {
        jdbc.update("delete from audit_event where org_id = ?", THEIRS);
        jdbc.update("delete from source");
        jdbc.update("delete from environment");
        jdbc.update("delete from service");
        jdbc.update("delete from team");
        jdbc.update("delete from organization where id = ?", THEIRS);
    }

    @Test
    @DisplayName("another organization's service is not in the list")
    void list_excludes_other_organizations() throws Exception {
        mockMvc.perform(get("/api/v1/services").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("our own services are listed while theirs are not")
    void list_includes_only_our_own() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(YAML)
                        .content(Files.readString(EXAMPLES.resolve("orders-api.yaml"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/services").param("limit", "100"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("orders-api"));
    }

    @Test
    @DisplayName("fetching another organization's service is 404, never 403")
    void fetch_reports_absent_not_forbidden() throws Exception {
        // 403 would confirm the service exists, which is an existence leak
        // across the boundary. Absent and forbidden must be indistinguishable.
        mockMvc.perform(get("/api/v1/services/their-secret-api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/not-found"));
    }

    @Test
    @DisplayName("the 404 body does not disclose anything about the hidden service")
    void the_not_found_body_leaks_nothing() throws Exception {
        String body = mockMvc.perform(get("/api/v1/services/their-secret-api"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("other-tenant")
                .doesNotContain("secret.example.com")
                .doesNotContain(THEIRS.toString())
                .doesNotContain(theirServiceId.toString());
    }

    @Test
    @DisplayName("updating another organization's service is 404, and changes nothing")
    void update_cannot_reach_across_the_boundary() throws Exception {
        mockMvc.perform(put("/api/v1/services/their-secret-api")
                        .contentType(YAML)
                        .header("If-Match", "\"0\"")
                        .content(Files.readString(EXAMPLES.resolve("orders-api.yaml"))))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                        "select version from service where id = ?", Long.class, theirServiceId))
                .as("their row must be untouched")
                .isZero();
    }

    @Test
    @DisplayName("their service name does not block us registering the same name")
    void names_are_unique_per_organization_not_globally() throws Exception {
        // If uniqueness were global rather than per organization, one tenant
        // could deny another the use of a name just by taking it first.
        String manifest = Files.readString(EXAMPLES.resolve("orders-api.yaml"))
                .replace("name: orders-api", "name: their-secret-api")
                .replace("serviceName: orders-api", "serviceName: their-secret-api");

        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(manifest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("their-secret-api"));

        assertThat(jdbc.queryForObject(
                        "select count(*) from service where slug = 'their-secret-api'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the database refuses a service pointed at another organization's team")
    void the_database_refuses_a_cross_organization_team() {
        UUID ourTeam = UUID.randomUUID();
        jdbc.update(
                "insert into team (id, org_id, slug, name, created_at, updated_at)"
                        + " values (?, ?, 'ours', 'Ours', now(), now())",
                ourTeam,
                OURS);

        // The composite foreign key (org_id, team_id) -> team (org_id, id) is
        // what makes this impossible rather than merely unlikely. Without it,
        // every single-column constraint here would be satisfied.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into service (
                            id, org_id, team_id, slug, repository, tier, lifecycle, schema_version,
                            manifest, manifest_digest, source_path, version, created_at, updated_at)
                        values (?, ?, ?, 'smuggled', 'other-tenant/smuggled', 1, 'active',
                            'opsatlas.ambitiousconcepts.io/v1', '{}'::jsonb, ?, 'service.yaml', 0, now(), now())
                        """,
                        UUID.randomUUID(),
                        THEIRS,
                        ourTeam,
                        "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbc.update("delete from team where id = ?", ourTeam);
    }

    // -- Sources (phase 6) --------------------------------------------------

    @Test
    @DisplayName("another organization's sources are not in the list")
    void source_list_excludes_other_organizations() throws Exception {
        mockMvc.perform(get("/api/v1/sources").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("fetching another organization's source is 404, never 403")
    void fetching_their_source_reports_absent() throws Exception {
        mockMvc.perform(get("/api/v1/sources/" + theirSourceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/not-found"));
    }

    @Test
    @DisplayName("the 404 for their source discloses nothing about it")
    void their_source_404_leaks_nothing() throws Exception {
        String body = mockMvc.perform(get("/api/v1/sources/" + theirSourceId))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("other-tenant").doesNotContain("their-secret-api");
    }

    @Test
    @DisplayName("syncing another organization's source is 404, and does not touch it")
    void syncing_their_source_is_refused() throws Exception {
        mockMvc.perform(post("/api/v1/sources/" + theirSourceId + "/sync"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                        "select last_attempt_at from source where id = ?", java.sql.Timestamp.class, theirSourceId))
                .as("their source must not have been polled on our behalf")
                .isNull();
    }

    @Test
    @DisplayName("disabling another organization's source is 404, and leaves it enabled")
    void disabling_their_source_is_refused() throws Exception {
        mockMvc.perform(post("/api/v1/sources/" + theirSourceId + "/disable"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select enabled from source where id = ?", Boolean.class, theirSourceId))
                .isTrue();
    }

    @Test
    @DisplayName("deleting another organization's source is 404, and it survives")
    void deleting_their_source_is_refused() throws Exception {
        mockMvc.perform(delete("/api/v1/sources/" + theirSourceId))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from source where id = ?", Integer.class, theirSourceId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("every endpoint in the API is either covered here or exempt with a reason")
    void no_endpoint_escapes_this_test() {
        Set<String> live = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            for (String path : paths(info)) {
                if (!path.startsWith("/api/v1")) {
                    // springdoc's own document endpoints. They serve the
                    // contract, not tenant rows.
                    continue;
                }
                info.getMethodsCondition().getMethods().forEach(method -> live.add(method + " " + path));
            }
        });

        Set<String> uncovered = new TreeSet<>(live);
        uncovered.removeAll(COVERED);
        uncovered.removeAll(NOT_TENANT_SCOPED);

        assertThat(uncovered)
                .as(
                        "These endpoints have unverified cross-organization isolation. Add a test "
                                + "here that asks each one about another organization's data, then list it in "
                                + "COVERED - or, if it genuinely holds no tenant data, in NOT_TENANT_SCOPED "
                                + "with the reason. CLAUDE.md section 2 requires it.")
                .isEmpty();

        Set<String> stale = new TreeSet<>(COVERED);
        stale.addAll(NOT_TENANT_SCOPED);
        stale.removeAll(live);

        assertThat(stale)
                .as("These are listed as covered or exempt but no longer exist; remove them")
                .isEmpty();
    }

    private static Set<String> paths(RequestMappingInfo info) {
        Set<String> patterns = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition()
                    .getPatterns()
                    .forEach(pattern -> patterns.add(pattern.getPatternString()));
        }
        return patterns;
    }

    // -- Scorecard and audit (phase 3) --------------------------------------

    @Test
    @DisplayName("another organization's scorecard is 404, never a score")
    void their_scorecard_is_not_readable() throws Exception {
        mockMvc.perform(get("/api/v1/services/their-secret-api/scorecard"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/not-found"));
    }

    @Test
    @DisplayName("our audit events are listed and theirs are not")
    void audit_log_excludes_other_organizations() throws Exception {
        // An audit log is a record of who did what, and it is the one table
        // here that is deliberately never deleted. A missing orgId filter would
        // publish another tenant's entire operational history.
        //
        // Registering first is what stops this asserting nothing: an endpoint
        // that always returned an empty page would satisfy every
        // doesNotContain below.
        mockMvc.perform(post("/api/v1/services")
                        .contentType(YAML)
                        .content(Files.readString(EXAMPLES.resolve("orders-api.yaml"))))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/v1/audit-events").param("limit", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("our own registration must be in our audit log").contains("orders-api");
        assertThat(body)
                .doesNotContain("their-operator")
                .doesNotContain("their-correlation-id")
                .doesNotContain("their-secret-api");
    }

    // -- Health (phase 7) ---------------------------------------------------

    @Test
    @DisplayName("another organization's service health is 404, not a reading")
    void their_service_health_is_not_readable() throws Exception {
        mockMvc.perform(get("/api/v1/services/their-secret-api/health"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/not-found"));
    }

    @Test
    @DisplayName("the fleet summary answers for our services and not for theirs")
    void fleet_health_does_not_answer_for_their_services() throws Exception {
        // This endpoint takes service ids from the caller, so it is the one
        // health path where guessing an identifier is the whole attack. The
        // answer for a foreign id must be indistinguishable from "never
        // observed", which is what an absent entry means - and their
        // environment is planted as DOWN, so a leak would be unmistakable.
        //
        // One of our own services is observed first and asked about in the same
        // request, so this cannot pass by the endpoint answering nobody.
        Registered ours = registerAndObserveOurOwn();

        mockMvc.perform(get("/api/v1/health")
                        .param("serviceIds", ours.serviceId().toString())
                        .param("serviceIds", theirServiceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses." + ours.serviceId()).value("HEALTHY"))
                .andExpect(jsonPath("$.statuses." + theirServiceId).doesNotExist());
    }

    /** A service of ours with one observed environment, for positive controls. */
    private Registered registerAndObserveOurOwn() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(YAML)
                        .content(Files.readString(EXAMPLES.resolve("orders-api.yaml"))))
                .andExpect(status().isCreated());

        String detail = mockMvc.perform(get("/api/v1/services/orders-api"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        com.fasterxml.jackson.databind.JsonNode node = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(detail);
        UUID serviceId = UUID.fromString(node.get("id").asText());
        UUID environmentId =
                UUID.fromString(node.get("environments").get(0).get("id").asText());

        mockMvc.perform(post("/api/v1/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "idempotencyKey": "our-own-batch-0001",
                                  "observerId": "test-observer",
                                  "observations": [
                                    {"environmentId": "%s", "observedAt": "%s", "outcome": "HEALTHY", "responseMs": 12}
                                  ]
                                }
                                """
                                        .formatted(environmentId, java.time.Instant.now())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.applied").value(1));

        return new Registered(serviceId, environmentId);
    }

    private record Registered(UUID serviceId, UUID environmentId) {}

    // -- Observation ingestion (phase 7) ------------------------------------

    @Test
    @DisplayName("observations for another organization's environment are ignored, never applied")
    void observations_cannot_be_written_into_another_organization() throws Exception {
        // The only machine-facing write in the system, and it takes an
        // environment id straight from the request body. If it trusted that id,
        // anything that could reach the endpoint could fabricate health for a
        // tenant it cannot even read.
        String batch =
                """
                {
                  "idempotencyKey": "cross-org-attempt-0001",
                  "observerId": "test-observer",
                  "observations": [
                    {"environmentId": "%s", "observedAt": "%s", "outcome": "HEALTHY", "responseMs": 12}
                  ]
                }
                """
                        .formatted(theirEnvironmentId, java.time.Instant.now().toString());

        mockMvc.perform(post("/api/v1/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.ignored").value(1));

        // Ignored means ignored: their counters must read exactly as planted.
        assertThat(jdbc.queryForObject(
                        "select status from environment_state where environment_id = ?",
                        String.class,
                        theirEnvironmentId))
                .isEqualTo("DOWN");
        assertThat(jdbc.queryForObject(
                        "select probes from environment_day where environment_id = ?",
                        Integer.class,
                        theirEnvironmentId))
                .as("a foreign observation must not move another organization's probe count")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("the database refuses health recorded against another organization's environment")
    void the_database_refuses_cross_organization_health() {
        // The application-level check above is the good error message; this is
        // the correctness (CLAUDE.md section 7). The composite foreign key
        // (org_id, environment_id) -> environment (org_id, id) is what makes a
        // scoping bug in operations impossible to persist rather than merely
        // unlikely.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into environment_state (
                            environment_id, org_id, service_id, status,
                            last_probe_at, consecutive_failures, version, updated_at)
                        values (?, ?, ?, 'HEALTHY', now(), 0, 0, now())
                        """,
                        theirEnvironmentId,
                        OURS,
                        theirServiceId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // -- Sources, continued -------------------------------------------------

    @Test
    @DisplayName("enabling another organization's source is 404, and changes nothing")
    void enabling_their_source_is_refused() throws Exception {
        jdbc.update("update source set enabled = false where id = ?", theirSourceId);

        mockMvc.perform(post("/api/v1/sources/" + theirSourceId + "/enable"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select enabled from source where id = ?", Boolean.class, theirSourceId))
                .as("their disabled source must not be startable by us")
                .isFalse();
    }

    @Test
    @DisplayName("their watched repository does not block us watching the same one")
    void watching_is_scoped_per_organization() {
        // Uniqueness is per organization. If it were global, one tenant could
        // deny another the ability to watch a public repository by watching it
        // first.
        SourceView ours = sources.watch(
                OURS, "github", SourceRef.of("other-tenant/their-secret-api", "main", "service.yaml"));

        assertThat(ours.id()).isNotEqualTo(theirSourceId);
        assertThat(jdbc.queryForObject(
                        "select count(*) from source where repository = 'other-tenant/their-secret-api'",
                        Integer.class))
                .isEqualTo(2);
    }
}
