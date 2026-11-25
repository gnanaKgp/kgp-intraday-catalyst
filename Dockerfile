FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Kolkata
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
COPY --from=builder /workspace/target/nse-catalyst-terminal-1.0.0.jar app.jar
RUN mkdir -p /app/data /app/logs

EXPOSE 8085
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Kolkata"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

