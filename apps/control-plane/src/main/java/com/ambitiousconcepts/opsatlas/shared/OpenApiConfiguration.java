package com.ambitiousconcepts.opsatlas.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI opsAtlasOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpsAtlas control plane")
                        .version("v1")
                        .description(
                                """
                                The control plane for the OpsAtlas service catalog.

                                Conventions that hold for every endpoint: collections are cursor-paginated \
                                and never offset-paginated; failures are RFC 9457 problem documents carrying \
                                a correlationId and, for validation failures, a violations array locating each \
                                problem by JSON Pointer; a correlation id is accepted on X-Correlation-Id or \
                                generated, and echoed on every response.

                                Nothing in this API reports observed health. No field describes a measurement \
                                the platform has taken, because nothing probes anything yet.
                                """));
    }

    /**
     * Marks every response property required, and lets {@code nullable} carry
     * whether its value may be absent.
     *
     * <p>Without this, springdoc emits no {@code required} array at all and the
     * generated TypeScript makes every field optional. That is the contract
     * failing to say what a caller can rely on: every consumer then defends
     * against absences that cannot happen, and the ones that genuinely can
     * happen stop standing out.
     *
     * <p>Required and nullable answer different questions, and both are marked
     * because both are true. Jackson serializes a null field rather than
     * omitting it, so <em>the key is always present</em> - that is what
     * {@code required} says. Whether the value may be null is a separate fact
     * about the domain, declared with {@code @Schema(nullable = true)} at the
     * field. Conflating them would produce {@code string | null | undefined} in
     * TypeScript for a field that is never undefined.
     */
    @Bean
    public OpenApiCustomizer describeRequiredAndNullableAccurately() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null || components.getSchemas() == null) {
                return;
            }
            components.getSchemas().values().forEach(OpenApiConfiguration::markRequired);
        };
    }

    @SuppressWarnings("rawtypes") // io.swagger's Schema.getProperties() is declared with a raw value type.
    private static void markRequired(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }

        List<String> required = new ArrayList<>(properties.keySet());
        // Nested inline object schemas get the same treatment, so a nested
        // record does not quietly revert to all-optional.
        properties.values().forEach(OpenApiConfiguration::markRequired);

        if (!required.isEmpty()) {
            schema.setRequired(required);
        }
    }
}
