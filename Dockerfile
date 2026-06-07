# Stage 1: Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

# Set working directory
WORKDIR /build

# Copy project files (use .dockerignore to control which files to copy)
COPY pom.xml ./
COPY VERSION ./
COPY mvnw ./
COPY .mvn ./.mvn
# include **/src/**/target
COPY .gitignore .
COPY bin ./bin
COPY . .

RUN ls -la && ls -la bin && find . -name "*.sh" -exec chmod +x {} \;

# Build the standalone application with Maven cache mount.
# The asset-standalone profile adds only browser4-apps/browser4-standalone to
# the default reactor (which already includes core/rest/agentic).  This avoids
# building browser4-bundle, whose Browser4Bundle.jar would collide with
# Browser4.jar if selected by accident.
ARG STANDALONE_MODULE=browser4-apps/browser4-standalone
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -Passet-standalone -DskipTests -B -V && \
    echo "Build completed successfully"

# Copy the JAR that Maven just built inside the container.
# Use RUN cp (not COPY) — COPY would pull from the Docker build context
# (host filesystem), which does not have the freshly-built JAR.
RUN cp ${STANDALONE_MODULE}/target/Browser4.jar /build/app.jar

# Validate the JAR before proceeding to the runtime stage.
RUN jar xf /build/app.jar META-INF/MANIFEST.MF && \
    grep -q 'Start-Class: ai.platon.pulsar.apps.Browser4StandaloneApplicationKt' META-INF/MANIFEST.MF || \
    (echo "ERROR: /build/app.jar has wrong Start-Class — not built from browser4-standalone" && exit 1) && \
    echo "JAR validated: Start-Class is Browser4StandaloneApplicationKt"

# Stage 2: Run stage
FROM eclipse-temurin:21-jre-alpine AS runner

# Set working directory
WORKDIR /app

# Set timezone
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Install Chromium and necessary dependencies with security updates
RUN apk update && apk upgrade && \
    apk add --no-cache \
    curl \
    chromium \
    nss \
    freetype \
    freetype-dev \
    harfbuzz \
    ca-certificates \
    ttf-freefont \
    dbus && \
    rm -rf /var/cache/apk/*

# Set Chromium environment variables
# Ignore BROWSER_CONTEXT_NUMBER, BROWSER_MAX_OPEN_TABS if BROWSER_CONTEXT_MODE is set to DEFAULT
ENV JAVA_OPTS="-Xms2G -Xmx10G -XX:+UseG1GC" \
    OPENROUTER_API_KEY=${OPENROUTER_API_KEY} \
    PROXY_ROTATION_URL=${PROXY_ROTATION_URL} \
    BROWSER_CONTEXT_MODE=DEFAULT \
    BROWSER_CONTEXT_NUMBER=2 \
    BROWSER_MAX_OPEN_TABS=8 \
    BROWSER_DISPLAY_MODE=HEADLESS

# Copy build artifact
COPY --from=builder /build/app.jar app.jar

# Expose port (documentation only)
EXPOSE 8182

# Create app data directory
RUN mkdir -p ~/.browser4/

# Create non-root user and set directory permissions
RUN addgroup --system --gid 1001 appuser && \
    adduser --system --uid 1001 --ingroup appuser appuser && \
    chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Add build arguments
LABEL maintainer="Vincent Zhang <ivincent.zhang@gmail.com>" \
      description="Browser4: An AI-Enabled, Super-Fast, Thread-Safe Browser Automation Solution! 💖"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
