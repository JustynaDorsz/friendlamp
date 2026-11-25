plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub build gets a higher version number, so a new APK installs over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.friendlamp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.friendlamp.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed signing key, so every build has the same signature and updates
    // install without uninstalling first. The key file in the repository is
    // encrypted; its password is the GitHub Actions secret ANDROID_KEYSTORE_PASSWORD.
    // Without the password (a local build), Android's default debug key is used.
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    if (!keystorePassword.isNullOrEmpty()) {
        signingConfigs {
            create("shared") {
                storeFile = file("friendlamp-release.jks")
                storePassword = keystorePassword
                keyAlias = "friendlamp"
                keyPassword = keystorePassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
