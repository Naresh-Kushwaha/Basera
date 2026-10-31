# syntax=docker/dockerfile:1
# ---- build: layered Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/basera-backend-*.jar extract --layers --destination /layers

# ---- runtime: JRE only, non-root, read-only-root-filesystem friendly ----
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system --gid 10001 basera && useradd --system --uid 10001 --gid basera --no-create-home basera \
 && mkdir -p /data/media /tmp && chown -R basera:basera /data
WORKDIR /app
COPY --from=build --chown=basera:basera /layers/dependencies/ ./
COPY --from=build --chown=basera:basera /layers/spring-boot-loader/ ./
COPY --from=build --chown=basera:basera /layers/snapshot-dependencies/ ./
COPY --from=build --chown=basera:basera /layers/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom" \
    BASERA_STORAGE_DIR=/data/media \
    SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/basera-backend-0.1.0-SNAPSHOT.jar"]
