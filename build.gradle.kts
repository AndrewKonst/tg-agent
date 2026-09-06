plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "dev.konst.tgbotai"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // --- Telegram ---
    implementation("dev.inmo:tgbotapi:36.1.0")

    // --- AI agent framework ---
    implementation("ai.koog:koog-agents:1.2.0")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // --- Ktor Client (BOM keeps Koog's and tgbotapi's Ktor on one version) ---
    implementation(platform("io.ktor:ktor-bom:3.5.2"))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")

    // --- Conversation history ---
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    // --- Logging ---
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Routes tgbotapi's java.util.logging output into Logback.
    implementation("org.slf4j:jul-to-slf4j:2.0.17")
    // Not runtimeOnly: logging/PollTimeoutFilter implements a Logback Filter.
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

application {
    mainClass.set("AppKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}
