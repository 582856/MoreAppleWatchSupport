plugins {
    id("com.android.application")
}

android {
    namespace = "jianchen.applewatch.supportmore"
    compileSdk = 36

    defaultConfig {
        applicationId = "jianchen.applewatch.supportmore"
        minSdk = 31
        targetSdk = 31
        versionCode = 37
        versionName = "1.36"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    compileOnly(files("libs/xposed-api-82.jar"))
    compileOnly(project(":libxposed-stubs"))
}
