package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** What the first responder reads at 03:00. */
@Component
class RunbookLinkedCheck implements PolicyCheck {

    @Override
    public String id() {
        return "runbook-linked";
    }

    @Override
    public String title() {
        return "Runbook";
    }

    @Override
    public String rationale() {
        return "Without a runbook, a page arrives with no first step.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.runbook().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("No spec.operations.runbook is declared, so a page for this service arrives with"
                        + " no first step. Note that this check confirms a runbook is declared, not that the link"
                        + " resolves or that its contents are current.");
    }
}
