FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY build/libs/app.jar /app/app.jar

USER appuser

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
