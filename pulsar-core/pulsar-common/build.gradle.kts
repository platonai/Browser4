// Example build.gradle.kts for pulsar-common module
// This demonstrates the pattern for migrating Maven dependencies to Gradle
//
// NOTE: This module still requires additional dependencies to be added.
// Please refer to pom.xml and add missing dependencies as needed.
// For example, Jackson dependencies are needed but not yet added to the version catalog.

dependencies {
    // TODO: Add dependency on pulsar-resources if needed
    // implementation(project(":pulsar-core:pulsar-resources"))
    
    // Spring core
    implementation(rootProject.libs.spring.core)
    
    // XML process
    implementation(rootProject.libs.xml.apis)
    
    // HTTP client
    implementation(rootProject.libs.httpclient5)
    
    // ICU for encoding detection
    implementation(rootProject.libs.icu4j)
    
    // Utilities
    implementation(rootProject.libs.guava)
    implementation(rootProject.libs.commons.io)
    implementation(rootProject.libs.commons.codec)
    implementation(rootProject.libs.commons.lang3)
    implementation(rootProject.libs.commons.math3)
    implementation(rootProject.libs.commons.collections4)
    implementation(rootProject.libs.gson)
    
    // Woodstox (XML processing)
    implementation(rootProject.libs.stax2.api)
    implementation(rootProject.libs.woodstox.core.asl)
    
    // TODO: Add Jackson dependencies
    // implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    // implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
    
    // Note: Kotlin stdlib and test dependencies are already added by root build.gradle.kts
}
