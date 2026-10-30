import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProps = Properties()
val signingFile = rootProject.file("signing.properties")
if (signingFile.isFile) {
    signingFile.inputStream().use { signingProps.load(it) }
}

fun signingValue(property: String, env: String): String? =
    (signingProps.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }

val keystorePath = signingValue("KEYSTORE_FILE", "KEYSTORE_PATH")
val keystorePass = signingValue("KEYSTORE_PASSWORD", "KEYSTORE_PASS")
val keyAlias = signingValue("KEY_ALIAS", "KEY_ALIAS")
val keyPass = signingValue("KEY_PASSWORD", "KEY_PASSWORD")
val keystoreFile = keystorePath?.let(::file)?.takeIf { it.isFile && it.length() > 0L }

android {
    namespace = "com.nestlock.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        /* The headset vendor's ProcessIntercept refuses third-party boot
         * broadcasts ("forbidden to start a 3rd process by broadcast") but
         * allows packages matching its common whitelist. The .cts suffix is on
         * that list, so the production package id keeps it. */
        applicationId = "com.nestlock.app.cts"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    /* A plain package id is kept for comparison on devices whose vendor does
     * not intercept boot broadcasts. */
    flavorDimensions += "variant"
    productFlavors {
        create("standard") {
            dimension = "variant"
            applicationId = "com.nestlock.app"
        }
        create("cts") {
            dimension = "variant"
            applicationId = "com.nestlock.app.cts"
        }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePass
                keyAlias = keyAlias
                keyPassword = keyPass
                storeType = "PKCS12"
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(
                if (keystoreFile != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
}
