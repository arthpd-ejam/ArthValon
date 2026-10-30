plugins {
    alias(libs.plugins.android.application)
    // The Kotlin Gradle plugin is already on the build classpath (root project applies
    // kotlin.jvm with `apply false`), so request kotlin.android without a version.
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "avalon.host"
    compileSdk = 34

    defaultConfig {
        applicationId = "avalon.host"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            // Not published anywhere: the debug APK is what friends install.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

dependencies {
    implementation(project(":server"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.material)
    implementation(libs.zxing.core)
    implementation(libs.slf4j.simple)
}
