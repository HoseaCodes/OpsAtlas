package com.ambitiousconcepts.opsatlas.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Writes {@code packages/contracts/openapi/control-plane.json} from the running
 * application.
 *
 * <p>ADR 0005 requires the document to be generated from the code and
 * drift-checked in CI. It originally specified the springdoc Gradle plugin;
 * this does the same job by booting the same application on a real port and
 * fetching {@code /v3/api-docs} over a real socket, which is the document the
 * running server actually serves. The ADR records why that substitution was
 * made.
 *
 * <p>It lives in the test source set because that is where the machinery to boot
 * the application already exists, but it is a generator, not an assertion about
 * behaviour. Two guards keep it honest:
 *
 * <ul>
 *   <li>It fails if the document is missing endpoints that exist, so a
 *       silently-empty document cannot be committed.
 *   <li>CI runs it and then {@code git diff --exit-code}, so a contract change
 *       that was not regenerated fails the build rather than reaching the
 *       frontend as a runtime type error.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentGenerator extends PostgresTestBase {

    private static final Path OUTPUT =
            Path.of(System.getProperty("opsatlas.contracts.dir"), "openapi", "control-plane.json");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("generate the OpenAPI document from the running application")
    void generate() throws Exception {
        String body = rest.getForObject("http://localhost:" + port + "/v3/api-docs", String.class);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode document = mapper.readTree(body);

        // A document that describes nothing would still be valid JSON and would
        // still commit cleanly. These assertions are what make the generated
        // file worth trusting.
        assertThat(document.get("openapi").asText()).startsWith("3.");
        assertThat(document.at("/paths/~1api~1v1~1services").isMissingNode())
                .as("the catalog collection must be described")
                .isFalse();
        assertThat(document.at("/paths/~1api~1v1~1services~1{slug}").isMissingNode())
                .as("the service detail endpoint must be described")
                .isFalse();
        assertThat(document.at("/paths/~1api~1v1~1services~1{slug}~1scorecard").isMissingNode())
                .as("the scorecard endpoint must be described")
                .isFalse();
        assertThat(document.at("/paths/~1api~1v1~1audit-events").isMissingNode())
                .as("the audit log must be described")
                .isFalse();
        assertThat(document.at("/paths/~1api~1v1~1policy~1rules").isMissingNode())
                .as("the rule set endpoint must be described")
                .isFalse();

        // springdoc records the URL it was generated from, which is a random
        // port here and a deployment address in any other environment. Left in,
        // it would make the drift check fail on every run for a reason that has
        // nothing to do with the contract - and a check that always fails is a
        // check everyone learns to ignore. A committed contract has no business
        // naming a server anyway: the base URL is the caller's to choose.
        ((com.fasterxml.jackson.databind.node.ObjectNode) document).remove("servers");

        Files.createDirectories(OUTPUT.getParent());
        // Pretty-printed and newline-terminated so the committed file produces a
        // readable diff when an endpoint changes. The diff is the review.
        Files.writeString(OUTPUT, mapper.writeValueAsString(document) + System.lineSeparator());
    }
}
