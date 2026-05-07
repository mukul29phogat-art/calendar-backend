# syntax=docker/dockerfile:1.7

# ---- build stage ---------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# 1) deps first (max layer reuse) — only pom.xml + the Maven wrapper change rarely
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# 2) sources + package
COPY src src
RUN ./mvnw -B package -DskipTests

# 3) explode the fat jar so the runtime image can copy in pieces (cheaper layer
#    reuse: deps layer changes slowly, app classes change on every rebuild).
RUN jar xf target/calendar-backend-*.jar && rm target/calendar-backend-*.jar

# ---- runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Run as a dedicated unprivileged user
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
USER app

# Preserve the exploded fat-jar structure that Spring Boot 3.3's JarLauncher
# expects (it reads BOOT-INF/classpath.idx + BOOT-INF/lib/*.jar at startup).
# Order: stable -> volatile so Docker's layer cache reuses dep layers when only
# app classes change.
#
# Deviation note: the implementation_plan.md spec for this Dockerfile
# flattens BOOT-INF/lib -> lib at /app, which works for the legacy
# org.springframework.boot.loader.JarLauncher in Spring Boot 2.x but FAILS at
# runtime with Spring Boot 3.2+ (which uses
# org.springframework.boot.loader.launch.JarLauncher and needs BOOT-INF
# intact). Verified locally with NoClassDefFoundError on SpringApplication.
COPY --from=build /build/BOOT-INF/lib BOOT-INF/lib
COPY --from=build /build/META-INF META-INF
COPY --from=build /build/org org
COPY --from=build /build/BOOT-INF/classes BOOT-INF/classes

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
