# SpicyGallery server: JRE 21 + ffmpeg (video probing/thumbnails). The jar is
# produced by `mvn install` (webclient SPA baked in) and copied in at build time.
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY spicygallery-server/target/spicygallery-server-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
