import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.lh.volsched"

    // Bump both to 36 once you're on AGP 8.9+. Nothing here needs it; the
    // device just has to be >= minSdk.
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lh.volsched"
        // Personal, single-device app: no reason to support anything older,
        // which removes every compatibility branch. USE_EXACT_ALARM needs 33+,
        // getStreamMinVolume needs 28+, direct boot needs 24+.
        minSdk = 34
        // Keep below 37. On Android 17, apps targeting 37 may only change volume
        // from a foreground service holding while-in-use capability, which a
        // service started from an alarm or boot receiver never gets. Below 37,
        // any non-shortService foreground service is enough (VolumeChangeService).
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

// Target 17 without jvmToolchain(17), which would demand an installed JDK 17
// and fail on a machine that only has 21. See core/build.gradle.kts.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}
