#ifndef BLIT_CONVERTER_H
#define BLIT_CONVERTER_H

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <unordered_map>

// BlitConverter owns an internal worker thread that holds the EGL context for
// its lifetime. All EGL/GL operations are posted to that thread, so callers
// may construct, use, and destroy this object from any thread.
//
// convertBGRAtoRGBA() returns a std::future<int> that resolves to a native
// release-fence FD (>= 0) on success, or -1 on failure. The future is ready
// once the GPU draw is submitted and the release fence has been dup'd.
//
// Supported source layouts:
//   1) format=R8G8B8A8_UNORM, producer wrote BGRA bytes: shader swaps R/B.
//   2) format=B8G8R8A8_UNORM, genuine BGRA storage: sampler performs format
//      interpretation, shader copies logical RGBA without swapping.
//
// Destination must report R8G8B8A8_UNORM.
// Both acquire-fence FDs are always consumed by the posted task.
class BlitConverter {
public:
    explicit BlitConverter(std::size_t expectedRegisteredBuffers = 8);
    ~BlitConverter();

    BlitConverter(const BlitConverter&) = delete;
    BlitConverter& operator=(const BlitConverter&) = delete;

    // Initializes EGL/GL on the worker thread. Calling this during renderer
    // creation moves context creation and shader compilation off the first
    // real frame. It is safe to call more than once.
    bool initialize();

    // Registers a persistent EGLImage/texture import. Registration is
    // idempotent for the same AHardwareBuffer pointer.
    bool registerBuffer(AHardwareBuffer* buffer);
    void unregisterBuffer(AHardwareBuffer* buffer);

    // Posts a BGRA→RGBA conversion. Returns a future for the release-fence FD.
    // Both acquire FDs are consumed by the posted task regardless of outcome.
    std::future<int> convertBGRAtoRGBA(AHardwareBuffer* source,
                                       AHardwareBuffer* destinationRGBA,
                                       int sourceAcquireFenceFd,
                                       int destinationAcquireFenceFd = -1);

    // Drains the queue and shuts the worker thread down.
    // Subsequent posts are rejected and their futures resolve to -1.
    void shutdown();

private:
    struct ImportedBuffer {
        AHardwareBuffer* buffer = nullptr;
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        GLuint texture = 0;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t format = 0;
        bool framebufferValidated = false;
    };

    // ---- Worker thread ----
    void workerMain();
    bool post(std::function<void()> task);

    std::thread             workerThread_;
    std::mutex              queueMutex_;
    std::condition_variable queueCv_;
    std::deque<std::function<void()>> taskQueue_;
    bool                    stopping_ = false;

    // ---- GL/EGL (worker thread only) ----
    bool initializeGL();
    bool ensureCurrent();
    void bindPrivateState();
    void cleanupGL();

    bool importBuffer(AHardwareBuffer* buffer, ImportedBuffer& imported);
    void destroyImportedBuffer(ImportedBuffer& imported);
    ImportedBuffer* findRegisteredBuffer(AHardwareBuffer* buffer);

    bool consumeAcquireFence(int& fenceFd, bool& serverWaitQueued);
    static void waitAndCloseFenceFd(int& fenceFd);
    int  createReleaseFence();

    // Actual per-frame work, called on the worker thread.
    int doConvert(AHardwareBuffer* source, AHardwareBuffer* destination,
                  int sourceAcquireFenceFd, int destinationAcquireFenceFd);

    static bool checkGl(const char* operation);
    static bool checkEgl(const char* operation);

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;

    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint fbo_ = 0;
    GLint  sourceSamplerLocation_ = -1;
    GLint  swapRedBlueLocation_   = -1;

    GLuint  currentFramebufferTexture_ = 0;
    GLsizei currentViewportWidth_      = -1;
    GLsizei currentViewportHeight_     = -1;

    bool initialized_        = false;
    bool supportsServerWait_ = false;

    std::unordered_map<AHardwareBuffer*, ImportedBuffer> registeredBuffers_;
};

#endif // BLIT_CONVERTER_H
