# The control plane, as a container.
#
# Two stages. The first has Gradle and the JDK and produces a jar; the second has
# a JRE and the jar. The build stage is 700MB of toolchain that has no business
# running in production, and a compiler present at runtime is a compiler an
# attacker can use.

# ---- build -----------------------------------------------------------------
# The toolchain the build declares, so the image is built by the same Java the
# tests ran on rather than whatever the base image happens to ship.
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /src

# The wrapper and the dependency declarations first, so a change to source code
# does not re-resolve every dependency. This layer is the slow one.
COPY gradlew settings.gradle.kts ./
COPY gradle ./gradle
COPY apps/control-plane/build.gradle.kts ./apps/control-plane/
RUN ./gradlew --no-daemon :control-plane:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY apps/control-plane/src ./apps/control-plane/src
COPY packages/contracts/schemas ./packages/contracts/schemas

# Not `build`: the tests need Docker for Testcontainers, which is not available
# inside a build, and running them here would mean either a broken build or
# tests quietly skipped. They run in CI, where a daemon exists.
RUN ./gradlew --no-daemon :control-plane:bootJar

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Not root. The control plane accepts a manifest from a monitored repository and
# parses it; that path is deliberately safe (ADR 0002), and a second line of
# defence costs one line.
RUN addgroup -g 1001 -S opsatlas && adduser -S opsatlas -u 1001 -G opsatlas

# Root-owned and read-only to the process: it needs to run its code, never to
# rewrite it.
COPY --from=build /src/apps/control-plane/build/libs/control-plane-*.jar /app/control-plane.jar

USER opsatlas

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Readiness rather than liveness: this reports UP only once Flyway has run and
# the datasource answers, which is the difference between "the process started"
# and "it can serve a request".
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/control-plane.jar"]
