import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.springframework.boot") version "3.3.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("maven-publish")
}

group = "ai.platon.pulsar"
version = "4.1.0-rc.1"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")
    
    group = rootProject.group
    version = rootProject.version
    
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all")
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    tasks.withType<Test> {
        useJUnitPlatform()
    }
    
    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        
        // Kotlin dependencies
        implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.20"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib")
        implementation("org.jetbrains.kotlin:kotlin-reflect")
        
        // Kotlin coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
        
        // Test dependencies
        testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    }
    
    // Publishing configuration
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                
                pom {
                    name.set(project.name)
                    description.set("A lightning-fast, coroutine-safe browser for your AI.")
                    url.set("https://github.com/platonai/browser4")
                    
                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    
                    developers {
                        developer {
                            name.set("Vincent Zhang")
                            email.set("ivincent.zhang@gmail.com")
                            organization.set("platon.ai")
                            organizationUrl.set("https://platon.ai")
                        }
                    }
                    
                    scm {
                        connection.set("scm:git:https://github.com/platonai/browser4.git")
                        developerConnection.set("scm:git:https://github.com/platonai/browser4.git")
                        url.set("https://github.com/platonai/browser4")
                    }
                }
            }
        }
    }
}
