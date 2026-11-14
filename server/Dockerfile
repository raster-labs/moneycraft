# syntax=docker/dockerfile:1.7
# ==============================================================================
# MoneyCraft 1.0.0 — Hardened Multi-Stage Spring Boot 4.1.2 / JRE 25 Dockerfile
# Builds `moneycraft-api` & `moneycraft-migrator` (Build context: repo root `.`)
# ==============================================================================

FROM maven:3.10.0-eclipse-temurin-25@sha256:a4734fd7909a9c2beddcacfa1229bd308a349f000bea1ec8db3287c8a16ca2a3 AS builder

WORKDIR /workspace

# 1. Cache Maven reactor POM graph & Checkstyle config before copying sources
COPY pom.xml ./pom.xml
COPY tools/checkstyle/moneycraft-checkstyle.xml ./tools/checkstyle/moneycraft-checkstyle.xml
COPY reader/pom.xml ./reader/pom.xml
COPY server/pom.xml ./server/pom.xml
COPY qa/pom.xml ./qa/pom.xml

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl reader,server -am dependency:go-offline -DskipTests || true

# 2. Copy clean-room reader & server sources and compile reproducible layered WAR
COPY reader/src ./reader/src
COPY server/src ./server/src

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl reader,server -am package -DskipTests -DskipITs=true && \
    java -Djarmode=tools -jar /workspace/server/target/moneycraft.war \
        extract --layers --launcher --destination /workspace/extracted

# ==============================================================================
# Stage 2: Hardened Non-Root Slim JRE 25 Runtime (`uid 10001:10001`)
# ==============================================================================
FROM eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS runtime

ARG MC_VERSION=1.0.0-SNAPSHOT
ARG MC_VCS_REF=dev

LABEL org.opencontainers.image.title="moneycraft-api" \
      org.opencontainers.image.description="MoneyCraft 1.0.0 Clean-Room ERP Backend (Spring Boot 4.1.2 / Java 25)" \
      org.opencontainers.image.version="${MC_VERSION}" \
      org.opencontainers.image.revision="${MC_VCS_REF}" \
      org.opencontainers.image.vendor="Raster Images Pvt. Ltd."

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fontconfig \
        tini \
        tzdata && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 moneycraft && \
    useradd --uid 10001 --gid 10001 --shell /usr/sbin/nologin --create-home --home-dir /opt/moneycraft moneycraft && \
    mkdir -p /var/log/moneycraft/oom /var/run/moneycraft && \
    chown -R 10001:10001 /opt/moneycraft /var/log/moneycraft /var/run/moneycraft

WORKDIR /opt/moneycraft

# Copy Spring Boot 4.1 extracted layers in least-frequently-changing order
COPY --from=builder --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=10001:10001 /workspace/extracted/application/ ./

# Container-aware Generational ZGC flags (JDK 25 native generational ZGC)
ENV TZ="UTC" \
    LANG="C.UTF-8" \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -XX:HeapDumpPath=/var/log/moneycraft/oom -Djava.security.egd=file:/dev/./urandom -Djava.awt.headless=true" \
    SERVER_PORT="8080" \
    SERVER_SHUTDOWN="graceful" \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE="25s" \
    MANAGEMENT_SERVER_PORT="8081" \
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED="true"

USER 10001:10001

EXPOSE 8080 8081

STOPSIGNAL SIGTERM

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health/liveness >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["java", "org.springframework.boot.loader.launch.WarLauncher"]
