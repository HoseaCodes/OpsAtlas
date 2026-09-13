package com.ambitiousconcepts.opsatlas.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Two claims about the rule set that nothing was checking.
 *
 * <p>The first is {@code CLAUDE.md} §2: bump {@code PolicyCatalog.VERSION}
 * whenever a rule is added, removed or changed in a way that alters its verdict.
 * That is what lets a score from last month still be read as what it meant then.
 * It was a sentence in a file, and a sentence in a file does not fail a build -
 * a rule could be added, every scorecard could start meaning something different,
 * and every stored {@code policy_set_version} would still claim otherwise.
 *
 * <p>The second is the fleet table printed in the README. It states the exact
 * score and the exact failing checks for all six example manifests, which is the
 * most falsifiable claim in that document and was maintained by hand.
 *
 * <p>Between them they cover both ways the version can go stale: a rule
 * appearing or disappearing, which the id list catches, and a rule quietly
 * changing its mind about a manifest, which the table catches.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicySetIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));

    /** {@code <root>/examples/services} upwards. */
    private static final Path README = EXAMPLES.getParent().getParent().resolve("README.md");

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    /**
     * The rule set this version stands for.
     *
     * <p>Changing this list without changing the version is the mistake being
     * guarded against, so they are written down together, here, on purpose.
     */
    private static final String VERSION = "2026-09-12.1";

    private static final List<String> RULES = List.of(
            "dependencies-declared",
            "environment-urls-declared",
            "journeys-declared",
            "liveness-probe-declared",
            "observability-service-name",
            "owner-declared",
            "production-environment-declared",
            "readiness-probe-declared",
            "runbook-linked",
            "slo-defined");

    /** The order the README prints them in, which is the order they registered. */
    private static final List<String> FLEET = List.of(
            "orders-api",
            "pricing-engine",
            "billing-worker",
            "customer-portal",
            "identity-bff",
            "legacy-report-runner");

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("the rule set is the one this policy version stands for")
    void the_rule_set_matches_its_version() throws Exception {
        String body = mockMvc.perform(get("/api/v1/policy/rules"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode rules = json.readTree(body);
        List<String> live = new ArrayList<>();
        rules.get("rules").forEach(rule -> live.add(rule.get("id").asText()));
        live.sort(String::compareTo);

        assertThat(rules.get("policySetVersion").asText())
                .as("the version served must be the one this test pins the rule set to")
                .isEqualTo(VERSION);

        assertThat(live)
                .as(
                        """
                        The rule set has changed. A stored score carries its policy_set_version \
                        so it can still be read as what it meant when it was computed, which stops \
                        being true the moment the set changes without the version changing.

                        Bump PolicyCatalog.VERSION, then update VERSION and RULES here.""")
                .isEqualTo(RULES);
    }

    @Test
    @DisplayName("the fleet table in the README is what the scorer actually produces")
    void the_readme_fleet_table_is_true() throws Exception {
        Map<String, Scored> actual = new LinkedHashMap<>();
        for (String slug : FLEET) {
            mockMvc.perform(post("/api/v1/services")
                            .contentType(YAML)
                            .content(Files.readString(EXAMPLES.resolve(slug + ".yaml"))))
                    .andExpect(status().isCreated());
            actual.put(slug, score(slug));
        }

        Map<String, Scored> documented = parseReadmeTable();

        assertThat(documented.keySet())
                .as("the README's fleet table should cover every example manifest")
                .containsExactlyElementsOf(FLEET);

        List<String> wrong = new ArrayList<>();
        documented.forEach((slug, claimed) -> {
            Scored real = actual.get(slug);
            if (claimed.tier() != real.tier()
                    || claimed.passed() != real.passed()
                    || claimed.applicable() != real.applicable()
                    || claimed.notApplicable() != real.notApplicable()) {
                wrong.add("%s: documented %s, actual %s".formatted(slug, claimed, real));
                return;
            }
            // A row that names its failing rules is held to those names. A row
            // that summarises counts - the tier 3 one, where seven ids on a line
            // would be worse reading - is held to the count it states.
            if (claimed.failing() == null) {
                if (real.failing().size() != claimed.applicable() - claimed.passed()) {
                    wrong.add("%s: says %d failing, actual %s"
                            .formatted(slug, claimed.applicable() - claimed.passed(), real.failing()));
                }
            } else if (!claimed.failing().equals(real.failing())) {
                wrong.add("%s: documented failing %s, actual %s".formatted(slug, claimed.failing(), real.failing()));
            }
        });

        assertThat(wrong)
                .as(
                        """
                        The README's fleet table no longer matches what the scorer produces.

                        If a rule changed its verdict, that is a verdict change: bump \
                        PolicyCatalog.VERSION as well as correcting the table.

                        What the scorer produces now, ready to paste:

                        %s"""
                                .formatted(asTable(actual)))
                .isEmpty();
    }

    // -- reading the real thing ---------------------------------------------

    private Scored score(String slug) throws Exception {
        JsonNode card = json.readTree(mockMvc.perform(get("/api/v1/services/" + slug + "/scorecard"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        var failing = new TreeSet<String>();
        int notApplicable = 0;
        for (JsonNode check : card.get("checks")) {
            String status = check.get("status").asText();
            if ("FAIL".equals(status)) {
                failing.add(check.get("checkId").asText());
            } else if ("NOT_APPLICABLE".equals(status)) {
                notApplicable++;
            }
        }

        JsonNode detail = json.readTree(mockMvc.perform(get("/api/v1/services/" + slug))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        return new Scored(
                detail.get("tier").asInt(),
                card.get("checksPassed").asInt(),
                card.get("checksApplicable").asInt(),
                notApplicable,
                List.copyOf(failing));
    }

    // -- reading the documentation ------------------------------------------

    /**
     * The table is the contract, so it is parsed rather than eyeballed.
     *
     * <p>Three shapes appear in the failing column: a dash for nothing failing, a
     * comma-separated list of check ids, and the summary line the tier 3 service
     * gets, where the interesting number is how many rules did not apply.
     */
    private static final Pattern ROW = Pattern.compile("^(\\S+)\\s+(\\d+)\\s+(\\d+)/(\\d+)\\s+(.*)$");

    private static final Pattern SUMMARY =
            Pattern.compile("^\\((\\d+) failing; (\\d+) not applicable at tier \\d+\\)$");

    private Map<String, Scored> parseReadmeTable() throws Exception {
        List<String> lines = Files.readAllLines(README);
        Map<String, Scored> documented = new LinkedHashMap<>();
        boolean inTable = false;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("service") && trimmed.contains("tier") && trimmed.contains("failing")) {
                inTable = true;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (trimmed.startsWith("```")) {
                break;
            }
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher row = ROW.matcher(trimmed);
            assertThat(row.matches())
                    .as("unreadable row in the README fleet table: '%s'", trimmed)
                    .isTrue();

            int passed = Integer.parseInt(row.group(3));
            int applicable = Integer.parseInt(row.group(4));
            String failingCell = row.group(5).strip();

            List<String> failing = List.of();
            int notApplicable = 0;

            Matcher summary = SUMMARY.matcher(failingCell);
            if (summary.matches()) {
                // The tier 3 row names counts rather than rules. The count of
                // failures is already implied by passed/applicable; what it
                // uniquely states is how many rules did not apply.
                assertThat(applicable - passed)
                        .as("the tier 3 row's own numbers disagree: '%s'", trimmed)
                        .isEqualTo(Integer.parseInt(summary.group(1)));
                notApplicable = Integer.parseInt(summary.group(2));
                failing = null;
            } else if (!"-".equals(failingCell)) {
                failing = new TreeSet<>(List.of(failingCell.split("\\s*,\\s*"))).stream()
                        .toList();
            }

            documented.put(
                    row.group(1),
                    new Scored(Integer.parseInt(row.group(2)), passed, applicable, notApplicable, failing));
        }

        assertThat(documented).as("no fleet table found in %s", README).isNotEmpty();
        return documented;
    }

    private static String asTable(Map<String, Scored> scores) {
        // Same column widths as the README, so a failure can be pasted straight
        // back into it rather than re-aligned by hand.
        StringBuilder out = new StringBuilder("service                  tier  score    failing\n");
        scores.forEach((slug, s) -> out.append("%-24s %-5d %-8s %s%n"
                .formatted(
                        slug,
                        s.tier(),
                        s.passed() + "/" + s.applicable(),
                        s.failing().isEmpty()
                                ? "-"
                                : s.notApplicable() > 0
                                        ? "(%d failing; %d not applicable at tier %d)"
                                                .formatted(s.failing().size(), s.notApplicable(), s.tier())
                                        : String.join(", ", s.failing()))));
        return out.toString();
    }

    /**
     * @param failing null when the README states counts instead of naming rules,
     *     which is the tier 3 row. Null rather than empty so "names no rules" and
     *     "names no rules because none fail" stay distinguishable.
     */
    private record Scored(int tier, int passed, int applicable, int notApplicable, List<String> failing) {}
}
