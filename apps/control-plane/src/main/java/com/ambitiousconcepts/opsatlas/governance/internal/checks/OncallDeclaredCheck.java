package com.ambitiousconcepts.opsatlas.governance.internal.checks;

import com.ambitiousconcepts.opsatlas.governance.api.Applicability;
import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import org.springframework.stereotype.Component;

/**
 * Whether there is a way to wake somebody up.
 *
 * <p>{@code spec.operations.contact} is a chat channel, and a chat channel at
 * 03:00 reaches nobody. This is the separate question of whether an escalation
 * path exists at all, and it is the one an owner-declared check does not answer:
 * a service can name an owning team and still have no rotation behind it.
 *
 * <p>Tier 1 is held to more than the others. A rotation that covers business
 * hours is a real answer for a tier 2 service and a stated gap for a tier 1 one,
 * which is paged around the clock by definition - so the tier 1 failure names
 * the coverage rather than pretending the declaration was missing.
 *
 * <p>What this cannot check is whether anyone actually answers. That needs the
 * paging provider, and ADR 0016 records why OpsAtlas does not claim to know it.
 */
@Component
class OncallDeclaredCheck implements PolicyCheck {

    @Override
    public String id() {
        return "oncall-declared";
    }

    @Override
    public String title() {
        return "On-call rotation";
    }

    @Override
    public String rationale() {
        return "An owner is who is accountable in the morning. A rotation is who answers tonight,"
                + " and the two are only the same service if somebody wrote the second one down.";
    }

    @Override
    public Applicability appliesTo(ServiceFacts facts) {
        // A tier 3 internal tool with no rotation is not failing; nobody has
        // promised to be woken for it. Marking it failed would make the fleet
        // number a lie, the same way it would for the SLO rule.
        return facts.isProductionCritical() ? Applicability.REQUIRED : Applicability.NOT_APPLICABLE;
    }

    @Override
    public CheckOutcome evaluate(ServiceFacts facts) {
        var oncall = facts.oncall().filter(declared -> declared.rotation().isPresent());
        if (oncall.isEmpty()) {
            return CheckOutcome.fail("No spec.operations.oncall.rotation is declared. A tier " + facts.tier()
                    + " service needs a schedule somebody can open at 03:00; spec.operations.contact is a chat"
                    + " channel, and a chat channel at 03:00 reaches nobody.");
        }
        if (facts.tier() == 1 && !oncall.get().isRoundTheClock()) {
            return CheckOutcome.fail("spec.operations.oncall.coverage is "
                    + oncall.get().coverage().orElse("not declared")
                    + ". A tier 1 service is paged around the clock by definition, so a rotation that does not"
                    + " claim 24x7 either is not one, or the tier is wrong.");
        }
        return CheckOutcome.pass();
    }
}
