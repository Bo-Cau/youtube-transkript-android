import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bei jedem Bau auf GitHub steigt die Versionsnummer automatisch,
// damit sich neue APKs über alte installieren lassen.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.transkript.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.transkript.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fester Signierschlüssel, damit Updates ohne Deinstallation möglich sind.
    // Nur für die private Nutzung gedacht.
    signingConfigs {
        create("release") {
            storeFile = file("transkript.jks")
            storePassword = "transkript-app"
            keyAlias = "transkript"
            keyPassword = "transkript-app"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
