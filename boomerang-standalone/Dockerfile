FROM eclipse-temurin:17-jre-alpine

# Add non-root user for security
RUN addgroup -S boomerang && adduser -S boomerang -G boomerang

WORKDIR /app

ARG JAR_FILE=target/boomerang-standalone-*.jar
COPY ${JAR_FILE} app.jar

RUN chown boomerang:boomerang app.jar
USER boomerang

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
