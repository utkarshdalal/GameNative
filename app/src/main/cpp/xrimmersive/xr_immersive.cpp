#include "xr_immersive.h"

#include <android/log.h>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <unistd.h>
#include <vector>

#define LOG_TAG "xrimmersive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace xrimmersive {

namespace {

bool XrCheck(XrResult result, const char *what) {
    if (XR_FAILED(result)) {
        LOGE("%s failed: %d", what, static_cast<int>(result));
        return false;
    }
    return true;
}

bool IsInstanceExtensionSupported(const char *name) {
    uint32_t count = 0;
    xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr);
    std::vector<XrExtensionProperties> properties(count, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, count, &count, properties.data());
    for (const auto &property : properties) {
        if (std::strcmp(property.extensionName, name) == 0) return true;
    }
    return false;
}

XrPosef IdentityPose() {
    XrPosef pose;
    pose.orientation.x = 0.0f;
    pose.orientation.y = 0.0f;
    pose.orientation.z = 0.0f;
    pose.orientation.w = 1.0f;
    pose.position.x = 0.0f;
    pose.position.y = 0.0f;
    pose.position.z = 0.0f;
    return pose;
}

}  // namespace

bool XrImmersiveSession::initialize(JavaVM *vm, jobject activityRef) {
    vm_ = vm;
    activityRef_ = activityRef;

    thread_ = std::thread([this] { runLoop(); });
    return true;
}

void XrImmersiveSession::requestStop() {
    stopRequested_.store(true);
}

void XrImmersiveSession::join() {
    if (thread_.joinable()) {
        thread_.join();
    }
}

InputSnapshot XrImmersiveSession::pollSnapshot() {
    std::lock_guard<std::mutex> lock(snapshotMutex_);
    InputSnapshot result = snapshot_;
    // Rising-edge flags are consumed on read.
    snapshot_.quickMenuClicked = false;
    snapshot_.pointerModeToggled = false;
    return result;
}

void XrImmersiveSession::submitFrame(const uint8_t *rgbaPixels, int32_t width, int32_t height,
                                      int32_t strideBytes) {
    if (width <= 0 || height <= 0) return;
    std::lock_guard<std::mutex> lock(frameMutex_);
    const size_t rowBytes = static_cast<size_t>(width) * 4;
    pendingFramePixels_.resize(rowBytes * static_cast<size_t>(height));
    if (strideBytes <= 0 || static_cast<size_t>(strideBytes) == rowBytes) {
        std::memcpy(pendingFramePixels_.data(), rgbaPixels, rowBytes * static_cast<size_t>(height));
    } else {
        // pendingFramePixels_ is always tightly packed — uploadPendingGameFrameLocked() feeds it
        // straight to glTexImage2D/glTexSubImage2D with no unpack row length set.
        for (int32_t y = 0; y < height; ++y) {
            std::memcpy(pendingFramePixels_.data() + rowBytes * static_cast<size_t>(y),
                        rgbaPixels + static_cast<size_t>(strideBytes) * static_cast<size_t>(y),
                        rowBytes);
        }
    }
    pendingFrameWidth_ = width;
    pendingFrameHeight_ = height;
    hasPendingFrame_ = true;
}

void XrImmersiveSession::setSharedGameBuffer(AHardwareBuffer *buffer) {
    std::lock_guard<std::mutex> lock(sharedBufferMutex_);
    // The renderer republishes the same buffer every frame; re-importing it would destroy and
    // recreate the EGLImage/texture on the render thread for nothing. Holding a reference on
    // pendingSharedBuffer_ also keeps its address from being recycled, so pointer identity is a
    // sound "same buffer" test.
    if (buffer == pendingSharedBuffer_) return;
    if (pendingSharedBuffer_ != nullptr) {
        AHardwareBuffer_release(pendingSharedBuffer_);
    }
    if (buffer != nullptr) {
        AHardwareBuffer_acquire(buffer);
    }
    pendingSharedBuffer_ = buffer;
    sharedBufferChanged_ = true;
}

// Must run on the render thread (this thread) — glEGLImageTargetTexture2DOES needs this
// thread's EGL context current. AHardwareBuffer's underlying memory is what's actually shared
// across contexts/processes; the EGLImage/texture wrapping it is per-context, so importing
// again here (GLRenderer already imported the same buffer once, into its own context) is
// expected and correct, not a duplicate/wasted step.
void XrImmersiveSession::importSharedBufferIfNeeded() {
    AHardwareBuffer *buffer = nullptr;
    {
        std::lock_guard<std::mutex> lock(sharedBufferMutex_);
        if (!sharedBufferChanged_) return;
        buffer = pendingSharedBuffer_;
        sharedBufferChanged_ = false;
        // Take our own reference before dropping the mutex: a concurrent setSharedGameBuffer()
        // may release the session's reference while the import below is still running.
        if (buffer != nullptr) AHardwareBuffer_acquire(buffer);
    }
    if (buffer == nullptr) return;
    std::unique_ptr<AHardwareBuffer, void (*)(AHardwareBuffer *)> bufferRef(
        buffer, AHardwareBuffer_release);

    if (sharedGameImage_ != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(eglDisplay_, sharedGameImage_);
        sharedGameImage_ = EGL_NO_IMAGE_KHR;
    }

    // setSharedGameBuffer() only re-arms sharedBufferChanged_ when the buffer identity changes,
    // so a failed import must re-arm it itself or this buffer would never be retried.
    auto retryNextFrame = [this, buffer] {
        std::lock_guard<std::mutex> lock(sharedBufferMutex_);
        if (pendingSharedBuffer_ == buffer) sharedBufferChanged_ = true;
    };

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
    if (clientBuffer == nullptr) {
        LOGE("Immersive direct-render: eglGetNativeClientBufferANDROID failed");
        retryNextFrame();
        return;
    }

    const EGLint attrs[] = {EGL_NONE};
    sharedGameImage_ = eglCreateImageKHR(eglDisplay_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                          clientBuffer, attrs);
    if (sharedGameImage_ == EGL_NO_IMAGE_KHR) {
        LOGE("Immersive direct-render: eglCreateImageKHR failed (0x%x)", eglGetError());
        retryNextFrame();
        return;
    }

    if (sharedGameTexture_ == 0) {
        glGenTextures(1, &sharedGameTexture_);
    }
    glBindTexture(GL_TEXTURE_2D, sharedGameTexture_);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, sharedGameImage_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    hasSharedGameTexture_ = true;
    LOGI("Immersive direct-render: shared game buffer imported into XR thread's context (texture=%u)",
         sharedGameTexture_);
}

void XrImmersiveSession::runLoop() {
    if (!setupInstanceAndSession()) {
        LOGE("OpenXR setup failed, aborting immersive session thread");
        teardown();
        return;
    }

    while (!stopRequested_.load()) {
        pollXrEvents();

        if (!sessionRunning_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
        XrFrameState frameState{XR_TYPE_FRAME_STATE};
        if (!XrCheck(xrWaitFrame(session_, &waitInfo, &frameState), "xrWaitFrame")) break;

        XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
        if (!XrCheck(xrBeginFrame(session_, &beginInfo), "xrBeginFrame")) break;

        applyPendingPassthroughState();
        syncControllerInputs(frameState.predictedDisplayTime);

        // Every xrBeginFrame must be matched by an xrEndFrame, but a layer may only reference a
        // swapchain image that was actually acquired — so a failed renderFrame() still ends the
        // frame, just with no layers.
        if (frameState.shouldRender && renderFrame()) {
            submitQuadLayer(frameState.predictedDisplayTime, localSpace_, swapchain_,
                             kSwapchainWidth, kSwapchainHeight, true);
        } else {
            XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
            endInfo.displayTime = frameState.predictedDisplayTime;
            endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            endInfo.layerCount = 0;
            endInfo.layers = nullptr;
            xrEndFrame(session_, &endInfo);
        }
    }

    teardown();
}

bool XrImmersiveSession::setupInstanceAndSession() {
    // Android requires the loader to be explicitly initialized before xrCreateInstance.
    PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                          reinterpret_cast<PFN_xrVoidFunction *>(&initializeLoader));
    if (initializeLoader == nullptr) {
        LOGE("xrInitializeLoaderKHR not available");
        return false;
    }

    XrLoaderInitInfoAndroidKHR loaderInitInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loaderInitInfo.applicationVM = vm_;
    loaderInitInfo.applicationContext = activityRef_;
    if (!XrCheck(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR *>(&loaderInitInfo)),
                 "xrInitializeLoaderKHR")) {
        return false;
    }

    std::vector<const char *> extensions = {
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
    };
    passthroughExtensionAvailable_ = IsInstanceExtensionSupported(XR_FB_PASSTHROUGH_EXTENSION_NAME);
    if (passthroughExtensionAvailable_) {
        extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
    } else {
        LOGI("XR_FB_passthrough not supported by this runtime — passthrough toggle will no-op");
    }

    // Perf/scheduling extensions GameNativeXR's libxr.so uses (confirmed via strings on the
    // binary — standard OpenXR extensions, nothing proprietary) that this module never
    // requested at all: without XR_EXT_performance_settings the runtime applies its own default
    // (often conservative) CPU/GPU clock levels for the session; without
    // XR_KHR_android_thread_settings, Horizon OS's scheduler has no hint which thread is the XR
    // render thread and may not prioritize it; XR_FB_display_refresh_rate lets us target a
    // lower refresh rate (less frame-budget pressure) instead of whatever the system default is.
    perfSettingsExtensionAvailable_ = IsInstanceExtensionSupported(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME);
    if (perfSettingsExtensionAvailable_) extensions.push_back(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME);
    threadSettingsExtensionAvailable_ = IsInstanceExtensionSupported(XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME);
    if (threadSettingsExtensionAvailable_) extensions.push_back(XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME);
    refreshRateExtensionAvailable_ = IsInstanceExtensionSupported(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    if (refreshRateExtensionAvailable_) extensions.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);

    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = vm_;
    androidInfo.applicationActivity = activityRef_;

    XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.next = &androidInfo;
    std::strncpy(createInfo.applicationInfo.applicationName, "GameNative",
                 XR_MAX_APPLICATION_NAME_SIZE - 1);
    createInfo.applicationInfo.applicationVersion = 1;
    std::strncpy(createInfo.applicationInfo.engineName, "GameNative", XR_MAX_ENGINE_NAME_SIZE - 1);
    createInfo.applicationInfo.engineVersion = 1;
    createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.enabledExtensionNames = extensions.data();

    if (!XrCheck(xrCreateInstance(&createInfo, &instance_), "xrCreateInstance")) return false;

    XrSystemGetInfo systemGetInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemGetInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (!XrCheck(xrGetSystem(instance_, &systemGetInfo, &systemId_), "xrGetSystem")) return false;

    // Passthrough needs ALPHA_BLEND — but requesting a blend mode xrEndFrame doesn't list as
    // supported for this view config is a validation error (this runtime rejected every frame
    // with xrEndFrame failing -1 once passthrough was toggled on, until this check was added).
    uint32_t blendModeCount = 0;
    xrEnumerateEnvironmentBlendModes(instance_, systemId_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                      0, &blendModeCount, nullptr);
    if (blendModeCount > 0) {
        std::vector<XrEnvironmentBlendMode> blendModes(blendModeCount);
        xrEnumerateEnvironmentBlendModes(instance_, systemId_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                          blendModeCount, &blendModeCount, blendModes.data());
        for (auto mode : blendModes) {
            if (mode == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) alphaBlendSupported_ = true;
        }
    }
    if (!alphaBlendSupported_) {
        LOGI("XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND not supported by this runtime — passthrough toggle will no-op");
    }

    PFN_xrGetOpenGLESGraphicsRequirementsKHR getGraphicsRequirements = nullptr;
    xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
                          reinterpret_cast<PFN_xrVoidFunction *>(&getGraphicsRequirements));
    XrGraphicsRequirementsOpenGLESKHR graphicsRequirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    if (getGraphicsRequirements != nullptr) {
        getGraphicsRequirements(instance_, systemId_, &graphicsRequirements);
    }

    // --- EGL context, dedicated to this session (not yet shared with the app's own
    // GLRenderer/DXVK-facing surface — see the header comment / plan follow-ups). ---
    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint eglMajor, eglMinor;
    eglInitialize(eglDisplay_, &eglMajor, &eglMinor);
    eglBindAPI(EGL_OPENGL_ES_API);

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint numConfigs = 0;
    eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs);
    if (numConfigs == 0) {
        LOGE("eglChooseConfig found no matching config");
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);

    const EGLint pbufferAttribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    eglPbufferSurface_ = eglCreatePbufferSurface(eglDisplay_, eglConfig_, pbufferAttribs);
    eglMakeCurrent(eglDisplay_, eglPbufferSurface_, eglPbufferSurface_, eglContext_);

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    graphicsBinding.display = eglDisplay_;
    graphicsBinding.config = eglConfig_;
    graphicsBinding.context = eglContext_;

    XrSessionCreateInfo sessionCreateInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionCreateInfo.next = &graphicsBinding;
    sessionCreateInfo.systemId = systemId_;
    if (!XrCheck(xrCreateSession(instance_, &sessionCreateInfo, &session_), "xrCreateSession")) {
        return false;
    }

    // Perf levels: request BOOST for both CPU and GPU domains — this game is running Box64
    // (x86 emulation) + Wine + our own screen-scraping capture on top of everything else, so
    // there's no reason to accept the runtime's own default (often more conservative) clock
    // levels for an immersive session that specifically exists to maximize performance.
    if (perfSettingsExtensionAvailable_) {
        PFN_xrPerfSettingsSetPerformanceLevelEXT setPerfLevel = nullptr;
        xrGetInstanceProcAddr(instance_, "xrPerfSettingsSetPerformanceLevelEXT",
                              reinterpret_cast<PFN_xrVoidFunction *>(&setPerfLevel));
        if (setPerfLevel != nullptr) {
            // BOOST is documented as a short-burst level, not meant for continuous sustained
            // rendering — requesting it for an entire session risks hitting thermal limits
            // sooner, which the runtime then compensates for by throttling clocks back down,
            // net WORSE and less consistent than just requesting the sustained level up front
            // (confirmed by testing: frame pacing got worse after BOOST was added, not better).
            XrCheck(setPerfLevel(session_, XR_PERF_SETTINGS_DOMAIN_CPU_EXT, XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT),
                    "xrPerfSettingsSetPerformanceLevelEXT(CPU)");
            XrCheck(setPerfLevel(session_, XR_PERF_SETTINGS_DOMAIN_GPU_EXT, XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT),
                    "xrPerfSettingsSetPerformanceLevelEXT(GPU)");
        }
    }

    // Thread hint: tells Horizon OS's scheduler which OS thread is the XR render thread (this
    // one — setupInstanceAndSession() runs on the dedicated thread created in initialize(), so
    // gettid() here is correct) so it can prioritize it. Without this the scheduler has no
    // signal that this thread's timing matters for frame delivery.
    if (threadSettingsExtensionAvailable_) {
        PFN_xrSetAndroidApplicationThreadKHR setThread = nullptr;
        xrGetInstanceProcAddr(instance_, "xrSetAndroidApplicationThreadKHR",
                              reinterpret_cast<PFN_xrVoidFunction *>(&setThread));
        if (setThread != nullptr) {
            XrCheck(setThread(session_, XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR, gettid()),
                    "xrSetAndroidApplicationThreadKHR");
        }
    }

    // Target 72Hz rather than whatever higher default the runtime picks (90/120Hz) — matches
    // GameNativeXR's own default. Less frame-budget pressure for a pipeline (game render +
    // PixelCopy readback + composite + texture upload) that doesn't have headroom to spare.
    if (refreshRateExtensionAvailable_) {
        PFN_xrRequestDisplayRefreshRateFB requestRefreshRate = nullptr;
        xrGetInstanceProcAddr(instance_, "xrRequestDisplayRefreshRateFB",
                              reinterpret_cast<PFN_xrVoidFunction *>(&requestRefreshRate));
        if (requestRefreshRate != nullptr) {
            XrCheck(requestRefreshRate(session_, 72.0f), "xrRequestDisplayRefreshRateFB");
        }
    }

    XrReferenceSpaceCreateInfo spaceCreateInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceCreateInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceCreateInfo.poseInReferenceSpace = IdentityPose();
    if (!XrCheck(xrCreateReferenceSpace(session_, &spaceCreateInfo, &localSpace_),
                 "xrCreateReferenceSpace")) {
        return false;
    }

    uint32_t formatCount = 0;
    xrEnumerateSwapchainFormats(session_, 0, &formatCount, nullptr);
    std::vector<int64_t> formats(formatCount);
    xrEnumerateSwapchainFormats(session_, formatCount, &formatCount, formats.data());
    int64_t chosenFormat = formats.empty() ? 0x8058 /* GL_RGBA8 */ : formats[0];
    for (int64_t f : formats) {
        if (f == 0x8058) {
            chosenFormat = f;
            break;
        }
    }

    XrSwapchainCreateInfo swapchainCreateInfo{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    swapchainCreateInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    swapchainCreateInfo.format = chosenFormat;
    swapchainCreateInfo.sampleCount = 1;
    swapchainCreateInfo.width = kSwapchainWidth;
    swapchainCreateInfo.height = kSwapchainHeight;
    swapchainCreateInfo.faceCount = 1;
    swapchainCreateInfo.arraySize = 1;
    swapchainCreateInfo.mipCount = 1;
    if (!XrCheck(xrCreateSwapchain(session_, &swapchainCreateInfo, &swapchain_), "xrCreateSwapchain")) {
        return false;
    }

    uint32_t imageCount = 0;
    xrEnumerateSwapchainImages(swapchain_, 0, &imageCount, nullptr);
    swapchainImages_.resize(imageCount);
    for (auto &image : swapchainImages_) image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
    xrEnumerateSwapchainImages(
        swapchain_, imageCount, &imageCount,
        reinterpret_cast<XrSwapchainImageBaseHeader *>(swapchainImages_.data()));

    glGenFramebuffers(1, &framebuffer_);

    // --- Input: action set + Meta Quest Touch controller bindings. ---
    XrActionSetCreateInfo actionSetInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
    std::strncpy(actionSetInfo.actionSetName, "gameplay", XR_MAX_ACTION_SET_NAME_SIZE - 1);
    std::strncpy(actionSetInfo.localizedActionSetName, "Gameplay", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
    actionSetInfo.priority = 0;
    if (!XrCheck(xrCreateActionSet(instance_, &actionSetInfo, &actionSet_), "xrCreateActionSet")) {
        return false;
    }

    auto createAction = [this](const char *name, XrActionType type, XrAction *out) {
        XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
        std::strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
        std::strncpy(info.localizedActionName, name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
        info.actionType = type;
        info.countSubactionPaths = 0;
        XrCheck(xrCreateAction(actionSet_, &info, out), name);
    };

    createAction("button_x", XR_ACTION_TYPE_BOOLEAN_INPUT, &buttonXAction_);
    createAction("button_y", XR_ACTION_TYPE_BOOLEAN_INPUT, &buttonYAction_);
    createAction("button_a", XR_ACTION_TYPE_BOOLEAN_INPUT, &buttonAAction_);
    createAction("button_b", XR_ACTION_TYPE_BOOLEAN_INPUT, &buttonBAction_);
    createAction("squeeze_left", XR_ACTION_TYPE_FLOAT_INPUT, &squeezeLAction_);
    createAction("squeeze_right", XR_ACTION_TYPE_FLOAT_INPUT, &squeezeRAction_);
    createAction("trigger_left", XR_ACTION_TYPE_FLOAT_INPUT, &triggerLAction_);
    createAction("trigger_right", XR_ACTION_TYPE_FLOAT_INPUT, &triggerRAction_);
    createAction("thumbstick_left", XR_ACTION_TYPE_VECTOR2F_INPUT, &thumbstickLAction_);
    createAction("thumbstick_right", XR_ACTION_TYPE_VECTOR2F_INPUT, &thumbstickRAction_);
    createAction("thumbstick_left_click", XR_ACTION_TYPE_BOOLEAN_INPUT, &thumbstickLClickAction_);
    createAction("thumbstick_right_click", XR_ACTION_TYPE_BOOLEAN_INPUT, &thumbstickRClickAction_);
    createAction("menu_left", XR_ACTION_TYPE_BOOLEAN_INPUT, &menuLAction_);
    createAction("aim_pose_left", XR_ACTION_TYPE_POSE_INPUT, &aimPoseLeftAction_);
    createAction("aim_pose_right", XR_ACTION_TYPE_POSE_INPUT, &aimPoseRightAction_);

    auto path = [this](const char *p) {
        XrPath result = XR_NULL_PATH;
        xrStringToPath(instance_, p, &result);
        return result;
    };

    std::vector<XrActionSuggestedBinding> bindings = {
        {buttonXAction_, path("/user/hand/left/input/x/click")},
        {buttonYAction_, path("/user/hand/left/input/y/click")},
        {buttonAAction_, path("/user/hand/right/input/a/click")},
        {buttonBAction_, path("/user/hand/right/input/b/click")},
        {squeezeLAction_, path("/user/hand/left/input/squeeze/value")},
        {squeezeRAction_, path("/user/hand/right/input/squeeze/value")},
        {triggerLAction_, path("/user/hand/left/input/trigger/value")},
        {triggerRAction_, path("/user/hand/right/input/trigger/value")},
        {thumbstickLAction_, path("/user/hand/left/input/thumbstick")},
        {thumbstickRAction_, path("/user/hand/right/input/thumbstick")},
        {thumbstickLClickAction_, path("/user/hand/left/input/thumbstick/click")},
        {thumbstickRClickAction_, path("/user/hand/right/input/thumbstick/click")},
        {menuLAction_, path("/user/hand/left/input/menu/click")},
        {aimPoseLeftAction_, path("/user/hand/left/input/aim/pose")},
        {aimPoseRightAction_, path("/user/hand/right/input/aim/pose")},
    };

    XrInteractionProfileSuggestedBinding suggestedBinding{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggestedBinding.interactionProfile = path("/interaction_profiles/oculus/touch_controller");
    suggestedBinding.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
    suggestedBinding.suggestedBindings = bindings.data();
    XrCheck(xrSuggestInteractionProfileBindings(instance_, &suggestedBinding),
            "xrSuggestInteractionProfileBindings");

    XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &actionSet_;
    XrCheck(xrAttachSessionActionSets(session_, &attachInfo), "xrAttachSessionActionSets");

    XrActionSpaceCreateInfo aimSpaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    aimSpaceInfo.poseInActionSpace = IdentityPose();
    aimSpaceInfo.action = aimPoseLeftAction_;
    XrCheck(xrCreateActionSpace(session_, &aimSpaceInfo, &aimSpaceLeft_), "xrCreateActionSpace(left aim)");
    aimSpaceInfo.action = aimPoseRightAction_;
    XrCheck(xrCreateActionSpace(session_, &aimSpaceInfo, &aimSpaceRight_), "xrCreateActionSpace(right aim)");

    if (passthroughExtensionAvailable_) {
        setupPassthrough();
    }

    LOGI("OpenXR immersive session initialized (%dx%d quad, %u swapchain images)",
         kSwapchainWidth, kSwapchainHeight, imageCount);
    return true;
}

void XrImmersiveSession::pollXrEvents() {
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (instance_ != XR_NULL_HANDLE && xrPollEvent(instance_, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto *stateEvent = reinterpret_cast<XrEventDataSessionStateChanged *>(&event);
            sessionState_ = stateEvent->state;
            LOGI("OpenXR session state changed: %d", static_cast<int>(sessionState_));
            switch (sessionState_) {
                case XR_SESSION_STATE_READY: {
                    XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
                    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    XrCheck(xrBeginSession(session_, &beginInfo), "xrBeginSession");
                    sessionRunning_ = true;
                    break;
                }
                case XR_SESSION_STATE_STOPPING:
                    xrEndSession(session_);
                    sessionRunning_ = false;
                    break;
                case XR_SESSION_STATE_EXITING:
                case XR_SESSION_STATE_LOSS_PENDING:
                    stopRequested_.store(true);
                    break;
                default:
                    break;
            }
        }
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
    }
}

namespace {
constexpr char kQuadVertexShader[] =
    "#version 300 es\n"
    "layout(location = 0) in vec2 aPosition;\n"
    "layout(location = 1) in vec2 aTexCoord;\n"
    "out vec2 vTexCoord;\n"
    "void main() {\n"
    "    vTexCoord = aTexCoord;\n"
    "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
    "}\n";

constexpr char kQuadFragmentShader[] =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vTexCoord;\n"
    "out vec4 fragColor;\n"
    "uniform sampler2D uTexture;\n"
    "void main() {\n"
    "    fragColor = texture(uTexture, vTexCoord);\n"
    "}\n";

// Direct-render path: uGameTexture is the shared-buffer texture GLRenderer writes into on its
// own thread (always opaque, drawn as the base); uOverlayTexture is the same CPU-uploaded
// menu/HUD layer as the non-direct path, alpha-blended on top — same visual result as the
// non-direct path's Kotlin-side Canvas composite, just with the base layer sourced from a
// shared GPU buffer instead of a PixelCopy'd CPU bitmap.
//
// uContentScale: the quad itself is grown by a margin band (pointer-mode resize/move handles
// live there — see POINTER_BORDER_MARGIN_METERS in ImmersiveXrActivity.kt), but uGameTexture is
// the raw game frame at its own native resolution with no margin baked in — sampling it at
// vTexCoord directly (spanning the WHOLE, margin-grown quad) would stretch it into the margin.
// uContentScale is content-half-extent / quad-half-extent per axis (< 1.0 whenever a margin is
// present); remapping vTexCoord by it recovers the sub-rectangle of the quad's UV space that is
// actually "the game", and anything outside that rectangle (the margin) is treated as if the
// game layer were fully transparent there — only uOverlayTexture (which DOES span the whole
// quad, including the margin — same bitmap the non-direct path already draws its handles/cursor
// into) shows through, at ITS OWN alpha, matching the non-direct path's compositing model.
constexpr char kDirectQuadFragmentShader[] =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vTexCoord;\n"
    "out vec4 fragColor;\n"
    "uniform sampler2D uGameTexture;\n"
    "uniform sampler2D uOverlayTexture;\n"
    "uniform vec2 uContentScale;\n"
    "void main() {\n"
    "    vec2 gameUv = (vTexCoord - 0.5) / uContentScale + 0.5;\n"
    "    bool insideGame = gameUv.x >= 0.0 && gameUv.x <= 1.0 && gameUv.y >= 0.0 && gameUv.y <= 1.0;\n"
    "    vec4 game = insideGame ? texture(uGameTexture, gameUv) : vec4(0.0);\n"
    "    vec4 overlay = texture(uOverlayTexture, vTexCoord);\n"
    "    vec3 rgb = mix(game.rgb, overlay.rgb, overlay.a);\n"
    "    float alpha = insideGame ? 1.0 : overlay.a;\n"
    "    fragColor = vec4(rgb, alpha);\n"
    "}\n";

GLuint CompileShader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}
}  // namespace

void XrImmersiveSession::ensureQuadGeometryAndShader() {
    if (quadProgram_ != 0) return;

    GLuint vertexShader = CompileShader(GL_VERTEX_SHADER, kQuadVertexShader);
    GLuint fragmentShader = CompileShader(GL_FRAGMENT_SHADER, kQuadFragmentShader);
    if (vertexShader == 0 || fragmentShader == 0) return;

    quadProgram_ = glCreateProgram();
    glAttachShader(quadProgram_, vertexShader);
    glAttachShader(quadProgram_, fragmentShader);
    glLinkProgram(quadProgram_);
    GLint linked = 0;
    glGetProgramiv(quadProgram_, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(quadProgram_, sizeof(log), nullptr, log);
        LOGE("Shader link error: %s", log);
    }
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    quadPositionLoc_ = 0;
    quadTexCoordLoc_ = 1;
    quadSamplerLoc_ = glGetUniformLocation(quadProgram_, "uTexture");

    // Full-screen quad (2 triangles as a strip): xy in clip space, uv in texture space.
    // v=0 maps to the bitmap's first (top) row, v=1 to its last (bottom) row — Android
    // Bitmaps are stored top-down, so this should show right-side-up; flip here if a
    // device test shows it upside down.
    const float vertices[] = {
        // x,    y,    u,    v
        -1.0f, -1.0f, 0.0f, 1.0f,
        1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f, 1.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 0.0f,
    };
    glGenBuffers(1, &quadVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glGenTextures(1, &gameTexture_);
    glBindTexture(GL_TEXTURE_2D, gameTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    GLuint directVertexShader = CompileShader(GL_VERTEX_SHADER, kQuadVertexShader);
    GLuint directFragmentShader = CompileShader(GL_FRAGMENT_SHADER, kDirectQuadFragmentShader);
    if (directVertexShader != 0 && directFragmentShader != 0) {
        directQuadProgram_ = glCreateProgram();
        glAttachShader(directQuadProgram_, directVertexShader);
        glAttachShader(directQuadProgram_, directFragmentShader);
        glLinkProgram(directQuadProgram_);
        GLint directLinked = 0;
        glGetProgramiv(directQuadProgram_, GL_LINK_STATUS, &directLinked);
        if (!directLinked) {
            char log[512];
            glGetProgramInfoLog(directQuadProgram_, sizeof(log), nullptr, log);
            LOGE("Direct quad shader link error: %s", log);
        }
        directQuadPositionLoc_ = 0;
        directQuadTexCoordLoc_ = 1;
        directGameSamplerLoc_ = glGetUniformLocation(directQuadProgram_, "uGameTexture");
        directOverlaySamplerLoc_ = glGetUniformLocation(directQuadProgram_, "uOverlayTexture");
        directContentScaleLoc_ = glGetUniformLocation(directQuadProgram_, "uContentScale");
    }
    glDeleteShader(directVertexShader);
    glDeleteShader(directFragmentShader);
}

void XrImmersiveSession::uploadPendingGameFrameLocked() {
    std::lock_guard<std::mutex> lock(frameMutex_);
    if (!hasPendingFrame_) return;

    glBindTexture(GL_TEXTURE_2D, gameTexture_);
    if (pendingFrameWidth_ != gameTextureWidth_ || pendingFrameHeight_ != gameTextureHeight_) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, pendingFrameWidth_, pendingFrameHeight_, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, pendingFramePixels_.data());
        gameTextureWidth_ = pendingFrameWidth_;
        gameTextureHeight_ = pendingFrameHeight_;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, pendingFrameWidth_, pendingFrameHeight_, GL_RGBA,
                         GL_UNSIGNED_BYTE, pendingFramePixels_.data());
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    hasPendingFrame_ = false;
}

bool XrImmersiveSession::renderFrame() {
    XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    uint32_t imageIndex = 0;
    if (!XrCheck(xrAcquireSwapchainImage(swapchain_, &acquireInfo, &imageIndex),
                 "xrAcquireSwapchainImage")) {
        return false;
    }

    XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    waitInfo.timeout = XR_INFINITE_DURATION;
    xrWaitSwapchainImage(swapchain_, &waitInfo);

    ensureQuadGeometryAndShader();
    importSharedBufferIfNeeded();
    uploadPendingGameFrameLocked();

    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                            swapchainImages_[imageIndex].image, 0);
    glViewport(0, 0, kSwapchainWidth, kSwapchainHeight);

    if (hasSharedGameTexture_ && directQuadProgram_ != 0) {
        // GLRenderer wrote this frame's game image straight into sharedGameTexture_ on its own
        // thread (zero CPU copy) — gameTexture_ here now carries just the overlay (menu/HUD),
        // still fed the same way as the non-direct path (uploadPendingGameFrameLocked, above).
        static int directFrameLogCounter = 0;
        if (++directFrameLogCounter % 300 == 1) {
            LOGI("Immersive direct-render: compositing shared game texture + overlay (frame #%d)",
                 directFrameLogCounter);
        }
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(directQuadProgram_);
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
        glEnableVertexAttribArray(directQuadPositionLoc_);
        glVertexAttribPointer(directQuadPositionLoc_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                               reinterpret_cast<void *>(0));
        glEnableVertexAttribArray(directQuadTexCoordLoc_);
        glVertexAttribPointer(directQuadTexCoordLoc_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                               reinterpret_cast<void *>(2 * sizeof(float)));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sharedGameTexture_);
        glUniform1i(directGameSamplerLoc_, 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, gameTexture_);
        glUniform1i(directOverlaySamplerLoc_, 1);
        glUniform2f(directContentScaleLoc_, quadContentScaleX_.load(), quadContentScaleY_.load());
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    } else if (gameTextureWidth_ > 0 && quadProgram_ != 0) {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(quadProgram_);
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
        glEnableVertexAttribArray(quadPositionLoc_);
        glVertexAttribPointer(quadPositionLoc_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                               reinterpret_cast<void *>(0));
        glEnableVertexAttribArray(quadTexCoordLoc_);
        glVertexAttribPointer(quadTexCoordLoc_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                               reinterpret_cast<void *>(2 * sizeof(float)));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gameTexture_);
        glUniform1i(quadSamplerLoc_, 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    } else {
        // No game frame captured yet (e.g. libxrimmersive.so present but the app hasn't
        // reached ImmersiveXrActivity's capture loop yet) — flat color instead of garbage.
        glClearColor(0.05f, 0.05f, 0.08f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    xrReleaseSwapchainImage(swapchain_, &releaseInfo);
    return true;
}

void XrImmersiveSession::submitQuadLayer(XrTime predictedDisplayTime, XrSpace space,
                                          XrSwapchain swapchain, int32_t width, int32_t height,
                                          bool sessionActive) {
    if (!sessionActive) return;

    XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    // Without this flag the compositor ignores the texture's alpha channel entirely and treats
    // every pixel as fully opaque — the composited bitmap already has real alpha=0 in the
    // pointer-margin band (Kotlin side clears it to transparent before drawing the game frame
    // inset), but with no blend flag those pixels' RGB (0,0,0, i.e. black) still render at full
    // opacity, which is exactly the black border the passthrough toggle was supposed to see
    // through. This makes the quad respect its own alpha, so the passthrough layer behind it
    // (when active) shows through those pixels instead.
    quad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    quad.space = space;
    quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    quad.subImage.swapchain = swapchain;
    quad.subImage.imageRect.offset.x = 0;
    quad.subImage.imageRect.offset.y = 0;
    quad.subImage.imageRect.extent.width = width;
    quad.subImage.imageRect.extent.height = height;
    quad.subImage.imageArrayIndex = 0;
    // Orbit around the player on BOTH axes instead of sliding on a flat plane: quadPosX_/quadPosY_
    // are tangential (left/right, up/down) offsets and quadPosZ_ is always -distance (Kotlin
    // never sends a raw Cartesian z), so distance = -quadPosZ_ is the orbit radius, yaw =
    // atan2(quadPosX_, distance) is the angle swept left/right, and pitch = atan2(quadPosY_,
    // distance) is the angle swept up/down. Position moves on the SPHERE of that radius (not a
    // flat plane), and orientation rotates to keep facing the player at every point on it —
    // moving the panel in any direction now feels like walking it around the player rather than
    // sliding a flat sign past them, including vertically (an earlier version only did this for
    // yaw/left-right, leaving up/down as a plain Cartesian translation that visibly turned away).
    //
    // Derivation: at yaw=pitch=0 (centered), identity orientation already faces the player (the
    // pre-existing, known-working baseline) — so the quad's local +Z axis is its "faces the
    // player" normal, and local +X/+Y are its right/up axes. Composing a yaw (about world Y) with
    // a pitch (about the quad's own, already-yawed local X) and solving for "local +Z ends up
    // pointing from the new position back to the player" gives position = distance *
    // (sin(yaw)cos(pitch), sin(pitch), -cos(yaw)cos(pitch)) and orientation quaternion (using
    // half-angles cy/sy for yaw, cx/sx for pitch, with the same yaw-sign convention as the
    // yaw-only case): (cy*sx, -sy*cx, sy*sx, cy*cx) — reduces exactly to the old yaw-only
    // quaternion (0, -sy, 0, cy) when pitch=0, confirming the two derivations agree.
    const float distance = -quadPosZ_.load();
    const float tangentialX = quadPosX_.load();
    const float tangentialY = quadPosY_.load();
    const float yaw = distance > 0.0001f ? std::atan2(tangentialX, distance) : 0.0f;
    const float pitch = distance > 0.0001f ? std::atan2(tangentialY, distance) : 0.0f;
    const float cy = std::cos(yaw * 0.5f);
    const float sy = std::sin(yaw * 0.5f);
    const float cx = std::cos(pitch * 0.5f);
    const float sx = std::sin(pitch * 0.5f);
    quad.pose = IdentityPose();
    quad.pose.orientation.x = cy * sx;
    quad.pose.orientation.y = -sy * cx;
    quad.pose.orientation.z = sy * sx;
    quad.pose.orientation.w = cy * cx;
    quad.pose.position.x = distance * std::sin(yaw) * std::cos(pitch);
    quad.pose.position.y = distance * std::sin(pitch);
    quad.pose.position.z = -distance * std::cos(yaw) * std::cos(pitch);
    quad.size.width = quadWidth_.load();
    quad.size.height = quadHeight_.load();

    // Unlike the quad, a passthrough layer isn't anchored to a location in the world — it
    // replaces the whole environment/background — so per Meta's own samples it takes
    // XR_NULL_HANDLE here, not a real reference space. Passing our local reference space (a
    // valid, non-null XrSpace meant for spatially-anchored layers like the quad) is a plausible
    // cause of the xrEndFrame validation failure (-1) seen on every frame once passthrough was
    // toggled on, previously misdiagnosed as an unsupported blend mode.
    XrCompositionLayerPassthroughFB passthroughLayer{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
    passthroughLayer.space = XR_NULL_HANDLE;
    passthroughLayer.layerHandle = passthroughLayer_;

    std::vector<const XrCompositionLayerBaseHeader *> layers;
    if (passthroughActive_ && passthroughLayer_ != XR_NULL_HANDLE) {
        layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&passthroughLayer));
    }
    layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad));

    // Passthrough shows the real world through gaps, so the "background" must be
    // ADDITIVE/ALPHA_BLEND rather than OPAQUE while it's active, or it'll just look black.
    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = predictedDisplayTime;
    endInfo.environmentBlendMode = passthroughActive_ ? XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND
                                                        : XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    endInfo.layerCount = static_cast<uint32_t>(layers.size());
    endInfo.layers = layers.data();
    XrCheck(xrEndFrame(session_, &endInfo), "xrEndFrame");
}

void XrImmersiveSession::setQuadTransform(float x, float y, float z, float width, float height,
                                           float contentScaleX, float contentScaleY) {
    quadPosX_.store(x);
    quadPosY_.store(y);
    quadPosZ_.store(z);
    quadWidth_.store(width);
    quadHeight_.store(height);
    quadContentScaleX_.store(contentScaleX);
    quadContentScaleY_.store(contentScaleY);
}

void XrImmersiveSession::setPassthroughEnabled(bool enabled) {
    passthroughRequested_.store(enabled);
}

void XrImmersiveSession::setupPassthrough() {
    auto resolve = [this](const char *name, PFN_xrVoidFunction *out) {
        xrGetInstanceProcAddr(instance_, name, out);
    };
    resolve("xrCreatePassthroughFB", reinterpret_cast<PFN_xrVoidFunction *>(&xrCreatePassthroughFB_));
    resolve("xrDestroyPassthroughFB", reinterpret_cast<PFN_xrVoidFunction *>(&xrDestroyPassthroughFB_));
    resolve("xrPassthroughStartFB", reinterpret_cast<PFN_xrVoidFunction *>(&xrPassthroughStartFB_));
    resolve("xrPassthroughPauseFB", reinterpret_cast<PFN_xrVoidFunction *>(&xrPassthroughPauseFB_));
    resolve("xrCreatePassthroughLayerFB",
            reinterpret_cast<PFN_xrVoidFunction *>(&xrCreatePassthroughLayerFB_));
    resolve("xrDestroyPassthroughLayerFB",
            reinterpret_cast<PFN_xrVoidFunction *>(&xrDestroyPassthroughLayerFB_));
    resolve("xrPassthroughLayerResumeFB",
            reinterpret_cast<PFN_xrVoidFunction *>(&xrPassthroughLayerResumeFB_));
    resolve("xrPassthroughLayerPauseFB",
            reinterpret_cast<PFN_xrVoidFunction *>(&xrPassthroughLayerPauseFB_));

    if (xrCreatePassthroughFB_ == nullptr || xrCreatePassthroughLayerFB_ == nullptr) {
        LOGE("XR_FB_passthrough advertised but function pointers missing — disabling");
        passthroughExtensionAvailable_ = false;
        return;
    }

    XrPassthroughCreateInfoFB passthroughCreateInfo{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
    if (!XrCheck(xrCreatePassthroughFB_(session_, &passthroughCreateInfo, &passthrough_),
                 "xrCreatePassthroughFB")) {
        passthroughExtensionAvailable_ = false;
        return;
    }

    XrPassthroughLayerCreateInfoFB layerCreateInfo{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
    layerCreateInfo.passthrough = passthrough_;
    layerCreateInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
    if (!XrCheck(xrCreatePassthroughLayerFB_(session_, &layerCreateInfo, &passthroughLayer_),
                 "xrCreatePassthroughLayerFB")) {
        passthroughExtensionAvailable_ = false;
        return;
    }

    LOGI("Passthrough initialized (inactive until toggled on)");
}

void XrImmersiveSession::teardownPassthrough() {
    if (passthroughLayer_ != XR_NULL_HANDLE && xrDestroyPassthroughLayerFB_ != nullptr) {
        xrDestroyPassthroughLayerFB_(passthroughLayer_);
        passthroughLayer_ = XR_NULL_HANDLE;
    }
    if (passthrough_ != XR_NULL_HANDLE && xrDestroyPassthroughFB_ != nullptr) {
        xrDestroyPassthroughFB_(passthrough_);
        passthrough_ = XR_NULL_HANDLE;
    }
}

void XrImmersiveSession::applyPendingPassthroughState() {
    if (!passthroughExtensionAvailable_) return;
    if (!alphaBlendSupported_) {
        passthroughRequested_.store(false);
        return;
    }
    const bool requested = passthroughRequested_.load();
    if (requested == passthroughActive_) return;

    if (requested) {
        // Only report "active" (and thus switch to ALPHA_BLEND + attach the passthrough layer in
        // submitQuadLayer) if the runtime actually started passthrough — otherwise we'd flip to
        // ALPHA_BLEND with no real passthrough image behind it, which just looks like a black
        // screen (nothing bridges the gap where OPAQUE mode's implicit skybox used to be).
        const bool started = XrCheck(xrPassthroughStartFB_(passthrough_), "xrPassthroughStartFB") &&
                              XrCheck(xrPassthroughLayerResumeFB_(passthroughLayer_), "xrPassthroughLayerResumeFB");
        if (!started) {
            LOGE("Passthrough failed to start — leaving environment opaque");
            passthroughRequested_.store(false);
        }
        passthroughActive_ = started;
    } else {
        XrCheck(xrPassthroughLayerPauseFB_(passthroughLayer_), "xrPassthroughLayerPauseFB");
        XrCheck(xrPassthroughPauseFB_(passthrough_), "xrPassthroughPauseFB");
        passthroughActive_ = false;
    }
}

void XrImmersiveSession::syncControllerInputs(XrTime predictedDisplayTime) {
    XrActiveActionSet activeActionSet{actionSet_, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeActionSet;
    if (!XrCheck(xrSyncActions(session_, &syncInfo), "xrSyncActions")) return;

    auto getBool = [this](XrAction action) {
        XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
        info.action = action;
        XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
        xrGetActionStateBoolean(session_, &info, &state);
        return state.isActive && state.currentState;
    };
    auto getFloat = [this](XrAction action) {
        XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
        info.action = action;
        XrActionStateFloat state{XR_TYPE_ACTION_STATE_FLOAT};
        xrGetActionStateFloat(session_, &info, &state);
        return state.isActive ? state.currentState : 0.0f;
    };
    auto getVector2 = [this](XrAction action, float *x, float *y) {
        XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
        info.action = action;
        XrActionStateVector2f state{XR_TYPE_ACTION_STATE_VECTOR2F};
        xrGetActionStateVector2f(session_, &info, &state);
        if (state.isActive) {
            *x = state.currentState.x;
            *y = state.currentState.y;
        } else {
            *x = *y = 0.0f;
        }
    };

    InputSnapshot next;
    next.buttons = 0;
    if (getBool(buttonXAction_)) next.buttons |= (1u << BUTTON_X);
    if (getBool(buttonYAction_)) next.buttons |= (1u << BUTTON_Y);
    if (getBool(buttonAAction_)) next.buttons |= (1u << BUTTON_A);
    if (getBool(buttonBAction_)) next.buttons |= (1u << BUTTON_B);
    if (getFloat(squeezeLAction_) > 0.5f) next.buttons |= (1u << BUTTON_LB);
    if (getFloat(squeezeRAction_) > 0.5f) next.buttons |= (1u << BUTTON_RB);

    // Menu/Start button: a short press still forwards a normal Start press to the game (for
    // the game's own pause/settings menu); holding it for 600ms instead opens the immersive
    // quick menu. The raw OpenXR click can bounce (several false->true transitions within a
    // single physical press), so lastMenuDebouncedPressed_ applies a short bounce-tolerance
    // window before measuring hold duration — a millisecond-scale dropout mid-hold must not
    // reset the 600ms timer.
    const auto nowMenu = std::chrono::steady_clock::now();
    const bool rawMenuPressed = getBool(menuLAction_);
    if (rawMenuPressed) lastMenuRawTrueTime_ = nowMenu;
    const bool debouncedMenuPressed = rawMenuPressed ||
        (nowMenu - lastMenuRawTrueTime_) < std::chrono::milliseconds(50);
    if (debouncedMenuPressed && !lastMenuDebouncedPressed_) {
        menuHoldStartTime_ = nowMenu;
        menuLongPressTriggered_ = false;
    }
    if (debouncedMenuPressed) {
        if (!menuLongPressTriggered_ &&
            (nowMenu - menuHoldStartTime_) >= std::chrono::milliseconds(600)) {
            next.quickMenuClicked = true;
            menuLongPressTriggered_ = true;
        }
    } else if (lastMenuDebouncedPressed_ && !menuLongPressTriggered_) {
        // Released before reaching the long-press threshold: forward a clean momentary Start
        // press to the game, same as a real controller's Menu button.
        menuDebouncedActive_ = true;
        menuPressStartTime_ = nowMenu;
    }
    lastMenuDebouncedPressed_ = debouncedMenuPressed;
    next.menuButtonHeld = debouncedMenuPressed;
    if (menuDebouncedActive_) {
        if ((nowMenu - menuPressStartTime_) < std::chrono::milliseconds(150)) {
            next.buttons |= (1u << BUTTON_START);
        } else {
            menuDebouncedActive_ = false;
        }
    }

    const bool l3Pressed = getBool(thumbstickLClickAction_);
    const bool r3Pressed = getBool(thumbstickRClickAction_);
    if (l3Pressed) next.buttons |= (1u << BUTTON_L3);
    if (r3Pressed) next.buttons |= (1u << BUTTON_R3);

    next.triggerL = getFloat(triggerLAction_);
    next.triggerR = getFloat(triggerRAction_);
    getVector2(thumbstickLAction_, &next.leftX, &next.leftY);
    getVector2(thumbstickRAction_, &next.rightX, &next.rightY);
    // Confirmed by testing: this OpenXR runtime's thumbstick Y is inverted relative to what
    // WinHandler's shared-memory XInput buffer expects (positive Y = down here, but the buffer
    // wants positive Y = up, same as a real Xbox controller) — up/down were swapped in-game.
    // Only the left stick was confirmed broken; negating both since they're read the same way
    // and almost certainly share the same convention.
    next.leftY = -next.leftY;
    next.rightY = -next.rightY;
    // No synthetic dpad: on a real Xbox controller the left stick and the d-pad are two
    // separate physical controls, and Touch controllers have no physical d-pad at all. Faking
    // one out of the stick's direction fired alongside the real analog values, which is why
    // stick movement felt like the d-pad instead of a proper analog Xbox stick. leftX/leftY/
    // rightX/rightY carry the real analog values; dpadUp/Down/Left/Right stay false.

    // XR pointer-mode toggle: double-click of either thumbstick (L3 or R3) — checked
    // independently rather than requiring both at once, since it's easier to double-click one
    // stick with one hand than to coordinate a multi-button chord. Reserved for grabbing the
    // quad's corner (resize) / top-bottom bar (reposition), and later a real VR game's own
    // pointer needs.
    const auto now = std::chrono::steady_clock::now();
    auto detectDoubleClick = [&](bool pressed, bool &lastPressed,
                                  std::chrono::steady_clock::time_point &lastClickTime,
                                  int &clickCount) {
        bool triggered = false;
        if (pressed && !lastPressed) {
            clickCount = (now - lastClickTime) < std::chrono::milliseconds(400) ? clickCount + 1 : 1;
            lastClickTime = now;
            if (clickCount >= 2) {
                triggered = true;
                clickCount = 0;
            }
        }
        lastPressed = pressed;
        return triggered;
    };
    const bool leftDoubleClick = detectDoubleClick(l3Pressed, lastL3Pressed_, lastL3ClickTime_, l3ClickCount_);
    const bool rightDoubleClick = detectDoubleClick(r3Pressed, lastR3Pressed_, lastR3ClickTime_, r3ClickCount_);
    next.pointerModeToggled = leftDoubleClick || rightDoubleClick;

    // Aim-pose ray for each hand, in the same LOCAL space the quad transform lives in — lets
    // Kotlin do a simple ray/plane intersection against the quad's known rectangle instead of
    // needing any OpenXR types on that side.
    auto rotateForward = [](const XrQuaternionf &q) {
        // Standard quaternion-rotate-vector for v=(0,0,-1) (OpenXR's forward), expanded by hand
        // since -1 in one component simplifies most of the cross-product terms away.
        const float vx = 0.0f, vy = 0.0f, vz = -1.0f;
        const float tx = 2.0f * (q.y * vz - q.z * vy);
        const float ty = 2.0f * (q.z * vx - q.x * vz);
        const float tz = 2.0f * (q.x * vy - q.y * vx);
        const float rx = vx + q.w * tx + (q.y * tz - q.z * ty);
        const float ry = vy + q.w * ty + (q.z * tx - q.x * tz);
        const float rz = vz + q.w * tz + (q.x * ty - q.y * tx);
        return std::array<float, 3>{rx, ry, rz};
    };

    XrSpaceLocation leftLoc{XR_TYPE_SPACE_LOCATION};
    XrSpaceLocation rightLoc{XR_TYPE_SPACE_LOCATION};
    constexpr XrSpaceLocationFlags kPoseValidBits =
        XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    const bool haveLeft = aimSpaceLeft_ != XR_NULL_HANDLE &&
        XrCheck(xrLocateSpace(aimSpaceLeft_, localSpace_, predictedDisplayTime, &leftLoc), "xrLocateSpace(left)") &&
        (leftLoc.locationFlags & kPoseValidBits) == kPoseValidBits;
    const bool haveRight = aimSpaceRight_ != XR_NULL_HANDLE &&
        XrCheck(xrLocateSpace(aimSpaceRight_, localSpace_, predictedDisplayTime, &rightLoc), "xrLocateSpace(right)") &&
        (rightLoc.locationFlags & kPoseValidBits) == kPoseValidBits;
    next.handPosesValid = haveLeft && haveRight;
    if (haveLeft) {
        next.leftHandPosX = leftLoc.pose.position.x;
        next.leftHandPosY = leftLoc.pose.position.y;
        next.leftHandPosZ = leftLoc.pose.position.z;
        const auto fwd = rotateForward(leftLoc.pose.orientation);
        next.leftHandFwdX = fwd[0];
        next.leftHandFwdY = fwd[1];
        next.leftHandFwdZ = fwd[2];
    }
    if (haveRight) {
        next.rightHandPosX = rightLoc.pose.position.x;
        next.rightHandPosY = rightLoc.pose.position.y;
        next.rightHandPosZ = rightLoc.pose.position.z;
        const auto fwd = rotateForward(rightLoc.pose.orientation);
        next.rightHandFwdX = fwd[0];
        next.rightHandFwdY = fwd[1];
        next.rightHandFwdZ = fwd[2];
    }

    // Rate-limited raw-value dump (~every 0.5s at 90Hz) — temporary, for diagnosing controller
    // mapping reports; safe to remove once the analog stick mapping is confirmed solid.
    if ((syncFrameCounter_++ % 45) == 0) {
        LOGI("controller raw: leftX=%.2f leftY=%.2f rightX=%.2f rightY=%.2f buttons=0x%03x",
             next.leftX, next.leftY, next.rightX, next.rightY, next.buttons);
    }

    std::lock_guard<std::mutex> lock(snapshotMutex_);
    // Preserve rising-edge flags that a concurrent pollSnapshot() hasn't consumed yet.
    next.quickMenuClicked = next.quickMenuClicked || snapshot_.quickMenuClicked;
    next.pointerModeToggled = next.pointerModeToggled || snapshot_.pointerModeToggled;
    snapshot_ = next;
}

void XrImmersiveSession::teardown() {
    teardownPassthrough();
    if (gameTexture_ != 0) {
        glDeleteTextures(1, &gameTexture_);
        gameTexture_ = 0;
    }
    if (quadVbo_ != 0) {
        glDeleteBuffers(1, &quadVbo_);
        quadVbo_ = 0;
    }
    if (quadProgram_ != 0) {
        glDeleteProgram(quadProgram_);
        quadProgram_ = 0;
    }
    if (directQuadProgram_ != 0) {
        glDeleteProgram(directQuadProgram_);
        directQuadProgram_ = 0;
    }
    if (sharedGameTexture_ != 0) {
        glDeleteTextures(1, &sharedGameTexture_);
        sharedGameTexture_ = 0;
    }
    if (sharedGameImage_ != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(eglDisplay_, sharedGameImage_);
        sharedGameImage_ = EGL_NO_IMAGE_KHR;
    }
    {
        std::lock_guard<std::mutex> lock(sharedBufferMutex_);
        if (pendingSharedBuffer_ != nullptr) {
            AHardwareBuffer_release(pendingSharedBuffer_);
            pendingSharedBuffer_ = nullptr;
        }
    }
    if (framebuffer_ != 0) {
        glDeleteFramebuffers(1, &framebuffer_);
        framebuffer_ = 0;
    }
    if (swapchain_ != XR_NULL_HANDLE) {
        xrDestroySwapchain(swapchain_);
        swapchain_ = XR_NULL_HANDLE;
    }
    if (localSpace_ != XR_NULL_HANDLE) {
        xrDestroySpace(localSpace_);
        localSpace_ = XR_NULL_HANDLE;
    }
    if (aimSpaceLeft_ != XR_NULL_HANDLE) {
        xrDestroySpace(aimSpaceLeft_);
        aimSpaceLeft_ = XR_NULL_HANDLE;
    }
    if (aimSpaceRight_ != XR_NULL_HANDLE) {
        xrDestroySpace(aimSpaceRight_);
        aimSpaceRight_ = XR_NULL_HANDLE;
    }
    if (actionSet_ != XR_NULL_HANDLE) {
        xrDestroyActionSet(actionSet_);
        actionSet_ = XR_NULL_HANDLE;
    }
    if (session_ != XR_NULL_HANDLE) {
        xrDestroySession(session_);
        session_ = XR_NULL_HANDLE;
    }
    if (instance_ != XR_NULL_HANDLE) {
        xrDestroyInstance(instance_);
        instance_ = XR_NULL_HANDLE;
    }
    if (eglContext_ != EGL_NO_CONTEXT) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(eglDisplay_, eglContext_);
        eglContext_ = EGL_NO_CONTEXT;
    }
    if (eglPbufferSurface_ != EGL_NO_SURFACE) {
        eglDestroySurface(eglDisplay_, eglPbufferSurface_);
        eglPbufferSurface_ = EGL_NO_SURFACE;
    }
}

}  // namespace xrimmersive
