import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val approvedSigner = providers.gradleProperty("AM2_APPROVED_SIGNER_SHA256").orElse("")

fun quotedBuildConfig(value: String): String = "\"$value\""

fun validateEndpoint(environment: String, value: String, scheme: String, host: String): String {
    val uri = URI(value)
    require(uri.scheme == scheme) { "$environment endpoint must use $scheme" }
    require(uri.host == host) { "$environment endpoint must use $host" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "$environment endpoint must not contain userinfo, query, or fragment"
    }
    return value
}

android {
    namespace = "com.am2.am2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.am2.tik"
        minSdk = 16
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true

        buildConfigField("String", "APPROVED_UPDATE_SIGNER_SHA256", "\"${approvedSigner.get()}\"")
        buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "false")
        buildConfigField("Boolean", "BUNDLED_CA_ENABLED", "false")

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    flavorDimensions += listOf("environment", "trust")
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "am² DEV")
            buildConfigField(
                "String",
                "WEBSOCKET_URL",
                quotedBuildConfig(validateEndpoint("dev", "wss://dev-api.am2-poc.com", "wss", "dev-api.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                quotedBuildConfig(validateEndpoint("dev", "https://dev-api.am2-poc.com/update/version.json", "https", "dev-api.am2-poc.com")),
            )
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            resValue("string", "app_name", "am² STAGING")
            buildConfigField(
                "String",
                "WEBSOCKET_URL",
                quotedBuildConfig(validateEndpoint("staging", "wss://staging-api.am2-poc.com", "wss", "staging-api.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                quotedBuildConfig(validateEndpoint("staging", "https://staging-api.am2-poc.com/update/version.json", "https", "staging-api.am2-poc.com")),
            )
        }
        create("production") {
            dimension = "environment"
            buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "true")
            buildConfigField(
                "String",
                "WEBSOCKET_URL",
                quotedBuildConfig(validateEndpoint("production", "wss://apiapi.am2-poc.com", "wss", "apiapi.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                quotedBuildConfig(validateEndpoint("production", "https://apiapi.am2-poc.com/update/version.json", "https", "apiapi.am2-poc.com")),
            )
            val signer = approvedSigner.get().replace(":", "").lowercase()
            if (gradle.startParameter.taskNames.any { it.contains("Production", ignoreCase = true) && it.contains("Release", ignoreCase = true) }) {
                require(Regex("^[0-9a-f]{64}$").matches(signer)) {
                    "Production release requires AM2_APPROVED_SIGNER_SHA256"
                }
            }
        }
        create("legacyCompat") {
            dimension = "trust"
            buildConfigField("Boolean", "BUNDLED_CA_ENABLED", "true")
        }
        create("systemTrust") {
            dimension = "trust"
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
        buildConfig = true
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
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // AndroidJUnitRunner 1.5.2 references this at startup on API 16/19.
    androidTestImplementation("androidx.tracing:tracing:1.0.0")
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
