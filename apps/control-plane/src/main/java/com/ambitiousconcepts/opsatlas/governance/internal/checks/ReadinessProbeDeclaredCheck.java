package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** Whether anything can tell a serving process from a hung one. */
@Component
class ReadinessProbeDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "readiness-probe-declared";
    }

    @Override
    public String title() {
        return "Readiness";
    }

    @Override
    public String rationale() {
        return "Without a readiness endpoint, a hung process is indistinguishable from a healthy one.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        // Applies to workers too. A queue consumer that stops consuming looks
        // exactly like one that is idle, which is the case this rule exists for.
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.readinessPath().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No spec.health.readiness is declared, so nothing can tell whether this service"
                        + " is actually serving. A hung process and a healthy one look identical until someone"
                        + " notices.");
    }
}
