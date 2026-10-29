// Android plugins are declared in :app only, so machines without the Android
// SDK (or without access to Google's Maven repository) can still build and
// test the JVM modules.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
