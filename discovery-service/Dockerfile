# ---------------------------------------------------------------------------
# discovery-service — container image for Render (Docker runtime, free plan).
#
# Built from the REPO ROOT as the build context, because the reactor reads the
# aggregator pom and every sibling pom, and because the shared config-repo/
# directory has to be inside this module's jar (it is on the classpath as
# classpath:/config-repo, which is how config-service serves it in a container
# with no repo checkout on disk):
#
#   docker build -f discovery-service/Dockerfile -t ecom-discovery-service .
#
# The root .dockerignore keeps that context at a few MB instead of the ~4 GB
# working tree. discovery-service/.dockerignore is NOT applied by the command above and is
# only relevant if this module is ever built with itself as the context.
# ---------------------------------------------------------------------------

# Stage 1 — build ------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first, sources after, so the dependency layer is cached and only
# re-resolved when a pom actually changes. The aggregator is at /build/pom.xml
# and every sibling pom lands at /build/<module>/pom.xml, so the whole reactor
# parses and `-pl`/`-am` resolve.
COPY pom.xml ./
COPY discovery-service/pom.xml discovery-service/pom.xml
COPY config-service/pom.xml config-service/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY customer-service/pom.xml customer-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY billing-service/pom.xml billing-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY ledger-service/pom.xml ledger-service/pom.xml

# `dependency:go-offline` is best-effort: it does not resolve every plugin a
# later `package` needs, and a failure here must not fail the image. The
# `package` step below re-resolves whatever is still missing, online.
RUN mvn -pl discovery-service -am dependency:go-offline -B || true

# All sources, not just this module's: spring-boot-maven-plugin's `repackage`
# runs against the reactor, and copying every src keeps this layer identical
# for all eight images so the Docker layer cache is shared.
COPY discovery-service/src discovery-service/src
COPY config-service/src config-service/src
COPY gateway-service/src gateway-service/src
COPY customer-service/src customer-service/src
COPY inventory-service/src inventory-service/src
COPY billing-service/src billing-service/src
COPY order-service/src order-service/src
COPY ledger-service/src ledger-service/src

# config-service serves config-repo/ from the classpath and ledger-service's
# tests build against it, so it belongs in the image.
COPY config-repo config-repo

RUN mvn -pl discovery-service -am package -DskipTests -B

# Stage 2 — runtime ----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Explicit jar name. The wildcard form would also match a `*.jar.original`
# (spring-boot-maven-plugin's pre-repackage backup) or a sources jar, and COPY
# resolves it at build time with no chance to inspect the result.
COPY --from=build /build/discovery-service/target/discovery-service-0.0.1-SNAPSHOT.jar app.jar

# Physical port only, never used as the advertised port: the app binds
# server.port, which reads Render's PORT (see application.properties), and
# image metadata cannot contain shell expansion.
EXPOSE 8080

# Render free gives 512 MB, and the default JVM sizing is a percentage of the
# memory it can see, which is the host's and not the container's. Cap the heap
# at 75% of the cgroup limit (MaxRAMPercentage reads the cgroup), use SerialGC
# because a 512 MB single-core container has nothing to gain from a parallel or
# concurrent collector, and stop C2 at tier 1 since these services serve a demo
# dashboard and never warm up to the point where C2 pays for itself.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

ENTRYPOINT ["java", "-jar", "app.jar"]
