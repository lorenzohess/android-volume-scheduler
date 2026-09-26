import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Emit Java 17 bytecode to match :app, which consumes this module and targets
// 17 like any Android app. A module built for 21 would advertise itself as
// needing a Java 21 runtime and can be rejected by :app's dependency resolution.
//
// No jvmToolchain(): that pins an exact JDK that must be installed, and Gradle
// can't download one without a toolchain resolver. Whatever JDK 17+ runs
// Gradle compiles this.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
