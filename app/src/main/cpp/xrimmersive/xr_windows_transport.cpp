#include "xr_windows_transport.h"

#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <algorithm>
#include <cstddef>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <cstdio>

#define LOG_TAG "GameNativeVR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace xrimmersive::windowsvr {
namespace {





bool readLine(int fd, std::string& out) {
    out.clear();
    char buffer[512];
    while (true) {
        ssize_t got = recv(fd, buffer, sizeof(buffer), MSG_PEEK);
        if (got == 0) return false;
        if (got < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        const void* newlinePtr = std::memchr(buffer, '\n', static_cast<size_t>(got));
        const size_t consume = newlinePtr != nullptr
            ? static_cast<const char*>(newlinePtr) - buffer + 1
            : static_cast<size_t>(got);
        size_t consumed = 0;
        while (consumed < consume) {
            const ssize_t read = recv(fd, buffer + consumed, consume - consumed, 0);
            if (read == 0) return false;
            if (read < 0) {
                if (errno == EINTR) continue;
                return false;
            }
            consumed += static_cast<size_t>(read);
        }
        const size_t textBytes = newlinePtr != nullptr ? consume - 1 : consume;
        if (out.size() < 512) {
            const size_t available = 512 - out.size();
            out.append(buffer, std::min(textBytes, available));
        }
        if (newlinePtr != nullptr) return true;
    }
}

bool writeAll(int fd, const void* data, size_t len) {
    const char* p = static_cast<const char*>(data);
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, p + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return false;
        }
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool replyOk(int fd) { return writeAll(fd, "OK\n", 3); }





socklen_t fillUnixAddr(sockaddr_un& addr, const std::string& path, bool& abstractOut) {
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (!path.empty() && path[0] == '@') {
        abstractOut = true;
        size_t nameLen = path.size() - 1;
        if (nameLen > sizeof(addr.sun_path) - 1) nameLen = sizeof(addr.sun_path) - 1;
        addr.sun_path[0] = '\0';
        std::memcpy(addr.sun_path + 1, path.data() + 1, nameLen);
        return static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + nameLen);
    }
    abstractOut = false;
    std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);
    return static_cast<socklen_t>(sizeof(addr));
}



long long parseKey(const std::string& line, const char* key, long long fallback) {
    std::string needle = std::string(key) + "=";
    size_t pos = 0;
    while ((pos = line.find(needle, pos)) != std::string::npos) {
        if (pos == 0 || line[pos - 1] == ' ') {
            pos += needle.size();
            return std::strtoll(line.c_str() + pos, nullptr, 0);
        }
        pos += needle.size();
    }
    return fallback;
}



int recvFd(int sockFd) {
    char payload = 0;
    iovec iov{};
    iov.iov_base = &payload;
    iov.iov_len = 1;

    union {
        char buf[CMSG_SPACE(sizeof(int))];
        cmsghdr align;
    } control{};

    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.buf;
    msg.msg_controllen = sizeof(control.buf);

    ssize_t n;
    do { n = ::recvmsg(sockFd, &msg, 0); } while (n < 0 && errno == EINTR);
    if (n <= 0) return -1;

    for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c != nullptr; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS &&
            c->cmsg_len == CMSG_LEN(sizeof(int))) {
            int fd = -1;
            std::memcpy(&fd, CMSG_DATA(c), sizeof(int));
            return fd;
        }
    }
    return -1;
}

bool sendFd(int sockFd, int fd) {
    char payload = 'F';
    iovec iov{};
    iov.iov_base = &payload;
    iov.iov_len = 1;
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        cmsghdr align;
    } control{};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.buf;
    msg.msg_controllen = sizeof(control.buf);
    cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(cmsg), &fd, sizeof(fd));
    ssize_t sent;
    do { sent = ::sendmsg(sockFd, &msg, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
    return sent == 1;
}

}

WindowsFrameTransport::WindowsFrameTransport() {
    for (int eye = 0; eye < kEyeCount; ++eye) {
        for (int image = 0; image < kMaxImages; ++image) {
            releaseFenceFds_[eye][image] = -1;
        }
    }
}

WindowsFrameTransport::~WindowsFrameTransport() { stop(); }

void WindowsFrameTransport::start(const std::string& socketPath) {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) return;
    socketPath_ = socketPath;
    acceptThread_ = std::thread([this] { acceptLoop(); });
}

void WindowsFrameTransport::stop() {
    if (running_.exchange(false)) {
        releaseCv_.notify_all();
        int client = clientFd_.exchange(-1);
        if (client >= 0) ::shutdown(client, SHUT_RDWR);
        int fd = listenFd_.exchange(-1);
        if (fd >= 0) {
            ::shutdown(fd, SHUT_RDWR);
            ::close(fd);
        }
    }
    if (acceptThread_.joinable()) acceptThread_.join();
    for (int eye = 0; eye < kEyeCount; ++eye) releaseEye(eye);
    std::lock_guard<std::mutex> lock(eyesMutex_);
    for (int eye = 0; eye < kEyeCount; ++eye) dropRetainedLocked(eye);
}

void WindowsFrameTransport::acceptLoop() {
    int listenFd = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (listenFd < 0) {
        LOGE("xr transport: socket() failed: %s", strerror(errno));
        running_ = false;
        return;
    }

    sockaddr_un addr{};
    bool abstract = false;
    socklen_t addrLen = fillUnixAddr(addr, socketPath_, abstract);
    if (!abstract) ::unlink(socketPath_.c_str());

    if (::bind(listenFd, reinterpret_cast<sockaddr*>(&addr), addrLen) != 0) {
        LOGE("xr transport: bind(%s) failed: %s", socketPath_.c_str(), strerror(errno));
        ::close(listenFd);
        running_ = false;
        return;
    }
    if (::listen(listenFd, 1) != 0) {
        LOGE("xr transport: listen() failed: %s", strerror(errno));
        ::close(listenFd);
        running_ = false;
        return;
    }
    listenFd_ = listenFd;
    LOGI("xr transport listening on %s", socketPath_.c_str());

    while (running_.load()) {
        int clientFd = ::accept(listenFd, nullptr, nullptr);
        if (clientFd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        LOGI("xr transport: producer connected");
        clientFd_ = clientFd;
        serviceClient(clientFd);
        int expectedClient = clientFd;
        clientFd_.compare_exchange_strong(expectedClient, -1);
        ::close(clientFd);
        for (int eye = 0; eye < kEyeCount; ++eye) resetEye(eye);
        LOGI("xr transport: producer disconnected");
    }

    int fd = listenFd_.exchange(-1);
    if (fd >= 0) ::close(fd);
    if (!abstract) ::unlink(socketPath_.c_str());
}

void WindowsFrameTransport::serviceClient(int clientFd) {
    std::string line;
    while (running_.load() && readLine(clientFd, line)) {
        if (line.rfind("HELLO", 0) == 0) {
            writeAll(clientFd, "OK GameNativeVR 2\n", 18);
        } else if (line.rfind("BUFFER", 0) == 0) {
            if (!handleBufferLine(clientFd, line)) {
                writeAll(clientFd, "ERR buffer\n", 11);
                return;
            }
        } else if (line.rfind("DMABUF", 0) == 0) {
            if (!handleDmabufLine(clientFd, line)) {
                writeAll(clientFd, "ERR dmabuf\n", 11);
                return;
            }
        } else if (line.rfind("FRAME", 0) == 0) {
            if (!handleFrameLine(clientFd, line)) return;
        } else if (line.rfind("ACQUIRE", 0) == 0) {
            if (!handleAcquireLine(clientFd, line)) return;
        } else if (line.rfind("BYE", 0) == 0) {
            replyOk(clientFd);
            return;
        } else {
            writeAll(clientFd, "ERR unknown\n", 12);
        }
    }
}

bool WindowsFrameTransport::handleBufferLine(int clientFd, const std::string& line) {
    long long eye = parseKey(line, "eye", -1);
    long long index = parseKey(line, "index", 0);
    long long w = parseKey(line, "w", 0);
    long long h = parseKey(line, "h", 0);
    const bool swapRedBlue = parseKey(line, "swizzle", 0) != 0;
    if (eye < 0 || eye >= kEyeCount || index < 0 || index >= kMaxImages ||
        w <= 0 || h <= 0) {
        LOGE("xr transport: bad BUFFER line: %s", line.c_str());
        return false;
    }

    if (!replyOk(clientFd)) return false;

    AHardwareBuffer* ahb = nullptr;
    if (AHardwareBuffer_recvHandleFromUnixSocket(clientFd, &ahb) != 0 || ahb == nullptr) {
        LOGE("xr transport: recvHandleFromUnixSocket failed for eye %lld", eye);
        return false;
    }

    storeEyeBuffer(static_cast<int>(eye), ahb,
                   static_cast<int32_t>(w), static_cast<int32_t>(h),
                   static_cast<int32_t>(index), swapRedBlue);
    LOGI("xr transport: received eye %lld AHB buffer %lldx%lld index=%lld", eye, w, h, index);
    return writeAll(clientFd, "OK stored\n", 10);
}

bool WindowsFrameTransport::handleDmabufLine(int clientFd, const std::string& line) {
    long long eye = parseKey(line, "eye", -1);
    long long imageIndex = parseKey(line, "index", -1);
    long long w = parseKey(line, "w", 0);
    long long h = parseKey(line, "h", 0);
    long long planeCount = parseKey(line, "planes", 1);
    if (eye < 0 || eye >= kEyeCount || imageIndex < 0 || imageIndex >= kMaxImages ||
        w <= 0 || h <= 0 || planeCount < 1 || planeCount > EyeFrame::kMaxPlanes) {
        LOGE("xr transport: bad DMABUF line: %s", line.c_str());
        return false;
    }

    EyeFrame frame;
    frame.kind = BufferKind::DmaBuf;
    frame.width = static_cast<int32_t>(w);
    frame.height = static_cast<int32_t>(h);
    frame.imageIndex = static_cast<int32_t>(imageIndex);
    frame.planeCount = static_cast<int>(planeCount);
    frame.fourcc = static_cast<uint32_t>(parseKey(line, "fourcc", 0));
    frame.modifier = static_cast<uint64_t>(parseKey(line, "modifier", 0));
    for (int plane = 0; plane < frame.planeCount; ++plane) {
        char strideKey[16];
        char offsetKey[16];
        std::snprintf(strideKey, sizeof(strideKey), "stride%d", plane);
        std::snprintf(offsetKey, sizeof(offsetKey), "offset%d", plane);
        frame.strides[plane] = static_cast<uint32_t>(
            parseKey(line, strideKey, plane == 0 ? parseKey(line, "stride", w * 4) : 0));
        frame.offsets[plane] = static_cast<uint32_t>(
            parseKey(line, offsetKey, plane == 0 ? parseKey(line, "offset", 0) : 0));
    }

    if (!replyOk(clientFd)) return false;
    for (int plane = 0; plane < frame.planeCount; ++plane) {
        frame.dmabufFds[plane] = recvFd(clientFd);
        if (frame.dmabufFds[plane] < 0) {
            for (int received = 0; received < plane; ++received) ::close(frame.dmabufFds[received]);
            LOGE("xr transport: recvFd (dma-buf plane %d) failed for eye %lld", plane, eye);
            return false;
        }
    }

    storeEyeDmabuf(static_cast<int>(eye), frame);
    LOGI("xr transport: received eye %lld dma-buf %lldx%lld fourcc=0x%08x planes=%d mod=0x%llx",
         eye, w, h, frame.fourcc, frame.planeCount,
         static_cast<unsigned long long>(frame.modifier));
    return writeAll(clientFd, "OK stored\n", 10);
}

bool WindowsFrameTransport::handleFrameLine(int clientFd, const std::string& line) {
    const long long eye = parseKey(line, "eye", -1);
    const long long index = parseKey(line, "index", -1);
    const bool hasFence = parseKey(line, "fence", 0) != 0;
    if (eye < 0 || eye >= kEyeCount || index < 0 || index >= kMaxImages) {
        writeAll(clientFd, "ERR frame\n", 10);
        return true;
    }

    int acquireFenceFd = -1;
    if (hasFence) {
        if (!replyOk(clientFd)) return false;
        acquireFenceFd = recvFd(clientFd);
        if (acquireFenceFd < 0) return false;
    }

    {
        std::lock_guard<std::mutex> lock(eyesMutex_);
        EyeFrame& registered = buffers_[eye][index];
        if (registered.kind == BufferKind::None) {
            if (acquireFenceFd >= 0) ::close(acquireFenceFd);
            writeAll(clientFd, "ERR unregistered\n", 17);
            return true;
        }
        const long long sourceX = parseKey(line, "x", 0);
        const long long sourceY = parseKey(line, "y", 0);
        const long long sourceWidth = parseKey(line, "w", registered.width);
        const long long sourceHeight = parseKey(line, "h", registered.height);
        if (sourceX < 0 || sourceY < 0 || sourceWidth <= 0 || sourceHeight <= 0 ||
            sourceX + sourceWidth > registered.width ||
            sourceY + sourceHeight > registered.height) {
            if (acquireFenceFd >= 0) ::close(acquireFenceFd);
            writeAll(clientFd, "ERR rect\n", 9);
            return true;
        }
        if (latest_[eye].acquireFenceFd >= 0) {
            ::close(latest_[eye].acquireFenceFd);
        }
        if (latest_[eye].kind != BufferKind::None && !latestClaimed_[eye]) {
            const int dropped = latest_[eye].imageIndex;
            if (dropped >= 0 && dropped < kMaxImages &&
                releasePending_[eye][dropped]) {
                releasePending_[eye][dropped] = false;
                if (releaseFenceFds_[eye][dropped] >= 0) {
                    ::close(releaseFenceFds_[eye][dropped]);
                    releaseFenceFds_[eye][dropped] = -1;
                }
            }
        }
        latest_[eye] = registered;
        latest_[eye].sourceX = static_cast<int32_t>(sourceX);
        latest_[eye].sourceY = static_cast<int32_t>(sourceY);
        latest_[eye].sourceWidth = static_cast<int32_t>(sourceWidth);
        latest_[eye].sourceHeight = static_cast<int32_t>(sourceHeight);
        latest_[eye].projectionValid =
            parseKey(line, "projection", 0) != 0;
        if (latest_[eye].projectionValid) {
            static constexpr const char* kOrientationKeys[4] = {
                "qx", "qy", "qz", "qw"};
            static constexpr const char* kPositionKeys[3] = {
                "px", "py", "pz"};
            static constexpr const char* kFovKeys[4] = {
                "fl", "fr", "fu", "fd"};
            for (int component = 0; component < 4; ++component) {
                latest_[eye].projectionOrientation[component] =
                    static_cast<float>(parseKey(
                        line, kOrientationKeys[component],
                        component == 3 ? 1000000 : 0)) / 1000000.0f;
                latest_[eye].projectionFov[component] =
                    static_cast<float>(parseKey(
                        line, kFovKeys[component],
                        component == 0 || component == 3
                            ? -750000 : 750000)) / 1000000.0f;
            }
            for (int component = 0; component < 3; ++component) {
                latest_[eye].projectionPosition[component] =
                    static_cast<float>(parseKey(
                        line, kPositionKeys[component], 0)) / 1000000.0f;
            }
        }
        latest_[eye].acquireFenceFd = acquireFenceFd;
        latest_[eye].serial = nextSerial_++;
        latestClaimed_[eye] = false;
        if (releaseFenceFds_[eye][index] >= 0) {
            ::close(releaseFenceFds_[eye][index]);
            releaseFenceFds_[eye][index] = -1;
        }
        releasePending_[eye][index] = true;
    }
    releaseCv_.notify_all();
    return writeAll(clientFd, hasFence ? "OK stored\n" : "OK\n",
                    hasFence ? 10 : 3);
}

bool WindowsFrameTransport::handleAcquireLine(int clientFd, const std::string& line) {
    const long long eye = parseKey(line, "eye", -1);
    const long long index = parseKey(line, "index", -1);
    const long long timeoutMs = parseKey(line, "timeout", 5000);
    if (eye < 0 || eye >= kEyeCount || index < 0 || index >= kMaxImages) {
        return writeAll(clientFd, "ERR acquire\n", 12);
    }

    std::unique_lock<std::mutex> lock(eyesMutex_);
    const auto ready = [this, eye, index] {
        return !running_.load() ||
               !releasePending_[eye][index] ||
               releaseFenceFds_[eye][index] >= 0;
    };
    bool signaled;
    if (timeoutMs < 0) {
        releaseCv_.wait(lock, ready);
        signaled = true;
    } else {
        signaled = releaseCv_.wait_for(
            lock, std::chrono::milliseconds(timeoutMs), ready);
    }
    if (!signaled) {
        LOGW("xr transport: timed out waiting for release eye=%lld index=%lld", eye, index);
        return writeAll(clientFd, "ERR timeout\n", 12);
    }
    if (!running_.load()) return false;

    const int fenceFd = releaseFenceFds_[eye][index];
    releaseFenceFds_[eye][index] = -1;
    releasePending_[eye][index] = false;
    lock.unlock();

    if (fenceFd < 0) return writeAll(clientFd, "OK fence=0\n", 11);
    const bool sent = writeAll(clientFd, "OK fence=1\n", 11) &&
                      sendFd(clientFd, fenceFd);
    ::close(fenceFd);
    return sent;
}


void WindowsFrameTransport::releaseSlotLocked(int eye, int imageIndex) {
    EyeFrame& slot = buffers_[eye][imageIndex];
    if (slot.kind == BufferKind::HardwareBuffer && slot.buffer != nullptr) {
        AHardwareBuffer_release(slot.buffer);
    } else if (slot.kind == BufferKind::DmaBuf) {
        for (int plane = 0; plane < slot.planeCount; ++plane) {
            if (slot.dmabufFds[plane] >= 0) ::close(slot.dmabufFds[plane]);
        }
    }
    if (slot.acquireFenceFd >= 0) ::close(slot.acquireFenceFd);
    slot = EyeFrame{};
    if (releaseFenceFds_[eye][imageIndex] >= 0) {
        ::close(releaseFenceFds_[eye][imageIndex]);
        releaseFenceFds_[eye][imageIndex] = -1;
    }
    releasePending_[eye][imageIndex] = false;
}

void WindowsFrameTransport::storeEyeBuffer(int eye, AHardwareBuffer* ahb,
                                    int32_t w, int32_t h, int32_t index,
                                    bool swapRedBlue) {
    std::lock_guard<std::mutex> lock(eyesMutex_);
    if (latest_[eye].kind != BufferKind::None &&
        latest_[eye].imageIndex == index) {
        if (latest_[eye].acquireFenceFd >= 0)
            ::close(latest_[eye].acquireFenceFd);
        latest_[eye] = EyeFrame{};
        latestClaimed_[eye] = false;
    }
    releaseSlotLocked(eye, index);
    EyeFrame& slot = buffers_[eye][index];
    slot.kind = BufferKind::HardwareBuffer;
    slot.buffer = ahb;
    slot.swapRedBlue = swapRedBlue;
    slot.width = w;
    slot.height = h;
    slot.imageIndex = index;
    slot.registrationSerial = nextSerial_++;
}

void WindowsFrameTransport::storeEyeDmabuf(int eye, const EyeFrame& incoming) {
    std::lock_guard<std::mutex> lock(eyesMutex_);
    if (latest_[eye].kind != BufferKind::None &&
        latest_[eye].imageIndex == incoming.imageIndex) {
        if (latest_[eye].acquireFenceFd >= 0)
            ::close(latest_[eye].acquireFenceFd);
        latest_[eye] = EyeFrame{};
        latestClaimed_[eye] = false;
    }
    releaseSlotLocked(eye, incoming.imageIndex);
    buffers_[eye][incoming.imageIndex] = incoming;
    buffers_[eye][incoming.imageIndex].registrationSerial = nextSerial_++;
}

void WindowsFrameTransport::releaseEye(int eye) {
    std::lock_guard<std::mutex> lock(eyesMutex_);
    if (latest_[eye].acquireFenceFd >= 0) {
        ::close(latest_[eye].acquireFenceFd);
    }
    latest_[eye] = EyeFrame{};
    latestClaimed_[eye] = false;
    for (int image = 0; image < kMaxImages; ++image) {
        releaseSlotLocked(eye, image);
    }
    releaseCv_.notify_all();
}

void WindowsFrameTransport::resetEye(int eye) {
    std::lock_guard<std::mutex> lock(eyesMutex_);
    if (latest_[eye].acquireFenceFd >= 0) {
        ::close(latest_[eye].acquireFenceFd);
    }
    latest_[eye] = EyeFrame{};
    latestClaimed_[eye] = false;
    for (int image = 0; image < kMaxImages; ++image) {
        if (releaseFenceFds_[eye][image] >= 0) {
            ::close(releaseFenceFds_[eye][image]);
            releaseFenceFds_[eye][image] = -1;
        }
        releasePending_[eye][image] = false;
    }
    releaseCv_.notify_all();
}

void WindowsFrameTransport::dropRetainedLocked(int eye) {
    EyeFrame& held = retained_[eye];
    if (held.kind == BufferKind::HardwareBuffer && held.buffer != nullptr) {
        AHardwareBuffer_release(held.buffer);
    } else if (held.kind == BufferKind::DmaBuf) {
        for (int plane = 0; plane < held.planeCount; ++plane) {
            if (held.dmabufFds[plane] >= 0) ::close(held.dmabufFds[plane]);
        }
    }
    held = EyeFrame{};
}

EyeFrame WindowsFrameTransport::pollEye(int eye) {
    if (eye < 0 || eye >= kEyeCount) return EyeFrame{};
    std::lock_guard<std::mutex> lock(eyesMutex_);
    EyeFrame snapshot = latest_[eye];
    latest_[eye].acquireFenceFd = -1;
    if (snapshot.kind == BufferKind::None) return snapshot;
    latestClaimed_[eye] = true;
    dropRetainedLocked(eye);
    if (snapshot.kind == BufferKind::HardwareBuffer && snapshot.buffer != nullptr) {
        AHardwareBuffer_acquire(snapshot.buffer);
    } else if (snapshot.kind == BufferKind::DmaBuf) {
        for (int plane = 0; plane < snapshot.planeCount; ++plane) {
            snapshot.dmabufFds[plane] =
                snapshot.dmabufFds[plane] >= 0 ? ::dup(snapshot.dmabufFds[plane]) : -1;
        }
    }
    retained_[eye] = snapshot;
    retained_[eye].acquireFenceFd = -1;
    return snapshot;
}

void WindowsFrameTransport::publishReleaseFence(int eye, int imageIndex, int releaseFenceFd) {
    if (eye < 0 || eye >= kEyeCount ||
        imageIndex < 0 || imageIndex >= kMaxImages) {
        if (releaseFenceFd >= 0) ::close(releaseFenceFd);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(eyesMutex_);
        if (releaseFenceFds_[eye][imageIndex] >= 0) {
            ::close(releaseFenceFds_[eye][imageIndex]);
        }
        releaseFenceFds_[eye][imageIndex] = releaseFenceFd;
        if (releaseFenceFd < 0) releasePending_[eye][imageIndex] = false;
    }
    releaseCv_.notify_all();
}

void WindowsFrameTransport::discardFrame(int eye, int imageIndex, uint64_t serial) {
    if (eye < 0 || eye >= kEyeCount ||
        imageIndex < 0 || imageIndex >= kMaxImages) return;
    {
        std::lock_guard<std::mutex> lock(eyesMutex_);
        if (latest_[eye].serial == serial &&
            latest_[eye].imageIndex == imageIndex) {
            if (latest_[eye].acquireFenceFd >= 0)
                ::close(latest_[eye].acquireFenceFd);
            latest_[eye] = EyeFrame{};
            latestClaimed_[eye] = true;
        }
        if (releaseFenceFds_[eye][imageIndex] >= 0) {
            ::close(releaseFenceFds_[eye][imageIndex]);
            releaseFenceFds_[eye][imageIndex] = -1;
        }
        releasePending_[eye][imageIndex] = false;
    }
    releaseCv_.notify_all();
}

bool WindowsFrameTransport::hasStereoContent() const {
    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(eyesMutex_));
    return latest_[0].kind != BufferKind::None &&
           latest_[1].kind != BufferKind::None;
}

}
