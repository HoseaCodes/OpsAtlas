package com.ambitiousconcepts.opsatlas.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
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

                        // TODO(auth): the metrics endpoints are open because
                        // Prometheus scrapes them and nothing issues it a token
                        // yet. They expose request counts and timings rather
                        // than catalog data, but "rather than" is not "never" -
                        // this is the next thing to close, not a resting place.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus", "/actuator/metrics/**")
                        .permitAll()

                        // The generated contract describes the API; it contains
                        // no data. Swagger UI is a different question and is
                        // answered by OPSATLAS_SWAGGER_UI, which must be off
                        // wherever this is genuinely reachable.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                        .permitAll()

                        // Everything else, read or write, needs a caller.
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
