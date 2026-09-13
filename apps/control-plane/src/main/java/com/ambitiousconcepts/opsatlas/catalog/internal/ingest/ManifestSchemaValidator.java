package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Stage 3 of ADR 0002: validate the parsed document against the schema its own
 * {@code apiVersion} selects.
 *
 * <p>An unrecognised {@code apiVersion} is a violation, never a fall back to the
 * nearest known version. CLAUDE.md section 8: "an unknown version is a
 * validation error, not a guess." Guessing would mean validating a document
 * against rules its author did not write it for, and then storing the result as
 * though it had been checked.
 *
 * <p>The schemas are loaded from the classpath, where the Gradle build placed
 * the single copy from {@code packages/contracts/schemas}. There is no second
 * copy to drift.
 */
@Component
public class ManifestSchemaValidator {

    /** apiVersion -> the schema resource that validates it. */
    private static final Map<String, String> SCHEMAS_BY_API_VERSION = Map.of(
            "opsatlas.ambitiousconcepts.io/v1", "contracts/schemas/service.v1.schema.json");

    private final Map<String, JsonSchema> compiled = new LinkedHashMap<>();

    ManifestSchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        // JSON Pointer rather than the library's default JSONPath, so that a
        // violation's location is the same string the Node checker and
        // examples/services/invalid/expected.json use.
        SchemaValidatorsConfig config =
                SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build();

        SCHEMAS_BY_API_VERSION.forEach((apiVersion, resource) -> {
            try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                compiled.put(apiVersion, factory.getSchema(in, config));
            } catch (IOException e) {
                // Starting without a schema would mean accepting manifests
                // nothing had checked. Fail at boot instead.
                throw new IllegalStateException(
                        "Could not load the schema '" + resource + "' for apiVersion " + apiVersion
                                + ". The control plane cannot validate manifests without it.",
                        e);
            }
        });
    }

    /**
     * @return the apiVersion the document was validated against, to be stored
     *     alongside the service so the row can always be interpreted by the
     *     rules that admitted it
     * @throws ValidationFailedException with one violation per schema failure,
     *     not just the first
     */
    public String validate(JsonNode document) {
        String apiVersion = document.path("apiVersion").asText(null);
        JsonSchema schema = compiled.get(apiVersion);

        if (schema == null) {
            throw new ValidationFailedException(
                    "Unrecognised apiVersion",
                    List.of(new Violation(
                            "/apiVersion",
                            "const",
                            "one of: " + String.join(", ", SCHEMAS_BY_API_VERSION.keySet()),
                            apiVersion,
                            apiVersion == null || apiVersion.isBlank()
                                    ? "No apiVersion was declared. It selects the schema this document is checked against."
                                    : "The apiVersion '" + apiVersion
                                            + "' is not one this control plane knows, so there is no schema to check "
                                            + "this document against. Known versions: "
                                            + String.join(", ", SCHEMAS_BY_API_VERSION.keySet()) + ".")));
        }

        Set<ValidationMessage> messages = schema.validate(document);
        if (messages.isEmpty()) {
            return apiVersion;
        }

        List<Violation> violations = messages.stream()
                .map(ManifestSchemaValidator::toViolation)
                // Stable order, so the same bad document always produces the
                // same response and a test can assert on it.
                .sorted(Comparator.comparing(Violation::pointer).thenComparing(Violation::keyword))
                .toList();

        throw new ValidationFailedException(
                violations.size() == 1
                        ? "The document did not satisfy the schema: 1 problem"
                        : "The document did not satisfy the schema: " + violations.size() + " problems",
                violations);
    }

    private static Violation toViolation(ValidationMessage message) {
        String pointer = message.getInstanceLocation().toString();
        if (pointer.isEmpty()) {
            pointer = "/";
        }
        String keyword = message.getType();
        return new Violation(pointer, keyword, expectationFor(message), null, explain(pointer, keyword, message));
    }

    /** What would have been accepted, in the schema's own terms. */
    private static String expectationFor(ValidationMessage message) {
        Object[] arguments = message.getArguments();
        if (arguments != null && arguments.length > 0 && arguments[0] != null) {
            return String.valueOf(arguments[0]);
        }
        return message.getType();
    }

    /**
     * A complete sentence the author of the manifest can act on.
     *
     * <p>The library's raw message is usable but terse. The keywords handled
     * explicitly below are the ones that read badly on their own; everything
     * else falls through to the library's text, which always says something
     * rather than nothing.
     */
    private static String explain(String pointer, String keyword, ValidationMessage message) {
        String where = "/".equals(pointer) ? "the document" : pointer;
        String raw = stripLeadingPointer(pointer, message.getMessage());

        return switch (keyword) {
            case "required" -> "A required property is missing at " + where + ": " + raw + ".";
            case "additionalProperties" -> "An unrecognised property was found at " + where + ": " + raw
                    + ". Check the spelling; unknown keys are rejected rather than ignored so a typo cannot"
                    + " silently do nothing.";
            case "pattern" -> "The value at " + where + " is not in the required format: " + raw + ".";
            case "type" -> "The value at " + where + " is the wrong type: " + raw + ".";
            case "enum" -> "The value at " + where + " is not one of the permitted values: " + raw + ".";
            case "maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum" ->
                "The value at " + where + " is outside the permitted range: " + raw + ".";
            case "minItems" -> "Not enough entries at " + where + ": " + raw + ".";
            case "oneOf" -> "The value at " + where + " matches none of the accepted shapes: " + raw + ".";
            default -> "The value at " + where + " " + raw + ".";
        };
    }

    /**
     * The validator prefixes its messages with the instance location, and this
     * class states the location itself. Left alone, every message reads
     * "...at /spec/tier: /spec/tier: must be...".
     */
    private static String stripLeadingPointer(String pointer, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "was not accepted";
        }
        String trimmed = rawMessage.trim();
        for (String prefix : new String[] {pointer + ": ", "$" + pointer + ": ", "$: "}) {
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return trimmed;
    }
}
