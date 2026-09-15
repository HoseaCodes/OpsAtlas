package com.ambitiousconcepts.opsatlas.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Gives every MockMvc request a token, so the suite runs through security
 * rather than around it.
 *
 * <p>The alternative was {@code @AutoConfigureMockMvc(addFilters = false)},
 * which is one word and turns the security filter chain off for every existing
 * test. That would leave two hundred tests exercising a request path that no
 * real caller can take, and the first thing anyone would notice is nothing -
 * they would all still pass.
 *
 * <p>The token is built rather than decoded: {@code jwt()} installs an
 * authentication directly, so no key, no issuer and no network are involved.
 * That is the right trade here because what these tests are about is the
 * endpoints, not the verifier - but it does mean none of them prove a real token
 * would be accepted. {@code AuthenticationIT} exists for that, and mints a token
 * against a real key set.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedByDefault {

    /** Storm-Gate stamps the subject as `id`, not `sub` (ADR 0013). */
    public static final String SUBJECT = "6aa7317cf6dd7f35e3d7d4d4";

    /**
     * Identity is issuer plus subject, so the tests carry both. Not a real
     * Storm-Gate URL: nothing here contacts an issuer, and a plausible-looking
     * one would invite somebody to think it did.
     */
    public static final String ISSUER = "https://issuer.test.invalid";

    @Bean
    MockMvcBuilderCustomizer authenticateEveryRequest() {
        return builder -> builder.defaultRequest(get("/")
                .with(jwt().jwt(token -> token.issuer(ISSUER).claim("id", SUBJECT).subject(SUBJECT))));
    }
}
