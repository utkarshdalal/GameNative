// JNI bridge to the Discord Social SDK (discordpp), used by
// app/src/main/java/app/gamenative/discord/DiscordNative.kt.
//
// Only the SDK's unauthenticated RPC path is used: set an application ID and call
// UpdateRichPresence / ClearRichPresence, never Client::Connect. On Android that publishes through
// the installed Discord app while it is signed in. If Discord is missing or signed out the SDK
// reports a failed result, which reaches Kotlin as "unavailable".

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <exception>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

#define DISCORDPP_IMPLEMENTATION
#if __has_include(<discord_partner_sdk/discordpp.h>)
#include <discord_partner_sdk/discordpp.h>
#else
#include <discordpp.h>
#endif

namespace {

#define LOG_TAG "discordbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr const char *kNativeClassName = "app/gamenative/discord/DiscordNative";

// Keep in sync with DiscordNative.OP_UPDATE_PRESENCE.
constexpr jint kOpUpdatePresence = 1;

// Presence changes a few times an hour at most, so the pump can tick slowly.
constexpr int kPumpIntervalMs = 250;

// Callback ticks spent flushing the final ClearRichPresence before the client is destroyed.
constexpr int kShutdownDrainTicks = 5;

JavaVM *gVm = nullptr;
jclass gNativeClass = nullptr;
jmethodID gOnNativeResult = nullptr;

// Guards gClient and the pump thread. Every entry point holds it for the whole call, so the pump
// thread can never observe a half-torn-down client.
std::mutex gMutex;
std::shared_ptr<discordpp::Client> gClient;
std::thread gPumpThread;
std::atomic<bool> gPumpRunning{false};

struct ScopedEnv {
    JNIEnv *env = nullptr;
    bool attached = false;

    ScopedEnv() {
        if (gVm == nullptr) return;
        if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return;
        if (gVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            env = nullptr;
        }
    }

    ~ScopedEnv() {
        if (attached && gVm != nullptr) gVm->DetachCurrentThread();
    }

    ScopedEnv(const ScopedEnv &) = delete;
    ScopedEnv &operator=(const ScopedEnv &) = delete;
};

// Must not be called while holding gMutex: it runs Kotlin code, and gMutex is not recursive.
void PostResult(jint op, bool success, const std::string &message) {
    ScopedEnv scoped;
    if (scoped.env == nullptr || gNativeClass == nullptr || gOnNativeResult == nullptr) return;

    jstring jMessage = scoped.env->NewStringUTF(message.c_str());
    scoped.env->CallStaticVoidMethod(gNativeClass, gOnNativeResult, op,
                                     success ? JNI_TRUE : JNI_FALSE, jMessage);
    if (scoped.env->ExceptionCheck()) {
        scoped.env->ExceptionDescribe();
        scoped.env->ExceptionClear();
    }
    if (jMessage != nullptr) scoped.env->DeleteLocalRef(jMessage);
}

std::string ToStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// nullopt for null/empty, so blank Rich Presence fields are never published.
std::optional<std::string> ToOptionalString(JNIEnv *env, jstring value) {
    std::string result = ToStdString(env, value);
    if (result.empty()) return std::nullopt;
    return result;
}

// discordpp delivers callbacks from RunCallbacks(), which a game would normally drive from its
// render loop. GameNative has no such loop, so the bridge owns a slow tick of its own.
void PumpLoop() {
    while (gPumpRunning.load(std::memory_order_acquire)) {
        discordpp::RunCallbacks();
        std::this_thread::sleep_for(std::chrono::milliseconds(kPumpIntervalMs));
    }
}

// Stops the pump and waits for it, leaving this thread the only caller of RunCallbacks().
// PumpLoop never takes gMutex, so this is safe to call with the lock held.
void StopPumpLocked() {
    gPumpRunning.store(false, std::memory_order_release);
    if (gPumpThread.joinable()) gPumpThread.join();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClass = env->FindClass(kNativeClassName);
    if (localClass == nullptr) {
        LOGE("JNI_OnLoad: could not find %s", kNativeClassName);
        return JNI_ERR;
    }
    gNativeClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);

    gOnNativeResult =
            env->GetStaticMethodID(gNativeClass, "onNativeResult", "(IZLjava/lang/String;)V");
    if (gOnNativeResult == nullptr) {
        LOGE("JNI_OnLoad: could not find onNativeResult(IZLjava/lang/String;)V");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_discord_DiscordNative_nativeInitialize(JNIEnv *, jobject, jlong applicationId) {
    if (applicationId <= 0) {
        LOGE("nativeInitialize: refusing to start without a Discord application ID");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(gMutex);
    if (gClient != nullptr) return JNI_TRUE;

    try {
        gClient = std::make_shared<discordpp::Client>();
        gClient->SetApplicationId(static_cast<uint64_t>(applicationId));
        gPumpRunning.store(true, std::memory_order_release);
        gPumpThread = std::thread(PumpLoop);
    } catch (const std::exception &e) {
        LOGE("nativeInitialize: %s", e.what());
        gPumpRunning.store(false, std::memory_order_release);
        gClient.reset();
        return JNI_FALSE;
    }

    LOGI("nativeInitialize: discordpp client ready (RPC-only, no account linking)");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_discord_DiscordNative_nativeUpdatePresence(JNIEnv *env, jobject, jstring name,
                                                               jstring details, jstring state,
                                                               jlong startTimestampMs,
                                                               jstring largeImage,
                                                               jstring largeText,
                                                               jstring smallImage,
                                                               jstring smallText) {
    // Marshal on the calling thread; this JNIEnv is not valid on the SDK's callback thread.
    const std::string activityName = ToStdString(env, name);
    const std::optional<std::string> activityDetails = ToOptionalString(env, details);
    const std::optional<std::string> activityState = ToOptionalString(env, state);
    const std::optional<std::string> assetLargeImage = ToOptionalString(env, largeImage);
    const std::optional<std::string> assetLargeText = ToOptionalString(env, largeText);
    const std::optional<std::string> assetSmallImage = ToOptionalString(env, smallImage);
    const std::optional<std::string> assetSmallText = ToOptionalString(env, smallText);

    bool dispatched = false;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gClient != nullptr) {
            dispatched = true;

            discordpp::Activity activity;
            activity.SetType(discordpp::ActivityTypes::Playing);
            activity.SetName(activityName);
            activity.SetDetails(activityDetails);
            activity.SetState(activityState);

            if (startTimestampMs > 0) {
                discordpp::ActivityTimestamps timestamps;
                timestamps.SetStart(static_cast<uint64_t>(startTimestampMs));
                activity.SetTimestamps(timestamps);
            }

            // Image fields take either an art-asset key uploaded in the Developer Portal or an
            // external image URL, so callers can pass cover art straight from a store CDN.
            if (assetLargeImage.has_value() || assetLargeText.has_value() ||
                assetSmallImage.has_value() || assetSmallText.has_value()) {
                discordpp::ActivityAssets assets;
                if (assetLargeImage.has_value()) assets.SetLargeImage(assetLargeImage);
                if (assetLargeText.has_value()) assets.SetLargeText(assetLargeText);
                if (assetSmallImage.has_value()) assets.SetSmallImage(assetSmallImage);
                if (assetSmallText.has_value()) assets.SetSmallText(assetSmallText);
                activity.SetAssets(assets);
            }

            try {
                gClient->UpdateRichPresence(activity, [](discordpp::ClientResult result) {
                    PostResult(kOpUpdatePresence, result.Successful(), result.ToString());
                });
            } catch (const std::exception &e) {
                LOGE("nativeUpdatePresence: %s", e.what());
                dispatched = false;
            }
        }
    }

    if (!dispatched) PostResult(kOpUpdatePresence, false, "presence update not dispatched");
}

JNIEXPORT void JNICALL
Java_app_gamenative_discord_DiscordNative_nativeClearPresence(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gClient == nullptr) return;
    try {
        gClient->ClearRichPresence();
    } catch (const std::exception &e) {
        LOGE("nativeClearPresence: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_app_gamenative_discord_DiscordNative_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gClient == nullptr) return;

    // Stop the pump first: destroying the client while PumpLoop sits inside RunCallbacks()
    // would tear down state that call is still walking.
    StopPumpLocked();

    try {
        // Now the only caller of RunCallbacks(), so the clear can be flushed before teardown.
        gClient->ClearRichPresence();
        for (int i = 0; i < kShutdownDrainTicks; ++i) {
            discordpp::RunCallbacks();
            std::this_thread::sleep_for(std::chrono::milliseconds(kPumpIntervalMs / 5));
        }
    } catch (const std::exception &e) {
        LOGE("nativeShutdown: %s", e.what());
    }

    gClient.reset();
    LOGI("nativeShutdown: discordpp client torn down");
}

}  // extern "C"
