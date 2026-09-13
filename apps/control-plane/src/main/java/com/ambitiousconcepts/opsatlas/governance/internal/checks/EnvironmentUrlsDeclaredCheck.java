package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

import java.util.List;

/** Whether there is an address to probe. Nothing can be observed without one. */
@Component
class EnvironmentUrlsDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "environment-urls-declared";
    }

    @Override
    public String title() {
        return "Environment URLs";
    }

    @Override
    public String rationale() {
        return "An environment with no URL cannot be probed, so it can never be reported as up or down.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        List<String> missing = facts.environments().stream()
                .filter(environment -> environment.url().isEmpty())
                .map(ServiceFacts.EnvironmentFacts::name)
                .toList();

        if (missing.isEmpty()) {
            return CheckOutcome.pass();
        }
        return CheckOutcome.fail("No URL is declared for " + String.join(", ", missing)
                + ". An environment with no address cannot be probed, so it will never be reported as up or down."
                + " A worker with no inbound URL should still expose one for its readiness endpoint.");
    }
}
