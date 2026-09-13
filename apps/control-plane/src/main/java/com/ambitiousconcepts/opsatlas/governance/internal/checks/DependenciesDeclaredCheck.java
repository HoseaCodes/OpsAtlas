package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/**
 * Whether the service has said what it calls.
 *
 * <p>The one rule where an empty answer passes. "I depend on nothing" is a
 * statement someone made; an absent key is a question nobody answered, and only
 * the second is a gap. The schema keeps the two distinguishable and this is why.
 */
@Component
class DependenciesDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "dependencies-declared";
    }

    @Override
    public String title() {
        return "Dependencies";
    }

    @Override
    public String rationale() {
        return "A dependency list is what makes a blast radius answerable without asking around.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return Applicability.REQUIRED;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        return facts.dependencies().isPresent()
                ? CheckOutcome.pass()
                : CheckOutcome.fail("spec.dependencies is not declared. If this service genuinely calls nothing,"
                        + " say so with an empty list - that is a statement, and an absent key is not.");
    }
}
