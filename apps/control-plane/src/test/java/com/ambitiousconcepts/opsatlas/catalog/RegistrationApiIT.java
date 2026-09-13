package com.ambitiousconcepts.opsatlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Registration end to end, over HTTP, against a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationApiIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String fixture(String name) {
        try {
            return Files.readString(EXAMPLES.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    /**
     * Edit a fixture, asserting the edit actually applied.
     *
     * <p>A plain {@code replace} that matches nothing returns the original
     * string, so a test built on one can send an unchanged manifest and still
     * satisfy assertions that were meant to prove the change took effect. This
     * fails loudly instead.
     */
    private static String edit(String document, String from, String to) {
        assertThat(document).as("fixture should contain '%s' to edit", from).contains(from);
        String edited = document.replace(from, to);
        assertThat(edited).as("the edit should have changed the document").isNotEqualTo(document);
        return edited;
    }

    private MvcResult register(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture(name)))
                .andReturn();
    }

    // -- Creating -----------------------------------------------------------

    @Test
    @DisplayName("registering a manifest creates the service and returns it")
    void registers_a_service() throws Exception {
        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture("orders-api.yaml")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/services/orders-api"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.slug").value("orders-api"))
                .andExpect(jsonPath("$.displayName").value("Orders API"))
                .andExpect(jsonPath("$.repository").value("ambitious-concepts/orders-api"))
                .andExpect(jsonPath("$.tier").value(1))
                .andExpect(jsonPath("$.owner").value("ambitious-concepts"))
                .andExpect(jsonPath("$.schemaVersion").value("opsatlas.ambitiousconcepts.io/v1"))
                .andExpect(jsonPath("$.manifestDigest").isNotEmpty())
                .andExpect(jsonPath("$.sourcePath").value("service.yaml"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("environments are stored with the service's health paths")
    void stores_environments() throws Exception {
        register("orders-api.yaml");

        mockMvc.perform(get("/api/v1/services/orders-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environments.length()").value(2))
                // Sorted by name, so the response is stable across requests.
                .andExpect(jsonPath("$.environments[0].name").value("production"))
                .andExpect(jsonPath("$.environments[0].url").value("https://orders.example.com"))
                .andExpect(jsonPath("$.environments[0].readinessPath").value("/actuator/health/readiness"))
                .andExpect(jsonPath("$.environments[0].livenessPath").value("/actuator/health/liveness"))
                .andExpect(jsonPath("$.environments[1].name").value("staging"))
                // Nothing observes anything in this phase, and the API says so
                // with a real null rather than by omitting the field.
                .andExpect(jsonPath("$.environments[0].lastObservedAt").doesNotExist());
    }

    @Test
    @DisplayName("the normalized manifest is returned alongside the flat fields")
    void returns_the_stored_manifest() throws Exception {
        register("orders-api.yaml");

        MvcResult result = mockMvc.perform(get("/api/v1/services/orders-api"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode manifest =
                MAPPER.readTree(result.getResponse().getContentAsString()).get("manifest");

        assertThat(manifest.at("/spec/journeys/0").asText()).isEqualTo("Place an order");
        assertThat(manifest.at("/spec/operations/slo/availability").asDouble()).isEqualTo(99.9);
        assertThat(manifest.at("/spec/dependencies/1/kind").asText()).isEqualTo("datastore");
    }

    @Test
    @DisplayName("a service with no owner registers and reports no owner")
    void unowned_services_register() throws Exception {
        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture("legacy-report-runner.yaml")))
                .andExpect(status().isCreated())
                // Not an error and not an empty string: nobody is accountable,
                // and the catalog's job includes saying so.
                .andExpect(jsonPath("$.owner").doesNotExist());

        assertThat(jdbc.queryForObject("select count(*) from team", Integer.class))
                .as("an unowned manifest must not invent a team")
                .isZero();
    }

    @Test
    @DisplayName("a team is discovered from the first manifest that names it, then reused")
    void teams_are_discovered_once() throws Exception {
        register("orders-api.yaml");
        register("pricing-engine.yaml");

        assertThat(jdbc.queryForObject("select count(*) from team", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from team", String.class))
                .isEqualTo("Ambitious Concepts");
    }

    @Test
    @DisplayName("every example manifest in the repository registers over HTTP")
    void all_examples_register() throws Exception {
        for (String name : new String[] {
            "orders-api.yaml",
            "pricing-engine.yaml",
            "billing-worker.yaml",
            "customer-portal.yaml",
            "identity-bff.yaml",
            "legacy-report-runner.yaml"
        }) {
            mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture(name)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/services").param("limit", "100"))
                .andExpect(jsonPath("$.items.length()").value(6));
    }

    // -- Idempotency and conflict -------------------------------------------

    @Test
    @DisplayName("re-posting an identical manifest is a replay, not a second service")
    void identical_manifest_is_idempotent() throws Exception {
        register("orders-api.yaml");

        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture("orders-api.yaml")))
                // 200, not 201: nothing was created.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("orders-api"))
                // The version must not move. A retried request that bumped it
                // would look like an edit to anything watching.
                .andExpect(jsonPath("$.version").value(0));

        assertThat(jdbc.queryForObject("select count(*) from service", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a changed manifest at the same location is a conflict, not a silent overwrite")
    void changed_manifest_conflicts() throws Exception {
        register("orders-api.yaml");

        String edited = edit(fixture("orders-api.yaml"), "tier: 1", "tier: 2");

        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(edited))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/conflict"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                // The route forward has to be in the response, or the caller
                // is left guessing whether to retry or rename.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PUT")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("If-Match")));

        assertThat(jdbc.queryForObject("select tier from service", Integer.class))
                .as("the conflicting POST must not have changed anything")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two repositories cannot register the same service name")
    void duplicate_names_conflict() throws Exception {
        register("orders-api.yaml");

        String clashing = edit(
                fixture("orders-api.yaml"),
                "repository: ambitious-concepts/orders-api",
                "repository: ambitious-concepts/orders-api-fork");

        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(clashing))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].pointer").value("/metadata/name"));
    }

    // -- Updating -----------------------------------------------------------

    @Nested
    @DisplayName("updating")
    class Updating {

        @Test
        @DisplayName("a matching If-Match applies the change and moves the version")
        void update_with_matching_if_match() throws Exception {
            register("orders-api.yaml");
            String edited = edit(fixture("orders-api.yaml"), "tier: 1", "tier: 2");

            mockMvc.perform(put("/api/v1/services/orders-api")
                            .contentType(YAML)
                            .header("If-Match", "\"0\"")
                            .content(edited))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tier").value(2))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(header().string("ETag", "\"1\""));
        }

        @Test
        @DisplayName("a missing If-Match is refused with 428, not applied")
        void update_without_if_match() throws Exception {
            register("orders-api.yaml");
            String edited = edit(fixture("orders-api.yaml"), "tier: 1", "tier: 2");

            mockMvc.perform(put("/api/v1/services/orders-api").contentType(YAML).content(edited))
                    .andExpect(status().isPreconditionRequired())
                    .andExpect(jsonPath("$.type")
                            .value("https://opsatlas.ambitiousconcepts.io/problems/precondition-required"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("If-Match")));

            assertThat(jdbc.queryForObject("select tier from service", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a stale If-Match is refused with 412")
        void update_with_stale_if_match() throws Exception {
            register("orders-api.yaml");
            String edited = edit(fixture("orders-api.yaml"), "tier: 1", "tier: 2");

            mockMvc.perform(put("/api/v1/services/orders-api")
                            .contentType(YAML)
                            .header("If-Match", "\"7\"")
                            .content(edited))
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Re-read")));
        }

        @Test
        @DisplayName("an unparseable If-Match is treated as absent, never as a match")
        void unparseable_if_match_is_not_a_match() throws Exception {
            register("orders-api.yaml");

            mockMvc.perform(put("/api/v1/services/orders-api")
                            .contentType(YAML)
                            .header("If-Match", "\"not-a-version\"")
                            .content(fixture("orders-api.yaml")))
                    .andExpect(status().isPreconditionRequired());
        }

        @Test
        @DisplayName("a rename is refused, because the name is the identity")
        void renames_are_refused() throws Exception {
            register("orders-api.yaml");
            String renamed = edit(fixture("orders-api.yaml"), "name: orders-api", "name: orders-api-v2");

            mockMvc.perform(put("/api/v1/services/orders-api")
                            .contentType(YAML)
                            .header("If-Match", "\"0\"")
                            .content(renamed))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.violations[0].pointer").value("/metadata/name"))
                    .andExpect(jsonPath("$.violations[0].keyword").value("immutable"));
        }

        @Test
        @DisplayName("an added environment appears; a removed one goes; a kept one keeps its id")
        void environments_are_reconciled_by_name() throws Exception {
            register("orders-api.yaml");

            String productionId = jdbc.queryForObject(
                    "select id::text from environment where name = 'production'", String.class);

            String edited = edit(fixture("orders-api.yaml"), "name: staging", "name: canary");

            mockMvc.perform(put("/api/v1/services/orders-api")
                            .contentType(YAML)
                            .header("If-Match", "\"0\"")
                            .content(edited))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.environments.length()").value(2))
                    .andExpect(jsonPath("$.environments[0].name").value("canary"))
                    .andExpect(jsonPath("$.environments[1].name").value("production"));

            // The surviving environment keeps its identity. Once observations
            // hang off this id, churning it on every manifest edit would throw
            // away the history attached to it.
            assertThat(jdbc.queryForObject(
                            "select id::text from environment where name = 'production'", String.class))
                    .isEqualTo(productionId);
        }

        @Test
        @DisplayName("updating an unknown service is a 404")
        void update_unknown_service() throws Exception {
            mockMvc.perform(put("/api/v1/services/never-registered")
                            .contentType(YAML)
                            .header("If-Match", "\"0\"")
                            .content(fixture("orders-api.yaml")))
                    .andExpect(status().isNotFound());
        }
    }

    // -- Rejection ----------------------------------------------------------

    @Test
    @DisplayName("an invalid manifest is rejected with located violations and stores nothing")
    void invalid_manifest_is_rejected() throws Exception {
        String bad = Files.readString(EXAMPLES.resolve("invalid").resolve("tier-out-of-range.yaml"));

        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/validation-failed"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.violations[0].pointer").value("/spec/tier"))
                .andExpect(jsonPath("$.violations[0].message").isNotEmpty());

        assertThat(jdbc.queryForObject("select count(*) from service", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("a JSON body is refused with the content types that would work")
    void wrong_content_type_is_refused() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiVersion\":\"opsatlas.ambitiousconcepts.io/v1\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("application/yaml")));
    }

    @Test
    @DisplayName("a registered service appears in the paginated list")
    void registered_services_are_listed() throws Exception {
        register("orders-api.yaml");
        register("pricing-engine.yaml");

        mockMvc.perform(get("/api/v1/services").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("orders-api"))
                .andExpect(jsonPath("$.items[0].owned").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    @DisplayName("paging with the returned cursor walks the catalog exactly once")
    void cursor_paging_walks_every_row_once() throws Exception {
        for (String name : new String[] {
            "orders-api.yaml", "pricing-engine.yaml", "billing-worker.yaml", "customer-portal.yaml"
        }) {
            register(name);
        }

        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            var request = get("/api/v1/services").param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode body = MAPPER.readTree(mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());

            body.get("items").forEach(item -> seen.add(item.get("slug").asText()));
            if (body.get("nextCursor").isNull()) {
                break;
            }
            cursor = body.get("nextCursor").asText();
        }

        assertThat(seen).hasSize(4).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("fetching an unknown service is a 404 with a correlation id")
    void unknown_service_is_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/services/never-registered"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/not-found"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
