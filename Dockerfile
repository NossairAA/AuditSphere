FROM maven:3.9.14-eclipse-temurin-21@sha256:98819eb3745bd2007c3f1a19b59085c1fa3929aecb7dbfa431dfcf5a4f18ce3c AS build
WORKDIR /build
COPY pom.xml .
COPY services services
ARG SERVICE
RUN mvn -B -pl services/${SERVICE} -am package -DskipTests
RUN cp services/${SERVICE}/target/${SERVICE}-*.jar /app.jar
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY --from=build /app.jar app.jar
# Sized for container memory limits. Kubernetes values can override this variable.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k"
USER 10001:10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
