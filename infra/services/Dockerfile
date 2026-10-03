# syntax=docker/dockerfile:1
FROM maven:3-eclipse-temurin-24 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps apps
COPY libs libs
ARG MODULE=core-platform
RUN case "$MODULE" in core-platform|inventory-service|notification-service) ;; *) exit 1 ;; esac
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp -Dmaven.test.skip=true install && mvn -B -ntp -pl apps/${MODULE} dependency:copy-dependencies -DincludeScope=runtime
RUN mkdir /output && cp apps/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /output/app.jar && cp -r apps/${MODULE}/target/dependency /output/lib

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ARG APP_MAIN=com.example.platform.PlatformApplication
ARG REVISION=unknown
ARG SOURCE=unknown
LABEL org.opencontainers.image.revision=${REVISION} org.opencontainers.image.source=${SOURCE}
ENV APP_MAIN=${APP_MAIN}
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"
ENV HEALTH_PORT=8080 HEALTH_PATH=/actuator/health/readiness
COPY --from=build /output /app
USER 10001:10001
HEALTHCHECK --interval=10s --timeout=4s --start-period=90s --retries=6 CMD curl --fail --silent --max-time 3 "http://127.0.0.1:${HEALTH_PORT}${HEALTH_PATH}" > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java -cp '/app/app.jar:/app/lib/*' \"$APP_MAIN\""]
