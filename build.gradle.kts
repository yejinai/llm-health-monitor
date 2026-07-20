plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.ktor.plugin") version "3.3.0"
}

group = "llmhealth"
version = "0.1.0"

application {
    mainClass.set("llmhealth.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.3.0")
    implementation("io.ktor:ktor-server-netty:3.3.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-server-cors:3.3.0")
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("io.ktor:ktor-client-okhttp:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.3.0")
}

kotlin {
    jvmToolchain(21)
}
