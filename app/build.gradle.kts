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
    testImplementation(libs.junit)
    // The hook code lives in the :xposed library; without this the app APK
    // would ship no hooks at all. libxposed discovers the module from the
    // META-INF/xposed files that come along with this dependency, so CI
    // asserts they really end up inside the APK.
    implementation(project(":xposed"))
    implementation(libs.libxposed.service)
    // NO MAP SDK AND NO TILE LIBRARY. The map is drawn by app/.../TileMapView.kt over the
    // grids in core-motion, because the three basemaps do not agree on what a tile index
    // means and Baidu's is not a Web Mercator grid at all. That removes osmdroid, and with
    // it the only third-party map dependency this module had.
}
