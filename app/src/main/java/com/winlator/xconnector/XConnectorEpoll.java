package com.winlator.xconnector;
import android.util.Log;

import android.util.SparseArray;
import androidx.annotation.Keep;
import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
    private final ConnectionHandler connectionHandler;
    private final int epollFd;
    private Thread epollThread;
    private final RequestHandler requestHandler;
    private final int serverFd;
    private final int shutdownFd;
    private boolean running = false;
    private boolean multithreadedClients = false;
    private boolean canReceiveAncillaryMessages = false;
    private boolean monitorClients = true;
    private int initialInputBufferCapacity = 128;
    private int initialOutputBufferCapacity = 128;
    private final SparseArray<Client> connectedClients = new SparseArray<>();

    private native boolean addFdToEpoll(int i, int i2);

    public static native void closeFd(int i);

    private native int createAFUnixSocket(String str);

    private native int createEpollFd();

    private native int createEventFd();

    private native boolean doEpollIndefinitely(int i, int i2, boolean z);

    private native void removeFdFromEpoll(int i, int i2);

    private native boolean waitForSocketRead(int i, int i2);

    static {
        System.loadLibrary("winlator");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;
        int createAFUnixSocket = createAFUnixSocket(socketConfig.path);
        this.serverFd = createAFUnixSocket;
        if (createAFUnixSocket < 0) {
            throw new RuntimeException("Failed to create an AF_UNIX socket.");
        }
        int createEpollFd = createEpollFd();
        this.epollFd = createEpollFd;
        if (createEpollFd < 0) {
            closeFd(createAFUnixSocket);
            throw new RuntimeException("Failed to create epoll fd.");
        }
        if (!addFdToEpoll(createEpollFd, createAFUnixSocket)) {
            closeFd(createAFUnixSocket);
            closeFd(createEpollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }
        int createEventFd = createEventFd();
        this.shutdownFd = createEventFd;
        if (!addFdToEpoll(createEpollFd, createEventFd)) {
            closeFd(createAFUnixSocket);
            closeFd(createEventFd);
            closeFd(createEpollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }
        this.epollThread = new Thread(this);
    }

    public synchronized void start() {
        Thread thread;
        if (!this.running && (thread = this.epollThread) != null) {
            this.running = true;
            thread.start();
        }
    }

    public synchronized void stop() {
        if (this.running && this.epollThread != null) {
            this.running = false;
            requestShutdown();
            while (this.epollThread.isAlive()) {
                try {
                    this.epollThread.join();
                } catch (InterruptedException e) {
                }
            }
            this.epollThread = null;
        }
    }

    @Override // java.lang.Runnable
    public void run() {
        while (this.running) {
            if (!doEpollIndefinitely(this.epollFd, this.serverFd, !this.multithreadedClients && this.monitorClients)) {
                break;
            }
        }
        shutdown();
    }

    @Keep
    private void handleNewConnection(int fd) {
        final Client client = new Client(this, new ClientSocket(fd));
        client.connected = true;
        if (this.multithreadedClients) {
            client.shutdownFd = createEventFd();
            client.pollThread = new Thread(() -> {
                connectionHandler.handleNewConnection(client);
                while (client.connected &&                        // stay in loop
                        waitForSocketRead(client.clientSocket.fd,  // until socket readable
                                client.shutdownFd)) { }  //   or shutdown signalled
            });
            client.pollThread.start();
        } else {
            this.connectionHandler.handleNewConnection(client);
        }
        this.connectedClients.put(fd, client);
    }

    @Keep
    private void handleExistingConnection(int fd) {
        Client client = this.connectedClients.get(fd);
        if (client == null) {
            return;
        }
        XInputStream inputStream = client.getInputStream();
        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(this.canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (this.running && this.requestHandler.handleRequest(client)) {
                        activePosition = inputStream.getActivePosition();
                    }
                    inputStream.setActivePosition(activePosition);
                    return;
                }
                killConnection(client);
                return;
            }
            this.requestHandler.handleRequest(client);
        } catch (IOException e) {
            killConnection(client);
        }
    }

    public Client getClient(int fd) {
        return this.connectedClients.get(fd);
    }

    public void killConnection(Client client) {
        client.connected = false;
        if (this.multithreadedClients) {
            if (Thread.currentThread() != client.pollThread) {
                client.requestShutdown();
                while (client.pollThread.isAlive()) {
                    try {
                        client.pollThread.join();
                    } catch (InterruptedException e) {
                    }
                }
                this.connectionHandler.handleConnectionShutdown(client);
                client.pollThread = null;
            }
            closeFd(client.shutdownFd);
        } else {
            this.connectionHandler.handleConnectionShutdown(client);
            removeFdFromEpoll(this.epollFd, client.clientSocket.fd);
        }
        closeFd(client.clientSocket.fd);
        this.connectedClients.remove(client.clientSocket.fd);
    }

    private void shutdown() {
        while (this.connectedClients.size() > 0) {
            Client client = this.connectedClients.valueAt(this.connectedClients.size() - 1);
            killConnection(client);
        }
        removeFdFromEpoll(this.epollFd, this.serverFd);
        removeFdFromEpoll(this.epollFd, this.shutdownFd);
        closeFd(this.serverFd);
        closeFd(this.shutdownFd);
        closeFd(this.epollFd);
    }

    public int getInitialInputBufferCapacity() {
        return this.initialInputBufferCapacity;
    }

    public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
        this.initialInputBufferCapacity = initialInputBufferCapacity;
    }

    public int getInitialOutputBufferCapacity() {
        return this.initialOutputBufferCapacity;
    }

    public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
        this.initialOutputBufferCapacity = initialOutputBufferCapacity;
    }

    public void setMultithreadedClients(boolean multithreadedClients) {
        this.multithreadedClients = multithreadedClients;
    }

    public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
        this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
    }

    public int getConnectedClientsCount() {
        return this.connectedClients.size();
    }

    public Client getConnectedClientAt(int index) {
        if (index >= 0 && index < this.connectedClients.size()) {
            return this.connectedClients.valueAt(index);
        }
        return null;
    }

    private void requestShutdown() {
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(8);
            data.asLongBuffer().put(1L);
            new ClientSocket(this.shutdownFd).write(data);
        } catch (IOException e) {
        }
    }
}
