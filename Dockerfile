# Stage 1: extract layers from the fat JAR
# Platform pinned to linux/amd64 — matches CI (ubuntu-latest) and deployment targets
FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine AS builder
WORKDIR /application
COPY target/*.jar application.jar
# Extracts layers into /application/application/{dependencies,spring-boot-loader,snapshot-dependencies,application}
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher

# Stage 2: minimal runtime image
FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /application
COPY --from=builder /application/application/dependencies/ ./
COPY --from=builder /application/application/spring-boot-loader/ ./
COPY --from=builder /application/application/snapshot-dependencies/ ./
COPY --from=builder /application/application/application/ ./
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
