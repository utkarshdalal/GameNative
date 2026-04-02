package com.winlator.xconnector;

import androidx.annotation.Keep;

@Keep
final class XConnectorEpollNative {
    static {
        System.loadLibrary("xconnectorpatch");
    }

    private XConnectorEpollNative() {
    }

    @Keep
    static native boolean addFdToEpoll(int epollFd, int fd);

    @Keep
    static native int createAFUnixSocket(String path);

    @Keep
    static native int createEpollFd();

    @Keep
    static native int createEventFd();

    @Keep
    static native boolean doEpollIndefinitely(XConnectorEpoll connector, int epollFd, int serverFd, boolean addClientToEpoll);

    @Keep
    static native void removeFdFromEpoll(int epollFd, int fd);

    @Keep
    static native boolean waitForSocketRead(XConnectorEpoll connector, int clientFd, int shutdownFd);

    @Keep
    static native void closeFd(int fd);
}
