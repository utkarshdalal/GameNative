#include "xr_windows_projection.h"

#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "xrimmersive", __VA_ARGS__)

namespace xrimmersive::windowsvr {

namespace {

constexpr uint32_t kDrmFormatAbgr8888 =
    static_cast<uint32_t>('A') | (static_cast<uint32_t>('B') << 8) |
    (static_cast<uint32_t>('2') << 16) | (static_cast<uint32_t>('4') << 24);
constexpr uint32_t kDrmFormatArgb8888 =
    static_cast<uint32_t>('A') | (static_cast<uint32_t>('R') << 8) |
    (static_cast<uint32_t>('2') << 16) | (static_cast<uint32_t>('4') << 24);

struct DmaBufSync {
    uint64_t flags;
};

constexpr uint64_t kDmaBufSyncRead = 1u << 0;
constexpr uint64_t kDmaBufSyncEnd = 1u << 2;

#ifndef DMA_BUF_IOCTL_SYNC
#define DMA_BUF_IOCTL_SYNC _IOW('b', 0, DmaBufSync)
#endif

GLuint compileShader(GLenum type, const char *source) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}

bool WindowsProjectionPresenter::initialize(XrSession session, int64_t format, uint32_t width,
                                            uint32_t height, EGLDisplay display) {
    session_ = session;
    width_ = width;
    height_ = height;
    display_ = display;
    for (auto &eye : eglImages_) eye.fill(EGL_NO_IMAGE_KHR);
    XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    info.format = format;
    info.sampleCount = 1;
    info.width = width;
    info.height = height;
    info.faceCount = 1;
    info.arraySize = 2;
    info.mipCount = 1;
    if (XR_FAILED(xrCreateSwapchain(session, &info, &swapchain_))) return false;
    uint32_t count = 0;
    if (XR_FAILED(xrEnumerateSwapchainImages(swapchain_, 0, &count, nullptr)) || count == 0) return false;
    images_.assign(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    if (XR_FAILED(xrEnumerateSwapchainImages(
            swapchain_, count, &count,
            reinterpret_cast<XrSwapchainImageBaseHeader *>(images_.data())))) return false;
    glGenFramebuffers(1, &framebuffer_);
    return ensureProgram();
}

bool WindowsProjectionPresenter::ensureProgram() {
    static const char *vertexSource =
        "#version 300 es\nlayout(location=0) in vec2 p;layout(location=1) in vec2 t;"
        "out vec2 uv;uniform vec4 u;void main(){uv=u.xy+t*u.zw;gl_Position=vec4(p,0,1);}";
    static const char *fragmentSource =
        "#version 300 es\nprecision mediump float;in vec2 uv;uniform sampler2D s;"
        "out vec4 c;void main(){c=texture(s,uv);}";
    const GLuint vertex = compileShader(GL_VERTEX_SHADER, vertexSource);
    const GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (vertex == 0 || fragment == 0) return false;
    program_ = glCreateProgram();
    glAttachShader(program_, vertex);
    glAttachShader(program_, fragment);
    glLinkProgram(program_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) return false;
    uvTransformLocation_ = glGetUniformLocation(program_, "u");
    const float vertices[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
    };
    glGenBuffers(1, &vertexBuffer_);
    glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glGenVertexArrays(1, &vertexArray_);
    glBindVertexArray(vertexArray_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void *>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          reinterpret_cast<void *>(2 * sizeof(float)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glUseProgram(program_);
    glUniform1i(glGetUniformLocation(program_, "s"), 0);
    glUseProgram(0);
    return true;
}

EGLImageKHR WindowsProjectionPresenter::createImageFromHardwareBuffer(AHardwareBuffer *buffer) {
    EGLClientBuffer client = eglGetNativeClientBufferANDROID(buffer);
    if (client == nullptr) return EGL_NO_IMAGE_KHR;
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    return eglCreateImageKHR(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes);
}

EGLImageKHR WindowsProjectionPresenter::createImageFromDmabuf(const EyeFrame &frame) {
    static constexpr EGLint planeFd[] = {
        EGL_DMA_BUF_PLANE0_FD_EXT, EGL_DMA_BUF_PLANE1_FD_EXT,
        EGL_DMA_BUF_PLANE2_FD_EXT, EGL_DMA_BUF_PLANE3_FD_EXT};
    static constexpr EGLint planeOffset[] = {
        EGL_DMA_BUF_PLANE0_OFFSET_EXT, EGL_DMA_BUF_PLANE1_OFFSET_EXT,
        EGL_DMA_BUF_PLANE2_OFFSET_EXT, EGL_DMA_BUF_PLANE3_OFFSET_EXT};
    static constexpr EGLint planePitch[] = {
        EGL_DMA_BUF_PLANE0_PITCH_EXT, EGL_DMA_BUF_PLANE1_PITCH_EXT,
        EGL_DMA_BUF_PLANE2_PITCH_EXT, EGL_DMA_BUF_PLANE3_PITCH_EXT};
    static constexpr EGLint modifierLow[] = {
        EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT,
        EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT};
    static constexpr EGLint modifierHigh[] = {
        EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT,
        EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT};
    EGLint attributes[64];
    int count = 0;
    attributes[count++] = EGL_WIDTH;
    attributes[count++] = frame.width;
    attributes[count++] = EGL_HEIGHT;
    attributes[count++] = frame.height;
    attributes[count++] = EGL_LINUX_DRM_FOURCC_EXT;
    attributes[count++] = static_cast<EGLint>(frame.fourcc);
    for (int plane = 0; plane < frame.planeCount; ++plane) {
        attributes[count++] = planeFd[plane];
        attributes[count++] = frame.dmabufFds[plane];
        attributes[count++] = planeOffset[plane];
        attributes[count++] = static_cast<EGLint>(frame.offsets[plane]);
        attributes[count++] = planePitch[plane];
        attributes[count++] = static_cast<EGLint>(frame.strides[plane]);
        if (frame.modifier != 0) {
            attributes[count++] = modifierLow[plane];
            attributes[count++] = static_cast<EGLint>(frame.modifier & 0xffffffffu);
            attributes[count++] = modifierHigh[plane];
            attributes[count++] = static_cast<EGLint>(frame.modifier >> 32);
        }
    }
    attributes[count] = EGL_NONE;
    return eglCreateImageKHR(display_, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attributes);
}

bool WindowsProjectionPresenter::waitForAcquireFence(int fenceFd) {
    if (fenceFd < 0) return true;
    const EGLint attributes[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fenceFd, EGL_NONE};
    EGLSyncKHR sync = eglCreateSyncKHR(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (sync != EGL_NO_SYNC_KHR) {
        const EGLBoolean result = eglWaitSyncKHR(display_, sync, 0);
        eglDestroySyncKHR(display_, sync);
        return result == EGL_TRUE;
    }
    pollfd descriptor{fenceFd, POLLIN, 0};
    int result;
    do result = poll(&descriptor, 1, 5000); while (result < 0 && errno == EINTR);
    close(fenceFd);
    return result > 0;
}

int WindowsProjectionPresenter::createReleaseFence() {
    const auto duplicate = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
        eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    if (duplicate == nullptr) {
        glFinish();
        return -1;
    }
    const EGLint attributes[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE};
    EGLSyncKHR sync = eglCreateSyncKHR(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (sync == EGL_NO_SYNC_KHR) {
        glFinish();
        return -1;
    }
    glFlush();
    const int fd = duplicate(display_, sync);
    eglDestroySyncKHR(display_, sync);
    if (fd < 0) glFinish();
    return fd;
}

bool WindowsProjectionPresenter::uploadLinearDmabufToTexture(
    uint32_t eye, int imageIndex, const EyeFrame &frame, GLuint &texture,
    uint64_t &cachedRegistration) {
    if (frame.planeCount != 1 || frame.dmabufFds[0] < 0 || frame.modifier != 0 ||
        frame.width <= 0 || frame.height <= 0 ||
        frame.strides[0] < static_cast<uint32_t>(frame.width) * 4u ||
        (frame.fourcc != kDrmFormatAbgr8888 && frame.fourcc != kDrmFormatArgb8888)) return false;
    const size_t rowBytes = static_cast<size_t>(frame.width) * 4u;
    const size_t mapLength = static_cast<size_t>(frame.offsets[0]) +
        static_cast<size_t>(frame.strides[0]) * static_cast<size_t>(frame.height - 1) + rowBytes;
    void *&mapping = cpuMappings_[eye][imageIndex];
    size_t &mappedLength = cpuMappingLengths_[eye][imageIndex];
    uint64_t &mappedRegistration = cpuMappingRegistrations_[eye][imageIndex];
    if (mapping != nullptr &&
        (mappedRegistration != frame.registrationSerial || mappedLength < mapLength)) {
        munmap(mapping, mappedLength);
        mapping = nullptr;
        mappedLength = 0;
        mappedRegistration = 0;
    }
    if (mapping == nullptr) {
        mapping = mmap(nullptr, mapLength, PROT_READ, MAP_SHARED, frame.dmabufFds[0], 0);
        if (mapping == MAP_FAILED) {
            mapping = nullptr;
            return false;
        }
        mappedLength = mapLength;
        mappedRegistration = frame.registrationSerial;
    }
    if (texture == 0) glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    const bool allocate = cpuTextureWidths_[eye][imageIndex] != frame.width ||
        cpuTextureHeights_[eye][imageIndex] != frame.height ||
        cachedRegistration != frame.registrationSerial;
    if (allocate) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        const bool swap = frame.fourcc == kDrmFormatArgb8888;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, swap ? GL_BLUE : GL_RED);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_G, GL_GREEN);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, swap ? GL_RED : GL_BLUE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ALPHA);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, frame.width, frame.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    DmaBufSync sync{kDmaBufSyncRead};
    ioctl(frame.dmabufFds[0], DMA_BUF_IOCTL_SYNC, &sync);
    const auto *source = static_cast<const uint8_t *>(mapping) + frame.offsets[0];
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(frame.strides[0] / 4u));
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                    GL_RGBA, GL_UNSIGNED_BYTE, source);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    const GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    sync.flags = kDmaBufSyncRead | kDmaBufSyncEnd;
    ioctl(frame.dmabufFds[0], DMA_BUF_IOCTL_SYNC, &sync);
    if (error != GL_NO_ERROR) return false;
    cpuTextureWidths_[eye][imageIndex] = frame.width;
    cpuTextureHeights_[eye][imageIndex] = frame.height;
    cachedRegistration = frame.registrationSerial;
    return true;
}

bool WindowsProjectionPresenter::importEyeBuffer(WindowsFrameTransport &transport, uint32_t eye,
                                                 EyeFrame &frame, bool &fresh) {
    frame = transport.pollEye(static_cast<int>(eye));
    if (frame.kind == BufferKind::None) return false;
    fresh = frame.serial != renderedSerials_[eye];
    if (!waitForAcquireFence(frame.acquireFenceFd)) {
        transport.discardFrame(static_cast<int>(eye), frame.imageIndex, frame.serial);
        if (fresh) renderedSerials_[eye] = frame.serial;
        return false;
    }
    frame.acquireFenceFd = -1;
    if (frame.imageIndex < 0 || frame.imageIndex >= WindowsFrameTransport::kMaxImages) {
        transport.discardFrame(static_cast<int>(eye), frame.imageIndex, frame.serial);
        return false;
    }
    const int image = frame.imageIndex;
    EGLImageKHR &cachedImage = eglImages_[eye][image];
    GLuint &cachedTexture = textures_[eye][image];
    uint64_t &registration = registrations_[eye][image];
    if (cpuFallback_[eye][image]) {
        if (!fresh && registration == frame.registrationSerial) return cachedTexture != 0;
        if (uploadLinearDmabufToTexture(eye, image, frame, cachedTexture, registration)) return true;
    }
    if (cachedImage != EGL_NO_IMAGE_KHR && registration == frame.registrationSerial) return true;
    if (cachedImage != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(display_, cachedImage);
        cachedImage = EGL_NO_IMAGE_KHR;
    }
    EGLImageKHR imported = EGL_NO_IMAGE_KHR;
    if (frame.kind == BufferKind::HardwareBuffer && frame.buffer != nullptr) {
        imported = createImageFromHardwareBuffer(frame.buffer);
    } else if (frame.kind == BufferKind::DmaBuf && frame.planeCount > 0) {
        imported = createImageFromDmabuf(frame);
        if (imported == EGL_NO_IMAGE_KHR) {
            cpuFallback_[eye][image] = true;
            static bool details = false;
            LOGI("windows vr eye %u image %d: EGL dma-buf import failed (0x%x) — CPU upload fallback engaged",
                 eye, image, eglGetError());
            if (!details) {
                details = true;
                const char *extensions = eglQueryString(display_, EGL_EXTENSIONS);
                LOGI("EGL dma_buf_import=%d modifiers=%d",
                     extensions != nullptr && strstr(extensions, "EGL_EXT_image_dma_buf_import") != nullptr,
                     extensions != nullptr && strstr(extensions, "EGL_EXT_image_dma_buf_import_modifiers") != nullptr);
            }
            if (uploadLinearDmabufToTexture(eye, image, frame, cachedTexture, registration)) return true;
        } else {
            LOGI("windows vr eye %u image %d: zero-copy EGL dma-buf import active", eye, image);
        }
    }
    if (imported == EGL_NO_IMAGE_KHR) {
        transport.discardFrame(static_cast<int>(eye), image, frame.serial);
        return false;
    }
    if (cachedTexture == 0) glGenTextures(1, &cachedTexture);
    glBindTexture(GL_TEXTURE_2D, cachedTexture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, imported);
    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        glBindTexture(GL_TEXTURE_2D, 0);
        eglDestroyImageKHR(display_, imported);
        transport.discardFrame(static_cast<int>(eye), image, frame.serial);
        return false;
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, frame.swapRedBlue ? GL_BLUE : GL_RED);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_G, GL_GREEN);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, frame.swapRedBlue ? GL_RED : GL_BLUE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ALPHA);
    glBindTexture(GL_TEXTURE_2D, 0);
    cachedImage = imported;
    registration = frame.registrationSerial;
    return true;
}

void WindowsProjectionPresenter::drawEye(uint32_t eye, const EyeFrame &source,
                                         uint32_t imageIndex) {
    glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, images_[imageIndex].image, 0, eye);
    glViewport(0, 0, static_cast<GLsizei>(width_), static_cast<GLsizei>(height_));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textures_[eye][source.imageIndex]);
    const float sourceWidth = source.sourceWidth > 0 ? source.sourceWidth : source.width;
    const float sourceHeight = source.sourceHeight > 0 ? source.sourceHeight : source.height;
    glUniform4f(uvTransformLocation_, source.sourceX / static_cast<float>(source.width),
                source.sourceY / static_cast<float>(source.height),
                sourceWidth / source.width, sourceHeight / source.height);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void WindowsProjectionPresenter::discardFresh(WindowsFrameTransport &transport,
                                               const std::array<EyeFrame, 2> &frames,
                                               const std::array<bool, 2> &fresh) {
    for (uint32_t eye = 0; eye < 2; ++eye) {
        if (!fresh[eye]) continue;
        transport.discardFrame(static_cast<int>(eye), frames[eye].imageIndex, frames[eye].serial);
        renderedSerials_[eye] = frames[eye].serial;
    }
}

bool WindowsProjectionPresenter::render(WindowsFrameTransport &transport, XrSpace space,
                                        XrCompositionLayerProjection *layer) {
    if (layer == nullptr || !transport.hasStereoContent()) return false;
    std::array<EyeFrame, 2> frames{};
    std::array<bool, 2> fresh{false, false};
    for (uint32_t eye = 0; eye < 2; ++eye) {
        if (!importEyeBuffer(transport, eye, frames[eye], fresh[eye])) {
            discardFresh(transport, frames, fresh);
            return false;
        }
    }
    XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    uint32_t imageIndex = 0;
    if (XR_FAILED(xrAcquireSwapchainImage(swapchain_, &acquire, &imageIndex))) {
        discardFresh(transport, frames, fresh);
        return false;
    }
    XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wait.timeout = XR_INFINITE_DURATION;
    if (XR_FAILED(xrWaitSwapchainImage(swapchain_, &wait))) {
        XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        xrReleaseSwapchainImage(swapchain_, &release);
        discardFresh(transport, frames, fresh);
        return false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glUseProgram(program_);
    glBindVertexArray(vertexArray_);
    for (uint32_t eye = 0; eye < 2; ++eye) {
        drawEye(eye, frames[eye], imageIndex);
        XrCompositionLayerProjectionView &view = views_[eye];
        view = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
        if (frames[eye].projectionValid) {
            view.pose.orientation = {
                frames[eye].projectionOrientation[0], frames[eye].projectionOrientation[1],
                frames[eye].projectionOrientation[2], frames[eye].projectionOrientation[3]};
            view.pose.position = {
                frames[eye].projectionPosition[0], frames[eye].projectionPosition[1],
                frames[eye].projectionPosition[2]};
            view.fov = {
                frames[eye].projectionFov[0], frames[eye].projectionFov[1],
                frames[eye].projectionFov[2], frames[eye].projectionFov[3]};
        }
        view.subImage.swapchain = swapchain_;
        view.subImage.imageRect = {{0, 0}, {static_cast<int32_t>(width_), static_cast<int32_t>(height_)}};
        view.subImage.imageArrayIndex = eye;
    }
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const uint32_t freshCount = static_cast<uint32_t>(fresh[0]) + static_cast<uint32_t>(fresh[1]);
    int sharedFence = freshCount > 0 ? createReleaseFence() : -1;
    if (freshCount == 0) glFlush();
    std::array<int, 2> releaseFences{-1, -1};
    if (sharedFence >= 0) {
        if (fresh[0] && fresh[1]) {
            releaseFences[0] = dup(sharedFence);
            releaseFences[1] = sharedFence;
            if (releaseFences[0] < 0) {
                close(sharedFence);
                releaseFences = {-1, -1};
                glFinish();
            }
        } else {
            releaseFences[fresh[0] ? 0 : 1] = sharedFence;
        }
    }
    for (uint32_t eye = 0; eye < 2; ++eye) {
        if (!fresh[eye]) continue;
        transport.publishReleaseFence(static_cast<int>(eye), frames[eye].imageIndex,
                                      releaseFences[eye]);
        renderedSerials_[eye] = frames[eye].serial;
    }
    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    if (XR_FAILED(xrReleaseSwapchainImage(swapchain_, &release))) return false;
    *layer = {XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    layer->space = space;
    layer->viewCount = 2;
    layer->views = views_.data();
    return true;
}

void WindowsProjectionPresenter::shutdown() {
    for (uint32_t eye = 0; eye < 2; ++eye) {
        for (int image = 0; image < WindowsFrameTransport::kMaxImages; ++image) {
            if (eglImages_[eye][image] != EGL_NO_IMAGE_KHR) {
                eglDestroyImageKHR(display_, eglImages_[eye][image]);
            }
            if (textures_[eye][image] != 0) glDeleteTextures(1, &textures_[eye][image]);
            if (cpuMappings_[eye][image] != nullptr) {
                munmap(cpuMappings_[eye][image], cpuMappingLengths_[eye][image]);
            }
        }
    }
    if (vertexArray_ != 0) glDeleteVertexArrays(1, &vertexArray_);
    if (vertexBuffer_ != 0) glDeleteBuffers(1, &vertexBuffer_);
    if (program_ != 0) glDeleteProgram(program_);
    if (framebuffer_ != 0) glDeleteFramebuffers(1, &framebuffer_);
    if (swapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(swapchain_);
    vertexArray_ = 0;
    vertexBuffer_ = 0;
    program_ = 0;
    framebuffer_ = 0;
    swapchain_ = XR_NULL_HANDLE;
}

}
