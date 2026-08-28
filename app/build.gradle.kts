import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val approvedSigner = providers.gradleProperty("AM2_APPROVED_SIGNER_SHA256").orElse("")

/*
 * Release signing material, supplied from outside the repository.
 *
 * Absent by default, because CI builds the production artifact unsigned on
 * purpose -- the release key is deliberately not there. An unconfigured build
 * is legitimate and has to keep working.
 *
 * The state worth guarding is the one in between. Hand Gradle a keystore path
 * with no password and it attaches no signing config at all, so the release
 * artifact comes out signed with the *debug* key: it builds, it installs, and
 * it is not a release. Nothing in the output says otherwise.
 *
 * This check runs at configuration time, so a half-configured machine fails
 * every Gradle invocation rather than only the release task. That is the
 * intent: the wrong state should be loud where it is set, not discovered
 * later in an artifact that already shipped.
 */
val signingProps: Map<String, String?> = listOf(
    "AM2_KEYSTORE_FILE",
    "AM2_KEYSTORE_PASSWORD",
    "AM2_KEY_ALIAS",
    "AM2_KEY_PASSWORD",
).associateWith { name ->
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
}
val signingConfigured = signingProps.values.all { it != null }
require(signingConfigured || signingProps.values.all { it == null }) {
    "Release signing is half configured; missing: " +
        signingProps.filterValues { it == null }.keys.joinToString(", ")
}

/*
 * The staging key, which is a different key on purpose.
 *
 * Android permits an install over an existing app only when the new package
 * carries the *same* signature. It does not care whether the key is called
 * debug or release -- a debug keystore holds a real private key. What matters
 * is continuity, and this project has never had any: every staging APK is
 * built on a runner that generates a debug key and discards it, so 1.1.119
 * could not be overwritten by 1.1.124 and each round of field testing costs
 * an operator their local state.
 *
 * Separate from the release key because this one has to live in CI to be of
 * any use, and the upload key must not. Collapsing them would put the app's
 * permanent identity on every runner that builds a staging APK.
 *
 * Unconfigured is legitimate: a developer without the key still builds and
 * runs, falling back to their own debug key. Continuity only matters for the
 * artifact that reaches a handset.
 */
val stagingSigningProps: Map<String, String?> = listOf(
    "AM2_STAGING_KEYSTORE_FILE",
    "AM2_STAGING_KEYSTORE_PASSWORD",
    "AM2_STAGING_KEY_ALIAS",
    "AM2_STAGING_KEY_PASSWORD",
).associateWith { name ->
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
}
val stagingSigningConfigured = stagingSigningProps.values.all { it != null }
require(stagingSigningConfigured || stagingSigningProps.values.all { it == null }) {
    "Staging signing is half configured; missing: " +
        stagingSigningProps.filterValues { it == null }.keys.joinToString(", ")
}

/**
 * The build's identity, supplied by CI as its run number.
 *
 * This was the literal 3 in every APK ever produced. The device decides an
 * update exists by comparing version codes, so an unchanging one made the
 * channel permanently answer "already current" -- and left neither end able to
 * name the build actually installed. A round of latency work was evaluated
 * against an APK that could not be shown to contain it.
 *
 * A local build keeps a low number, so a developer APK can never look newer
 * than a published one and is never offered to a field device.
 */
val buildVersionCode = providers.gradleProperty("AM2_VERSION_CODE")
    .map { property ->
        val parsed = property.trim().toIntOrNull()
        require(parsed != null && parsed > 0) { "AM2_VERSION_CODE must be a positive integer" }
        parsed
    }
    .orElse(1)

/*
 * The release a human declared, read from version.properties rather than
 * written here.
 *
 * CI has to know this string to write the update manifest the field app fetches,
 * and a quoted literal inside a build script is not something another job can
 * read. -PAM2_VERSION_NAME overrides it for a one-off build.
 */
val buildVersionName = providers.gradleProperty("AM2_VERSION_NAME")
    .orElse(
        providers.provider {
            val file = layout.projectDirectory.file("version.properties").asFile
            require(file.isFile) { "version.properties is missing: ${file.path}" }
            val declared = Properties()
                .apply { file.inputStream().use { load(it) } }
                .getProperty("versionName")
                ?.trim()
                .orEmpty()
            require(declared.isNotEmpty()) { "version.properties declares no versionName" }
            declared
        }
    )

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
        versionCode = buildVersionCode.get()
        versionName = buildVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true

        buildConfigField("String", "APPROVED_UPDATE_SIGNER_SHA256", "\"${approvedSigner.get()}\"")
        buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "false")
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev+${buildVersionCode.get()}"
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
            buildConfigField(
                "String",
                "UPDATE_APK_URL",
                quotedBuildConfig(validateEndpoint("dev", "https://dev-api.am2-poc.com/update/update.apk", "https", "dev-api.am2-poc.com")),
            )
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging+${buildVersionCode.get()}"
            resValue("string", "app_name", "am² STAGING")
            // Staging carries its own channel, so the update path can be
            // exercised before a production release depends on it.
            buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "true")
            buildConfigField(
                "String",
                "WEBSOCKET_URL",
                quotedBuildConfig(validateEndpoint("staging", "wss://staging-apiapi.am2-poc.com", "wss", "staging-apiapi.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                quotedBuildConfig(validateEndpoint("staging", "https://staging-apiapi.am2-poc.com/update/version.json", "https", "staging-apiapi.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_APK_URL",
                quotedBuildConfig(validateEndpoint("staging", "https://staging-apiapi.am2-poc.com/update/update.apk", "https", "staging-apiapi.am2-poc.com")),
            )
        }
        create("production") {
            dimension = "environment"
            /*
             * Sideloaded to every unit and never listed anywhere, so it carries
             * the build like the internal lanes do. Only `play` stays a plain
             * release, because only `play` has a store listing to keep tidy.
             */
            versionNameSuffix = "+${buildVersionCode.get()}"
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
            buildConfigField(
                "String",
                "UPDATE_APK_URL",
                quotedBuildConfig(validateEndpoint("production", "https://apiapi.am2-poc.com/update/update.apk", "https", "apiapi.am2-poc.com")),
            )
            val signer = approvedSigner.get().replace(":", "").lowercase()
            if (gradle.startParameter.taskNames.any { it.contains("Production", ignoreCase = true) && it.contains("Release", ignoreCase = true) }) {
                require(Regex("^[0-9a-f]{64}$").matches(signer)) {
                    "Production release requires AM2_APPROVED_SIGNER_SHA256"
                }
            }
        }

        /*
         * The Play artifact.
         *
         * The same application as `production`, talking to the same endpoints,
         * and deliberately without an applicationIdSuffix: a suffix would make
         * this a different app that could never upgrade an installation
         * already in the field.
         *
         * What differs is that it cannot update itself. Play does not permit
         * an app it distributes to install an APK outside Play, so
         * SELF_UPDATE_ENABLED stays false and REQUEST_INSTALL_PACKAGES is
         * stripped from the merged manifest in src/play. Leaving the
         * permission in place and merely unused would still read, to a
         * reviewer, as a permission the app asked for.
         *
         * The update URLs are still defined because AboutActivity references
         * them at compile time; with self-update off they are never fetched.
         */
        create("play") {
            dimension = "environment"
            buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "false")
            buildConfigField(
                "String",
                "WEBSOCKET_URL",
                quotedBuildConfig(validateEndpoint("play", "wss://apiapi.am2-poc.com", "wss", "apiapi.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                quotedBuildConfig(validateEndpoint("play", "https://apiapi.am2-poc.com/update/version.json", "https", "apiapi.am2-poc.com")),
            )
            buildConfigField(
                "String",
                "UPDATE_APK_URL",
                quotedBuildConfig(validateEndpoint("play", "https://apiapi.am2-poc.com/update/update.apk", "https", "apiapi.am2-poc.com")),
            )
        }
    }

    signingConfigs {
        /*
         * staging is a product flavour on the *debug* build type, so
         * assembleStagingDebug signs with this one. Overriding the existing
         * debug config rather than inventing a `staging` build type: a fourth
         * build type would be one nobody assembles.
         */
        if (stagingSigningConfigured) {
            getByName("debug") {
                storeFile = file(stagingSigningProps.getValue("AM2_STAGING_KEYSTORE_FILE")!!)
                storePassword = stagingSigningProps.getValue("AM2_STAGING_KEYSTORE_PASSWORD")
                keyAlias = stagingSigningProps.getValue("AM2_STAGING_KEY_ALIAS")
                keyPassword = stagingSigningProps.getValue("AM2_STAGING_KEY_PASSWORD")
            }
        }
        if (signingConfigured) {
            create("release") {
                storeFile = file(signingProps.getValue("AM2_KEYSTORE_FILE")!!)
                storePassword = signingProps.getValue("AM2_KEYSTORE_PASSWORD")
                keyAlias = signingProps.getValue("AM2_KEY_ALIAS")
                keyPassword = signingProps.getValue("AM2_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Null when unconfigured, which leaves the artifact unsigned --
            // the deliberate CI behaviour. It is never the debug config.
            signingConfig = if (signingConfigured) signingConfigs.getByName("release") else null
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
