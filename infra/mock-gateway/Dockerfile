FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps apps
COPY libs libs
ARG MODULE=core-platform
RUN mvn -B -ntp -Dmaven.test.skip=true install && mvn -B -ntp -pl apps/${MODULE} dependency:copy-dependencies -DincludeScope=runtime
RUN mkdir /output && cp apps/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /output/app.jar && cp -r apps/${MODULE}/target/dependency /output/lib

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG APP_MAIN=com.example.gateway.MockGatewayServer
ENV APP_MAIN=${APP_MAIN}
COPY --from=build /output /app
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java -cp '/app/app.jar:/app/lib/*' \"$APP_MAIN\""]
