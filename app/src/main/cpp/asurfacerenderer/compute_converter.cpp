#include "compute_converter.h"
#include <android/log.h>
#include <unistd.h>
#include <cstring>

#define LOG_TAG "ComputeConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// EGL extension function pointers
static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
static PFNEGLCREATESYNCKHRPROC eglCreateSyncKHR = nullptr;
static PFNEGLDESTROYSYNCKHRPROC eglDestroySyncKHR = nullptr;
static PFNEGLCLIENTWAITSYNCKHRPROC eglClientWaitSyncKHR = nullptr;
static PFNEGLDUPNATIVEFENCEFDANDROIDPROC eglDupNativeFenceFDANDROID = nullptr;
static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;

static void loadEGLExtensions() {
    static bool loaded = false;
    if (loaded) return;

    eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    eglCreateSyncKHR = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    eglDestroySyncKHR = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    eglClientWaitSyncKHR = (PFNEGLCLIENTWAITSYNCKHRPROC)eglGetProcAddress("eglClientWaitSyncKHR");
    eglDupNativeFenceFDANDROID = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
    eglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");

    loaded = true;
}

// Compute shader - swap R and B channels in parallel
static const char* computeShaderSource = R"(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0, rgba8) readonly uniform highp image2D srcImage;
layout(binding = 1, rgba8) writeonly uniform highp image2D dstImage;

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(srcImage);

    // Bounds check to prevent out-of-bounds access
    if (pos.x >= size.x || pos.y >= size.y) {
        return;
    }

    // Read pixel from source
    vec4 color = imageLoad(srcImage, pos);

    // Swap R and B: BGRA -> RGBA
    vec4 swapped = vec4(color.b, color.g, color.r, color.a);

    // Write to destination
    imageStore(dstImage, pos, swapped);
}
)";

ComputeConverter::ComputeConverter()
    : display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT), surface(EGL_NO_SURFACE),
      computeProgram(0), initialized(false) {
}

ComputeConverter::~ComputeConverter() {
    cleanup();
}

bool ComputeConverter::initializeGL() {
    if (initialized) return true;

    // Load EGL extensions
    loadEGLExtensions();

    // Get EGL display
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("Failed to get EGL display");
        return false;
    }

    if (!eglInitialize(display, nullptr, nullptr)) {
        LOGE("Failed to initialize EGL");
        return false;
    }

    // Choose config
    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs)) {
        LOGE("Failed to choose EGL config");
        return false;
    }

    // Create pbuffer surface (1x1, we won't use it)
    EGLint pbufferAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    surface = eglCreatePbufferSurface(display, config, pbufferAttribs);
    if (surface == EGL_NO_SURFACE) {
        LOGE("Failed to create pbuffer surface");
        return false;
    }

    // Create context with OpenGL ES 3.1 (required for compute shaders)
    EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION_KHR, 1,
        EGL_NONE
    };
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("Failed to create EGL context");
        return false;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        LOGE("Failed to make context current");
        return false;
    }

    // Check if compute shaders are supported
    GLint maxComputeWorkGroupCount[3];
    glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, &maxComputeWorkGroupCount[0]);
    if (maxComputeWorkGroupCount[0] == 0) {
        LOGE("Compute shaders not supported on this device");
        return false;
    }

    // Compile compute shader
    GLuint computeShader = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(computeShader, 1, &computeShaderSource, nullptr);
    glCompileShader(computeShader);

    GLint success;
    glGetShaderiv(computeShader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(computeShader, 512, nullptr, infoLog);
        LOGE("Compute shader compilation failed: %s", infoLog);
        return false;
    }

    // Create program
    computeProgram = glCreateProgram();
    glAttachShader(computeProgram, computeShader);
    glLinkProgram(computeProgram);

    glGetProgramiv(computeProgram, GL_LINK_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetProgramInfoLog(computeProgram, 512, nullptr, infoLog);
        LOGE("Compute program linking failed: %s", infoLog);
        return false;
    }

    glDeleteShader(computeShader);

    initialized = true;
    LOGI("Compute converter initialized successfully");
    return true;
}

void ComputeConverter::cleanup() {
    if (computeProgram) glDeleteProgram(computeProgram);

    if (context != EGL_NO_CONTEXT) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(display, context);
    }
    if (surface != EGL_NO_SURFACE) {
        eglDestroySurface(display, surface);
    }
    if (display != EGL_NO_DISPLAY) {
        eglTerminate(display);
    }

    initialized = false;
}

int ComputeConverter::convertBGRAtoRGBA(AHardwareBuffer* srcBGRA, AHardwareBuffer* dstRGBA, int srcFenceFd) {
    if (!initializeGL()) {
        if (srcFenceFd >= 0) close(srcFenceFd);
        return -1;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        LOGE("Failed to make context current");
        if (srcFenceFd >= 0) close(srcFenceFd);
        return -1;
    }

    // Get buffer dimensions
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(srcBGRA, &desc);

    // Wait for source fence
    if (srcFenceFd >= 0) {
        EGLint attribs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, srcFenceFd, EGL_NONE };
        EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
        if (sync != EGL_NO_SYNC_KHR) {
            eglClientWaitSyncKHR(display, sync, 0, EGL_FOREVER_KHR);
            eglDestroySyncKHR(display, sync);
        }
        close(srcFenceFd);
    }

    // Create EGLImage from source buffer
    EGLClientBuffer srcClientBuffer = eglGetNativeClientBufferANDROID(srcBGRA);
    EGLint srcAttribs[] = { EGL_NONE };
    EGLImageKHR srcImage = eglCreateImageKHR(display, EGL_NO_CONTEXT,
                                              EGL_NATIVE_BUFFER_ANDROID,
                                              srcClientBuffer, srcAttribs);
    if (srcImage == EGL_NO_IMAGE_KHR) {
        LOGE("Failed to create source EGLImage");
        return -1;
    }

    // Create texture from source image
    GLuint srcTexture;
    glGenTextures(1, &srcTexture);
    glBindTexture(GL_TEXTURE_2D, srcTexture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, srcImage);

    // Create EGLImage from destination buffer
    EGLClientBuffer dstClientBuffer = eglGetNativeClientBufferANDROID(dstRGBA);
    EGLint dstAttribs[] = { EGL_NONE };
    EGLImageKHR dstImage = eglCreateImageKHR(display, EGL_NO_CONTEXT,
                                              EGL_NATIVE_BUFFER_ANDROID,
                                              dstClientBuffer, dstAttribs);
    if (dstImage == EGL_NO_IMAGE_KHR) {
        LOGE("Failed to create destination EGLImage");
        glDeleteTextures(1, &srcTexture);
        eglDestroyImageKHR(display, srcImage);
        return -1;
    }

    // Create texture from destination image
    GLuint dstTexture;
    glGenTextures(1, &dstTexture);
    glBindTexture(GL_TEXTURE_2D, dstTexture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, dstImage);

    // Bind images to compute shader
    glUseProgram(computeProgram);
    glBindImageTexture(0, srcTexture, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA8);
    glBindImageTexture(1, dstTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("GL error after binding image textures: 0x%x", err);
        glDeleteTextures(1, &srcTexture);
        glDeleteTextures(1, &dstTexture);
        eglDestroyImageKHR(display, srcImage);
        eglDestroyImageKHR(display, dstImage);
        return -1;
    }

    // Dispatch compute shader
    // Work group size is 16x16, so we need to dispatch enough groups to cover the image
    GLuint numGroupsX = (desc.width + 15) / 16;
    GLuint numGroupsY = (desc.height + 15) / 16;

    LOGI("Dispatching compute shader: %dx%d groups for %dx%d image",
         numGroupsX, numGroupsY, desc.width, desc.height);
    glDispatchCompute(numGroupsX, numGroupsY, 1);

    err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("GL error after glDispatchCompute: 0x%x", err);
        glDeleteTextures(1, &srcTexture);
        glDeleteTextures(1, &dstTexture);
        eglDestroyImageKHR(display, srcImage);
        eglDestroyImageKHR(display, dstImage);
        return -1;
    }

    // Memory barrier to ensure writes are complete
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // Flush and create fence
    glFlush();

    EGLSyncKHR fence = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
    int fenceFd = -1;
    if (fence != EGL_NO_SYNC_KHR) {
        fenceFd = eglDupNativeFenceFDANDROID(display, fence);
        eglDestroySyncKHR(display, fence);
    }

    // Cleanup
    glDeleteTextures(1, &srcTexture);
    glDeleteTextures(1, &dstTexture);
    eglDestroyImageKHR(display, srcImage);
    eglDestroyImageKHR(display, dstImage);

    return fenceFd;
}
