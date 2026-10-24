import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing, kept outside the repo: storeFile, storePassword, keyAlias, keyPassword.
 * Read from LYRIFY_KEYSTORE_PROPS, else ~/.android-keys/lyrify-keystore.properties.
 * Without it the release build is simply unsigned, so the project builds anywhere.
 */
val releaseKeystore: Properties? = run {
    val path = providers.environmentVariable("LYRIFY_KEYSTORE_PROPS").orNull
        ?: "${System.getProperty("user.home")}/.android-keys/lyrify-keystore.properties"
    val file = File(path)
    if (file.isFile) Properties().apply { file.inputStream().use { load(it) } } else null
}

android {
    namespace = "app.lyrify"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lyrify"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
