plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "org.pacer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.pacer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("APP_VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").get()
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core-motion"))
    // The hook code lives in the :xposed library; without this the app APK
    // would ship no hooks at all. libxposed discovers the module from the
    // META-INF/xposed files that come along with this dependency, so the CI
    // guard below asserts they really end up inside the APK.
    implementation(project(":xposed"))
    implementation(libs.libxposed.service)
}
