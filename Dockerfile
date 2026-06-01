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
# browser4-apps is only in the `all-modules` profile (not the default reactor),
# so we activate the profile to make it visible, then scope the build to only
# browser4-standalone plus its dependencies via -pl + -am.  This avoids building
# browser4-bundle (whose Browser4Bundle.jar would collide with Browser4.jar in a glob).
ARG STANDALONE_MODULE=browser4-apps/browser4-standalone
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -Pall-modules -pl ${STANDALONE_MODULE} -am -DskipTests -Dmaven.javadoc.skip=true -B -V && \
    echo "Build completed successfully"

# Copy the standalone JAR using its exact known path — no glob / find needed.
COPY ${STANDALONE_MODULE}/target/Browser4.jar /build/app.jar

# Validate the JAR before proceeding to the runtime stage.
RUN jar tf /build/app.jar | grep -q 'Browser4StandaloneApplication' || \
    (echo "ERROR: /build/app.jar does not contain Browser4StandaloneApplication class" && exit 1) && \
    echo "JAR validated: contains Browser4StandaloneApplication"

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
