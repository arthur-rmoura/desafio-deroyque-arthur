# syntax=docker/dockerfile:1

# ---- build: compila e extrai o jar em camadas (dependências mudam pouco e ficam em cache) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Os testes rodam no CI (precisam de Docker para o Testcontainers); a imagem só empacota.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/activation-service-*.jar extract --layers --launcher --destination extracted

# ---- runtime: apenas JRE, usuário sem privilégios ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER app

# Heap proporcional ao limite de memória do contêiner; em OOM a JVM encerra e o orquestrador reinicia.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs

EXPOSE 8080 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1

# exec form: a JVM é o PID 1 e recebe o SIGTERM, permitindo o desligamento gracioso.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
