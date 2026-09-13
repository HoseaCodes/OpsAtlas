package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ingestion pipeline, against the repository's own fixtures.
 *
 * <p>These read {@code examples/services/} directly rather than from a copy in
 * test resources. The same files are checked by the Node script in
 * {@code packages/contracts}, and {@code invalid/expected.json} states what each
 * bad manifest must produce - so if the JVM and the workspace ever disagree
 * about a manifest, one of these two suites fails rather than both quietly
 * believing themselves.
 */
class ManifestValidationTest {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ManifestIngestor ingestor = newIngestor();

    private static ManifestIngestor newIngestor() {
        // Constructed directly rather than through Spring. This pipeline has no
        // infrastructure dependencies, so these tests need no application
        // context and run in milliseconds - which is what makes it reasonable to
        // run every fixture through it on every build.
        return new ManifestIngestor(
                new ManifestLoader(MAPPER),
                new ManifestSchemaValidator(),
                new ManifestBinder(),
                new ManifestSemantics(),
                MAPPER);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture " + path, e);
        }
    }

    // -- Valid fixtures -----------------------------------------------------

    static Stream<Path> validFixtures() throws IOException {
        try (var files = Files.list(EXAMPLES)) {
            return files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validFixtures")
    @DisplayName("every example manifest ingests cleanly")
    void every_example_manifest_ingests(Path fixture) {
        var ingested = ingestor.ingest(read(fixture));

        assertThat(ingested.schemaVersion()).isEqualTo("opsatlas.ambitiousconcepts.io/v1");
        assertThat(ingested.digest()).matches("[a-f0-9]{64}");
        assertThat(ingested.manifest().metadata().name()).isNotBlank();
        assertThat(ingested.manifest().spec().environments()).isNotEmpty();
    }

    @Test
    @DisplayName("the digest is stable for identical bytes and differs for changed ones")
    void digest_is_content_addressed() {
        String document = read(EXAMPLES.resolve("orders-api.yaml"));

        assertThat(ingestor.ingest(document).digest()).isEqualTo(ingestor.ingest(document).digest());

        // A comment is a real change to a real file, and a team re-committing
        // one should see the digest move. This is what makes re-registration
        // idempotent without an idempotency key.
        assertThat(ingestor.ingest(document + "\n# a later edit\n").digest())
                .isNotEqualTo(ingestor.ingest(document).digest());
    }

    @Test
    @DisplayName("a fully populated manifest binds every field")
    void binds_every_field() {
        ManifestV1 manifest = ingestor.ingest(read(EXAMPLES.resolve("orders-api.yaml"))).manifest();

        assertThat(manifest.metadata().name()).isEqualTo("orders-api");
        assertThat(manifest.metadata().displayName()).contains("Orders API");
        assertThat(manifest.metadata().owner()).contains("ambitious-concepts");
        assertThat(manifest.metadata().repository()).isEqualTo("ambitious-concepts/orders-api");

        assertThat(manifest.spec().tier()).isEqualTo(1);
        assertThat(manifest.spec().lifecycle()).isEqualTo("active");
        assertThat(manifest.spec().runtime()).contains("spring-boot");
        assertThat(manifest.spec().environments()).hasSize(2);
        assertThat(manifest.spec().environments().get(0).name()).isEqualTo("production");
        assertThat(manifest.spec().environments().get(0).url()).contains("https://orders.example.com");

        // This one is load-bearing: a binder bug here silently fails the
        // readiness and liveness scorecard checks for every service.
        assertThat(manifest.spec().health()).isPresent();
        assertThat(manifest.spec().health().orElseThrow().readiness())
                .contains("/actuator/health/readiness");
        assertThat(manifest.spec().health().orElseThrow().liveness())
                .contains("/actuator/health/liveness");

        assertThat(manifest.spec().observability().orElseThrow().serviceName()).contains("orders-api");
        assertThat(manifest.spec().operations().orElseThrow().runbook()).contains("docs/runbook.md");
        assertThat(manifest.spec().operations().orElseThrow().slo().orElseThrow().availability())
                .isEqualTo(99.9);
        assertThat(manifest.spec().operations().orElseThrow().slo().orElseThrow().window())
                .isEqualTo("30d");
        assertThat(manifest.spec().journeys()).contains(List.of("Place an order", "Track an order"));
    }

    @Test
    @DisplayName("a bare string dependency and an object dependency collapse to one shape")
    void dependency_shorthand_and_long_form_agree() {
        ManifestV1 manifest = ingestor.ingest(read(EXAMPLES.resolve("orders-api.yaml"))).manifest();
        List<ManifestV1.Dependency> dependencies = manifest.spec().dependencies().orElseThrow();

        assertThat(dependencies)
                .containsExactly(
                        new ManifestV1.Dependency("pricing-engine", ManifestV1.DependencyKind.SERVICE),
                        new ManifestV1.Dependency("postgres", ManifestV1.DependencyKind.DATASTORE),
                        new ManifestV1.Dependency("payments.example.net", ManifestV1.DependencyKind.EXTERNAL));
    }

    @Test
    @DisplayName("absent dependencies and an empty list are different answers")
    void absent_and_empty_are_distinguishable() {
        // identity-bff omits the key entirely. "I have not said" is a gap the
        // scorecard reports; "I have none" is a statement it accepts.
        ManifestV1 absent = ingestor.ingest(read(EXAMPLES.resolve("identity-bff.yaml"))).manifest();
        assertThat(absent.spec().dependencies()).isEmpty();

        ManifestV1 stated = ingestor.ingest(read(EXAMPLES.resolve("orders-api.yaml"))).manifest();
        assertThat(stated.spec().dependencies()).isPresent();
    }

    @Test
    @DisplayName("an unowned manifest is valid; being unowned is a scorecard matter")
    void unowned_manifests_still_register() {
        ManifestV1 manifest =
                ingestor.ingest(read(EXAMPLES.resolve("legacy-report-runner.yaml"))).manifest();

        assertThat(manifest.metadata().owner()).isEmpty();
        assertThat(manifest.spec().tier()).isEqualTo(3);
    }

    // -- Invalid fixtures ---------------------------------------------------

    record ExpectedFailure(String fixture, String stage, List<Violation> expected) {
        @Override
        public String toString() {
            return fixture;
        }
    }

    static Stream<ExpectedFailure> invalidFixtures() throws IOException {
        JsonNode fixtures = MAPPER.readTree(
                        Files.readString(EXAMPLES.resolve("invalid").resolve("expected.json")))
                .get("fixtures");

        List<ExpectedFailure> failures = new java.util.ArrayList<>();
        fixtures.properties().forEach(entry -> {
            JsonNode spec = entry.getValue();
            List<Violation> expected = new java.util.ArrayList<>();
            spec.get("violations")
                    .forEach(v -> expected.add(new Violation(
                            v.get("pointer").asText(),
                            v.get("keyword").asText(),
                            "stated in expected.json",
                            null,
                            "stated in expected.json")));
            failures.add(new ExpectedFailure(entry.getKey(), spec.get("stage").asText(), expected));
        });

        failures.sort(java.util.Comparator.comparing(ExpectedFailure::fixture));
        return failures.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    @DisplayName("every invalid fixture fails at exactly the pointer expected.json states")
    void invalid_fixtures_fail_where_expected(ExpectedFailure expectation) {
        String document = read(EXAMPLES.resolve("invalid").resolve(expectation.fixture()));

        ValidationFailedException thrown =
                catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest(document));

        assertThat(thrown)
                .as("%s must be rejected, not accepted", expectation.fixture())
                .isNotNull();

        for (Violation want : expectation.expected()) {
            assertThat(thrown.violations())
                    .as("%s should report %s (%s); it reported %s",
                            expectation.fixture(),
                            want.pointer(),
                            want.keyword(),
                            thrown.violations().stream()
                                    .map(v -> v.pointer() + " (" + v.keyword() + ")")
                                    .toList())
                    .anyMatch(got -> got.pointer().equals(want.pointer())
                            && got.keyword().equals(want.keyword()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    @DisplayName("every violation is actionable: a pointer and a complete sentence")
    void every_violation_is_actionable(ExpectedFailure expectation) {
        String document = read(EXAMPLES.resolve("invalid").resolve(expectation.fixture()));

        ValidationFailedException thrown =
                catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest(document));

        assertThat(thrown.violations()).isNotEmpty();
        for (Violation violation : thrown.violations()) {
            assertThat(violation.pointer()).startsWith("/");
            assertThat(violation.message())
                    .as("a violation message must be a sentence, not a keyword")
                    .hasSizeGreaterThan(20)
                    .endsWith(".");
            assertThat(violation.message())
                    .as("CLAUDE.md section 8: \"Invalid YAML\" is not an acceptable error message")
                    .isNotEqualToIgnoringCase("invalid yaml");
        }
    }

    // -- Hostile and malformed input ---------------------------------------

    @Nested
    @DisplayName("input the loader must refuse")
    class HostileInput {

        @Test
        @DisplayName("a document over the size cap is rejected before it is parsed")
        void oversized_input_is_capped() {
            // The payload is valid YAML; it is refused for its size alone, which
            // is what makes this a cap rather than a parse failure.
            String huge = "apiVersion: opsatlas.ambitiousconcepts.io/v1\n# " + "x".repeat(70 * 1024) + "\n";

            assertThatThrownBy(() -> ingestor.ingest(huge))
                    .isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).violations())
                            .anyMatch(v -> v.keyword().equals("maxSize")));
        }

        @Test
        @DisplayName("a billion-laughs document is a parse error, not heap exhaustion")
        void alias_expansion_is_refused() {
            String bomb =
                    """
                    apiVersion: opsatlas.ambitiousconcepts.io/v1
                    kind: Service
                    a: &a ["x","x","x","x","x","x","x","x","x"]
                    b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
                    c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]
                    d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]
                    e: [*d,*d,*d,*d,*d,*d,*d,*d,*d]
                    """;

            assertThatThrownBy(() -> ingestor.ingest(bomb)).isInstanceOf(ValidationFailedException.class);
        }

        @Test
        @DisplayName("a duplicate key is an error, not last-one-wins")
        void duplicate_keys_are_refused() {
            String document =
                    """
                    apiVersion: opsatlas.ambitiousconcepts.io/v1
                    kind: Service
                    metadata:
                      name: orders-api
                      name: something-else
                      repository: ambitious-concepts/orders-api
                    spec:
                      tier: 1
                      environments:
                        - name: production
                    """;

            assertThatThrownBy(() -> ingestor.ingest(document)).isInstanceOf(ValidationFailedException.class);
        }

        @Test
        @DisplayName("a YAML tag naming a Java type is not honoured")
        void java_type_tags_are_not_constructed() {
            // The point of ADR 0002: there is no code path from a manifest to a
            // constructed Java object of the document's choosing.
            String document =
                    """
                    apiVersion: opsatlas.ambitiousconcepts.io/v1
                    kind: Service
                    metadata: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://example.com/"]]
                    spec:
                      tier: 1
                      environments:
                        - name: production
                    """;

            assertThatThrownBy(() -> ingestor.ingest(document)).isInstanceOf(ValidationFailedException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\n\n", "# only a comment\n"})
        @DisplayName("an empty document says so, rather than failing obscurely")
        void empty_documents_are_named(String document) {
            ValidationFailedException thrown =
                    catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest(document));

            assertThat(thrown.violations()).anyMatch(v -> v.keyword().equals("empty"));
        }

        @Test
        @DisplayName("a YAML syntax error reports the line it is on")
        void syntax_errors_are_located() {
            String document =
                    """
                    apiVersion: opsatlas.ambitiousconcepts.io/v1
                    kind: Service
                    metadata:
                      name: orders-api
                     repository: badly-indented
                    """;

            ValidationFailedException thrown =
                    catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest(document));

            assertThat(thrown.violations()).hasSize(1);
            assertThat(thrown.violations().get(0).message())
                    .contains("line")
                    .doesNotContainIgnoringCase("invalid yaml:");
        }

        @Test
        @DisplayName("a top-level list is refused with an explanation")
        void non_mapping_root_is_refused() {
            ValidationFailedException thrown =
                    catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest("- a\n- b\n"));

            assertThat(thrown.violations()).anyMatch(v -> v.keyword().equals("type"));
        }
    }

    // -- apiVersion selection ----------------------------------------------

    @Test
    @DisplayName("an unknown apiVersion names the versions that are known")
    void unknown_api_version_lists_known_versions() {
        String document = read(EXAMPLES.resolve("invalid").resolve("unknown-api-version.yaml"));

        ValidationFailedException thrown =
                catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest(document));

        assertThat(thrown.violations()).hasSize(1);
        Violation violation = thrown.violations().get(0);
        assertThat(violation.pointer()).isEqualTo("/apiVersion");
        // Not a guess at the nearest version, and not a bare rejection: the
        // caller is told what this control plane does accept.
        assertThat(violation.message()).contains("opsatlas.ambitiousconcepts.io/v1");
    }

    @Test
    @DisplayName("a schema failure reports every problem, not only the first")
    void all_violations_are_reported() {
        String document =
                """
                apiVersion: opsatlas.ambitiousconcepts.io/v1
                kind: Service
                metadata:
                  name: Not_A_Slug
                  repository: not-a-repository
                spec:
                  tier: 9
                  environments:
                    - name: production
                """;

        ValidationFailedException thrown =
                catchThrowableOfType(ValidationFailedException.class, () -> ingestor.ingest(document));

        assertThat(thrown.violations())
                .extracting(Violation::pointer)
                .contains("/metadata/name", "/metadata/repository", "/spec/tier");
    }
}
