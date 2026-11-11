import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `maven-publish`
    signing
    kotlin("jvm") version "2.4.21"
    id("com.diffplug.spotless") version "8.11.0"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "io.github.j5ik2o"
version = rootProject.file("version").readText().trim()

extra["isReleaseVersion"] = !version.toString().endsWith("SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("ch.qos.logback:logback-classic:1.6.6")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("software.amazon.awssdk:apache5-client:2.57.11")
    testImplementation("software.amazon.awssdk:netty-nio-client:2.57.11")
    testImplementation("com.networknt:json-schema-validator:2.0.8")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.+")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    api(project(":core"))
    api(project(":dynamodb"))
    api(project(":spanner"))
    api(project(":bigtable"))
    api(project(":sqlite"))
}

val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt).orElse(11)
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaVersion.map(JavaLanguageVersion::of))
}

tasks {

    withType<Test> {
        useJUnitPlatform()
        javaLauncher.set(testJavaLauncher)
        doFirst {
            val launcher = javaLauncher.get()
            logger.lifecycle("Test JVM: Java ${launcher.metadata.languageVersion} (${launcher.executablePath})")
        }
    }

    this.register<Copy>("javadocToDocsFolder") {
        from(javadoc)
        into("docs/javadoc")
    }

    assemble {
        dependsOn("javadocToDocsFolder")
    }

    this.register<Jar>("sourcesJar") {
        from(sourceSets.main.get().allJava)
        archiveClassifier.set("sources")
    }

    this.register<Jar>("javadocJar") {
        from(javadoc)
        archiveClassifier.set("javadoc")
    }

    withType<Sign> {
        onlyIf { project.extra["isReleaseVersion"] as Boolean }
    }

    withType<Wrapper> {
        gradleVersion = "9.9.0"
    }

    withType<JavaCompile> {
        options.release.set(11)
        options.compilerArgs.add("-Xlint:deprecation")
        dependsOn(spotlessApply)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

spotless {
    java {
        googleJavaFormat()
    }
    kotlin {
        target("**/*.kt")
        targetExclude(".takt/**")
        ktlint()
    }
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["kotlin"])
            afterEvaluate {
                artifactId = tasks.jar.get().archiveBaseName.get()
            }
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])

            setVersion(project.version)
            pom {
                name.set(project.name)
                packaging = "jar"
                description.set("Event Store Adapter for Kotlin")
                url.set("https://github.com/j5ik2o/event-store-adapter-kotlin")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/license/mit/")
                    }
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("j5ik2o")
                        name.set("Junichi Kato")
                        email.set("j5ik2o@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:j5ik2o/event-store-adapter-kotlin.git")
                    developerConnection.set("scm:git:git@github.com:j5ik2o/event-store-adapter-kotlin.git")
                    url.set("https://github.com/j5ik2o/event-store-adapter-kotlin")
                }
            }
        }
    }
}

nexusPublishing {
    this.repositories {
        this.sonatype {
            packageGroup = "io.github.j5ik2o"
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            username = System.getenv("SONATYPE_USERNAME")
            password = System.getenv("SONATYPE_PASSWORD")
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["mavenJava"])
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    group = rootProject.group
    version = rootProject.version
    extensions.configure<BasePluginExtension> { archivesName.set("event-store-adapter-kotlin-${project.name}") }
    repositories { mavenCentral() }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(11)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "event-store-adapter-kotlin-${project.name}"
            pom {
                name.set(artifactId)
                description.set("Event Store Adapter for Kotlin ${project.name}")
                url.set("https://github.com/j5ik2o/event-store-adapter-kotlin")
                licenses {
                    license { name.set("MIT"); url.set("https://opensource.org/license/mit/") }
                    license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") }
                }
                developers { developer { id.set("j5ik2o"); name.set("Junichi Kato") } }
                scm { url.set("https://github.com/j5ik2o/event-store-adapter-kotlin") }
            }
        }
    }
    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(System.getenv("SIGNING_KEY"), System.getenv("SIGNING_PASSWORD"))
        sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
    }
    tasks.withType<Sign>().configureEach { onlyIf { rootProject.extra["isReleaseVersion"] as Boolean } }
}

tasks.register("verifyModulePoms") {
    dependsOn("generatePomFileForMavenJavaPublication")
    dependsOn(subprojects.map { it.tasks.named("generatePomFileForMavenJavaPublication") })
    doLast {
        val bundlePom = layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile.readText()
        for (module in listOf("core", "dynamodb", "spanner", "bigtable", "sqlite")) {
            check(bundlePom.contains("event-store-adapter-kotlin-$module"))
        }
        check(!bundlePom.contains("software.amazon.awssdk"))
        val corePom = project(":core").layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile.readText()
        for (driver in listOf("dynamodb", "spanner", "bigtable", "sqlite")) {
            check(!corePom.contains("event-store-adapter-java-$driver")) { "Driver leaked into core POM: $driver" }
            val pom = project(":$driver").layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile.readText()
            check(pom.contains("event-store-adapter-java-$driver"))
            check(pom.contains("<version>5.2.0</version>"))
        }
    }
}
tasks.check { dependsOn("verifyModulePoms") }
