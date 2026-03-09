# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Baixa dependências em cache separado (rebuild mais rápido)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Usuário não-root (boa prática de segurança)
RUN addgroup -S crivo && adduser -S crivo -G crivo
USER crivo

COPY --from=build /app/target/backend-spring-0.0.1-SNAPSHOT.jar app.jar

# Porta que o Fly.io vai expor
EXPOSE 8081

# JVM otimizada para container (memória limitada do free tier)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]
