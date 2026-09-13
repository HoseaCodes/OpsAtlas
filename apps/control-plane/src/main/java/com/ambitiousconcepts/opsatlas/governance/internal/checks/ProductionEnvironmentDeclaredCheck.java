package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/** Tier 1 and 2 only: a customer-facing service that names no production is describing something else. */
@Component
class ProductionEnvironmentDeclaredCheck implements PolicyCheck {

    private static final String PRODUCTION = "production";

    @Override
    public String id() {
        return "production-environment-declared";
    }

    @Override
    public String title() {
        return "Production";
    }

    @Override
    public String rationale() {
        return "Availability, error budget and incident routing are all measured against production. Without one"
                + " named, none of them have a subject.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        return facts.isProductionCritical() ? Applicability.REQUIRED : Applicability.NOT_APPLICABLE;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        boolean declared = facts.environments().stream()
                .anyMatch(environment -> PRODUCTION.equals(environment.name()));

        if (declared) {
            return CheckOutcome.pass();
        }
        return CheckOutcome.fail("No environment named 'production' is declared. Availability, error budget and"
                + " incident routing are all measured against production, so a tier " + facts.tier() + " service"
                + " needs one named exactly that. Declared: "
                + (facts.environments().isEmpty()
                        ? "none"
                        : String.join(
                                ", ",
                                facts.environments().stream()
                                        .map(ServiceFacts.EnvironmentFacts::name)
                                        .toList()))
                + ".");
    }
}
