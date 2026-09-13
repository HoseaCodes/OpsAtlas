package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.Scorecard;
import com.ambitiousconcepts.opsatlas.governance.api.ScorecardService;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultScorecardService implements ScorecardService {

    private static final Logger log = LoggerFactory.getLogger(DefaultScorecardService.class);

    private final PolicyCatalog catalog;
    private final PolicyResultRepository results;
    private final PolicyResultCheckRepository resultChecks;
    private final Clock clock;

    DefaultScorecardService(
            PolicyCatalog catalog,
            PolicyResultRepository results,
            PolicyResultCheckRepository resultChecks,
            Clock clock) {
        this.catalog = catalog;
        this.results = results;
        this.resultChecks = resultChecks;
        this.clock = clock;
    }

    /**
     * No {@code @Transactional} of its own: this is called from inside the
     * caller's transaction and must join it, so that a service row and its
     * scorecard commit or roll back together (CLAUDE.md section 8). Declaring
     * REQUIRES_NEW here would break exactly that guarantee.
     */
    @Override
    public Scorecard evaluateAndStore(UUID orgId, ServiceFacts facts) {
        Instant now = clock.instant();
        Map<PolicyCheck, CheckOutcome> outcomes = evaluate(facts);

        int applicable = (int) outcomes.values().stream().filter(CheckOutcome::applicable).count();
        int passed = (int) outcomes.values().stream().filter(CheckOutcome::passed).count();

        PolicyResultEntity result =
                PolicyResultEntity.of(orgId, facts.serviceId(), catalog.version(), now, passed, applicable);
        results.save(result);

        List<PolicyResultCheckEntity> rows = new ArrayList<>();
        outcomes.forEach((check, outcome) ->
                rows.add(PolicyResultCheckEntity.of(result.getId(), check.id(), outcome)));
        resultChecks.saveAll(rows);

        log.info(
                "Evaluated {} against policy set {}: {}/{} applicable checks passed",
                facts.slug(),
                catalog.version(),
                passed,
                applicable);

        return toScorecard(result, outcomes);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Scorecard> latestFor(UUID orgId, UUID serviceId) {
        return results.findFirstByOrgIdAndServiceIdOrderByEvaluatedAtDescIdDesc(orgId, serviceId)
                .map(this::toScorecard);
    }

    @Override
    public List<PolicyCheck> rules() {
        return catalog.all();
    }

    @Override
    public String policySetVersion() {
        return catalog.version();
    }

    /**
     * Run every rule.
     *
     * <p>A rule that throws does not abort the evaluation and does not silently
     * pass: it is recorded as a failure naming itself. One broken rule must not
     * take down registration for every service, and must not quietly award a
     * point it did not earn.
     */
    private Map<PolicyCheck, CheckOutcome> evaluate(ServiceFacts facts) {
        Map<PolicyCheck, CheckOutcome> outcomes = new LinkedHashMap<>();
        for (PolicyCheck check : catalog.all()) {
            outcomes.put(check, evaluateOne(check, facts));
        }
        return outcomes;
    }

    private CheckOutcome evaluateOne(PolicyCheck check, ServiceFacts facts) {
        try {
            if (check.appliesTo(facts) == Applicability.NOT_APPLICABLE) {
                return CheckOutcome.notApplicable();
            }
            return check.evaluate(facts);
        } catch (RuntimeException e) {
            log.error("Policy check '{}' threw while evaluating service {}", check.id(), facts.slug(), e);
            return CheckOutcome.fail("This check could not be evaluated because it failed with an internal error."
                    + " It is recorded as failing rather than passing, because a rule that did not run has not"
                    + " been satisfied. This is a defect in the control plane, not in the manifest.");
        }
    }

    private Scorecard toScorecard(PolicyResultEntity result) {
        Map<String, PolicyCheck> byId = new LinkedHashMap<>();
        catalog.all().forEach(check -> byId.put(check.id(), check));

        List<Scorecard.CheckResult> checks =
                resultChecks.findByPolicyResultIdOrderByCheckIdAsc(result.getId()).stream()
                        .map(row -> new Scorecard.CheckResult(
                                row.getCheckId(),
                                // A rule removed since this evaluation still has
                                // stored verdicts. Showing its id is better than
                                // dropping the row and changing a past score.
                                byId.containsKey(row.getCheckId())
                                        ? byId.get(row.getCheckId()).title()
                                        : row.getCheckId(),
                                row.getStatus(),
                                row.getDetail()))
                        .toList();

        return new Scorecard(
                result.getServiceId(),
                result.getPolicySetVersion(),
                result.getEvaluatedAt(),
                result.getChecksPassed(),
                result.getChecksApplicable(),
                checks);
    }

    private Scorecard toScorecard(PolicyResultEntity result, Map<PolicyCheck, CheckOutcome> outcomes) {
        List<Scorecard.CheckResult> checks = outcomes.entrySet().stream()
                .map(entry -> new Scorecard.CheckResult(
                        entry.getKey().id(),
                        entry.getKey().title(),
                        entry.getValue().status().name(),
                        entry.getValue().detail()))
                .toList();

        return new Scorecard(
                result.getServiceId(),
                result.getPolicySetVersion(),
                result.getEvaluatedAt(),
                result.getChecksPassed(),
                result.getChecksApplicable(),
                checks);
    }
}
