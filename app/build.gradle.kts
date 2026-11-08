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
        versionCode = 11
        versionName = "2.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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
