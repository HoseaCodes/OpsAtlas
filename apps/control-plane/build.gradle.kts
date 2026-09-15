plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.ambitiousconcepts"
version = "0.1.0"
description = "OpsAtlas control plane"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Authentication (ADR 0013). The resource server starter brings Spring
    // Security plus the JOSE support that verifies an RS256 token against a
    // published JWKS - OpsAtlas checks tokens and can never mint one. Both are
    // in the stack CLAUDE.md section 5 already fixes for the control plane.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation(libs.uuid.generator)
    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)
    implementation(libs.springdoc.openapi)

    // Telemetry. Versions come from the Spring Boot BOM rather than being
    // pinned here, so the bridge and the exporter cannot drift apart from the
    // Boot release that wires them.
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.micrometer.registry.prometheus)

    // Spring Security is in the fixed stack but is deliberately absent until
    // authentication is a phase. Adding the starter now would put every
    // endpoint behind a generated password - a capability the project does not
    // actually have. See docs/adr/0003-org-scoping-stub.md.

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.archunit.junit5)
}

/**
 * The service.yaml JSON Schema has exactly one copy, in packages/contracts.
 * It is copied into the jar at build time so the control plane and the
 * workspace validate against identical bytes rather than two files that agree
 * until someone edits one of them. ADR 0002.
 */
val contractSchemas = rootProject.file("packages/contracts/schemas")

tasks.named<ProcessResources>("processResources") {
    from(contractSchemas) {
        into("contracts/schemas")
    }
    // A missing schema must fail the build, not produce a jar that cannot
    // validate anything.
    doLast {
        val copied = File(destinationDir, "contracts/schemas/service.v1.schema.json")
        require(copied.isFile) {
            "The service.yaml schema was not copied from $contractSchemas. " +
                "The control plane cannot validate manifests without it."
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

// The OpenAPI generator boots the application to write the document. It is not
// a behaviour test, so it is excluded from `test` and run explicitly by
// `make openapi`, which keeps `make test` from writing into the working tree.
val openApiGenerator = "com.ambitiousconcepts.opsatlas.contracts.OpenApiDocumentGenerator"

tasks.register<Test>("generateOpenApiDocument") {
    description = "Writes packages/contracts/openapi/control-plane.json from the running application."
    group = "documentation"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching(openApiGenerator) }
    outputs.upToDateWhen { false }
}

tasks.named<Test>("test") {
    filter { excludeTestsMatching(openApiGenerator) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Tests read the example manifests and their expected violations from the
    // repository rather than from a copy. examples/services/invalid/expected.json
    // is the single source of truth shared with the Node checker; a copy here
    // would let the two drift silently.
    systemProperty("opsatlas.examples.dir", rootProject.file("examples/services").absolutePath)
    systemProperty("opsatlas.contracts.dir", rootProject.file("packages/contracts").absolutePath)


    // Files read at runtime are invisible to Gradle unless they are declared,
    // and an undeclared input makes a test look green when it simply did not
    // run: editing only README.md left this task up-to-date, so PolicySetIT -
    // which asserts the README's fleet table against the real scorer - was
    // skipped by the very change it exists to catch. CI never hit it, having no
    // cache to be up to date against, which is the kind of difference between
    // local and CI that is worth removing rather than remembering.
    inputs.dir(rootProject.file("examples/services")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("packages/contracts/schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("README.md")).withPathSensitivity(PathSensitivity.RELATIVE)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }

    // The documentation cites how many tests back a claim, and those numbers
    // drift. Two already had when this was written: ObservationIngestIT was
    // cited as 23 and runs 17, and a nest of failure cases was cited as seven
    // and holds eight. The whole argument the README makes is "here is the
    // evidence, counted" - one number that has quietly moved undermines every
    // number beside it, because the reader cannot tell which.
    //
    // It runs here rather than as a test because the truth is the JUnit results,
    // and a test cannot read the results of the run it is part of. Counting
    // @Test in the sources was tried first and is wrong: it misses what
    // @ParameterizedTest expands to, and misses ArchUnit's @ArchTest fields
    // entirely, so it disagreed with reality on three classes out of four.
    doLast {
        verifyDocumentedTestCounts(rootProject.rootDir, reports.junitXml.outputLocation.get().asFile)
    }
}

/**
 * Fails the build when a test count cited in the documentation is not true.
 *
 * Only classes that actually ran are checked, so a filtered run (`--tests ...`)
 * verifies what it can rather than failing on what it did not execute.
 */
fun verifyDocumentedTestCounts(root: File, resultsDir: File) {
    val results = resultsDir.listFiles { f -> f.name.endsWith(".xml") } ?: return
    if (results.isEmpty()) return

    // Nested classes count toward the file that holds them, which is how the
    // documentation cites them and how a reader would count.
    val actual = mutableMapOf<String, Int>()
    results.forEach { file ->
        val xml = groovy.xml.XmlParser().parse(file)
        // From the file name, not the name attribute: a @Nested class reports
        // its display name there ("idempotency"), so reading that attributes a
        // nested class's tests to nothing and undercounts its parent.
        val outer = file.name
            .removePrefix("TEST-")
            .removeSuffix(".xml")
            .substringBefore('$')
            .substringAfterLast('.')
        val count = xml.attribute("tests").toString().toInt()
        actual[outer] = (actual[outer] ?: 0) + count
    }

    // `ScorecardApiIT` (12), `OrgIsolationIT` (22 tests), `ArchitectureTest` - 15 rules.
    // Only backticked class names, so prose that happens to sit near a number is
    // left alone.
    val claim = Regex("`([A-Z][A-Za-z0-9]*(?:IT|Test))`[^.\n]{0,40}?(?:\\(|\u2014\\s)(\\d+)\\s*(?:tests?|rules?)?\\b")

    val wrong = mutableListOf<String>()
    var checked = 0
    listOf("README.md", "CLAUDE.md").forEach { name ->
        val doc = File(root, name)
        if (!doc.exists()) return@forEach
        claim.findAll(doc.readText()).forEach { match ->
            val className = match.groupValues[1]
            val cited = match.groupValues[2].toInt()
            val real = actual[className] ?: return@forEach // did not run; nothing to compare
            checked++
            if (cited != real) {
                wrong += "$name says $className has $cited, it ran $real"
            }
        }
    }

    if (wrong.isNotEmpty()) {
        throw GradleException(
            buildString {
                appendLine("Documentation cites test counts that are not true:")
                wrong.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("What actually ran:")
                actual.toSortedMap().forEach { (k, v) -> appendLine("  $k = $v") }
            }
        )
    }
    logger.lifecycle("Documented test counts checked: $checked citation(s) agree with what ran.")
}
