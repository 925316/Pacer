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

    // From the environment, so no key material is in the tree. A debug key cannot sign a
    // published build: CI generates a fresh one per runner, so installs could not upgrade.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val releaseKeystore = releaseKeystorePath
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
        ?: file("pacer-release.jks")
    val releasePasswords = listOf(
        "RELEASE_KEYSTORE_PASSWORD",
        "RELEASE_KEY_ALIAS",
        "RELEASE_KEY_PASSWORD",
    ).associateWith { System.getenv(it) }
    val signingReady = releaseKeystore.exists() && releasePasswords.values.none { it.isNullOrBlank() }

    signingConfigs {
        if (signingReady) {
            println("release signing: enabled (keystore=${releaseKeystorePath ?: "pacer-release.jks"})")
            create("release") {
                storeFile = releaseKeystore
                storePassword = releasePasswords.getValue("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releasePasswords.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releasePasswords.getValue("RELEASE_KEY_PASSWORD")
            }
        } else {
            // An unsigned release still builds, so say so rather than fail.
            println(
                "release signing: DISABLED " +
                    "(keystore exists=${releaseKeystore.exists()}, " +
                    releasePasswords.entries.joinToString { "${it.key} set=${!it.value.isNullOrBlank()}" } +
                    ")",
            )
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    // META-INF/xposed files that come along with this dependency, so the release
    // workflow unpacks the built APK and asserts they are really inside it.
    implementation(project(":xposed"))
    implementation(libs.libxposed.service)
    // NO MAP SDK AND NO TILE LIBRARY. The map is drawn by app/.../TileMapView.kt over the
    // grids in core-motion, because the three basemaps do not agree on what a tile index
    // means and Baidu's is not a Web Mercator grid at all. That removes osmdroid, and with
    // it the only third-party map dependency this module had.
}
