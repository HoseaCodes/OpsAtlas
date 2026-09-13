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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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

    private UUID theirServiceId;
    private UUID theirSourceId;

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

        jdbc.update(
                """
                insert into environment (id, org_id, service_id, name, url, version, created_at, updated_at)
                values (?, ?, ?, 'production', 'https://secret.example.com', 0, now(), now())
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

    @AfterEach
    void removeAnotherOrganizationsData() {
        jdbc.update("delete from source where org_id = ?", THEIRS);
        jdbc.update("delete from environment where org_id = ?", THEIRS);
        jdbc.update("delete from service where org_id = ?", THEIRS);
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
