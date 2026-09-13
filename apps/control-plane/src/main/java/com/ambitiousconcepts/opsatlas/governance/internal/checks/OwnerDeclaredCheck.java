package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** Who gets paged. Everything else in an incident is downstream of this one. */
@Component
class OwnerDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "owner-declared";
    }

    @Override
    public String title() {
        return "Owner";
    }

    @Override
    public String rationale() {
        return "An unowned service has nobody to page, so its alerts fall through to whoever notices first.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.owner().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No metadata.owner is declared, so nobody is accountable for this service and"
                        + " no alert can be routed to a rotation. Add metadata.owner to service.yaml.");
    }
}
