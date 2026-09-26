pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "volume-scheduler"

// :core is pure Kotlin/JVM and always builds.
include(":core")

// :app needs the Android SDK. Including it unconditionally makes the whole
// build fail at configuration time on a machine without one, which would also
// take :core down with it. So only include it when an SDK is actually present.
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle(
        "No Android SDK found - skipping :app. " +
            "Open this project in Android Studio, or set ANDROID_HOME, to build the app.",
    )
}
