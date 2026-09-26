// Android plugins are deliberately not declared here. Declaring them at the
// root (even with `apply false`) forces Gradle to resolve the AGP classpath on
// every build, including :core-only builds on machines with no Android SDK.
// They are applied directly in app/build.gradle.kts instead.
