// All Gradle plugins are put on the ROOT build classpath so the Kotlin and
// Android plugins can see each other. The Android Gradle Plugin is only added
// when an Android SDK is available (same condition as settings.gradle.kts), so
// a machine without the SDK (or without access to Google's Maven repository)
// can still build and test the JVM modules.
val androidAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") } ||
    providers.gradleProperty("withAndroid").isPresent

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        val agp = "8.5.2"
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") } ||
            project.hasProperty("withAndroid")
        ) {
            classpath("com.android.tools.build:gradle:$agp")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

extra["androidAvailable"] = androidAvailable
