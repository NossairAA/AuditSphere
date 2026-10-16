FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY services services
ARG SERVICE
RUN mvn -B -pl services/${SERVICE} -am package -DskipTests
RUN cp services/${SERVICE}/target/${SERVICE}-*.jar /app.jar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
USER 10001:10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
