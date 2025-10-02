# Dockerfile (recommended)
FROM eclipse-temurin:19-jre
WORKDIR /app

COPY target/lb-app.jar /app/app.jar
COPY config/application.yaml /app/config/application.yaml
# (optional) COPY config/logback.xml /app/config/logback.xml

EXPOSE 8080 9000
ENTRYPOINT ["java","--enable-preview","-jar","/app/app.jar","--config=/app/config/application.yaml"]
