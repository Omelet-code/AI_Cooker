FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S cooker && adduser -S cooker -G cooker
COPY --from=builder /workspace/target/cooker-backend-*.jar app.jar

USER cooker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

