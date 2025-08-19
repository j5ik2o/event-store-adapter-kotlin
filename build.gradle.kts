import org.gradle.kotlin.dsl.register

plugins {
    `java-library`
    `maven-publish`
    signing
    kotlin("jvm") version "2.2.10"
    id("com.diffplug.spotless") version "6.21.0"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "io.github.j5ik2o"
version = File("./version").readText().trim()

extra["isReleaseVersion"] = !version.toString().endsWith("SNAPSHOT")

repositories {
    mavenCentral()

    // 認証付き snapshot リポジトリ
    maven {
        name = "sonatypeSnapshots"
        url  = uri("https://central.sonatype.com/repository/maven-snapshots/")
        credentials {
            username = System.getenv("SONATYPE_USERNAME")
            password = System.getenv("SONATYPE_PASSWORD")
        }
        mavenContent { snapshotsOnly() }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")

    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:localstack:1.21.3")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.+")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("com.github.j5ik2o:event-store-adapter-java:1.1.174")
    implementation("software.amazon.awssdk:dynamodb:2.32.25")
    implementation("de.huxhorn.sulky:de.huxhorn.sulky.ulid:8.3.0")
}

tasks {

    withType<Test> {
        useJUnitPlatform()
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
        gradleVersion = "8.14.3"
    }

    withType<JavaCompile> {
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
}

spotless {
    java {
        googleJavaFormat()
    }
    kotlin {
        target("**/*.kt")
        ktlint()
            .userData(mapOf(
                "experimental" to "true",
                "indent_size" to "2",
                "trim_trailing_whitespace" to "true",
                "max_line_length" to "120"
            ))
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
                description.set("Event Store Adapter for Java")
                url.set("https://github.com/j5ik2o/event-store-adapter-java")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/license/mit/")
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
                    connection.set("scm:git:git@github.com:j5ik2o/event-store-adapter-java.git")
                    developerConnection.set("scm:git:git@github.com:j5ik2o/event-store-adapter-java.git")
                    url.set("https://github.com/j5ik2o/event-store-adapter-java")
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
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
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

