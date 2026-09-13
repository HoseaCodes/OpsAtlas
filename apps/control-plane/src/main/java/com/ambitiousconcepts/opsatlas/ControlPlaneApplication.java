package com.ambitiousconcepts.opsatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OpsAtlas control plane.
 *
 * <p>A modular monolith. Each module owns a top-level package and exposes only
 * its {@code api} sub-package; {@code internal} is private to the module and
 * {@code ArchitectureTest} fails the build when that is violated. See
 * {@code docs/adr/0001-modular-monolith-as-one-gradle-module.md}.
 *
 * <p>Modules present: {@code shared}, {@code identity}, {@code catalog},
 * {@code governance}, {@code integrations}. {@code operations} arrives with the
 * observer and is in {@code docs/roadmap.md}, deliberately not on disk.
 */
@SpringBootApplication
@EnableScheduling
public class ControlPlaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
