package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** Tier 1 and 2 only: without a target there is no error budget and no threshold to alert on. */
@Component
class SloDefinedCheck implements PolicyCheck {

    @Override
    public String id() {
        return "slo-defined";
    }

    @Override
    public String title() {
        return "SLO";
    }

    @Override
    public String rationale() {
        return "An SLO is what makes an error budget, and an error budget is what makes an alert threshold"
                + " something other than a guess.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        // A tier 3 internal tool with no SLO is not failing; it has no such
        // obligation. Marking it failed would make the fleet number a lie.
        return facts.isProductionCritical() ? Applicability.REQUIRED : Applicability.NOT_APPLICABLE;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.slo().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No spec.operations.slo is declared. A tier " + facts.tier() + " service needs"
                        + " one: with no target there is no error budget, and with no budget there is no burn rate"
                        + " to alert on.");
    }
}
