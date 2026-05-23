plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "cloud.kl8techgroup.kl8wall"
    compileSdk = 35

    defaultConfig {
        applicationId = "cloud.kl8techgroup.kl8wall"
        minSdk = 24
        targetSdk = 35
        versionCode = 42
        versionName = "2.0.40"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: (project.findProperty("RELEASE_STORE_PASSWORD") as? String) ?: "android"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: (project.findProperty("RELEASE_KEY_ALIAS") as? String) ?: "kl8wall"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: (project.findProperty("RELEASE_KEY_PASSWORD") as? String) ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreFile = file("release.keystore")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            val apkOutput = this as? com.android.build.gradle.api.ApkVariantOutput
            if (apkOutput != null) {
                if (buildType.name == "release") {
                    apkOutput.outputFileName = "kl8wall-v${versionCode}.apk"
                } else {
                    apkOutput.outputFileName = "kl8wall-v${versionCode}-${buildType.name}.apk"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    android.set(true)
    verbose.set(true)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.argon2kt)

    // Server
    implementation(libs.nanohttpd)
    implementation(libs.jmdns)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // MQTT
    implementation(libs.mqtt.client)

    // Protobuf Lite
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

