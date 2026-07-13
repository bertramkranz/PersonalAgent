FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
COPY gradle.properties gradle.properties
COPY src src

RUN sed -i 's/\r$//' ./gradlew \
 && chmod +x ./gradlew \
 && ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:17-jre
WORKDIR /opt/bertbot

RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm git \
 && npm install -g github:gemini-cli-extensions/workspace#v0.0.8 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/install/PersonalAgent/lib ./lib
COPY docker/entrypoint.sh /entrypoint.sh

RUN sed -i 's/\r$//' /entrypoint.sh \
 && chmod +x /entrypoint.sh

EXPOSE 8088

ENV BERTBOT_RUN_MODE=webhook
ENV BERTBOT_WEBHOOK_HOST=0.0.0.0
ENV BERTBOT_WEBHOOK_PORT=8088

ENTRYPOINT ["/entrypoint.sh"]
