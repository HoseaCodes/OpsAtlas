package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** Whether telemetry can be joined to this catalog entry. */
@Component
class ObservabilityServiceNameCheck implements PolicyCheck {

    @Override
    public String id() {
        return "observability-service-name";
    }

    @Override
    public String title() {
        return "Telemetry name";
    }

    @Override
    public String rationale() {
        return "Traces and metrics are only usable here if the name they report under is known.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.observabilityServiceName().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No spec.observability.serviceName is declared, so traces and metrics this"
                        + " service emits cannot be joined to its catalog entry. This checks that the name is"
                        + " declared, not that any telemetry has arrived.");
    }
}
