import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("app/keystores/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply {
        load(FileInputStream(keystorePropertiesFile))
    }
} else null

// Add PostHog API key and host as build-time variables
val posthogApiKey: String = project.findProperty("POSTHOG_API_KEY") as String? ?: System.getenv("POSTHOG_API_KEY") ?: ""
val posthogHost: String = project.findProperty("POSTHOG_HOST") as String? ?: System.getenv("POSTHOG_HOST") ?: "https://us.i.posthog.com"

// Add Supabase URL and key as build-time variables
val supabaseUrl: String = project.findProperty("SUPABASE_URL") as String? ?: System.getenv("SUPABASE_URL") ?: "https://your-project.supabase.co"
val supabaseKey: String = project.findProperty("SUPABASE_KEY") as String? ?: System.getenv("SUPABASE_KEY") ?: ""

android {
    namespace = "app.gamenative"
    compileSdk = 35

    // https://developer.android.com/ndk/downloads
    ndkVersion = "22.1.7171670"

    signingConfigs {
        create("pluvia") {
            if (keystoreProperties != null) {
                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    defaultConfig {
        applicationId = "app.gamenative"

        minSdk = 26
        targetSdk = 28

        versionCode = 2
        versionName = "0.3.3"

        buildConfigField("boolean", "GOLD", "false")
        buildConfigField("String", "POSTHOG_API_KEY", "\"$posthogApiKey\"")
        buildConfigField("String", "POSTHOG_HOST", "\"$posthogHost\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        val iconValue = "@mipmap/ic_launcher"
        val iconRoundValue = "@mipmap/ic_launcher_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(
            // getDefaultProguardFile("proguard-android-optimize.txt"),
            getDefaultProguardFile("proguard-android.txt"),
            "proguard-rules.pro",
        )
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }
        create("release-signed") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("pluvia")
        }
        create("release-gold") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("pluvia")
            applicationIdSuffix = ".gold"
            buildConfigField("boolean", "GOLD", "true")
            val iconValue = "@mipmap/ic_launcher_gold"
            val iconRoundValue = "@mipmap/ic_launcher_gold_round"
            manifestPlaceholders.putAll(
                mapOf(
                    "icon" to iconValue,
                    "roundIcon" to iconRoundValue,
                ),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    packaging {
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
        }
        jniLibs {
            // 'extractNativeLibs' was not enough to keep the jniLibs and
            // the libs went missing after adding on-demand feature delivery
            useLegacyPackaging = true
        }
    }
    dynamicFeatures += setOf(":ubuntufs")

    kotlinter {
        ignoreFormatFailures  = false
    }

    // cmake on release builds a proot that fails to process ld-2.31.so
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    // (For now) Uncomment for LeakCanary to work.
    // configurations {
    //     debugImplementation {
    //         exclude(group = "junit", module = "junit")
    //     }
    // }
}

dependencies {
    implementation(libs.material)
    // JavaSteaml
    val localBuild = false // Change to 'true' needed when building JavaSteam manually
    if (localBuild) {
        implementation(files("../../JavaSteam/build/libs/javasteam-1.6.1-SNAPSHOT.jar"))
        implementation(libs.bundles.steamkit.dev)
    } else {
        implementation(libs.steamkit) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
    }
    implementation(libs.spongycastle)

    // Split Modules
    implementation(libs.bundles.google)

    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.landscapist.coil)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.apng)
    implementation(libs.datastore.preferences)
    implementation(libs.jetbrains.kotlinx.json)
    implementation(libs.kotlin.coroutines)
    implementation(libs.timber)
    implementation(libs.zxing)

    // Google Protobufs
    implementation(libs.protobuf.java)

    // Hilt
    implementation(libs.bundles.hilt)

    // KSP (Hilt, Room)
    ksp(libs.bundles.ksp)

    // Room Database
    implementation(libs.bundles.room)

    // Memory Leak Detection
    // debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)

    // Add PostHog Android SDK dependency
    implementation("com.posthog:posthog-android:3.+")

    // 1) import the platform – it pins *every* Supabase + Ktor module
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))

    // 2) add whichever supabase-kt modules you actually use
    implementation("io.github.jan-tennert.supabase:postgrest-kt")   // PostgREST
    implementation("io.github.jan-tennert.supabase:realtime-kt")

    implementation("io.ktor:ktor-client-android:3.1.3")
}
