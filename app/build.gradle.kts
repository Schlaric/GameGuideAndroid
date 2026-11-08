plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gameguide.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gameguide.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "2.39"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("gameguide") {
            storeFile = rootProject.file("gameguide-release.jks")
            storePassword = System.getenv("GAMEGUIDE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("GAMEGUIDE_KEY_ALIAS")
            // PKCS12 stores use the keystore password for the private key as well.
            keyPassword = storePassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("gameguide")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gameguide")
        }
    }

    // uBlock requires the _locales directory. Android's default asset
    // ignore pattern excludes directories beginning with "_", so omit
    // that one default rule while keeping the other standard exclusions.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:144.0.20251027123126")
}
