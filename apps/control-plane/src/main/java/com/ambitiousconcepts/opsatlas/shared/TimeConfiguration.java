package com.ambitiousconcepts.opsatlas.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time, as a dependency rather than a static call.
 *
 * <p>CLAUDE.md section 7: all timestamps are UTC. Injecting a {@link Clock}
 * fixed to UTC means no code has to remember that, and a test can hold time
 * still rather than sleeping.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
