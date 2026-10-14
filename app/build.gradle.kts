import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing uses credentials supplied by the owner in keystore.properties (never committed).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.astra.audiotext"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.astra.audiotext"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so every published APK installs over the previous one.
        versionCode = (System.getenv("ASTRA_BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.1.0" + (System.getenv("ASTRA_BUILD_NUMBER")?.let { ".$it" } ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Hindi + English UI; other locales can be added under res/values-xx.
        resourceConfigurations += listOf("en", "hi")
    }

    signingConfigs {
        // Stable, PUBLIC debug key committed to the repo so every debug APK built by CI can be installed
        // over the previous one. It protects nothing and must never be used for a store release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Owner's release key when keystore.properties exists; otherwise the public debug key, so CI
            // can still publish an installable, fully optimized (R8) APK.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Extract native libraries on install: ggml picks its CPU kernel variant by scanning the
        // native library directory, which is empty when libraries stay compressed inside the APK.
        jniLibs { useLegacyPackaging = true }
    }

    // Reproducible builds: no dependency metadata blob (it is also only readable by Google Play).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = false
        abortOnError = false // report is produced by CI (lintDebug); the build never silently ignores it
        disable += listOf("MissingTranslation")
    }
}

dependencies {
    implementation(project(":engine-whisper"))
    implementation(project(":core:common"))
    implementation(project(":core:privacy"))
    implementation(project(":core:text"))
    implementation(project(":core:transcript"))
    implementation(project(":core:audio"))
    implementation(project(":core:security"))
    implementation(project(":core:models"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
