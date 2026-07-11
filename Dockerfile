FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
COPY gradle.properties gradle.properties
COPY src src

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:17-jre
WORKDIR /opt/bertbot

COPY --from=build /workspace/build/install/PersonalAgent/lib ./lib
COPY docker/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

EXPOSE 8088

ENV BERTBOT_RUN_MODE=webhook
ENV BERTBOT_WEBHOOK_HOST=0.0.0.0
ENV BERTBOT_WEBHOOK_PORT=8088

ENTRYPOINT ["/entrypoint.sh"]
