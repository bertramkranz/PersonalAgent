plugins {
    kotlin("jvm") version "2.3.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // OpenAI Java SDK
    implementation("com.openai:openai-java:4.42.0")

    // JSON serialization (used by FileBertBotStateStore and BertBotMemory)
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines (optional, for async operations)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JDBC driver for optional PostgreSQL-backed state persistence.
    runtimeOnly("org.postgresql:postgresql:42.7.4")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

application {
    mainClass.set("com.personalagent.bertbot.app.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("runHeadless") {
    group = "application"
    description = "Run BertBot in headless prompt mode."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.personalagent.bertbot.app.HeadlessMainKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runMcpServer") {
    group = "application"
    description = "Run BertBot as a local MCP server over stdio."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.personalagent.bertbot.app.McpServerMainKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runWebhookServer") {
    group = "application"
    description = "Run BertBot webhook server for Telegram, Slack, and WhatsApp payload routing."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.personalagent.bertbot.app.WebhookMainKt")
    standardInput = System.`in`
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("check") {
    dependsOn("detekt", "ktlintCheck")
}
