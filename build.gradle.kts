import java.util.Properties

// PS Bridge: PlayStation Remote Play and PS Plus cloud streaming as a service
// for another app, with no screen of its own.
// AGPL-3.0 (see COPYING): it builds on chiaki-ng and CloudPad.
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// Release signing is optional and kept out of this source tree: a
// signing.properties next to this file (storeFile, storePassword, keyAlias,
// keyPassword) signs the release. It must be the key of the client app: the
// bridge only serves an app carrying the same signature.
val signingProps = Properties().apply {
    val f = file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tals.psbridge"
    compileSdk = 36
    // r28: 16 KB page alignment by default, which Play requires for native code.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.tals.psbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.4"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The licence text shown in the app's legal notices: COPYING itself, copied in
    // at build time so the two can never differ.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licence-assets"))
}

val copyLicence = tasks.register<Copy>("copyLicence") {
    from("COPYING")
    into(layout.buildDirectory.dir("generated/licence-assets"))
}
tasks.named("preBuild") { dependsOn(copyLicence) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
