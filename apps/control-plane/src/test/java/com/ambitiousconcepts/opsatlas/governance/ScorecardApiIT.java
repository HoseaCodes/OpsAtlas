package com.ambitiousconcepts.opsatlas.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.catalog.api.ServiceRegistration;
import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalScope;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Scorecards and audit, over HTTP, against a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class ScorecardApiIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ServiceRegistration registration;

    @Autowired
    private PrincipalScope principals;

    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static String fixture(String name) {
        try {
            return Files.readString(EXAMPLES.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    private void register(String name) throws Exception {
        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture(name)))
                .andExpect(status().isCreated());
    }

    // -- Scoring on registration --------------------------------------------

    @Test
    @DisplayName("a fully declared tier 1 service scores 10 of 10")
    void complete_manifest_scores_full_marks() throws Exception {
        register("orders-api.yaml");

        mockMvc.perform(get("/api/v1/services/orders-api/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksPassed").value(10))
                .andExpect(jsonPath("$.checksApplicable").value(10))
                .andExpect(jsonPath("$.policySetVersion").isNotEmpty())
                .andExpect(jsonPath("$.checks.length()").value(10));
    }

    @Test
    @DisplayName("a tier 1 service missing a runbook and journeys scores 8 of 10 and says which two")
    void partial_manifest_names_what_is_missing() throws Exception {
        register("pricing-engine.yaml");

        String body = mockMvc.perform(get("/api/v1/services/pricing-engine/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksPassed").value(8))
                .andExpect(jsonPath("$.checksApplicable").value(10))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode checks = MAPPER.readTree(body).get("checks");
        java.util.List<String> failed = new java.util.ArrayList<>();
        checks.forEach(check -> {
            if ("FAIL".equals(check.get("status").asText())) {
                failed.add(check.get("checkId").asText());
                // Every failure must carry a reason. A scorecard that says
                // "8 of 10" without saying which two is a number, not a tool.
                assertThat(check.get("detail").asText()).isNotBlank().hasSizeGreaterThan(40);
            }
        });

        assertThat(failed).containsExactlyInAnyOrder("runbook-linked", "journeys-declared");
    }

    @Test
    @DisplayName("a tier 3 unowned service is excused three rules rather than failed on them")
    void tier_three_denominator_shrinks() throws Exception {
        register("legacy-report-runner.yaml");

        String body = mockMvc.perform(get("/api/v1/services/legacy-report-runner/scorecard"))
                .andExpect(status().isOk())
                // Seven applicable, not ten. The denominator moves with the tier,
                // which is what stops an internal tool reading as the worst thing
                // in the fleet.
                .andExpect(jsonPath("$.checksApplicable").value(7))
                .andExpect(jsonPath("$.checksPassed").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode checks = MAPPER.readTree(body).get("checks");
        java.util.List<String> notApplicable = new java.util.ArrayList<>();
        checks.forEach(check -> {
            if ("NOT_APPLICABLE".equals(check.get("status").asText())) {
                notApplicable.add(check.get("checkId").asText());
            }
        });

        assertThat(notApplicable)
                .containsExactlyInAnyOrder("slo-defined", "journeys-declared", "production-environment-declared");
        // All ten rules are still reported, so the matrix has no gaps.
        assertThat(checks).hasSize(10);
    }

    @Test
    @DisplayName("a worker with no environment URLs is told which environments lack one")
    void worker_scores_reflect_its_shape() throws Exception {
        register("billing-worker.yaml");

        String body = mockMvc.perform(get("/api/v1/services/billing-worker/scorecard"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode checks = MAPPER.readTree(body).get("checks");
        JsonNode urls = null;
        for (JsonNode check : checks) {
            if ("environment-urls-declared".equals(check.get("checkId").asText())) {
                urls = check;
            }
        }

        assertThat(urls).isNotNull();
        assertThat(urls.get("status").asText()).isEqualTo("FAIL");
        assertThat(urls.get("detail").asText()).contains("production").contains("staging");
    }

    // -- Re-scoring ---------------------------------------------------------

    @Test
    @DisplayName("an update re-scores, and the history keeps both evaluations")
    void updates_are_rescored_and_history_is_kept() throws Exception {
        register("pricing-engine.yaml");

        String fixed = fixture("pricing-engine.yaml")
                .replace("      window: 30d", "      window: 30d\n    runbook: docs/runbook.md");
        assertThat(fixed).contains("runbook");

        mockMvc.perform(put("/api/v1/services/pricing-engine")
                        .contentType(YAML)
                        .header("If-Match", "\"0\"")
                        .content(fixed))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/services/pricing-engine/scorecard"))
                .andExpect(jsonPath("$.checksPassed").value(9));

        // Two evaluations retained, not one overwritten. A compliance trend is
        // only recoverable if past scores survive.
        assertThat(jdbc.queryForObject("select count(*) from policy_result", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a replayed registration does not write a second scorecard")
    void replays_do_not_rescore() throws Exception {
        register("orders-api.yaml");

        mockMvc.perform(post("/api/v1/services").contentType(YAML).content(fixture("orders-api.yaml")))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from policy_result", Integer.class))
                .isEqualTo(1);
    }

    // -- The transactional guarantee ----------------------------------------

    @Test
    @DisplayName("a registration that fails leaves no service, no scorecard and no audit entry")
    void registration_is_all_or_nothing() {
        // CLAUDE.md section 8: the scorecard and the audit event are part of the
        // same transaction as the service row.
        //
        // Forcing a failure here is harder than it looks, and the first two
        // attempts did not test what they claimed. A planted row with the same
        // slug is caught by rejectIfSlugTaken before any write is issued, and so
        // is a clash on (repository, source_path) - both unique constraints are
        // pre-checked so the caller gets a 409 rather than a 500. Good design,
        // useless for this test.
        //
        // So the failure is made to land at flush instead: an organization that
        // does not exist passes every application-level check, then violates the
        // foreign key when the inserts go to the database - by which point the
        // service, its scorecard and its audit event have all been issued.
        UUID missingOrg = UUID.fromString("00000000-0000-4000-8000-00000000dead");

        assertThatThrownBy(() -> principals.runAs(
                        new Principal(missingOrg, "test", "Test"),
                        () -> registration.register(missingOrg, fixture("orders-api.yaml"), "service.yaml", null)))
                // The specific exception, so this cannot pass because something
                // unrelated failed earlier - which is exactly how the previous
                // version of this test passed while never reaching the database.
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from service", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from policy_result", Integer.class))
                .as("a scorecard must not survive a registration that did not happen")
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_event", Integer.class))
                .as("an audit entry must not survive a registration that did not happen")
                .isZero();
    }

    // -- Audit --------------------------------------------------------------

    @Test
    @DisplayName("registering writes an audit entry carrying the correlation id of the request")
    void registration_is_audited() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(YAML)
                        .header("X-Correlation-Id", "audit-trace-0001")
                        .content(fixture("orders-api.yaml")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/audit-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("service.registered"))
                .andExpect(jsonPath("$.items[0].subjectType").value("service"))
                // The link between "something happened" and "here are the log
                // lines for it" (CLAUDE.md section 9).
                .andExpect(jsonPath("$.items[0].correlationId").value("audit-trace-0001"))
                .andExpect(jsonPath("$.items[0].actor").value("local-operator"))
                .andExpect(jsonPath("$.items[0].payload.slug").value("orders-api"))
                .andExpect(jsonPath("$.items[0].payload.checksPassed").value(10))
                .andExpect(jsonPath("$.items[0].payload.manifestDigest").isNotEmpty());
    }

    @Test
    @DisplayName("an update is audited separately and records the digest it replaced")
    void updates_are_audited() throws Exception {
        register("orders-api.yaml");
        String edited = fixture("orders-api.yaml").replace("tier: 1", "tier: 2");

        mockMvc.perform(put("/api/v1/services/orders-api")
                        .contentType(YAML)
                        .header("If-Match", "\"0\"")
                        .content(edited))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-events"))
                .andExpect(jsonPath("$.items.length()").value(2))
                // Newest first.
                .andExpect(jsonPath("$.items[0].action").value("service.updated"))
                .andExpect(jsonPath("$.items[0].payload.previousManifestDigest").isNotEmpty())
                .andExpect(jsonPath("$.items[1].action").value("service.registered"));
    }

    @Test
    @DisplayName("the audit log pages with a cursor like every other collection")
    void audit_log_is_cursor_paged() throws Exception {
        for (String name : new String[] {
            "orders-api.yaml", "pricing-engine.yaml", "billing-worker.yaml", "customer-portal.yaml"
        }) {
            register(name);
        }

        JsonNode first = MAPPER.readTree(mockMvc.perform(get("/api/v1/audit-events").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(first.get("nextCursor").isNull()).isFalse();

        JsonNode second = MAPPER.readTree(mockMvc.perform(get("/api/v1/audit-events")
                        .param("limit", "2")
                        .param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        java.util.Set<String> ids = new java.util.HashSet<>();
        first.get("items").forEach(item -> ids.add(item.get("id").asText()));
        second.get("items").forEach(item -> ids.add(item.get("id").asText()));
        assertThat(ids).hasSize(4);
    }

    // -- The rule set itself ------------------------------------------------

    @Test
    @DisplayName("the rules endpoint says in the payload that these are declaration checks")
    void rules_endpoint_is_honest_about_what_it_checks() throws Exception {
        mockMvc.perform(get("/api/v1/policy/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(10))
                .andExpect(jsonPath("$.policySetVersion").isNotEmpty())
                .andExpect(jsonPath("$.rules[0].rationale").isNotEmpty())
                // Every rule declares itself declaration-only, and the notice
                // travels in the payload rather than being hardcoded in the
                // console, so the two cannot drift apart.
                .andExpect(jsonPath("$.rules[0].declarationOnly").value(true))
                .andExpect(jsonPath("$.declarationOnlyNotice")
                        .value(org.hamcrest.Matchers.containsString("do not verify")));
    }

    @Test
    @DisplayName("a scorecard for an unknown service is a 404")
    void unknown_service_scorecard_is_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/services/never-registered/scorecard"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
