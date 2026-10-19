# Etapa 1: compila o binário nativo com GraalVM (Spring AOT + native-image).
FROM ghcr.io/graalvm/native-image-community:21 AS build
COPY --from=maven:3.9.9-eclipse-temurin-21 /usr/share/maven /usr/share/maven
ENV PATH=/usr/share/maven/bin:$PATH
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -Pnative -DskipTests native:compile

# Etapa 2: imagem final só com o executável (sem JVM).
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=build /app/target/nutriviver-backend /app/nutriviver-backend
EXPOSE 8080
ENTRYPOINT ["/app/nutriviver-backend"]
