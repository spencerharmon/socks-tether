plugins {
    id("com.android.application")
}

android {
    namespace = "com.spencerharmon.sockstether"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.spencerharmon.sockstether"
        minSdk = 26
        // < 34 on purpose: no foreground-service-type declaration is required,
        // and the app still installs and runs on current Android.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
