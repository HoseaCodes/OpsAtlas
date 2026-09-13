package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** Whether anything can decide to restart it. */
@Component
class LivenessProbeDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "liveness-probe-declared";
    }

    @Override
    public String title() {
        return "Liveness";
    }

    @Override
    public String rationale() {
        return "Readiness says whether to send traffic; liveness says whether to restart. They answer different"
                + " questions and a service needs both.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.livenessPath().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No spec.health.liveness is declared. Readiness decides whether to route traffic;"
                        + " liveness decides whether to restart a wedged process, and nothing can make that call"
                        + " without one.");
    }
}
