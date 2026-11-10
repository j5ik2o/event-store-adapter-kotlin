dependencies {
    api("io.github.j5ik2o:event-store-adapter-java-core:5.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("io.github.j5ik2o:event-store-adapter-java-dynamodb:5.2.0")
    compileOnly("io.github.j5ik2o:event-store-adapter-java-spanner:5.2.0")
    compileOnly("io.github.j5ik2o:event-store-adapter-java-bigtable:5.2.0")
    compileOnly("io.github.j5ik2o:event-store-adapter-java-sqlite:5.2.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("testJavaVersion").orElse("11").get().toInt()))
    })
}
