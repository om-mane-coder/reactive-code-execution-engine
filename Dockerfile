# Stage 1: Build the application
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY . .
# Run gradle build using wrapper
RUN ./gradlew bootJar --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
