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

    /**
     * Modules that may not exist yet.
     *
     * <p>Deliberately naming nothing real: every module in CLAUDE.md section 5
     * now has contents. The placeholder keeps the rule compilable and the intent
     * visible - a module proposed in future has to be added here first.
     */
    private static final String[] NO_MODULES_ARE_PENDING = {"com.ambitiousconcepts.opsatlas.__pending__.."};

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
    static final ArchRule integrations_internals_are_private = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.integrations..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.integrations.internal..")
            .because("the sync machinery is private to integrations (ADR 0001)");

    /**
     * The dependency runs integrations -> catalog -> governance, and only that
     * way. Catalog must not learn where a manifest came from: it accepts a
     * document from anywhere, which is exactly why a polled manifest and a
     * pasted one cannot be treated differently (ADR 0008).
     */
    @ArchTest
    static final ArchRule catalog_does_not_depend_on_integrations = noClasses()
            .that()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.catalog..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.integrations..")
            .because("catalog does not know how a manifest reached it, and must not start knowing");

    @ArchTest
    static final ArchRule governance_does_not_depend_on_integrations = noClasses()
            .that()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.governance..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.integrations..")
            .because("policy is evaluated against facts, never against where they were fetched from");

    /**
     * ADR 0008's central claim, as a build rule: OpsAtlas never writes to a
     * monitored repository. Only integrations may make outbound HTTP calls at
     * all, so a write path could not appear anywhere else without this failing.
     */
    @ArchTest
    static final ArchRule only_integrations_makes_outbound_http_calls = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.integrations..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web.client..", "java.net.http..")
            .because("reaching out to another system is integrations' job and nobody else's (ADR 0008)");

    @ArchTest
    static final ArchRule operations_internals_are_private = noClasses()
            .that()
            .resideOutsideOfPackage("com.ambitiousconcepts.opsatlas.operations..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.operations.internal..")
            .because("the observation machinery is private to operations (ADR 0001)");

    /**
     * ADR 0010. operations resolves environment ids through catalog.api, so the
     * dependency runs operations -> catalog. Health is served by its own
     * endpoints rather than folded into the catalog's responses precisely so
     * that this can be asserted: two modules that each need the other are one
     * module wearing two names.
     */
    @ArchTest
    static final ArchRule catalog_does_not_depend_on_operations = noClasses()
            .that()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.catalog..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.ambitiousconcepts.opsatlas.operations..")
            .because("the catalog carries no health; operations serves it separately (ADR 0010)");

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
     * Phase discipline, from CLAUDE.md section 3 rule 3.
     *
     * <p>Every module named in CLAUDE.md section 5 now exists and has contents:
     * {@code integrations} was admitted in phase 6 and {@code operations} in
     * phase 7, each by deleting it from this list. That is what made admitting a
     * module a deliberate, reviewable act rather than a directory appearing.
     *
     * <p>The rule is kept, empty, because the next module to be proposed should
     * have to be added here to be allowed - and an empty list is a clearer
     * statement of "nothing is pending" than a deleted test.
     */
    @ArchTest
    static final ArchRule no_packages_for_future_phases = noClasses()
            .should()
            .resideInAnyPackage(NO_MODULES_ARE_PENDING)
            .because("a module may only exist once its phase is active");
}
