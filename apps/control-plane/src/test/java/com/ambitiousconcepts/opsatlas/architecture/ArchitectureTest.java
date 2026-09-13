package com.ambitiousconcepts.opsatlas.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The module boundaries from ADR 0001, enforced.
 *
 * <p>These rules are the entire difference between "a modular monolith" and "a
 * monolith with some packages". ADR 0001 is explicit that this is test-time
 * enforcement rather than compile-time, and that the cost is a bad import
 * compiling cleanly until this runs.
 */
@AnalyzeClasses(
        packages = "com.ambitiousconcepts.opsatlas",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule catalog_internals_are_private = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.catalog..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.catalog.internal..")
            .because("other modules talk to catalog through catalog.api, never through its internals (ADR 0001)");

    @ArchTest
    static final ArchRule identity_internals_are_private = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.identity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.identity.internal..")
            .because("other modules talk to identity through identity.api, never through its internals (ADR 0001)");

    @ArchTest
    static final ArchRule shared_depends_on_no_sibling_module = noClasses()
            .that()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.ambitiousconcepts.opsatlas.catalog..",
                    "com.ambitiousconcepts.opsatlas.identity..",
                    "com.ambitiousconcepts.opsatlas.governance..",
                    "com.ambitiousconcepts.opsatlas.operations..",
                    "com.ambitiousconcepts.opsatlas.integrations..")
            .because("CLAUDE.md section 5: shared depends on nothing");

    @ArchTest
    static final ArchRule entities_do_not_leave_their_module = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.catalog..")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(jakarta.persistence.Entity.class)
            .because("a persistence entity crossing a module boundary makes that module's storage public (ADR 0001)");

    @ArchTest
    static final ArchRule controllers_live_in_web_packages = noClasses()
            .that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should()
            .resideOutsideOfPackages(
                    "com.ambitiousconcepts.opsatlas..web..", "com.ambitiousconcepts.opsatlas.shared..")
            .because("each module's HTTP surface belongs in its own web package, so the API surface is greppable");

    /**
     * Phase discipline, from CLAUDE.md section 3 rule 3: no package exists for a
     * phase that is not being implemented. {@code operations} and
     * {@code integrations} are in docs/roadmap.md and must not appear on disk
     * until their phase is active. Deleting this rule is how they are admitted -
     * which makes admitting them a deliberate, reviewable act.
     */
    @ArchTest
    static final ArchRule no_packages_for_future_phases = noClasses()
            .should()
            .resideInAnyPackage(
                    "com.ambitiousconcepts.opsatlas.operations..",
                    "com.ambitiousconcepts.opsatlas.integrations..")
            .because("these modules belong to later phases and must not exist as empty scaffolding");
}
