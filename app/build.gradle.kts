plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.am2.am2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.am2.tik"
        minSdk = 16
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.multidex:multidex:2.0.1")

    // Media library
    implementation("androidx.media:media:1.6.0")

    // OKHTTP 3.12.x is the last version supporting API < 21
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // Google Play Services Location - version 18.0.0 is safer for very old devices
    implementation("com.google.android.gms:play-services-location:18.0.0")

    // OsmDroid for Maps
    implementation(libs.osmdroid.android)

    // Lifecycle - 2.5.1 is the last version supporting API 16
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-service:2.5.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val checkLogPolicy by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fail when Android code bypasses sanitized logging"
    workingDir(rootDir)
    commandLine("python3", "scripts/check_log_policy.py")
}

tasks.named("preBuild") {
    dependsOn(checkLogPolicy)
}
