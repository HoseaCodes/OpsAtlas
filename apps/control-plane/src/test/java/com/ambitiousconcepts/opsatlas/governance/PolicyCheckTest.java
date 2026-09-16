package com.ambitiousconcepts.opsatlas.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The rules themselves.
 *
 * <p>Loaded through Spring so the set under test is exactly the set that runs in
 * production. A hand-written list here could drift from the beans that actually
 * exist, and then the interesting property - that every rule can both pass and
 * fail - would be asserted about the wrong set.
 *
 * <p>PER_CLASS lifecycle so the injected beans can feed {@code @MethodSource}
 * directly; JUnit otherwise requires a static source, which cannot see them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyCheckTest extends PostgresTestBase {

    @Autowired
    private List<PolicyCheck> checks;

    /** A manifest with everything declared. Every rule should pass against it. */
    private static ServiceFacts complete(int tier) {
        return new ServiceFacts(
                UUID.randomUUID(),
                "orders-api",
                tier,
                Optional.of("ambitious-concepts"),
                List.of(new ServiceFacts.EnvironmentFacts("production", Optional.of("https://orders.example.com"))),
                Optional.of("/actuator/health/readiness"),
                Optional.of("/actuator/health/liveness"),
                Optional.of("orders-api"),
                Optional.of("docs/runbook.md"),
                Optional.of(new ServiceFacts.Slo(99.9, "30d")),
                Optional.of(List.of("Place an order")),
                Optional.of(List.of("postgres")),
                Optional.of(new ServiceFacts.Oncall(
                        Optional.of("https://pagerduty.example.com/schedules/P1"),
                        Optional.of("24x7"),
                        Optional.of("platform-leads"))));
    }

    /** A manifest with nothing beyond what the schema forces. Every applicable rule should fail. */
    private static ServiceFacts bare(int tier) {
        return new ServiceFacts(
                UUID.randomUUID(),
                "legacy-report-runner",
                tier,
                Optional.empty(),
                List.of(new ServiceFacts.EnvironmentFacts("staging", Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private List<PolicyCheck> rules() {
        return checks.stream().sorted(java.util.Comparator.comparing(PolicyCheck::id)).toList();
    }

    /**
     * The {@code @MethodSource} for the parameterized rules below. Yields ids
     * rather than beans so a failure names the rule that failed instead of an
     * object hash.
     */
    private List<String> ruleIds() {
        return rules().stream().map(PolicyCheck::id).toList();
    }

    private PolicyCheck rule(String id) {
        return rules().stream()
                .filter(check -> check.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule with id " + id));
    }

    @Test
    @DisplayName("the rule set is the eleven declaration checks, with unique ids")
    void the_rule_set_is_what_it_claims() {
        assertThat(rules()).hasSize(11);
        assertThat(rules().stream().map(PolicyCheck::id).toList()).doesNotHaveDuplicates();
        assertThat(rules().stream().map(PolicyCheck::id))
                .containsExactlyInAnyOrder(
                        "owner-declared",
                        "oncall-declared",
                        "runbook-linked",
                        "slo-defined",
                        "readiness-probe-declared",
                        "liveness-probe-declared",
                        "observability-service-name",
                        "environment-urls-declared",
                        "dependencies-declared",
                        "journeys-declared",
                        "production-environment-declared");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleIds")
    @DisplayName("every rule passes a fully declared tier 1 manifest")
    void every_rule_passes_a_complete_manifest(String ruleId) {
        PolicyCheck rule = rule(ruleId);
        ServiceFacts facts = complete(1);
        assertThat(rule.appliesTo(facts)).isEqualTo(Applicability.REQUIRED);
        assertThat(rule.evaluate(facts).status()).isEqualTo(CheckOutcome.Status.PASS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleIds")
    @DisplayName("every rule that applies to a bare tier 1 manifest fails it")
    void every_applicable_rule_fails_a_bare_manifest(String ruleId) {
        PolicyCheck rule = rule(ruleId);
        // A rule that cannot fail is padding on a scorecard. This asserts that
        // each one is actually load-bearing.
        ServiceFacts facts = bare(1);
        if (rule.appliesTo(facts) == Applicability.REQUIRED) {
            assertThat(rule.evaluate(facts).status())
                    .as("%s should fail a manifest that declares nothing", rule.id())
                    .isEqualTo(CheckOutcome.Status.FAIL);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleIds")
    @DisplayName("every failure explains itself in a complete sentence")
    void every_failure_is_actionable(String ruleId) {
        PolicyCheck rule = rule(ruleId);
        ServiceFacts facts = bare(1);
        if (rule.appliesTo(facts) == Applicability.REQUIRED) {
            String detail = rule.evaluate(facts).detail();
            assertThat(detail).as("%s must say why it failed", rule.id()).isNotBlank();
            assertThat(detail).hasSizeGreaterThan(40).endsWith(".");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleIds")
    @DisplayName("every rule has a title and a rationale a reader can use")
    void every_rule_explains_its_purpose(String ruleId) {
        PolicyCheck rule = rule(ruleId);
        assertThat(rule.title()).isNotBlank().hasSizeLessThan(20);
        assertThat(rule.rationale()).isNotBlank().hasSizeGreaterThan(30);
        assertThat(rule.id()).matches("[a-z][a-z0-9-]*");
    }

    // -- Tier-conditional applicability -------------------------------------

    @Test
    @DisplayName("tier 3 is excused the SLO, journeys and production rules rather than failed")
    void tier_three_obligations_are_fewer() {
        ServiceFacts tierThree = bare(3);

        List<String> notApplicable = rules().stream()
                .filter(rule -> rule.appliesTo(tierThree) == Applicability.NOT_APPLICABLE)
                .map(PolicyCheck::id)
                .toList();

        // Not a style preference. Marked as failing, an internal tool looks like
        // the worst thing in the fleet and the fleet compliance number becomes
        // a number nobody can act on.
        assertThat(notApplicable)
                .containsExactlyInAnyOrder(
                        "slo-defined", "journeys-declared", "production-environment-declared", "oncall-declared");
    }

    @Test
    @DisplayName("tier 2 carries the same obligations as tier 1")
    void tier_two_is_production_critical() {
        ServiceFacts tierTwo = bare(2);
        assertThat(rules().stream().filter(rule -> rule.appliesTo(tierTwo) == Applicability.NOT_APPLICABLE))
                .isEmpty();
    }

    // -- Individual rules worth pinning down --------------------------------

    @Test
    @DisplayName("dependencies-declared: an empty list passes, an absent key fails")
    void empty_dependencies_are_a_statement() {
        ServiceFacts stated = withDependencies(Optional.of(List.of()));
        ServiceFacts unstated = withDependencies(Optional.empty());

        // "I call nothing" is an answer. "I have not said" is not.
        assertThat(rule("dependencies-declared").evaluate(stated).status()).isEqualTo(CheckOutcome.Status.PASS);
        assertThat(rule("dependencies-declared").evaluate(unstated).status()).isEqualTo(CheckOutcome.Status.FAIL);
    }

    @Test
    @DisplayName("journeys-declared: an empty list is not a declaration")
    void empty_journeys_do_not_count() {
        // Unlike dependencies: "this service is on no customer journey" is not
        // a meaningful claim for a tier 1 service, so an empty list reads as
        // the field having been left unfilled.
        ServiceFacts facts = withJourneys(Optional.of(List.of()));
        assertThat(rule("journeys-declared").evaluate(facts).status()).isEqualTo(CheckOutcome.Status.FAIL);
    }

    @Test
    @DisplayName("environment-urls-declared: the failure names which environments are missing one")
    void missing_urls_are_named() {
        ServiceFacts facts = new ServiceFacts(
                UUID.randomUUID(),
                "orders-api",
                1,
                Optional.of("ambitious-concepts"),
                List.of(
                        new ServiceFacts.EnvironmentFacts("production", Optional.of("https://orders.example.com")),
                        new ServiceFacts.EnvironmentFacts("staging", Optional.empty())),
                Optional.of("/readyz"),
                Optional.of("/healthz"),
                Optional.of("orders-api"),
                Optional.of("docs/runbook.md"),
                Optional.of(new ServiceFacts.Slo(99.9, "30d")),
                Optional.of(List.of("Place an order")),
                Optional.of(List.of()),
                Optional.of(new ServiceFacts.Oncall(
                        Optional.of("https://pagerduty.example.com/schedules/P1"),
                        Optional.of("24x7"),
                        Optional.empty())));

        CheckOutcome outcome = rule("environment-urls-declared").evaluate(facts);

        assertThat(outcome.status()).isEqualTo(CheckOutcome.Status.FAIL);
        assertThat(outcome.detail()).contains("staging").doesNotContain("production");
    }

    /**
     * The tier 1 case the rule exists for, and the one a simpler rule would miss.
     *
     * <p>A declared rotation covering business hours passes for tier 2 and fails
     * for tier 1. Without this, "has a rotation" would be satisfied by a rotation
     * that is asleep at exactly the hour a tier 1 service needs one.
     */
    @Test
    @DisplayName("a business-hours rotation passes at tier 2 and fails at tier 1")
    void business_hours_coverage_is_judged_by_tier() {
        ServiceFacts.Oncall businessHours = new ServiceFacts.Oncall(
                Optional.of("https://pagerduty.example.com/schedules/P1"),
                Optional.of("business-hours"),
                Optional.empty());

        CheckOutcome atTierTwo = rule("oncall-declared").evaluate(withOncall(2, businessHours));
        assertThat(atTierTwo.status()).isEqualTo(CheckOutcome.Status.PASS);

        CheckOutcome atTierOne = rule("oncall-declared").evaluate(withOncall(1, businessHours));
        assertThat(atTierOne.status()).isEqualTo(CheckOutcome.Status.FAIL);
        assertThat(atTierOne.detail()).contains("business-hours").contains("24x7");
    }

    @Test
    @DisplayName("an oncall block with no rotation URL is not a rotation")
    void an_oncall_block_without_a_rotation_fails() {
        // Declaring coverage without saying where the schedule is leaves nothing
        // to open at 03:00, so the block being present must not be enough.
        CheckOutcome outcome = rule("oncall-declared")
                .evaluate(withOncall(
                        1, new ServiceFacts.Oncall(Optional.empty(), Optional.of("24x7"), Optional.empty())));

        assertThat(outcome.status()).isEqualTo(CheckOutcome.Status.FAIL);
        assertThat(outcome.detail()).contains("spec.operations.oncall.rotation");
    }

    /** A complete manifest at the given tier, with its on-call block replaced. */
    private static ServiceFacts withOncall(int tier, ServiceFacts.Oncall oncall) {
        ServiceFacts base = complete(tier);
        return new ServiceFacts(
                base.serviceId(),
                base.slug(),
                base.tier(),
                base.owner(),
                base.environments(),
                base.readinessPath(),
                base.livenessPath(),
                base.observabilityServiceName(),
                base.runbook(),
                base.slo(),
                base.journeys(),
                base.dependencies(),
                Optional.of(oncall));
    }

    @Test
    @DisplayName("production-environment-declared: a service with only staging fails, and is told what it declared")
    void production_must_be_named_exactly() {
        CheckOutcome outcome = rule("production-environment-declared").evaluate(bare(1));

        assertThat(outcome.status()).isEqualTo(CheckOutcome.Status.FAIL);
        assertThat(outcome.detail()).contains("staging");
    }

    // -- The outcome type itself --------------------------------------------

    @Test
    @DisplayName("a failing outcome with no reason cannot be constructed")
    void failures_must_explain_themselves() {
        assertThatThrownBy(() -> CheckOutcome.fail("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must say why");
    }

    @Test
    @DisplayName("a passing or not-applicable outcome needs no reason")
    void passes_need_no_reason() {
        assertThat(CheckOutcome.pass().detail()).isNull();
        assertThat(CheckOutcome.notApplicable().detail()).isNull();
        assertThat(CheckOutcome.notApplicable().applicable()).isFalse();
    }

    private static ServiceFacts withDependencies(Optional<List<String>> dependencies) {
        ServiceFacts base = complete(1);
        return new ServiceFacts(
                base.serviceId(),
                base.slug(),
                base.tier(),
                base.owner(),
                base.environments(),
                base.readinessPath(),
                base.livenessPath(),
                base.observabilityServiceName(),
                base.runbook(),
                base.slo(),
                base.journeys(),
                dependencies,
                base.oncall());
    }

    private static ServiceFacts withJourneys(Optional<List<String>> journeys) {
        ServiceFacts base = complete(1);
        return new ServiceFacts(
                base.serviceId(),
                base.slug(),
                base.tier(),
                base.owner(),
                base.environments(),
                base.readinessPath(),
                base.livenessPath(),
                base.observabilityServiceName(),
                base.runbook(),
                base.slo(),
                journeys,
                base.dependencies(),
                base.oncall());
    }
}
