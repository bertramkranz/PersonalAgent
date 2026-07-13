plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("org.sonarqube") version "6.1.0.5360"
    jacoco
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

val koogVersion = "1.0.0"
val koogBetaVersion = "1.0.0-beta"

dependencies {
    // Koog core runtime and optional features for chat memory, long-term memory, and telemetry.
    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:agents-features-memory:$koogVersion")
    implementation("ai.koog:agents-features-opentelemetry:$koogVersion")
    implementation("ai.koog:agents-features-longterm-memory:$koogBetaVersion")

    implementation("io.opentelemetry:opentelemetry-api:1.51.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.51.0")
    implementation("io.opentelemetry:opentelemetry-exporter-logging:1.51.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.51.0")

    // OpenAI Java SDK
    implementation("com.openai:openai-java:4.42.0")

    // JSON serialization (used by FileBertBotStateStore and BertBotMemory)
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines (optional, for async operations)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("net.dv8tion:JDA:5.0.0-beta.24")

    // JDBC driver for optional PostgreSQL-backed state persistence.
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.21.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)

    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.required.set(true)
    }
}

sonar {
    properties {
        property("sonar.projectKey", "bertramkranz_PersonalAgent")
        property("sonar.projectName", "PersonalAgent")
        property("sonar.coverage.jacoco.xmlReportPaths", layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.absolutePath)
        property("sonar.junit.reportPaths", layout.buildDirectory.dir("test-results/test").get().asFile.absolutePath)
        property("sonar.kotlin.detekt.reportPaths", layout.buildDirectory.file("reports/detekt/detekt.xml").get().asFile.absolutePath)
    }
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

tasks.register<JavaExec>("runDiscordBot") {
    group = "application"
    description = "Run BertBot Discord bot listener for two-way message integration."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.personalagent.bertbot.app.DiscordBotMainKt")
    standardInput = System.`in`
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("check") {
    dependsOn("detekt", "ktlintCheck", "jacocoTestReport")
}
