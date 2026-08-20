#pragma once

#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#define EGL_EGLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>

#define XR_USE_PLATFORM_ANDROID 1
#define XR_USE_GRAPHICS_API_OPENGL_ES 1
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

namespace xrimmersive {

// Bit indices match XrGamepadBridge.kt / WinHandler.sendVirtualGamepadState's Xbox layout.
enum ButtonBit {
    BUTTON_A = 0,
    BUTTON_B = 1,
    BUTTON_X = 2,
    BUTTON_Y = 3,
    BUTTON_LB = 4,
    BUTTON_RB = 5,
    BUTTON_BACK = 6,
    BUTTON_START = 7,
    BUTTON_L3 = 8,
    BUTTON_R3 = 9,
};

// Snapshot of one frame's controller state, written by the OpenXR thread and
// polled from Kotlin. Deliberately plain-old-data so it can be copied under a
// single mutex without touching any JNI/OpenXR types.
struct InputSnapshot {
    uint32_t buttons = 0;
    // No d-pad: Touch controllers have no physical one, and faking it from the thumbstick
    // direction fought with the real analog values (see syncControllerInputs()).
    float leftX = 0.0f;
    float leftY = 0.0f;
    float rightX = 0.0f;
    float rightY = 0.0f;
    float triggerL = 0.0f;
    float triggerR = 0.0f;
    // Rising-edge only: holding the Touch controllers' Menu button for 600ms summons
    // GameNative's own quick menu. A short press of the same button is still forwarded as a
    // normal gamepad Start press (games use it for their own pause/settings menu).
    bool quickMenuClicked = false;
    // Debounced level (not edge) state of the same Menu button — true for the entire physical
    // hold, from press to release, regardless of whether it's still building up to the 600ms
    // threshold or has already fired quickMenuClicked. Lets Kotlin suppress menu-navigation
    // reads (thumbstick-as-dpad, click) while the hand that's holding this button may still be
    // resting on/near the thumbstick — see handleMenuNavigation's suppression kdoc.
    bool menuButtonHeld = false;

    // Rising-edge only: double-clicking either thumbstick (L3 or R3) toggles "XR pointer
    // mode" — a laser-pointer-driven direct manipulation scheme for the quad (grab a corner to
    // resize, grab the top/bottom bar to reposition), reserved with a real VR game's own
    // pointer needs in mind later.
    bool pointerModeToggled = false;
    // Aim-pose ray for each hand (world position + normalized forward direction, in the same
    // LOCAL reference space the quad transform is expressed in) — only meaningful pointer-mode.
    bool handPosesValid = false;
    float leftHandPosX = 0.0f, leftHandPosY = 0.0f, leftHandPosZ = 0.0f;
    float leftHandFwdX = 0.0f, leftHandFwdY = 0.0f, leftHandFwdZ = -1.0f;
    float rightHandPosX = 0.0f, rightHandPosY = 0.0f, rightHandPosZ = 0.0f;
    float rightHandFwdX = 0.0f, rightHandFwdY = 0.0f, rightHandFwdZ = -1.0f;
};

// Owns the OpenXR instance/session and its dedicated frame-loop thread.
class XrImmersiveSession {
public:
    bool initialize(JavaVM *vm, jobject activityRef);
    void requestStop();
    void join();

    InputSnapshot pollSnapshot();

    // Called from the JNI bridge with a freshly PixelCopy'd RGBA_8888 frame of the game's
    // actual rendered output (see ImmersiveXrActivity's capture loop). Copies into a
    // double-buffered CPU-side slot; the render thread uploads it to the GPU on its own.
    // strideBytes is the source's row pitch (AndroidBitmapInfo::stride), which is not always
    // width*4 — the copy repacks each row so the pending buffer stays tightly packed.
    void submitFrame(const uint8_t *rgbaPixels, int32_t width, int32_t height,
                      int32_t strideBytes);

    // Quad position (meters, in the LOCAL reference space; z is negative = in front of the
    // player) and size (meters). x/y/z are reinterpreted as (tangential X offset, tangential Y
    // offset, -radius) rather than plain Cartesian coordinates — see submitQuadLayer's kdoc — so
    // the quad orbits the player on both axes and stays facing them as x and/or y move away from
    // 0, instead of sliding on a flat plane and turning away. contentScaleX/Y are content-half-extent / quad-half-extent
    // per axis (1.0 if the quad has no margin band beyond its content, e.g. the non-direct-render
    // path where the composited bitmap already has the margin baked in) — only meaningful for
    // the direct-render path's shader, which needs to know how much of the quad's UV space is
    // real game texture vs. margin-only overlay (see kDirectQuadFragmentShader). Safe to call
    // from any thread — read by the render thread at the top of each frame.
    void setQuadTransform(float x, float y, float z, float width, float height,
                          float contentScaleX, float contentScaleY);

    // Enables/disables the XR_FB_passthrough layer behind the quad. No-op (logged) if the
    // runtime doesn't support the extension.
    void setPassthroughEnabled(bool enabled);

    // Direct-render path — see the member comment near sharedBufferMutex_. Safe to call from
    // any thread; the actual EGLImage/texture import happens lazily on the render thread.
    // Pass the SAME AHardwareBuffer every frame (GLRenderer writes into it repeatedly) — this
    // only needs calling again if the buffer identity itself changes (e.g. a resize).
    void setSharedGameBuffer(AHardwareBuffer *buffer);

private:
    void runLoop();
    bool setupInstanceAndSession();
    void teardown();
    void pollXrEvents();
    // Returns whether a swapchain image was acquired, rendered and released — false means the
    // caller must NOT submit a layer referencing it.
    bool renderFrame();
    void syncControllerInputs(XrTime predictedDisplayTime);
    void submitQuadLayer(XrTime predictedDisplayTime, XrSpace space, XrSwapchain swapchain,
                          int32_t width, int32_t height, bool sessionActive);
    void ensureQuadGeometryAndShader();
    void uploadPendingGameFrameLocked();
    void setupPassthrough();
    void teardownPassthrough();
    void applyPendingPassthroughState();

    JavaVM *vm_ = nullptr;
    jobject activityRef_ = nullptr;

    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId systemId_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace localSpace_ = XR_NULL_HANDLE;
    XrSwapchain swapchain_ = XR_NULL_HANDLE;
    XrSessionState sessionState_ = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning_ = false;

    // Reverted back to 1280x720 — bumping this to 1920x1080 (2.25x the pixels) to reduce
    // pointer-margin softening measurably made overall performance worse (confirmed by the user
    // immediately after that change), and performance matters far more than this margin's
    // sharpness. Revisit only alongside real perf headroom, not before.
    static constexpr int32_t kSwapchainWidth = 1280;
    static constexpr int32_t kSwapchainHeight = 720;

    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglPbufferSurface_ = EGL_NO_SURFACE;
    EGLConfig eglConfig_ = nullptr;
    GLuint framebuffer_ = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> swapchainImages_;

    // Textured quad used to draw the captured game frame into the swapchain image.
    GLuint gameTexture_ = 0;
    GLuint quadProgram_ = 0;
    GLuint quadVbo_ = 0;
    GLint quadPositionLoc_ = -1;
    GLint quadTexCoordLoc_ = -1;
    GLint quadSamplerLoc_ = -1;
    int32_t gameTextureWidth_ = 0;
    int32_t gameTextureHeight_ = 0;

    // Direct-render path (setSharedGameBuffer): the game (GLRenderer, on its own thread/EGL
    // context) writes straight into an AHardwareBuffer-backed texture instead of us
    // screen-scraping its finished frame via PixelCopy. importSharedBufferIfNeeded() imports
    // that same buffer a second time, into THIS thread's own context (AHardwareBuffer memory is
    // shareable across contexts/processes; the texture/EGLImage wrapping it is not, so each side
    // needs its own). When active, gameTexture_ above is repurposed to carry just the overlay
    // layer (menu/HUD/Resume button — still CPU-composited, see uploadPendingGameFrameLocked)
    // instead of the full composited frame, and the two are blended in directQuadProgram_.
    std::mutex sharedBufferMutex_;
    AHardwareBuffer *pendingSharedBuffer_ = nullptr;
    bool sharedBufferChanged_ = false;
    EGLImageKHR sharedGameImage_ = EGL_NO_IMAGE_KHR;
    GLuint sharedGameTexture_ = 0;
    bool hasSharedGameTexture_ = false;
    GLuint directQuadProgram_ = 0;
    GLint directQuadPositionLoc_ = -1;
    GLint directQuadTexCoordLoc_ = -1;
    GLint directGameSamplerLoc_ = -1;
    GLint directOverlaySamplerLoc_ = -1;
    GLint directContentScaleLoc_ = -1;

    void importSharedBufferIfNeeded();

    // Latest captured game frame, written by submitFrame() (any thread) and consumed by
    // the render thread. Separate from snapshotMutex_ — controller input and video frames
    // are unrelated and shouldn't contend on the same lock.
    std::mutex frameMutex_;
    std::vector<uint8_t> pendingFramePixels_;
    int32_t pendingFrameWidth_ = 0;
    int32_t pendingFrameHeight_ = 0;
    bool hasPendingFrame_ = false;

    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrAction buttonAAction_ = XR_NULL_HANDLE;
    XrAction buttonBAction_ = XR_NULL_HANDLE;
    XrAction buttonXAction_ = XR_NULL_HANDLE;
    XrAction buttonYAction_ = XR_NULL_HANDLE;
    XrAction squeezeLAction_ = XR_NULL_HANDLE;
    XrAction squeezeRAction_ = XR_NULL_HANDLE;
    XrAction triggerLAction_ = XR_NULL_HANDLE;
    XrAction triggerRAction_ = XR_NULL_HANDLE;
    XrAction thumbstickLAction_ = XR_NULL_HANDLE;
    XrAction thumbstickRAction_ = XR_NULL_HANDLE;
    XrAction thumbstickLClickAction_ = XR_NULL_HANDLE;
    XrAction thumbstickRClickAction_ = XR_NULL_HANDLE;
    XrAction menuLAction_ = XR_NULL_HANDLE;
    uint64_t syncFrameCounter_ = 0;

    // XR pointer-mode toggle: double-click of either thumbstick (L3 or R3) — see
    // InputSnapshot::pointerModeToggled.
    XrAction aimPoseLeftAction_ = XR_NULL_HANDLE;
    XrAction aimPoseRightAction_ = XR_NULL_HANDLE;
    XrSpace aimSpaceLeft_ = XR_NULL_HANDLE;
    XrSpace aimSpaceRight_ = XR_NULL_HANDLE;
    bool lastL3Pressed_ = false;
    std::chrono::steady_clock::time_point lastL3ClickTime_;
    int l3ClickCount_ = 0;
    bool lastR3Pressed_ = false;
    std::chrono::steady_clock::time_point lastR3ClickTime_;
    int r3ClickCount_ = 0;

    // Debounce state for the Menu/Start button. A short press still forwards a normal Start
    // press to the game (menuDebouncedActive_/menuPressStartTime_); holding it for 600ms opens
    // the immersive quick menu instead (see quickMenuClicked in the .cpp). The raw OpenXR click
    // can bounce (several false->true transitions within a single physical press), so
    // lastMenuDebouncedPressed_/lastMenuRawTrueTime_ apply a short bounce-tolerance window
    // before measuring hold duration.
    std::chrono::steady_clock::time_point lastMenuRawTrueTime_;
    bool lastMenuDebouncedPressed_ = false;
    std::chrono::steady_clock::time_point menuHoldStartTime_;
    bool menuLongPressTriggered_ = false;
    bool menuDebouncedActive_ = false;
    std::chrono::steady_clock::time_point menuPressStartTime_;

    std::thread thread_;
    std::atomic<bool> stopRequested_{false};

    std::mutex snapshotMutex_;
    InputSnapshot snapshot_;

    // Quad transform — defaults match the original hardcoded values (2m in front, 16:9,
    // 1.6m wide). Atomics: written from Kotlin/JNI callers, read from the render thread.
    std::atomic<float> quadPosX_{0.0f};
    std::atomic<float> quadPosY_{0.0f};
    std::atomic<float> quadPosZ_{-2.0f};
    std::atomic<float> quadWidth_{1.6f};
    std::atomic<float> quadHeight_{0.9f};
    std::atomic<float> quadContentScaleX_{1.0f};
    std::atomic<float> quadContentScaleY_{1.0f};

    // Passthrough (XR_FB_passthrough). All the passthrough* function pointers are resolved
    // once in setupInstanceAndSession() and are null if the runtime doesn't support the
    // extension, in which case setPassthroughEnabled() is a no-op.
    bool passthroughExtensionAvailable_ = false;
    // Whether this runtime/view-config actually lists ALPHA_BLEND in
    // xrEnumerateEnvironmentBlendModes — requesting an unlisted blend mode in xrEndFrame is a
    // validation error (observed: xrEndFrame failing with -1 on every frame once passthrough
    // was toggled), so passthrough is refused entirely if this is false.
    bool alphaBlendSupported_ = false;

    // Perf/scheduling extensions — see setupInstanceAndSession()'s comment for why these were
    // missing entirely before (this is the same architecture GameNativeXR's libxr.so uses, per
    // standard OpenXR extensions, not anything proprietary).
    bool perfSettingsExtensionAvailable_ = false;
    bool threadSettingsExtensionAvailable_ = false;
    bool refreshRateExtensionAvailable_ = false;

    std::atomic<bool> passthroughRequested_{false};
    bool passthroughActive_ = false;
    XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer_ = XR_NULL_HANDLE;
    PFN_xrCreatePassthroughFB xrCreatePassthroughFB_ = nullptr;
    PFN_xrDestroyPassthroughFB xrDestroyPassthroughFB_ = nullptr;
    PFN_xrPassthroughStartFB xrPassthroughStartFB_ = nullptr;
    PFN_xrPassthroughPauseFB xrPassthroughPauseFB_ = nullptr;
    PFN_xrCreatePassthroughLayerFB xrCreatePassthroughLayerFB_ = nullptr;
    PFN_xrDestroyPassthroughLayerFB xrDestroyPassthroughLayerFB_ = nullptr;
    PFN_xrPassthroughLayerResumeFB xrPassthroughLayerResumeFB_ = nullptr;
    PFN_xrPassthroughLayerPauseFB xrPassthroughLayerPauseFB_ = nullptr;
};

}  // namespace xrimmersive
