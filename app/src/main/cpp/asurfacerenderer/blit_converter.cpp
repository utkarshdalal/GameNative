#include "blit_converter.h"

#include <android/log.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <poll.h>
#include <utility>

#define LOG_TAG "BlitConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5

#ifndef BLIT_CONVERTER_ENABLE_GL_CHECKS
#define BLIT_CONVERTER_ENABLE_GL_CHECKS 0
#endif

// Full-target invalidation usually avoids a tile-buffer load on mobile GPUs.
#ifndef BLIT_CONVERTER_INVALIDATE_DESTINATION
#define BLIT_CONVERTER_INVALIDATE_DESTINATION 1
#endif

namespace {
    PFNEGLCREATEIMAGEKHRPROC               gEglCreateImageKHR              = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC              gEglDestroyImageKHR             = nullptr;
    PFNEGLCREATESYNCKHRPROC                gEglCreateSyncKHR               = nullptr;
    PFNEGLDESTROYSYNCKHRPROC               gEglDestroySyncKHR              = nullptr;
    PFNEGLCLIENTWAITSYNCKHRPROC            gEglClientWaitSyncKHR           = nullptr;
    PFNEGLWAITSYNCKHRPROC                  gEglWaitSyncKHR                 = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC      gEglDupNativeFenceFDANDROID     = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC gEglGetNativeClientBufferANDROID = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC    gGlEGLImageTargetTexture2DOES   = nullptr;

    std::once_flag gLoadFunctionsOnce;

    void loadExtensionFunctions() {
        std::call_once(gLoadFunctionsOnce, [] {
            gEglCreateImageKHR = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
                    eglGetProcAddress("eglCreateImageKHR"));
            gEglDestroyImageKHR = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                    eglGetProcAddress("eglDestroyImageKHR"));
            gEglCreateSyncKHR = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
                    eglGetProcAddress("eglCreateSyncKHR"));
            gEglDestroySyncKHR = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
                    eglGetProcAddress("eglDestroySyncKHR"));
            gEglClientWaitSyncKHR = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
                    eglGetProcAddress("eglClientWaitSyncKHR"));
            gEglWaitSyncKHR = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
                    eglGetProcAddress("eglWaitSyncKHR"));
            gEglDupNativeFenceFDANDROID =
                    reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
                            eglGetProcAddress("eglDupNativeFenceFDANDROID"));
            gEglGetNativeClientBufferANDROID =
                    reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
                            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
            gGlEGLImageTargetTexture2DOES =
                    reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
                            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        });
    }

    bool hasExtension(const char* extensionList, const char* extension) {
        if (!extensionList || !extension || !*extension || std::strchr(extension, ' ')) {
            return false;
        }
        const std::size_t length = std::strlen(extension);
        const char* current = extensionList;
        while ((current = std::strstr(current, extension)) != nullptr) {
            const bool validStart = current == extensionList || current[-1] == ' ';
            const char end = current[length];
            const bool validEnd = end == '\0' || end == ' ';
            if (validStart && validEnd) return true;
            current += length;
        }
        return false;
    }

    bool hasGlExtension(const char* extension) {
        GLint count = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        for (GLint i = 0; i < count; ++i) {
            const char* current = reinterpret_cast<const char*>(
                    glGetStringi(GL_EXTENSIONS, i));
            if (current && std::strcmp(current, extension) == 0) return true;
        }
        return false;
    }

    constexpr char kVertexShader[] = R"GLSL(#version 300 es
void main() {
    vec2 position;
    if (gl_VertexID == 0) {
        position = vec2(-1.0, -1.0);
    } else if (gl_VertexID == 1) {
        position = vec2( 3.0, -1.0);
    } else {
        position = vec2(-1.0,  3.0);
    }
    gl_Position = vec4(position, 0.0, 1.0);
}
)GLSL";

    constexpr char kFragmentShader[] = R"GLSL(#version 300 es
precision highp float;
precision highp int;

uniform highp sampler2D uSource;
uniform lowp int uSwapRedBlue;
layout(location = 0) out highp vec4 outColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 color = texelFetch(uSource, pixel, 0);
    outColor = (uSwapRedBlue != 0) ? color.bgra : color;
}
)GLSL";

    GLuint compileShader(GLenum type, const char* source) {
        const GLuint shader = glCreateShader(type);
        if (!shader) {
            LOGE("glCreateShader failed for type 0x%x", type);
            return 0;
        }
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled == GL_TRUE) return shader;
        GLint logLength = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLength);
        if (logLength < 1) logLength = 1;
        char* log = new char[static_cast<std::size_t>(logLength)];
        glGetShaderInfoLog(shader, logLength, nullptr, log);
        LOGE("Shader compilation failed: %s", log);
        delete[] log;
        glDeleteShader(shader);
        return 0;
    }

    GLuint createProgram() {
        const GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
        if (!vs) return 0;
        const GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
        if (!fs) { glDeleteShader(vs); return 0; }
        const GLuint prog = glCreateProgram();
        if (!prog) { glDeleteShader(vs); glDeleteShader(fs); return 0; }
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = GL_FALSE;
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        if (linked == GL_TRUE) return prog;
        GLint logLength = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &logLength);
        if (logLength < 1) logLength = 1;
        char* log = new char[static_cast<std::size_t>(logLength)];
        glGetProgramInfoLog(prog, logLength, nullptr, log);
        LOGE("Program link failed: %s", log);
        delete[] log;
        glDeleteProgram(prog);
        return 0;
    }

    bool validateDescriptors(const AHardwareBuffer_Desc& src,
                             const AHardwareBuffer_Desc& dst) {
        if (src.width == 0 || src.height == 0 || src.layers != 1) {
            LOGE("Invalid source descriptor: %ux%u layers=%u",
                 src.width, src.height, src.layers);
            return false;
        }
        if (dst.width != src.width || dst.height != src.height || dst.layers != 1) {
            LOGE("Destination dimensions mismatch: src=%ux%u dst=%ux%u layers=%u",
                 src.width, src.height, dst.width, dst.height, dst.layers);
            return false;
        }
        const bool supportedSrc =
                src.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
                src.format == AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
        if (!supportedSrc || dst.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
            LOGE("Unsupported descriptors: source=%u destination=%u",
                 src.format, dst.format);
            return false;
        }
        return true;
    }

}

BlitConverter::BlitConverter(std::size_t expectedRegisteredBuffers) {
    registeredBuffers_.reserve(expectedRegisteredBuffers);
    // Start the worker thread. It will spin on taskQueue_ until stopping_.
    workerThread_ = std::thread(&BlitConverter::workerMain, this);
}

BlitConverter::~BlitConverter() {
    shutdown();
}

bool BlitConverter::initialize() {
    auto promise = std::make_shared<std::promise<bool>>();
    std::future<bool> future = promise->get_future();

    if (!post([this, promise] {
        const bool ok = initializeGL() && ensureCurrent();
        if (!ok) cleanupGL();
        promise->set_value(ok);
    })) {
        return false;
    }

    return future.get();
}

void BlitConverter::workerMain() {
    for (;;) {
        std::function<void()> task;
        {
            std::unique_lock<std::mutex> lk(queueMutex_);
            queueCv_.wait(lk, [this] {
                return stopping_ || !taskQueue_.empty();
            });

            if (taskQueue_.empty()) {
                // stopping_ must be true; drain is complete.
                break;
            }

            task = std::move(taskQueue_.front());
            taskQueue_.pop_front();
        }
        task();
    }

    cleanupGL();
}

bool BlitConverter::post(std::function<void()> task) {
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        if (stopping_) return false;
        taskQueue_.push_back(std::move(task));
    }
    queueCv_.notify_one();
    return true;
}

void BlitConverter::shutdown() {
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        if (stopping_) return;
        stopping_ = true;
        // Any unconsumed tasks will not run; fulfil their promises to -1 so
        // callers blocked on futures are unblocked. We can't reach into the
        // closures here, so we push a sentinel that drains them instead.
        // Simpler: just notify and let workerMain drain the real queue first,
        // then exit. Tasks posted after stopping_ = true are dropped, but
        // convertBGRAtoRGBA already checks stopping_ inside the posted closure.
    }
    queueCv_.notify_all();
    if (workerThread_.joinable()) {
        workerThread_.join();
    }
}

bool BlitConverter::registerBuffer(AHardwareBuffer* buffer) {
    if (!buffer) return false;
    auto promise = std::make_shared<std::promise<bool>>();
    std::future<bool> future = promise->get_future();
    if (!post([this, buffer, promise] {
        if (!initializeGL() || !ensureCurrent()) {
            promise->set_value(false);
            return;
        }
        if (findRegisteredBuffer(buffer)) {
            promise->set_value(true);
            return;
        }
        ImportedBuffer imported;
        bool ok = importBuffer(buffer, imported);
        if (ok) registeredBuffers_.emplace(buffer, imported);
        promise->set_value(ok);
    })) {
        return false;
    }
    return future.get();
}

void BlitConverter::unregisterBuffer(AHardwareBuffer* buffer) {
    if (!buffer) return;
    auto promise = std::make_shared<std::promise<void>>();
    std::future<void> future = promise->get_future();
    if (!post([this, buffer, promise] {
        if (initialized_ && ensureCurrent()) {
            auto it = registeredBuffers_.find(buffer);
            if (it != registeredBuffers_.end()) {
                destroyImportedBuffer(it->second);
                registeredBuffers_.erase(it);
            }
        }
        promise->set_value();
    })) {
        return;
    }
    future.get();
}

std::future<int> BlitConverter::convertBGRAtoRGBA(AHardwareBuffer* source, AHardwareBuffer* destination, int sourceAcquireFenceFd, int destinationAcquireFenceFd) {
    auto promise = std::make_shared<std::promise<int>>();
    std::future<int> future = promise->get_future();

    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        if (stopping_) {
            // Worker is gone; close fds and resolve immediately.
            waitAndCloseFenceFd(sourceAcquireFenceFd);
            waitAndCloseFenceFd(destinationAcquireFenceFd);
            promise->set_value(-1);
            return future;
        }

        taskQueue_.push_back([this, source, destination, sourceAcquireFenceFd, destinationAcquireFenceFd, promise]() mutable {
            int result = doConvert(
                    source,
                    destination,
                    sourceAcquireFenceFd,
                    destinationAcquireFenceFd);
            promise->set_value(result);
        });
    }
    queueCv_.notify_one();
    return future;
}

int BlitConverter::doConvert(AHardwareBuffer* source, AHardwareBuffer* destination, int sourceAcquireFenceFd, int destinationAcquireFenceFd) {
    if (!source || !destination || source == destination) {
        LOGE("Invalid source/destination AHardwareBuffer");
        waitAndCloseFenceFd(sourceAcquireFenceFd);
        waitAndCloseFenceFd(destinationAcquireFenceFd);
        return -1;
    }

    if (!initializeGL() || !ensureCurrent()) {
        waitAndCloseFenceFd(sourceAcquireFenceFd);
        waitAndCloseFenceFd(destinationAcquireFenceFd);
        return -1;
    }

    AHardwareBuffer_Desc sourceDesc{};
    AHardwareBuffer_Desc dstDesc{};
    AHardwareBuffer_describe(source, &sourceDesc);
    AHardwareBuffer_describe(destination, &dstDesc);

    if (!validateDescriptors(sourceDesc, dstDesc)) {
        waitAndCloseFenceFd(sourceAcquireFenceFd);
        waitAndCloseFenceFd(destinationAcquireFenceFd);
        return -1;
    }

    if ((sourceDesc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) == 0) {
        LOGW("Source usage lacks GPU_SAMPLED_IMAGE: 0x%llx",
             static_cast<unsigned long long>(sourceDesc.usage));
    }
    if ((dstDesc.usage & AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT) == 0) {
        LOGW("Destination usage lacks GPU_COLOR_OUTPUT: 0x%llx",
             static_cast<unsigned long long>(dstDesc.usage));
    }

    bool srcServerWait = false;
    if (!consumeAcquireFence(sourceAcquireFenceFd, srcServerWait)) {
        waitAndCloseFenceFd(destinationAcquireFenceFd);
        return -1;
    }

    bool dstServerWait = false;
    if (!consumeAcquireFence(destinationAcquireFenceFd, dstServerWait)) {
        if (srcServerWait) glFinish();
        return -1;
    }

    const bool serverWaitQueued = srcServerWait || dstServerWait;

    ImportedBuffer transientSource;
    ImportedBuffer transientDestination;

    ImportedBuffer* importedSource = findRegisteredBuffer(source);
    if (!importedSource) {
        if (!importBuffer(source, transientSource)) {
            if (serverWaitQueued) glFinish();
            return -1;
        }
        importedSource = &transientSource;
    }

    ImportedBuffer* importedDst = findRegisteredBuffer(destination);
    if (!importedDst) {
        if (!importBuffer(destination, transientDestination)) {
            if (serverWaitQueued) glFinish();
            destroyImportedBuffer(transientSource);
            return -1;
        }
        importedDst = &transientDestination;
    }

    auto failAfterQueuedWork = [&]() -> int {
        glFinish();
        destroyImportedBuffer(transientSource);
        destroyImportedBuffer(transientDestination);
        return -1;
    };

    if (currentFramebufferTexture_ != importedDst->texture) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, importedDst->texture, 0);
        currentFramebufferTexture_ = importedDst->texture;
    }

    if (!importedDst->framebufferValidated) {
        const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Destination framebuffer is incomplete: 0x%x", status);
            return failAfterQueuedWork();
        }
        importedDst->framebufferValidated = true;
    }

    const GLsizei w = static_cast<GLsizei>(sourceDesc.width);
    const GLsizei h = static_cast<GLsizei>(sourceDesc.height);
    if (currentViewportWidth_ != w || currentViewportHeight_ != h) {
        glViewport(0, 0, w, h);
        currentViewportWidth_  = w;
        currentViewportHeight_ = h;
    }

#if BLIT_CONVERTER_INVALIDATE_DESTINATION
    const GLenum attachment = GL_COLOR_ATTACHMENT0;
    glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &attachment);
#endif

    const bool swapRedBlue = sourceDesc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    glUniform1i(swapRedBlueLocation_, swapRedBlue ? 1 : 0);
    glBindTexture(GL_TEXTURE_2D, importedSource->texture);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    if (!checkGl("fullscreen BGRA-byte to RGBA blit")) {
        return failAfterQueuedWork();
    }

    const int releaseFenceFd = createReleaseFence();
    if (releaseFenceFd < 0) {
        return failAfterQueuedWork();
    }

    destroyImportedBuffer(transientSource);
    destroyImportedBuffer(transientDestination);
    return releaseFenceFd;
}

bool BlitConverter::initializeGL() {
    if (initialized_) return true;

    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) { checkEgl("eglGetDisplay"); return false; }

    if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
        checkEgl("eglInitialize"); return false;
    }

    loadExtensionFunctions();

    if (!gEglCreateImageKHR || !gEglDestroyImageKHR ||
        !gEglCreateSyncKHR  || !gEglDestroySyncKHR  ||
        !gEglClientWaitSyncKHR || !gEglDupNativeFenceFDANDROID ||
        !gEglGetNativeClientBufferANDROID || !gGlEGLImageTargetTexture2DOES) {
        LOGE("Required EGL/GL extension functions are unavailable");
        return false;
    }

    const char* eglExt = eglQueryString(display_, EGL_EXTENSIONS);
    if (!hasExtension(eglExt, "EGL_ANDROID_get_native_client_buffer") ||
        !hasExtension(eglExt, "EGL_ANDROID_native_fence_sync")) {
        LOGE("Required Android EGL extensions are unavailable");
        return false;
    }

    supportsServerWait_ = gEglWaitSyncKHR != nullptr && hasExtension(eglExt, "EGL_KHR_wait_sync");

    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        checkEgl("eglBindAPI"); return false;
    }

    const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(display_, configAttribs, &config, 1, &configCount) != EGL_TRUE ||
        configCount < 1) {
        checkEgl("eglChooseConfig"); return false;
    }

    const EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    surface_ = eglCreatePbufferSurface(display_, config, pbufAttribs);
    if (surface_ == EGL_NO_SURFACE) {
        checkEgl("eglCreatePbufferSurface"); return false;
    }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, ctxAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        checkEgl("eglCreateContext"); return false;
    }

    if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
        checkEgl("eglMakeCurrent(init)"); return false;
    }

    if (!hasGlExtension("GL_OES_EGL_image")) {
        LOGE("GL_OES_EGL_image is unavailable"); return false;
    }

    program_ = createProgram();
    if (!program_) return false;

    sourceSamplerLocation_ = glGetUniformLocation(program_, "uSource");
    swapRedBlueLocation_   = glGetUniformLocation(program_, "uSwapRedBlue");
    if (sourceSamplerLocation_ < 0 || swapRedBlueLocation_ < 0) {
        LOGE("Could not find shader uniforms: source=%d swap=%d", sourceSamplerLocation_, swapRedBlueLocation_);
        return false;
    }

    glGenVertexArrays(1, &vao_);
    glGenFramebuffers(1, &fbo_);
    if (!vao_ || !fbo_) { LOGE("Could not create VAO/FBO"); return false; }

    bindPrivateState();
    if (!checkGl("initialize private GL state")) return false;

    initialized_ = true;
    LOGI("Initialized: renderer=%s, version=%s, server-wait=%s",
         reinterpret_cast<const char*>(glGetString(GL_RENDERER)),
         reinterpret_cast<const char*>(glGetString(GL_VERSION)),
         supportsServerWait_ ? "yes" : "no");
    return true;
}

bool BlitConverter::ensureCurrent() {
    if (eglGetCurrentContext() == context_ &&
        eglGetCurrentDisplay() == display_) {
        return true;
    }
    if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
        checkEgl("eglMakeCurrent"); return false;
    }
    bindPrivateState();
    currentFramebufferTexture_ = 0;
    currentViewportWidth_  = -1;
    currentViewportHeight_ = -1;
    return true;
}

void BlitConverter::bindPrivateState() {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glBindVertexArray(vao_);
    glUseProgram(program_);
    glUniform1i(sourceSamplerLocation_, 0);
    glUniform1i(swapRedBlueLocation_, 0);
    glActiveTexture(GL_TEXTURE0);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DITHER);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
}

void BlitConverter::cleanupGL() {
    bool hasResources = display_ != EGL_NO_DISPLAY ||
                        context_ != EGL_NO_CONTEXT ||
                        surface_ != EGL_NO_SURFACE ||
                        program_ || vao_ || fbo_ ||
                        !registeredBuffers_.empty();

    if (!hasResources) return;

    bool contextCurrent = false;
    if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT) {
        contextCurrent = ensureCurrent();
    }

    if (contextCurrent) {
        for (auto& entry : registeredBuffers_) {
            destroyImportedBuffer(entry.second);
        }
        registeredBuffers_.clear();

        if (currentFramebufferTexture_ != 0 && fbo_ != 0) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
            currentFramebufferTexture_ = 0;
        }

        if (program_) glDeleteProgram(program_);
        if (vao_)     glDeleteVertexArrays(1, &vao_);
        if (fbo_)     glDeleteFramebuffers(1, &fbo_);
    } else if (!registeredBuffers_.empty()) {
        LOGE("Leaking registered imports: EGL context unavailable");
    }

    program_ = 0; vao_ = 0; fbo_ = 0;
    sourceSamplerLocation_ = -1; swapRedBlueLocation_ = -1;
    currentFramebufferTexture_ = 0;
    currentViewportWidth_ = -1; currentViewportHeight_ = -1;

    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        eglTerminate(display_);
    }

    registeredBuffers_.clear();
    display_     = EGL_NO_DISPLAY;
    context_     = EGL_NO_CONTEXT;
    surface_     = EGL_NO_SURFACE;
    initialized_ = false;
    supportsServerWait_ = false;
}

bool BlitConverter::importBuffer(AHardwareBuffer* buffer, ImportedBuffer& imported) {
    if (!buffer) return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);

    bool supportedFormat = desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
                           desc.format == AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;

    if (desc.width == 0 || desc.height == 0 || desc.layers != 1 || !supportedFormat) {
        LOGE("Cannot import AHB: %ux%u layers=%u format=%u",
             desc.width, desc.height, desc.layers, desc.format);
        return false;
    }

    AHardwareBuffer_acquire(buffer);

    EGLClientBuffer clientBuffer = gEglGetNativeClientBufferANDROID(buffer);
    if (!clientBuffer) {
        LOGE("eglGetNativeClientBufferANDROID returned null");
        AHardwareBuffer_release(buffer);
        return false;
    }

    const EGLint imageAttribs[] = { EGL_NONE };
    EGLImageKHR image = gEglCreateImageKHR(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttribs);
    if (image == EGL_NO_IMAGE_KHR) {
        checkEgl("eglCreateImageKHR");
        AHardwareBuffer_release(buffer);
        return false;
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);

    while (glGetError() != GL_NO_ERROR) {}
    gGlEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
    GLenum importError = glGetError();
    if (importError != GL_NO_ERROR) {
        LOGE("glEGLImageTargetTexture2DOES failed for format=%u: 0x%x", desc.format, importError);
        glDeleteTextures(1, &texture);
        gEglDestroyImageKHR(display_, image);
        AHardwareBuffer_release(buffer);
        return false;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);

    if (!texture || !checkGl("import AHardwareBuffer texture")) {
        if (texture) glDeleteTextures(1, &texture);
        gEglDestroyImageKHR(display_, image);
        AHardwareBuffer_release(buffer);
        return false;
    }

    imported.buffer              = buffer;
    imported.image               = image;
    imported.texture             = texture;
    imported.width               = desc.width;
    imported.height              = desc.height;
    imported.format              = desc.format;
    imported.framebufferValidated = false;
    return true;
}

void BlitConverter::destroyImportedBuffer(ImportedBuffer& imported) {
    if (!imported.buffer) return;

    if (currentFramebufferTexture_ == imported.texture) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        currentFramebufferTexture_ = 0;
    }

    if (imported.texture) glDeleteTextures(1, &imported.texture);
    if (imported.image != EGL_NO_IMAGE_KHR) gEglDestroyImageKHR(display_, imported.image);
    AHardwareBuffer_release(imported.buffer);
    imported = ImportedBuffer{};
}

BlitConverter::ImportedBuffer*
BlitConverter::findRegisteredBuffer(AHardwareBuffer* buffer) {
    const auto it = registeredBuffers_.find(buffer);
    return it == registeredBuffers_.end() ? nullptr : &it->second;
}

void BlitConverter::waitAndCloseFenceFd(int& fenceFd) {
    if (fenceFd < 0) return;
    pollfd descriptor{};
    descriptor.fd = fenceFd;
    descriptor.events = POLLIN;
    int result;
    do { result = poll(&descriptor, 1, -1); } while (result < 0 && errno == EINTR);
    if (result < 0) LOGE("poll(native fence) failed: %s", std::strerror(errno));
    close(fenceFd);
    fenceFd = -1;
}

bool BlitConverter::consumeAcquireFence(int& fenceFd, bool& serverWaitQueued) {
    serverWaitQueued = false;
    if (fenceFd < 0) return true;

    const EGLint syncAttribs[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fenceFd, EGL_NONE
    };
    EGLSyncKHR sync = gEglCreateSyncKHR(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, syncAttribs);
    if (sync == EGL_NO_SYNC_KHR) {
        checkEgl("eglCreateSyncKHR(acquire)");
        waitAndCloseFenceFd(fenceFd);
        return false;
    }
    fenceFd = -1; // EGL owns it now

    bool synchronized = false;
    if (supportsServerWait_) {
        if (gEglWaitSyncKHR(display_, sync, 0) == EGL_TRUE) {
            serverWaitQueued = true;
            synchronized = true;
        } else {
            checkEgl("eglWaitSyncKHR");
            LOGW("Server wait failed; falling back to CPU wait");
        }
    }
    if (!synchronized) {
        const EGLint result = gEglClientWaitSyncKHR(display_, sync, 0, EGL_FOREVER_KHR);
        synchronized = result != EGL_FALSE;
        if (!synchronized) checkEgl("eglClientWaitSyncKHR");
    }
    if (gEglDestroySyncKHR(display_, sync) != EGL_TRUE) {
        checkEgl("eglDestroySyncKHR(acquire)");
    }
    return synchronized;
}

int BlitConverter::createReleaseFence() {
    const EGLint attribs[] = { EGL_NONE };
    EGLSyncKHR sync = gEglCreateSyncKHR(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
    if (sync == EGL_NO_SYNC_KHR) { checkEgl("eglCreateSyncKHR(release)"); return -1; }

    glFlush();

    int fenceFd = gEglDupNativeFenceFDANDROID(display_, sync);
    if (fenceFd == EGL_NO_NATIVE_FENCE_FD_ANDROID) {
        checkEgl("eglDupNativeFenceFDANDROID");
        fenceFd = -1;
    }
    if (gEglDestroySyncKHR(display_, sync) != EGL_TRUE) {
        checkEgl("eglDestroySyncKHR(release)");
        if (fenceFd >= 0) { close(fenceFd); fenceFd = -1; }
    }
    return fenceFd;
}

bool BlitConverter::checkGl(const char* operation) {
#if BLIT_CONVERTER_ENABLE_GL_CHECKS
    bool ok = true;
    for (;;) {
        const GLenum error = glGetError();
        if (error == GL_NO_ERROR) break;
        LOGE("%s: GL error 0x%x", operation, error);
        ok = false;
    }
    return ok;
#else
    (void)operation;
    return true;
#endif
}

bool BlitConverter::checkEgl(const char* operation) {
    const EGLint error = eglGetError();
    if (error == EGL_SUCCESS) return true;
    LOGE("%s: EGL error 0x%x", operation, error);
    return false;
}
