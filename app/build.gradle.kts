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
        versionCode = 7
        versionName = "2.0.0"

        ndk {
            // The AI runs on Snapdragon phones only, all arm64; the QNN libraries are arm64 only.
            abiFilters += "arm64-v8a"
        }

        // The run log names the stack the AI ran on.
        buildConfigField("String", "ORT_VERSION", "\"${libs.versions.onnxruntime.get()}\"")
        buildConfigField("String", "QNN_EP_VERSION", "\"${libs.versions.qnnEp.get()}\"")
        buildConfigField("String", "QNN_RUNTIME_VERSION", "\"${libs.versions.qnnRuntime.get()}\"")
    }

    packaging {
        jniLibs {
            // Extracted to nativeLibraryDir on install, as in the measured spike: the QNN EP plugin is registered by
            // file path, and the Hexagon DSP loads its skel library from a folder listed in ADSP_LIBRARY_PATH.
            useLegacyPackaging = true
            // qnn-runtime ships every Hexagon generation plus the DSP and GPU backends. Only HTP is used, on the
            // supported chips' Hexagon versions (models/NpuChips): v69 (8 Gen 1), v75 (8 Gen 3), v79 (8 Elite),
            // v81 (8 Elite Gen 5). The rest stays out of the APK.
            excludes += listOf(
                "**/libQnnDsp.so",
                "**/libQnnDspV66Skel.so",
                "**/libQnnDspV66Stub.so",
                "**/libQnnGpu.so",
                "**/libQnnHtpV68Skel.so",
                "**/libQnnHtpV68Stub.so",
                "**/libQnnHtpV73Skel.so",
                "**/libQnnHtpV73Stub.so",
            )
        }
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
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.qnn.ep)
    implementation(libs.qnn.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
}
