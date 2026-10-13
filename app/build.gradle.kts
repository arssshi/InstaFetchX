plugins {
    id("com.android.application")
}

android {
    namespace = "com.instafetchx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.instafetchx"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    androidResources {
        // English-only resources keep the APK small.
        localeFilters += "en"
    }

    signingConfigs {
        // Optional: for a personal release keystore, create ./release.jks (see README)
        // and pass -PINSTAFETCHX_STORE_FILE etc. If absent, the debug key is used so the
        // APK installs locally / can be attached to GitHub releases.
        create("instafetchx") {
            val storePath = project.findProperty("INSTAFETCHX_STORE_FILE")?.toString()
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = project.findProperty("INSTAFETCHX_STORE_PASSWORD")?.toString() ?: ""
                keyAlias = project.findProperty("INSTAFETCHX_KEY_ALIAS")?.toString() ?: "instafetchx"
                keyPassword = project.findProperty("INSTAFETCHX_KEY_PASSWORD")?.toString() ?: ""
            }
        }
    }

    val fetchSign = signingConfigs.getByName("instafetchx")
    val useFetchSign = fetchSign.storeFile != null

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useFetchSign) fetchSign else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        shaders = false
        resValues = false
        buildConfig = false
    }

    // Every byte counts: no dependency-metadata blob, no lint packaging overhead.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/kotlin/**",
                "/*.properties"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// NOTE: dependencies {} is intentionally empty.
// Zero third-party libraries — only java.*, javax.* and android.* are used.
