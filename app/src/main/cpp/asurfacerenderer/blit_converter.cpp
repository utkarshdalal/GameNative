#include "blit_converter.h"
#include <android/log.h>
#include <unistd.h>
#include <cstring>

#define LOG_TAG "BlitConverter"
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

// Vertex shader - simple fullscreen quad
static const char* vertexShaderSource = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTexCoord;
out vec2 TexCoord;

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    TexCoord = aTexCoord;
}
)";

// Fragment shader - swap R and B channels
static const char* fragmentShaderSource = R"(#version 300 es
precision mediump float;
in vec2 TexCoord;
out vec4 FragColor;
uniform sampler2D srcTexture;

void main() {
    vec4 color = texture(srcTexture, TexCoord);
    // Swap R and B: BGRA -> RGBA
    FragColor = vec4(color.b, color.g, color.r, color.a);
}
)";

// Fullscreen quad vertices
static const float quadVertices[] = {
    // positions   // texCoords
    -1.0f,  1.0f,  0.0f, 1.0f,
    -1.0f, -1.0f,  0.0f, 0.0f,
     1.0f, -1.0f,  1.0f, 0.0f,
    -1.0f,  1.0f,  0.0f, 1.0f,
     1.0f, -1.0f,  1.0f, 0.0f,
     1.0f,  1.0f,  1.0f, 1.0f
};

BlitConverter::BlitConverter()
    : display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT), surface(EGL_NO_SURFACE),
      program(0), vao(0), vbo(0), fbo(0), initialized(false) {
}

BlitConverter::~BlitConverter() {
    cleanup();
}

bool BlitConverter::initializeGL() {
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

    // Create context
    EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
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

    // Compile shaders
    GLuint vertexShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShader, 1, &vertexShaderSource, nullptr);
    glCompileShader(vertexShader);

    GLint success;
    glGetShaderiv(vertexShader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(vertexShader, 512, nullptr, infoLog);
        LOGE("Vertex shader compilation failed: %s", infoLog);
        return false;
    }

    GLuint fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShader, 1, &fragmentShaderSource, nullptr);
    glCompileShader(fragmentShader);

    glGetShaderiv(fragmentShader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(fragmentShader, 512, nullptr, infoLog);
        LOGE("Fragment shader compilation failed: %s", infoLog);
        return false;
    }

    // Link program
    program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);

    glGetProgramiv(program, GL_LINK_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetProgramInfoLog(program, 512, nullptr, infoLog);
        LOGE("Shader program linking failed: %s", infoLog);
        return false;
    }

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    // Create VAO and VBO
    glGenVertexArrays(1, &vao);
    glGenBuffers(1, &vbo);

    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);

    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));
    glEnableVertexAttribArray(1);

    glBindVertexArray(0);

    // Create FBO
    glGenFramebuffers(1, &fbo);

    initialized = true;
    LOGI("Blit converter initialized successfully");
    return true;
}

void BlitConverter::cleanup() {
    if (program) glDeleteProgram(program);
    if (vao) glDeleteVertexArrays(1, &vao);
    if (vbo) glDeleteBuffers(1, &vbo);
    if (fbo) glDeleteFramebuffers(1, &fbo);

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

int BlitConverter::convertBGRAtoRGBA(AHardwareBuffer* srcBGRA, AHardwareBuffer* dstRGBA, int srcFenceFd) {
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
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

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

    // Bind FBO and attach destination texture
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dstTexture, 0);

    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer not complete");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteTextures(1, &srcTexture);
        glDeleteTextures(1, &dstTexture);
        eglDestroyImageKHR(display, srcImage);
        eglDestroyImageKHR(display, dstImage);
        return -1;
    }

    // Render
    glViewport(0, 0, desc.width, desc.height);
    glUseProgram(program);
    glBindTexture(GL_TEXTURE_2D, srcTexture);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    // Flush and create fence
    glFlush();

    EGLSyncKHR fence = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
    int fenceFd = -1;
    if (fence != EGL_NO_SYNC_KHR) {
        fenceFd = eglDupNativeFenceFDANDROID(display, fence);
        eglDestroySyncKHR(display, fence);
    }

    // Cleanup
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindVertexArray(0);
    glDeleteTextures(1, &srcTexture);
    glDeleteTextures(1, &dstTexture);
    eglDestroyImageKHR(display, srcImage);
    eglDestroyImageKHR(display, dstImage);

    return fenceFd;
}
