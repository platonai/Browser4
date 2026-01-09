# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy build tooling first for better layer caching
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn ./.mvn
COPY browser4/pom.xml browser4/pom.xml
COPY browser4/browser4-spa/pom.xml browser4/browser4-spa/pom.xml

# Copy the remaining sources
COPY . .
RUN chmod +x mvnw

# Build only the SPA module and its dependencies
RUN --mount=type=cache,target=/root/.m2 ./mvnw -pl browser4/browser4-spa -am package -DskipTests -Dmaven.javadoc.skip=true -B
RUN test -f browser4/browser4-spa/target/Browser4SPA.jar
RUN cp browser4/browser4-spa/target/Browser4SPA.jar /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=spa \
    JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC" \
    CHROME_BIN=/usr/bin/chromium \
    BROWSER_DISPLAY_MODE=HEADLESS

RUN apt-get update && \
    apt-get install -y --no-install-recommends chromium fonts-liberation ca-certificates tzdata && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/app.jar /app/app.jar

RUN useradd -r -u 1001 -g users appuser && chown -R appuser:users /app
USER appuser

EXPOSE 8182
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

