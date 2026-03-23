FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY boomerang-sample/target/boomerang-sample-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
