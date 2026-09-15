package com.ambitiousconcepts.opsatlas.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may reach what (ADR 0013).
 *
 * <p>Until now nothing authenticated anything: eight write endpoints were open
 * to whatever could reach the port, including observation ingestion, which takes
 * an environment id from the request body and folds results into counters - so
 * fabricated health left no per-probe record to audit against.
 *
 * <p>OpsAtlas is a resource server and nothing more. It verifies RS256 tokens
 * against the issuer's published JWKS and holds no signing key, so it can check
 * a token and cannot mint one. That asymmetry is the whole reason for choosing a
 * JWKS-publishing issuer over anything symmetric, and it is why the configuration
 * below names a key set rather than a secret.
 *
 * <p>Conditional on being a servlet application, and that is structural rather
 * than a switch: {@code HttpSecurity} exists only in a web context, so without
 * this a non-web test context fails to start. It is deliberately not a property
 * toggle - {@code TracingConfiguration} carried a {@code @ConditionalOnProperty}
 * once and silently loaded nothing, and the failure mode for a security filter
 * chain that quietly does not load is considerably worse than a missing span.
 * There is no configuration that turns authentication off; an application with
 * no web tier has no endpoints to protect.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    /**
     * Keeps this system's own keys away from the token decoder.
     *
     * <p>A service credential may arrive on {@code Authorization: Bearer}, because
     * some callers can send nothing else. Spring Security's bearer filter does not
     * ask whether anybody has already authenticated the request - it resolves the
     * header, tries to decode it as a JWT, fails, and replaces a perfectly good
     * authentication with a 401. Refusing to resolve our own prefix is the
     * supported way to say "this one is not a token".
     *
     * <p>A JWT never carries that prefix, so a real token still reaches the
     * decoder untouched.
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> {
            String authorization = request.getHeader("Authorization");
            if (authorization != null
                    && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    && authorization.substring(7).trim().startsWith(ServiceCredentials.KEY_PREFIX)) {
                return null;
            }
            return standard.resolve(request);
        };
    }

    @Bean
    SecurityFilterChain api(
            HttpSecurity http,
            ProvisionedPrincipals provisioned,
            ProblemSecurityResponses problems,
            ServiceCredentialFilter serviceCredentials)
            throws Exception {
        return http
                // No sessions and no cookies: every caller presents a bearer
                // token on every request. That also removes CSRF as a category
                // rather than as a filter - there is no ambient credential for a
                // forged request to ride on.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        // Liveness and readiness answer a load balancer that has
                        // no credential to offer, and disclose nothing.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()

                        // The generated contract describes the API; it contains
                        // no data. The Swagger UI assets are served with it so
                        // the page can load and offer its Authorize button - the
                        // endpoints it calls still require a credential, and the
                        // page itself only ships when OPSATLAS_SWAGGER_UI is on.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()

                        // Everything else, read or write, needs a caller this
                        // system knows. A verified token is not a membership:
                        // the issuer will mint one for any account it holds, and
                        // its accounts are not this catalog's users.
                        .anyRequest()
                        .access(provisioned))
                // Before the bearer-token filter: a machine that has already
                // authenticated on its own header must not then be handed to the
                // JWT decoder, which would find no token and start over.
                .addFilterBefore(serviceCredentials, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .build();
    }
}
