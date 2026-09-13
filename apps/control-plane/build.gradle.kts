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

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
