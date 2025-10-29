import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.openapi.generator") version "7.6.0"
    java
    `maven-publish`
    id("pl.allegro.tech.build.axion-release") version "1.20.1"
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "com.velaris.api"

// --- Versioning (axion-release) ---
scmVersion {
    tag { prefix.set("v") }
    versionIncrementer("incrementPatch")
}
version = scmVersion.version

repositories {
    mavenCentral()
}

val apiSpec = "$projectDir/api/velaris-api.yaml"

// --- Paths for generated code ---
val generatedServerDir = layout.buildDirectory.dir("generated/java-server").get().asFile
val generatedKotlinClientDir = layout.buildDirectory.dir("generated/kotlin-client").get().asFile

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// --- Source sets ---
sourceSets {
    create("javaServer") {
        java.srcDirs("${generatedServerDir}/src/main/java")
    }
    create("kotlinClient") {
        kotlin.srcDir("${generatedKotlinClientDir}/src/main/kotlin")
    }
}

// --- Configurations for custom source sets ---
configurations["javaServerImplementation"].extendsFrom(configurations["implementation"])
configurations["javaServerRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])
configurations["javaServerCompileOnly"].extendsFrom(configurations["compileOnly"])

configurations["kotlinClientImplementation"].extendsFrom(configurations["implementation"])
configurations["kotlinClientRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])
configurations["kotlinClientCompileOnly"].extendsFrom(configurations["compileOnly"])

// --- Dependencies ---
dependencies {
    // --- Implementation ---
    implementation(libs.jackson.databind.nullable)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp3)
    implementation(libs.okhttp3.logging.interceptor)
    implementation(libs.jetbrains.kotlinx.serialization.json)
    implementation(libs.jetbrains.kotlinx.coroutines.core)

    // --- CompileOnly (annotation processors etc.) ---
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.springdoc.openapi.ui)
}

// --- OpenAPI server generation ---
val openApiGenerateServer = tasks.register<GenerateTask>("openApiGenerateServer") {
    group = "openapi"  // grupa w ./gradlew tasks
    description = "Generates Java Spring server code from OpenAPI spec"

    generatorName.set("spring")
    inputSpec.set(apiSpec)
    outputDir.set(generatedServerDir.toString())

    packageName.set("com.velaris")
    apiPackage.set("com.velaris.api")
    modelPackage.set("com.velaris.api.model")
    invokerPackage.set("com.velaris.api.invoker")

    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "dateLibrary" to "java8",
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "hideGenerationTimestamp" to "true"
        )
    )

    inputs.file(inputSpec)
    outputs.dir(generatedServerDir)
    doFirst { delete(generatedServerDir) }
}

// --- OpenAPI Kotlin client generation ---
val openApiGenerateKotlinClient = tasks.register<GenerateTask>("openApiGenerateKotlinClient") {
    group = "openapi"
    description = "Generates Kotlin client code from OpenAPI spec"

    generatorName.set("kotlin")
    inputSpec.set(apiSpec)
    outputDir.set(generatedKotlinClientDir.toString())

    packageName.set("com.velaris.api.client")
    apiPackage.set("com.velaris.api.client")
    modelPackage.set("com.velaris.api.client.model")
    invokerPackage.set("com.velaris.api.client.invoker")

    configOptions.set(
        mapOf(
            "library" to "jvm-retrofit2",
            "dateLibrary" to "java8",
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "enumPropertyNaming" to "UPPERCASE",
            "serializableModel" to "true",
            "generateTests" to "false"
        )
    )

    inputs.file(inputSpec)
    outputs.dir(generatedKotlinClientDir)
    doFirst { delete(generatedKotlinClientDir) }
}

// --- Ensure server generation before compilation ---
tasks.withType<JavaCompile>().configureEach {
    dependsOn(openApiGenerateServer)
}

// --- Compile server Kotlin ---
tasks.withType<KotlinCompile>().configureEach {
    dependsOn(openApiGenerateKotlinClient)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

// --- Disable default JAR ---
tasks.named<Jar>("jar") {
    enabled = false
}

// --- JAR tasks ---
tasks.register<Jar>("apiServerJar") {
    group = "build"
    description = "Packages the generated Java Spring server code into a JAR"

    archiveBaseName.set("velaris-api-server")
    dependsOn(openApiGenerateServer)
    from(sourceSets["javaServer"].output)
}

tasks.register<Jar>("apiClientJar") {
    group = "build"
    description = "Packages the generated Kotlin client code into a JAR"

    archiveBaseName.set("velaris-api-client")
    dependsOn(openApiGenerateKotlinClient)
    from(sourceSets["kotlinClient"].output)
}

// --- Assemble includes both JARs ---
tasks.named("assemble") {
    dependsOn("apiServerJar", "apiClientJar")
}

// --- Publishing ---
publishing {
    publications {
        create<MavenPublication>("javaServer") {
            groupId = project.group.toString()
            artifactId = "velaris-api-server"
            version = project.version.toString()
            artifact(tasks.named("apiServerJar").get())
        }
        create<MavenPublication>("kotlinClient") {
            groupId = project.group.toString()
            artifactId = "velaris-api-client"
            version = project.version.toString()
            artifact(tasks.named("apiClientJar").get())
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Velaris-app/velaris-api")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
            }
        }
    }
}