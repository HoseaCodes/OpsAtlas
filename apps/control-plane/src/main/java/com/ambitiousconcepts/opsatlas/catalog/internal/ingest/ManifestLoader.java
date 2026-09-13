package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.springframework.stereotype.Component;

/**
 * Stage 1 and 2 of ADR 0002: cap the input, then parse it to plain data.
 *
 * <p>This is the only place in the application where content from a monitored
 * repository is parsed, and it is written so that the unsafe options do not
 * exist rather than being switched off:
 *
 * <ul>
 *   <li>{@link Load} returns only {@code Map}, {@code List}, {@code String},
 *       {@code Number}, {@code Boolean} and null. There is no constructor-based
 *       deserialization to configure away, so a manifest cannot name a Java type
 *       and cannot cause one to be instantiated. CLAUDE.md section 3 rule 4.
 *   <li>Alias expansion for collections is capped at zero, which makes the
 *       billion-laughs class of document a parse error rather than a heap
 *       exhaustion.
 *   <li>Duplicate keys are an error, not a silent last-one-wins.
 *   <li>Input is size-capped before a parser sees a byte.
 * </ul>
 *
 * <p>A syntax error still produces a located violation. "Invalid YAML" is not an
 * acceptable message (section 8), so the line and column from the parser's mark
 * are carried into the response.
 */
@Component
public class ManifestLoader {

    /**
     * A service.yaml is a declaration, not a document. The largest example in
     * this repository is under 1 KiB; 64 KiB is room for a generous one and
     * still small enough that the cap does real work.
     */
    static final int MAX_BYTES = 64 * 1024;

    private static final LoadSettings SETTINGS = LoadSettings.builder()
            .setLabel("service.yaml")
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            // Zero, not a small number. Nothing in the schema needs an anchor,
            // so any collection alias is either a mistake or an attack.
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(MAX_BYTES)
            .build();

    private final ObjectMapper objectMapper;

    ManifestLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param document the raw submitted bytes, as text
     * @return the parsed document as a JSON tree of plain values
     * @throws ValidationFailedException if the input is too large, empty, not a
     *     YAML mapping, or does not parse
     */
    public JsonNode load(String document) {
        rejectIfOversized(document);

        Object root;
        try {
            root = new Load(SETTINGS).loadFromString(document);
        } catch (MarkedYamlEngineException e) {
            throw syntaxError(e.getProblem(), e.getProblemMark());
        } catch (YamlEngineException e) {
            throw syntaxError(e.getMessage(), Optional.empty());
        }

        if (root == null) {
            throw single(new Violation(
                    "/",
                    "empty",
                    "a service.yaml document",
                    null,
                    "The submitted document is empty. It must contain a Service manifest."));
        }
        if (!(root instanceof java.util.Map)) {
            throw single(new Violation(
                    "/",
                    "type",
                    "an object at the top level",
                    root.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT),
                    "The top level of a service.yaml must be a mapping with apiVersion, kind, metadata and spec."));
        }

        // Safe: every value in the tree is a plain type produced by the loader
        // above. Nothing here can be a Java object the document asked for.
        return objectMapper.valueToTree(root);
    }

    private static void rejectIfOversized(String document) {
        int bytes = document.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw single(new Violation(
                    "/",
                    "maxSize",
                    "at most " + MAX_BYTES + " bytes",
                    bytes + " bytes",
                    "The document is " + bytes + " bytes, over the " + MAX_BYTES
                            + " byte limit. A service.yaml describes a service; it is not a place to store data."));
        }
    }

    private static ValidationFailedException syntaxError(String problem, Optional<Mark> mark) {
        String where = mark.map(m -> " at line " + (m.getLine() + 1) + ", column " + (m.getColumn() + 1))
                .orElse("");
        String detail = problem != null && !problem.isBlank() ? problem : "the document could not be parsed";
        return single(new Violation(
                "/",
                "syntax",
                "well-formed YAML",
                null,
                "The document is not valid YAML" + where + ": " + detail + "."));
    }

    private static ValidationFailedException single(Violation violation) {
        return new ValidationFailedException(violation.message(), List.of(violation));
    }
}
