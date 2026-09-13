package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * The one way a service.yaml enters this system.
 *
 * <p>ADR 0002's pipeline, in order, with no way to enter it halfway: cap and
 * parse, validate against the schema the document's own apiVersion selects, bind
 * to records, then check the rules the schema cannot express. Each stage runs
 * only if the one before it succeeded, which is what lets the binder assume its
 * input is well formed and the semantic checks assume the binder's output is
 * complete.
 *
 * <p>Everything else in {@code catalog} depends on this class rather than on the
 * stages, so there is a single place to read to know what happens to repository
 * content.
 */
@Component
public class ManifestIngestor {

    private final ManifestLoader loader;
    private final ManifestSchemaValidator schemaValidator;
    private final ManifestBinder binder;
    private final ManifestSemantics semantics;
    private final ObjectMapper objectMapper;

    ManifestIngestor(
            ManifestLoader loader,
            ManifestSchemaValidator schemaValidator,
            ManifestBinder binder,
            ManifestSemantics semantics,
            ObjectMapper objectMapper) {
        this.loader = loader;
        this.schemaValidator = schemaValidator;
        this.binder = binder;
        this.semantics = semantics;
        this.objectMapper = objectMapper;
    }

    /**
     * @param document the raw submitted service.yaml
     * @throws com.ambitiousconcepts.opsatlas.shared.ValidationFailedException
     *     carrying every violation found at the first stage that failed
     */
    public IngestedManifest ingest(String document) {
        JsonNode tree = loader.load(document);
        String schemaVersion = schemaValidator.validate(tree);
        ManifestV1 manifest = binder.bind(tree);
        semantics.check(manifest);

        return new IngestedManifest(manifest, schemaVersion, writeJson(tree), digestOf(document));
    }

    private String writeJson(JsonNode tree) {
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A tree that was just validated could not be serialized", e);
        }
    }

    /**
     * SHA-256 of the submitted bytes.
     *
     * <p>Over the raw document rather than the parsed tree, on purpose: two
     * documents that differ only in comments or key order are genuinely
     * different files, and a team re-committing one should see its manifest
     * digest change. This is what makes re-registering an unchanged manifest a
     * no-op, and it is what stands in for an idempotency key on the registration
     * endpoint (CLAUDE.md section 9).
     */
    private static String digestOf(String document) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(document.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform but was not available", e);
        }
    }

    /**
     * @param manifest      the bound, validated document
     * @param schemaVersion the apiVersion it was validated against, stored with the service
     * @param canonicalJson the validated tree as JSON, stored so policy can be re-evaluated
     *                      without re-reading the source repository
     * @param digest        SHA-256 of the raw submitted bytes
     */
    public record IngestedManifest(
            ManifestV1 manifest, String schemaVersion, String canonicalJson, String digest) {}
}
