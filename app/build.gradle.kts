plugins {
    id("com.android.application")
}

// The release key comes from the environment, so it never sits in the repository. Without it a
// release build comes out unsigned.
val keystoreFile: String? = System.getenv("NOEUD_KEYSTORE_FILE")

val appVersion = "0.1.0"

android {
    namespace = "com.synaelle.noeud"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.synaelle.noeud"
        minSdk = 26
        targetSdk = 37
        versionName = appVersion
        // Android only updates to a higher code: 1.2.3 is 10203
        versionCode = appVersion.split(".").fold(0) { code, part -> code * 100 + part.toInt() }
        buildConfigField("String", "RELEASES", "\"https://api.github.com/repos/Synaelle/noeud-android/releases/latest\"")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("NOEUD_KEY_PASSWORD")
                keyAlias = System.getenv("NOEUD_KEY_ALIAS")
                keyPassword = System.getenv("NOEUD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // a debug build can look for releases elsewhere, to try the updater
            System.getenv("NOEUD_RELEASES")?.let { buildConfigField("String", "RELEASES", "\"$it\"") }
        }
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // the platform's org.json is only a stub in JVM tests
    testImplementation("org.json:json:20240303")
}
