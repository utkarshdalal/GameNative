# JNI bridge between app/src/main/java/app/gamenative/discord and the Discord Social SDK's C++
# API. Only wired into the Gradle build when app/libs/discord_partner_sdk.aar exists, see the
# discordSdkBundled guard in app/build.gradle.kts.
cmake_minimum_required(VERSION 3.22.1)
project(discordbridge)

add_library(discordbridge SHARED
    discord_bridge.cpp
)

# Provided by discord_partner_sdk.aar via prefab (android.buildFeatures.prefab is already on).
find_package(discord_partner_sdk REQUIRED CONFIG)

target_link_libraries(discordbridge
    discord_partner_sdk::discord_partner_sdk
    android
    log
)

target_compile_features(discordbridge PRIVATE cxx_std_17)
target_compile_options(discordbridge PRIVATE -Wall -Wextra -Werror=return-type -Wno-unused-parameter)
