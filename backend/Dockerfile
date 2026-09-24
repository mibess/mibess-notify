FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:25-jre
RUN groupadd -g 10001 notify && useradd -u 10001 -g notify -M -s /usr/sbin/nologin notify
WORKDIR /app
COPY --from=build --chown=notify:notify /build/target/notify-1.0.0.jar /app/notify.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/app/notify.jar"]
