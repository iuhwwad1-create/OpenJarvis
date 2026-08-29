plugins {
    id("com.android.application")
}

android {
    namespace = "ai.openjarvis.quest"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.openjarvis.quest"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            minifyEnabled = false
        }
    }
}
