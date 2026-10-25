FROM maven:3.9.14-eclipse-temurin-21@sha256:98819eb3745bd2007c3f1a19b59085c1fa3929aecb7dbfa431dfcf5a4f18ce3c AS build
WORKDIR /build
COPY pom.xml .
COPY services services
ARG SERVICE
RUN mvn -B -pl services/${SERVICE} -am package -DskipTests
RUN cp services/${SERVICE}/target/${SERVICE}-*.jar /app.jar
# Ubuntu 24.04 variant: the 26.04 image ships /usr/bin/pebble, built with a vulnerable Go standard library.
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
WORKDIR /app
COPY --from=build /app.jar app.jar
# Sized for container memory limits. Kubernetes values can override this variable.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k"
USER 10001:10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
