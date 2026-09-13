package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/**
 * Tier 1 and 2 only: the one thing the platform cannot infer from telemetry.
 *
 * <p>Traces show which services call which. They do not show which customer
 * experience breaks when one of them fails, and that is the question asked first
 * in every incident.
 */
@Component
class JourneysDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "journeys-declared";
    }

    @Override
    public String title() {
        return "Journeys";
    }

    @Override
    public String rationale() {
        return "Traces reveal which services call which, never which customer experience breaks when one fails."
                + " That is the first question asked in an incident and the only one a manifest can answer.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return facts.isProductionCritical() ? Applicability.REQUIRED : Applicability.NOT_APPLICABLE;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        boolean declared = facts.journeys().filter(journeys -> !journeys.isEmpty()).isPresent();
        return declared
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No spec.journeys are declared. For a tier " + facts.tier() + " service this is"
                        + " the field that lets an on-call engineer answer \"who is affected\" without asking a"
                        + " product manager, and it is the one thing telemetry cannot supply.");
    }
}
