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
    alias(libs.plugins.secrets.gradle)
    alias(libs.plugins.room)
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

val metaAppId: String = project.findProperty("META_APP_ID") as String? ?: System.getenv("META_APP_ID") ?: ""
val productSku: String = project.findProperty("PRODUCT_SKU") as String? ?: System.getenv("PRODUCT_SKU") ?: ""

room {
    schemaDirectory("$projectDir/schemas")
}

// Debug-only: package the repo's manifest.json so debug builds read it locally (never in release).
val copyDebugManifest by tasks.registering(Copy::class) {
    from(rootProject.file("manifest.json"))
    into(layout.buildDirectory.dir("generated/debugManifest"))
}

android {
    namespace = "app.gamenative"
    compileSdk = 36

    // https://developer.android.com/ndk/downloads
    ndkVersion = "27.3.13750724"

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

        manifestPlaceholders["screenOrientation"] = "unspecified"
        buildConfigField("boolean", "XR_BUILD", "false")
        buildConfigField("boolean", "MODERN_XR", "false")

        versionCode = 21
        versionName = "1.1.1"

        buildConfigField("boolean", "GOLD", "false")
        fun secret(name: String) =
            project.findProperty(name) as String? ?: System.getenv(name) ?: ""

        buildConfigField("String", "POSTHOG_API_KEY", "\"${secret("POSTHOG_API_KEY")}\"")
        buildConfigField("String", "POSTHOG_HOST",  "\"${secret("POSTHOG_HOST")}\"")
        buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"${secret("STEAMGRIDDB_API_KEY")}\"")
        buildConfigField("String", "CLOUD_PROJECT_NUMBER", "\"${secret("CLOUD_PROJECT_NUMBER")}\"")
        val iconValue = "@mipmap/ic_launcher"
        val iconRoundValue = "@mipmap/ic_launcher_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        ndk {
            //abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Localization support - specify which languages to include
        resourceConfigurations += listOf(
            "en",      // English (default)
            "es",      // Spanish
            "da",      // Danish
            "pt-rBR",  // Portuguese (Brazilian)
            "zh-rTW",  // Traditional Chinese
            "zh-rCN",  // Simplified Chinese
            "fr",      // French
            "de",      // German
            "uk",      // Ukrainian
            "it",      // Italian
            "ro",      // Română
            "pl",      // Polish
            "ru",      // Russian
            "ko",      // Korean
            "ja",      // Japanese
            // TODO: Add more languages here using the ISO 639-1 locale code with regional qualifiers (e.g., "pt-rPT" for European Portuguese)
        )

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

    flavorDimensions += "androidApi"
    productFlavors {
        create("legacy") {
            dimension = "androidApi"
            targetSdk = 28
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            buildConfigField("boolean", "MODERN_ANDROID", "false")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic.so\"")
        }
        create("legacyXr") {
            dimension = "androidApi"
            targetSdk = 28
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            buildConfigField("boolean", "MODERN_ANDROID", "false")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic.so\"")
            buildConfigField("boolean", "XR_BUILD", "true")
            manifestPlaceholders["screenOrientation"] = "landscape"
        }
        create("modern") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("boolean", "MODERN_ANDROID", "true")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        }
        create("modernXr") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("boolean", "MODERN_ANDROID", "true")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
            buildConfigField("boolean", "XR_BUILD", "true")
            buildConfigField("boolean", "MODERN_XR", "true")
            buildConfigField("String", "META_APP_ID", "\"$metaAppId\"")
            buildConfigField("String", "PRODUCT_SKU", "\"$productSku\"")
            manifestPlaceholders["screenOrientation"] = "landscape"
        }
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

    packaging {
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // 'extractNativeLibs' was not enough to keep the jniLibs and
            // the libs went missing after adding on-demand feature delivery
            useLegacyPackaging = true
        }
    }
    // snappy-java jar ships android .so files under org/xerial/snappy/native/Linux/android-*/ — AGP's
    // mergeNativeLibs strips .so files that aren't already at lib/<abi>/. extractSnappyAndroidJni
    // (below, outside android {}) relocates them to build/generated/snappy-jni/<abi>/libsnappyjava.so;
    // this srcDir picks them up as jniLibs so they end up at lib/<abi>/libsnappyjava.so in the APK,
    // which System.loadLibrary("snappyjava") can find (see use.systemlib wiring in PluviaApp).
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/snappy-jni"))
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Locale files ship full AndroidX appcompat (abc_*) translations that aren't in the
        // default locale. These extra translations are harmless and pre-existing; without this
        // the release-only lintVital pass fails on 150+ ExtraTranslation errors.
        disable += "ExtraTranslation"
    }
    dynamicFeatures += setOf(":ubuntufs")

    // Configure Assets to be used in different variants
    sourceSets {
        getByName("legacy") {
            java.srcDir("src/nonXr/java")
            assets {
                srcDirs("src/legacy/assets", "src/main/assets")
            }
        }
        getByName("legacyXr") {
            java.srcDir("src/nonXr/java")
            manifest.srcFile("src/legacy/AndroidManifest.xml")
            assets {
                srcDirs("src/legacy/assets", "src/main/assets")
            }
            jniLibs {
                srcDirs("src/legacy/jniLibs")
            }
        }
        getByName("modern") {
            java.srcDir("src/nonXr/java")
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
        }
        getByName("modernXr") {
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
            jniLibs {
                srcDirs("src/modern/jniLibs")
            }
        }
        getByName("debug") {
            assets.srcDir(copyDebugManifest)
        }
    }

    kotlinter {
        ignoreFormatFailures  = false
    }

    // externalNativeBuild {
    //   cmake {
    //       path = file("src/main/cpp/asurfacerenderer/CMakeLists.txt")
    //   }
    // }

    // externalNativeBuild {
    //    cmake {
    //        path = file("src/main/cpp/evshim/CMakeLists.txt")
    //    }
    // }

    // xconnectorpatch is shipped as a prebuilt jniLib because our APK packaging flow
    // does not rebuild native libraries during release creation.
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/xconnectorpatch/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    // build extras needed in libwinlator_bionic.so
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/extras/CMakeLists.txt")   // the file shown above
    //         version = "3.22.1"
    //     }
    // }

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

// isolated resolvable configuration so we can fish the snappy-java jar out at build time
// without affecting main classpath resolution.
val snappyJniExtract: Configuration by configurations.creating {
    isTransitive = false
    isCanBeResolved = true
    isCanBeConsumed = false
}

// WHY: AGP's mergeNativeLibs task silently drops .so files that live anywhere in a jar other than
// lib/<abi>/. snappy-java places Android natives at org/xerial/snappy/native/Linux/android-*/, so
// shipping the jar unchanged yields an APK with zero libsnappyjava.so. we fish them out here and
// relocate to lib/<abi>/libsnappyjava.so via the jniLibs srcDir registered in android.sourceSets.
val extractSnappyAndroidJni by tasks.registering(Copy::class) {
    from({ snappyJniExtract.map { zipTree(it) } }) {
        include("org/xerial/snappy/native/Linux/android-aarch64/libsnappyjava.so")
        include("org/xerial/snappy/native/Linux/android-arm/libsnappyjava.so")
    }
    into(layout.buildDirectory.dir("generated/snappy-jni"))
    eachFile {
        val abi = when {
            path.contains("android-aarch64") -> "arm64-v8a"
            path.contains("android-arm") -> "armeabi-v7a"
            else -> return@eachFile
        }
        relativePath = RelativePath(true, abi, "libsnappyjava.so")
    }
    includeEmptyDirs = false
    doLast {
        // snappy-java's ABI subdir naming is the load-bearing assumption above. if a future
        // version reshuffles (e.g. android-arm64, per-OS reorg), the include() patterns
        // silently match nothing and the APK ships without libsnappyjava.so. fail loudly.
        val produced = layout.buildDirectory.dir("generated/snappy-jni").get().asFile
            .walkTopDown().filter { it.isFile && it.name == "libsnappyjava.so" }.count()
        check(produced > 0) {
            "extractSnappyAndroidJni produced no .so files — snappy-java jar layout likely changed; " +
                "audit include() patterns in app/build.gradle.kts vs the snappy-java version in libs.versions.toml."
        }
    }
}

tasks.named("preBuild").configure { dependsOn(extractSnappyAndroidJni) }

dependencies {
    implementation(libs.material)

    // Chrome Custom Tabs for GOG OAuth
    implementation("androidx.browser:browser:1.8.0")

    // JavaSteam
    val localBuild = false // Change to 'true' needed when building JavaSteam manually
    if (localBuild) {
        implementation(files("../../JavaSteam/build/libs/javasteam-1.8.0.1-25-SNAPSHOT.jar"))
        implementation(files("../../JavaSteam/javasteam-depotdownloader/build/libs/javasteam-depotdownloader-1.8.0.1-25-SNAPSHOT.jar"))
        implementation(libs.bundles.javasteam.dev)
    } else {
        implementation(libs.javasteam) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
        implementation(libs.javasteam.depotdownloader) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
    }
    implementation(libs.spongycastle)
    implementation(libs.okhttp.dnsoverhttps)

    // Split Modules
    implementation(libs.bundles.google)

    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.libarchive.android)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    // leveldb — phase 6 save-sync (D-101, D-101.5). pure-java iq80 picked over leveldbjni-all:1.8
    // (2013 artifact, no android-arm64-v8a natives) because we need a custom Java comparator
    // (idb_cmp1). VENDORED as the :iq80-leveldb module (fork of 0.12) — Table.uncompressedScratch
    // made ThreadLocal to fix concurrent-decompression block corruption (reader vs background
    // compaction). see iq80-leveldb/NOTICE.md. snappy-java handles chromium-style compression via JNI.
    implementation(project(":iq80-leveldb"))
    implementation(libs.snappy.java)
    // separate non-transitive pull of same jar so extractSnappyAndroidJni can relocate its
    // android .so files into lib/<abi>/. see that task definition above.
    snappyJniExtract(libs.snappy.java)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.landscapist.coil)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
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
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.orgJson)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.rhino) // JVM JS engine -- executes pure shims (path.js, require-dispatcher.js) in tests

    // Add PostHog Android SDK dependency
    implementation("com.posthog:posthog-android:3.8.0")

    implementation("com.auth0.android:jwtdecode:2.0.2")

    "modernXrImplementation"("com.meta.horizon.platform.sdk:core-kotlin:0.2.2")
    "modernXrImplementation"("com.meta.horizon.platform.sdk:iap-kotlin:0.2.2")
}
