FROM maven:3.9.6-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /app/target/nso-web.jar /app/nso-web.jar
COPY sql ./sql
COPY data ./data
COPY web ./web
COPY certs ./certs

ENV PORT=8080
ENV NSO_DATA_DIR=/app/data
ENV DB_POOL_MAX=5
ENV AUTO_INIT_DB=true

EXPOSE 8080

# Giới hạn heap RAM phù hợp Render Free (512MB RAM tổng, Java target < 250MB RSS)
ENTRYPOINT ["java", "-Xms64m", "-Xmx160m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=72m", "-jar", "/app/nso-web.jar"]
