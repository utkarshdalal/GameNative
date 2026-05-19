#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <stdbool.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "XConnectorPatch"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define MAX_EVENTS 10
#define MAX_TRACKED_FDS 1024

typedef struct {
    int fd;
    bool open;
} FdTracker;

static FdTracker fdTracking[MAX_TRACKED_FDS] = {0};

static void trackFd(int fd) {
    if (fd < 0) {
        return;
    }

    for (int i = 0; i < MAX_TRACKED_FDS; i++) {
        if (fdTracking[i].open && fdTracking[i].fd == fd) {
            return;
        }
    }

    for (int i = 0; i < MAX_TRACKED_FDS; i++) {
        if (!fdTracking[i].open) {
            fdTracking[i].fd = fd;
            fdTracking[i].open = true;
            return;
        }
    }

    LOGD("fd tracker full, continuing without tracking fd=%d", fd);
}

static bool untrackFd(int fd) {
    for (int i = 0; i < MAX_TRACKED_FDS; i++) {
        if (fdTracking[i].open && fdTracking[i].fd == fd) {
            fdTracking[i].open = false;
            fdTracking[i].fd = -1;
            return true;
        }
    }
    return false;
}

static void closeTrackedFd(int fd) {
    if (fd < 0) {
        return;
    }

    bool wasTracked = untrackFd(fd);
    if (close(fd) == 0) {
        LOGD("closed fd=%d tracked=%s", fd, wasTracked ? "true" : "false");
    } else if (errno != EBADF) {
        LOGD("failed to close fd=%d tracked=%s errno=%d (%s)", fd, wasTracked ? "true" : "false", errno, strerror(errno));
    }
}

static int waitForEpollEvents(int epollFd, struct epoll_event *events, int maxEvents) {
    while (true) {
        int numFds = epoll_wait(epollFd, events, maxEvents, -1);
        if (numFds >= 0) {
            return numFds;
        }
        if (errno == EINTR) {
            LOGD("epoll_wait interrupted for epollFd=%d, retrying", epollFd);
            continue;
        }
        LOGD("epoll_wait failed for epollFd=%d errno=%d (%s)", epollFd, errno, strerror(errno));
        return -1;
    }
}

static int waitForPollEvents(struct pollfd *pfds, nfds_t count) {
    while (true) {
        int res = poll(pfds, count, -1);
        if (res >= 0) {
            return res;
        }
        if (errno == EINTR) {
            LOGD("poll interrupted for fd=%d, retrying", count > 0 ? pfds[0].fd : -1);
            continue;
        }
        LOGD("poll failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_createAFUnixSocket(JNIEnv *env, jclass clazz, jstring path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGD("socket creation failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    trackFd(fd);

    struct sockaddr_un serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sun_family = AF_LOCAL;

    const char *pathPtr = (*env)->GetStringUTFChars(env, path, 0);
    int addrLength = (int)(sizeof(sa_family_t) + strlen(pathPtr));
    strncpy(serverAddr.sun_path, pathPtr, sizeof(serverAddr.sun_path) - 1);
    serverAddr.sun_path[sizeof(serverAddr.sun_path) - 1] = '\0';
    (*env)->ReleaseStringUTFChars(env, path, pathPtr);

    unlink(serverAddr.sun_path);
    if (bind(fd, (struct sockaddr *) &serverAddr, addrLength) < 0) {
        LOGD("bind failed for fd=%d errno=%d (%s)", fd, errno, strerror(errno));
        closeTrackedFd(fd);
        return -1;
    }
    if (listen(fd, MAX_EVENTS) < 0) {
        LOGD("listen failed for fd=%d errno=%d (%s)", fd, errno, strerror(errno));
        closeTrackedFd(fd);
        return -1;
    }

    return fd;
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_createEpollFd(JNIEnv *env, jclass clazz) {
    int fd = epoll_create(MAX_EVENTS);
    if (fd < 0) {
        LOGD("epoll_create failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    trackFd(fd);
    return fd;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_addFdToEpoll(JNIEnv *env, jclass clazz, jint epollFd, jint fd) {
    struct epoll_event event;
    event.data.fd = fd;
    event.events = EPOLLIN;
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &event) < 0) {
        LOGD("epoll_ctl ADD failed epollFd=%d fd=%d errno=%d (%s)", epollFd, fd, errno, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_removeFdFromEpoll(JNIEnv *env, jclass clazz, jint epollFd, jint fd) {
    if (epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, NULL) < 0 && errno != EBADF && errno != ENOENT) {
        LOGD("epoll_ctl DEL failed epollFd=%d fd=%d errno=%d (%s)", epollFd, fd, errno, strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_createEventFd(JNIEnv *env, jclass clazz) {
    int fd = eventfd(0, EFD_NONBLOCK);
    if (fd < 0) {
        LOGD("eventfd creation failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    trackFd(fd);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    closeTrackedFd(fd);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_doEpollIndefinitely(JNIEnv *env, jclass clazz, jobject connector, jint epollFd, jint serverFd, jboolean addClientToEpoll) {
    jclass connectorClass = (*env)->GetObjectClass(env, connector);
    jmethodID handleNewConnection = (*env)->GetMethodID(env, connectorClass, "handleNewConnection", "(I)V");
    jmethodID handleExistingConnection = (*env)->GetMethodID(env, connectorClass, "handleExistingConnection", "(I)V");

    if (handleNewConnection == NULL || handleExistingConnection == NULL) {
        LOGD("failed to resolve XConnectorEpoll callbacks");
        return JNI_FALSE;
    }

    struct epoll_event events[MAX_EVENTS];
    int numFds = waitForEpollEvents(epollFd, events, MAX_EVENTS);
    if (numFds < 0) {
        return JNI_FALSE;
    }

    for (int i = 0; i < numFds; i++) {
        if (events[i].data.fd == serverFd) {
            int clientFd;
            do {
                clientFd = accept(serverFd, NULL, NULL);
            } while (clientFd < 0 && errno == EINTR);

            if (clientFd < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    LOGD("accept failed for serverFd=%d errno=%d (%s)", serverFd, errno, strerror(errno));
                }
                continue;
            }

            trackFd(clientFd);
            if (addClientToEpoll) {
                struct epoll_event event;
                event.data.fd = clientFd;
                event.events = EPOLLIN;
                if (epoll_ctl(epollFd, EPOLL_CTL_ADD, clientFd, &event) < 0) {
                    LOGD("epoll_ctl ADD failed for clientFd=%d errno=%d (%s)", clientFd, errno, strerror(errno));
                    closeTrackedFd(clientFd);
                    continue;
                }
            }
            (*env)->CallVoidMethod(env, connector, handleNewConnection, clientFd);
        } else if ((events[i].events & EPOLLIN) != 0) {
            (*env)->CallVoidMethod(env, connector, handleExistingConnection, events[i].data.fd);
        }
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_waitForSocketRead(JNIEnv *env, jclass clazz, jobject connector, jint clientFd, jint shutdownFd) {
    struct pollfd pfds[2];
    pfds[0].fd = clientFd;
    pfds[0].events = POLLIN;
    pfds[0].revents = 0;

    pfds[1].fd = shutdownFd;
    pfds[1].events = POLLIN;
    pfds[1].revents = 0;

    int res = waitForPollEvents(pfds, 2);
    if (res < 0 || (pfds[1].revents & POLLIN)) {
        return JNI_FALSE;
    }
    if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
        return JNI_FALSE;
    }

    if (pfds[0].revents & POLLIN) {
        jclass connectorClass = (*env)->GetObjectClass(env, connector);
        jmethodID handleExistingConnection = (*env)->GetMethodID(env, connectorClass, "handleExistingConnection", "(I)V");
        if (handleExistingConnection == NULL) {
            LOGD("failed to resolve handleExistingConnection callback");
            return JNI_FALSE;
        }
        (*env)->CallVoidMethod(env, connector, handleExistingConnection, clientFd);
    }

    return JNI_TRUE;
}
