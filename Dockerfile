# Use OpenJDK 17 slim image as base for the build stage
FROM openjdk:17-jdk-slim AS build

# Set the working directory in the container
WORKDIR /app

# Define an argument for the JAR file location
ARG JAR_FILE=build/libs/*.jar

# Copy the JAR file from the build context to the container
COPY ${JAR_FILE} app.jar

# Create a new stage for the runtime environment
FROM openjdk:17-jdk-slim

# Set the working directory in the container
WORKDIR /app

# Copy the application configuration file to the container
COPY src/main/resources/application.yml /app/application.yml

# Copy the JAR file from the build stage to the runtime stage
COPY --from=build /app/app.jar app.jar

# Expose port 8080 for the application
EXPOSE 8080

# Define the entrypoint for the container
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Dotel.resource.attributes=service.name=auth-server", "-jar", "app.jar"]
