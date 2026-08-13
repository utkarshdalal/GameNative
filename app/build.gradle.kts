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

// =====================================================
// librashader native build tasks
// =====================================================

val librashaderSourceDir = rootProject.file("librashader")
val librashaderBuildDir = layout.buildDirectory.dir("generated/librashader")

val ndkDir: File
    get() {
        val fromAndroid = android.ndkDirectory
        if (fromAndroid.exists()) return fromAndroid
        val fromEnv = System.getenv("ANDROID_NDK_HOME")
        if (fromEnv != null && File(fromEnv).exists()) return File(fromEnv)
        throw GradleException("ANDROID_NDK_HOME not set and ndkDirectory not found. Install NDK via SDK Manager.")
    }

val cargoBin: String
    get() {
        val home = System.getenv("HOME") ?: "/home/annapaula"
        val candidates = listOf(
            "$home/.cargo/bin/cargo",
            "/usr/local/bin/cargo",
            "/usr/bin/cargo"
        )
        for (c in candidates) { if (File(c).exists()) return c }
        return ""
    }

data class AbiConfig(val abi: String, val rustTarget: String, val ndkClangPrefix: String)
val abiConfigs = listOf(
    AbiConfig("arm64-v8a",   "aarch64-linux-android",    "aarch64-linux-android"),
    AbiConfig("armeabi-v7a", "armv7-linux-androideabi",  "armv7a-linux-androideabi"),
    AbiConfig("x86_64",      "x86_64-linux-android",     "x86_64-linux-android")
)

val copyLibrashaderHeaders by tasks.registering(Copy::class) {
    from(librashaderSourceDir.resolve("include")) {
        include("librashader.h", "librashader_ld.h")
    }
    into(librashaderBuildDir.map { it.dir("include") })
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
            // CMake builds adrenotools hook libs alongside vulkan_renderer;
            // prebuilt copies exist in jniLibs — pick the CMake-built ones
            pickFirsts.addAll(listOf(
                "lib/arm64-v8a/libhook_impl.so",
                "lib/arm64-v8a/libmain_hook.so",
                "lib/arm64-v8a/libfile_redirect_hook.so",
                "lib/arm64-v8a/libgsl_alloc_hook.so",
                "lib/armeabi-v7a/libhook_impl.so",
                "lib/armeabi-v7a/libmain_hook.so",
                "lib/armeabi-v7a/libfile_redirect_hook.so",
                "lib/armeabi-v7a/libgsl_alloc_hook.so",
            ))
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
        getByName("main") {
            jniLibs {
                srcDir(librashaderBuildDir.map { it.dir("jniLibs") })
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // (For now) Uncomment for LeakCanary to work.
    // configurations {
    //     debugImplementation {
    //         exclude(group = "junit", module = "junit")
    //     }
    // }
}

dependencies {
    implementation(libs.material)

    // Chrome Custom Tabs for GOG OAuth
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

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

    // Add PostHog Android SDK dependency
    implementation("com.posthog:posthog-android:3.8.0")

    implementation("com.auth0.android:jwtdecode:2.0.2")

    // Samsung Performance SDK
    implementation(files("src/main/lib/perfsdk-v1.0.0.jar"))

    "modernXrImplementation"("com.meta.horizon.platform.sdk:core-kotlin:0.2.2")
    "modernXrImplementation"("com.meta.horizon.platform.sdk:iap-kotlin:0.2.2")
}

// =====================================================
// Per-ABI librashader build and copy tasks
// =====================================================

abiConfigs.forEach { config ->
    val taskName = "compileLibrashader${config.abi.replaceFirstChar { it.uppercase() }}"

    tasks.register(taskName) {
        dependsOn(copyLibrashaderHeaders)
        doLast {
            val ndk = ndkDir
            val toolchainDir = ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
            val clang = toolchainDir.resolve("${config.ndkClangPrefix}26-clang").absolutePath
            val clangxx = toolchainDir.resolve("${config.ndkClangPrefix}26-clang++").absolutePath
            val ar = toolchainDir.resolve("llvm-ar").absolutePath
            val rustTargetUpper = config.rustTarget.replace('-', '_').uppercase()

            val env = mapOf(
                "CC_${config.rustTarget}" to clang,
                "CXX_${config.rustTarget}" to clangxx,
                "AR_${config.rustTarget}" to ar,
                "CARGO_TARGET_${rustTargetUpper}_LINKER" to clang,
                "CARGO_TARGET_${rustTargetUpper}_RUSTFLAGS" to "-C link-arg=-Wl,-soname,liblibrashader.so",
                "ANDROID_NDK_HOME" to ndk.absolutePath
            )

            if (cargoBin.isEmpty()) {
                logger.warn("cargo not found, skipping librashader build for ${config.abi}")
            } else {
                exec {
                    workingDir = librashaderSourceDir
                    environment(env)
                    commandLine(
                        cargoBin,
                        "ndk",
                        "--target", config.rustTarget,
                        "--platform", "26",
                        "--",
                        "build",
                        "--package", "librashader-capi",
                        "--profile", "optimized",
                        "--no-default-features",
                        "--features", "runtime-vulkan,stable"
                    )
                }
            }
        }
    }

    val copyTaskName = "copyLibrashader${config.abi.replaceFirstChar { it.uppercase() }}"
    tasks.register(copyTaskName) {
        dependsOn(taskName)
        doLast {
            val profileDir = "optimized"
            val srcSo = librashaderSourceDir.resolve("target/${config.rustTarget}/$profileDir/liblibrashader_capi.so")
            val jniLibsDir = librashaderBuildDir.map { it.dir("jniLibs/${config.abi}") }.get().asFile
            jniLibsDir.mkdirs()

            if (srcSo.exists()) {
                srcSo.copyTo(jniLibsDir.resolve("liblibrashader.so"), overwrite = true)
                logger.lifecycle("librashader: copied ${srcSo} -> ${jniLibsDir}/liblibrashader.so")
            } else {
                val altSo = librashaderSourceDir.resolve("target/${config.rustTarget}/release/liblibrashader_capi.so")
                if (altSo.exists()) {
                    altSo.copyTo(jniLibsDir.resolve("liblibrashader.so"), overwrite = true)
                    logger.lifecycle("librashader: copied (from release) ${altSo} -> ${jniLibsDir}/liblibrashader.so")
                } else {
                    throw GradleException("librashader .so not found at ${srcSo} or ${altSo}")
                }
            }
        }
    }
}

val buildLibrashaderAll by tasks.registering {
    dependsOn(
        abiConfigs.flatMap { config ->
            val abi = config.abi.replaceFirstChar { it.uppercase() }
            listOf(
                tasks.named("compileLibrashader$abi"),
                tasks.named("copyLibrashader$abi")
            )
        }
    )
}

tasks.named("preBuild") {
    dependsOn(buildLibrashaderAll)
}
