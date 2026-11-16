pluginManagement {
    repositories {
        mavenCentral()
        // Public mirror of Maven Central: used when repo.maven.apache.org rate-limits CI runners (HTTP 429).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Public mirror of Maven Central: used when repo.maven.apache.org rate-limits CI runners (HTTP 429).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
    }
}

rootProject.name = "avalon-offline"

include(":engine", ":server")

// The Android host app needs the Android SDK. It is included when an SDK is
// available (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties) or when
// -PwithAndroid is passed, so the JVM modules can be built and tested on a
// machine without the SDK.
val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkAvailable || providers.gradleProperty("withAndroid").isPresent) {
    include(":app")
}
