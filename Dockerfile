FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=builder /app/build/libs/barie-core.jar app.jar
RUN chown spring:spring app.jar

USER spring:spring

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 1081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dfile.encoding=UTF-8 -jar /app/app.jar"]