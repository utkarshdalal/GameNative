// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dagger.hilt) apply false
    alias(libs.plugins.jetbrains.jvm) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.secrets.gradle) apply false
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    resolutionStrategy.cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
}
