plugins {
    // Auto-provisions the JVM toolchain (JDK 21) regardless of which JDK runs Gradle
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "llm-health-monitor"
