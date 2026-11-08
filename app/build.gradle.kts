plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")

    dependencies {
        implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
    }
}

android {
    namespace = "com.gameguide.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gameguide.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
