package com.ambitiousconcepts.opsatlas.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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

                                Every endpoint requires a caller. A person presents a bearer token from \
                                the configured identity provider; a machine of this system's own - the \
                                observer - presents the key it was issued on X-OpsAtlas-Key. A token that \
                                verifies but belongs to nobody provisioned here is answered 403, not 401: \
                                the credential is genuine, it is simply not known to this deployment.

                                Health here is probe availability, measured by an observer against a \
                                declared health endpoint from one vantage point. It is not an SLO \
                                measurement, and no field in this API should be read as one.
                                """));
    }

    /**
     * Says how a caller authenticates.
     *
     * <p>Two schemes, because there are two kinds of caller and they are not
     * interchangeable. A person carries a bearer token minted by the identity
     * provider; the observer carries a key this system issued, on a header of
     * its own so it is never handed to the token decoder (ADR 0013).
     *
     * <p>Listed as two entries rather than one, which in OpenAPI means "either
     * of these" rather than "both of these". Without this the document said
     * nothing about authentication at all: the generated client did not know a
     * token existed, and Swagger UI offered no way to send one, so every
     * try-it-out call answered 401 with no indication why.
     */
    @Bean
    public OpenApiCustomizer describeAuthentication() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();

            components.addSecuritySchemes(
                    "bearerToken",
                    new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("A token from the configured identity provider. Verified against its "
                                    + "published key set; this system holds no signing key and cannot mint one."));

            components.addSecuritySchemes(
                    "serviceCredential",
                    new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.HEADER)
                            .name("X-OpsAtlas-Key")
                            .description("A key issued by this system to one of its own machines. The audit log "
                                    + "records the caller as service:<name> rather than as a person."));

            openApi.components(components)
                    .addSecurityItem(new SecurityRequirement().addList("bearerToken"))
                    .addSecurityItem(new SecurityRequirement().addList("serviceCredential"));
        };
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
