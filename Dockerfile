# Stage 1: Build stage
# maven:3.9.9-eclipse-temurin-25-alpine does not exist on Docker Hub (3.9.x
# gained temurin-25 tags starting at 3.9.11); 3.9.16 is the latest 3.9.x with
# the temurin-25-alpine variant.
FROM maven:3.9.16-eclipse-temurin-25-alpine AS builder

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

# Build the browser4-swarm plugin and collect it for the runtime plugins/
# directory.  The asset-standalone reactor does not include plugin modules,
# so build the plugin explicitly (its provided deps resolve against the
# host classpath at runtime).  Browser4StandaloneApplication loads every
# JAR in ./plugins/ via PluginClasspathEnhancer, which registers the swarm
# facade (SwarmFacadeMount) and makes /api/swarm/* available.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q package -pl browser4-plugins/browser4-swarm -am -DskipTests -B && \
    mkdir -p /build/plugins && \
    cp browser4-plugins/browser4-swarm/target/browser4-swarm-*.jar /build/plugins/ && \
    echo "Plugins collected:" && ls -la /build/plugins

# Validate the JAR before proceeding to the runtime stage.
RUN jar xf /build/app.jar META-INF/MANIFEST.MF && \
    grep -q 'Start-Class: ai.platon.pulsar.apps.Browser4StandaloneApplicationKt' META-INF/MANIFEST.MF || \
    (echo "ERROR: /build/app.jar has wrong Start-Class — not built from browser4-standalone" && exit 1) && \
    echo "JAR validated: Start-Class is Browser4StandaloneApplicationKt"

# Stage 2: Run stage
FROM eclipse-temurin:25-jre-alpine AS runner

# Set working directory
WORKDIR /app

# Set timezone
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Install Chromium and necessary dependencies with security updates.
# Pin the chromium version: the temurin alpine base image tracks a rolling
# Alpine repo, and the unpinned `chromium` package silently jumped 149 → 151
# between CI runs (2026-08-21 ok → 2026-08-24 E2E hangs: slow navigation,
# blank pages, CDP evaluate timeouts). 149.0.7827.53-r0 is the last known-good
# build (Alpine v3.23); the explicit --repository keeps the version resolvable
# even when the base image points at a newer Alpine release.
RUN apk update && apk upgrade && \
    apk add --no-cache \
    --repository https://dl-cdn.alpinelinux.org/alpine/v3.23/main \
    --repository https://dl-cdn.alpinelinux.org/alpine/v3.23/community \
    curl \
    chromium=149.0.7827.53-r0 \
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

# Runtime plugins (loaded from ./plugins/ relative to the working directory
# by PluginClasspathEnhancer at startup)
COPY --from=builder /build/plugins/ /app/plugins/

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
