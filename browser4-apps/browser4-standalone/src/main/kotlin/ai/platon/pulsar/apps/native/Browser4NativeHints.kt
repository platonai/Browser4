package ai.platon.pulsar.apps.native

import ai.platon.pulsar.apps.Browser4StandaloneApplication
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * Registers GraalVM native-image reachability metadata for the Browser4
 * standalone application.
 *
 * This is wired into Spring AOT processing via `@ImportRuntimeHints` on
 * [Browser4StandaloneApplication].  During the `process-aot` phase, Spring
 * calls [registerHints] so that the resulting native-image configuration
 * includes the reflection, resource, and proxy declarations that the
 * application needs at runtime.
 *
 * Additional metadata for third-party libraries (Netty, Jetty, Jackson,
 * Kotlin reflect, etc.) is discovered automatically from
 * `META-INF/native-image/` descriptors shipped inside those libraries'
 * JARs, because the native-maven-plugin has `<metadataRepository>` enabled.
 */
class Browser4NativeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        // -- Application entry point -------------------------------------------
        // Spring Boot instantiates the @SpringBootApplication class reflectively.
        hints.reflection().registerType(
            Browser4StandaloneApplication::class.java,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS
        )

        // -- Resources ---------------------------------------------------------
        // Configuration files loaded via classpath resource resolution.
        hints.resources().registerPattern("application*.properties")
        hints.resources().registerPattern("application*.yml")
        hints.resources().registerPattern("application*.yaml")

        // Spring Framework auto-configuration and service-loader files.
        hints.resources().registerPattern("META-INF/spring/*")
        hints.resources().registerPattern("META-INF/spring/**")
        hints.resources().registerPattern("META-INF/services/*")
        hints.resources().registerPattern("META-INF/services/**")

        // Logging configuration.
        hints.resources().registerPattern("logback*.xml")
        hints.resources().registerPattern("org/springframework/boot/logging/**")

        // Static web resources (served by Spring MVC / Jetty).
        hints.resources().registerPattern("static/**")
    }
}
