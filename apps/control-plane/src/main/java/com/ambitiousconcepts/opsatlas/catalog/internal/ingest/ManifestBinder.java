package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Dependency;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.DependencyKind;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Environment;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Health;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Metadata;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Observability;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Operations;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Slo;
import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1.Spec;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * Stage 4 of ADR 0002: turn a validated document into {@link ManifestV1}.
 *
 * <p>Every field is read explicitly. There is no reflection, no annotation-driven
 * binding and no type information taken from the document, so a manifest cannot
 * influence which Java types get constructed. That is the property ADR 0002 is
 * protecting, and writing the binder by hand is what makes it visible instead of
 * a consequence of {@code ObjectMapper} configuration.
 *
 * <p>This runs only on documents that already passed
 * {@link ManifestSchemaValidator}, so required fields are known to be present and
 * of the right type. Where this class still checks, it is because a bug here
 * should throw rather than silently produce a half-bound record.
 */
@Component
public class ManifestBinder {

    /** The schema's default, applied here so downstream code never sees an absent lifecycle. */
    private static final String DEFAULT_LIFECYCLE = "active";

    public ManifestV1 bind(JsonNode document) {
        return new ManifestV1(
                required(document, "apiVersion").asText(),
                bindMetadata(required(document, "metadata")),
                bindSpec(required(document, "spec")));
    }

    private static Metadata bindMetadata(JsonNode node) {
        return new Metadata(
                required(node, "name").asText(),
                text(node, "displayName"),
                text(node, "owner"),
                required(node, "repository").asText(),
                text(node, "description"));
    }

    private static Spec bindSpec(JsonNode node) {
        return new Spec(
                required(node, "tier").asInt(),
                text(node, "lifecycle").orElse(DEFAULT_LIFECYCLE),
                text(node, "runtime"),
                bindEnvironments(required(node, "environments")),
                object(node, "health").map(ManifestBinder::bindHealth),
                object(node, "observability").map(ManifestBinder::bindObservability),
                object(node, "operations").map(ManifestBinder::bindOperations),
                array(node, "journeys").map(ManifestBinder::bindStrings),
                array(node, "dependencies").map(ManifestBinder::bindDependencies));
    }

    private static List<Environment> bindEnvironments(JsonNode node) {
        List<Environment> environments = new ArrayList<>();
        node.forEach(entry -> environments.add(new Environment(required(entry, "name").asText(), text(entry, "url"))));
        return List.copyOf(environments);
    }

    private static Health bindHealth(JsonNode node) {
        return new Health(text(node, "readiness"), text(node, "liveness"));
    }

    private static Observability bindObservability(JsonNode node) {
        return new Observability(text(node, "serviceName"), text(node, "dashboard"));
    }

    private static Operations bindOperations(JsonNode node) {
        return new Operations(
                object(node, "slo").map(ManifestBinder::bindSlo), text(node, "runbook"), text(node, "contact"));
    }

    private static Slo bindSlo(JsonNode node) {
        return new Slo(required(node, "availability").asDouble(), required(node, "window").asText());
    }

    private static List<String> bindStrings(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }

    /**
     * A dependency is either a bare string or an object. The schema accepts both;
     * this collapses them to one shape so nothing downstream has to know that.
     */
    private static List<Dependency> bindDependencies(JsonNode node) {
        List<Dependency> dependencies = new ArrayList<>();
        node.forEach(entry -> {
            if (entry.isTextual()) {
                dependencies.add(new Dependency(entry.asText(), DependencyKind.SERVICE));
            } else {
                dependencies.add(new Dependency(
                        required(entry, "name").asText(),
                        text(entry, "kind").map(DependencyKind::parse).orElse(DependencyKind.SERVICE)));
            }
        });
        return List.copyOf(dependencies);
    }

    // -- Readers ------------------------------------------------------------

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            // Unreachable for a schema-validated document. If it happens, the
            // schema and this binder have disagreed, and that is a defect to
            // surface loudly rather than bind around.
            throw new IllegalStateException("Field '" + field
                    + "' is absent after schema validation. The schema and ManifestBinder have drifted apart.");
        }
        return value;
    }

    private static Optional<String> text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() || !value.isTextual() ? Optional.empty() : Optional.of(value.asText());
    }

    private static Optional<JsonNode> object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || !value.isObject() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<JsonNode> array(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || !value.isArray() ? Optional.empty() : Optional.of(value);
    }
}
