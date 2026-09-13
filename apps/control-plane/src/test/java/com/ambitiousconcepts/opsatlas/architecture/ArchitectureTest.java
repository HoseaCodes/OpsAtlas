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
    static final ArchRule governance_internals_are_private = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.governance..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.governance.internal..")
            .because("catalog calls governance through governance.api only (ADR 0001)");

    /**
     * The dependency between catalog and governance runs one way: catalog calls
     * governance when it registers something. If governance ever reached back
     * into catalog, the two would be one module wearing two names, and neither
     * could be reasoned about alone.
     */
    @ArchTest
    static final ArchRule governance_does_not_depend_on_catalog = noClasses()
            .that()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.governance.internal..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.catalog..")
            .because("the catalog -> governance dependency is one-directional and must stay that way");

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
    static final ArchRule catalog_entities_do_not_leave_catalog = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.catalog..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.catalog.internal.domain..")
            .because("a persistence entity crossing a module boundary makes that module's storage public (ADR 0001)");

    /**
     * CLAUDE.md section 3 rule 1, as a build rule: every policy check reads only
     * what a manifest declares. A check reaching into a repository, an HTTP
     * client or another module would be verifying something at runtime, and the
     * API tells callers these are declaration checks.
     */
    @ArchTest
    static final ArchRule policy_checks_only_read_facts = noClasses()
            .that()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.governance.internal.checks..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.ambitiousconcepts.opsatlas.catalog..",
                    "com.ambitiousconcepts.opsatlas.identity..",
                    "org.springframework.data..",
                    "java.net..",
                    "java.sql..")
            .because("slice-one policy checks are declaration-only: they read ServiceFacts and nothing else");

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
