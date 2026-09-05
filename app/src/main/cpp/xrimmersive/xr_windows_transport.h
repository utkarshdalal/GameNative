
















#pragma once

#include <android/hardware_buffer.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

namespace xrimmersive::windowsvr {



enum class BufferKind { None, HardwareBuffer, DmaBuf };




struct EyeFrame {
    static constexpr int kMaxPlanes = 4;
    BufferKind kind{BufferKind::None};

    AHardwareBuffer* buffer{nullptr};
    bool swapRedBlue{false};

    int planeCount{0};
    int dmabufFds[kMaxPlanes]{-1, -1, -1, -1};
    uint32_t fourcc{0};
    uint32_t strides[kMaxPlanes]{0, 0, 0, 0};
    uint32_t offsets[kMaxPlanes]{0, 0, 0, 0};
    uint64_t modifier{0};

    int32_t width{0};
    int32_t height{0};
    int32_t sourceX{0};
    int32_t sourceY{0};
    int32_t sourceWidth{0};
    int32_t sourceHeight{0};
    bool flipY{false};
    bool projectionValid{false};
    float projectionOrientation[4]{0.0f, 0.0f, 0.0f, 1.0f};
    float projectionPosition[3]{0.0f, 0.0f, 0.0f};
    float projectionFov[4]{-0.75f, 0.75f, 0.75f, -0.75f};
    int32_t imageIndex{0};
    int32_t acquireFenceFd{-1};
    uint64_t registrationSerial{0};
    uint64_t serial{0};
};

class WindowsFrameTransport {
public:
    static constexpr int kEyeCount = 2;
    static constexpr int kMaxImages = 128;

    WindowsFrameTransport();
    ~WindowsFrameTransport();

    WindowsFrameTransport(const WindowsFrameTransport&) = delete;
    WindowsFrameTransport& operator=(const WindowsFrameTransport&) = delete;

    void start(const std::string& socketPath);

    void stop();

    EyeFrame pollEye(int eye);

    void publishReleaseFence(int eye, int imageIndex, int releaseFenceFd);

    void discardFrame(int eye, int imageIndex, uint64_t serial);

    bool hasStereoContent() const;

private:
    void acceptLoop();
    void serviceClient(int clientFd);
    bool handleBufferLine(int clientFd, const std::string& line);
    bool handleDmabufLine(int clientFd, const std::string& line);
    bool handleFrameLine(int clientFd, const std::string& line);
    bool handleAcquireLine(int clientFd, const std::string& line);
    void storeEyeBuffer(int eye, AHardwareBuffer* ahb, int32_t w, int32_t h,
                        int32_t index, bool swapRedBlue);
    void storeEyeDmabuf(int eye, const EyeFrame& incoming);
    void releaseSlotLocked(int eye, int imageIndex);
    void releaseEye(int eye);
    void resetEye(int eye);
    void dropRetainedLocked(int eye);

    std::string socketPath_;
    std::atomic<bool> running_{false};
    std::atomic<int> listenFd_{-1};
    std::atomic<int> clientFd_{-1};
    std::thread acceptThread_;

    std::mutex eyesMutex_;
    std::condition_variable releaseCv_;
    EyeFrame buffers_[kEyeCount][kMaxImages];
    EyeFrame latest_[kEyeCount];
    EyeFrame retained_[kEyeCount];
    bool latestClaimed_[kEyeCount]{false, false};
    int releaseFenceFds_[kEyeCount][kMaxImages];
    bool releasePending_[kEyeCount][kMaxImages]{};
    uint64_t nextSerial_{1};
};

}
