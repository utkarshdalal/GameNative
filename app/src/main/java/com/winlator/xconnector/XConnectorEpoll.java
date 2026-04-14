package com.winlator.xconnector;
import android.util.Log;

import android.util.SparseArray;
import androidx.annotation.Keep;
import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
    private static final String TAG = "XConnectorEpoll";

    private final String connectorLabel;
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

    private boolean addFdToEpoll(int epollFd, int fd) {
        return XConnectorEpollNative.addFdToEpoll(epollFd, fd);
    }

    public static native void closeFd(int i);

    private static void closeTrackedFd(int fd) {
        XConnectorEpollNative.closeFd(fd);
    }

    private int createAFUnixSocket(String path) {
        return XConnectorEpollNative.createAFUnixSocket(path);
    }

    private int createEpollFd() {
        return XConnectorEpollNative.createEpollFd();
    }

    private int createEventFd() {
        return XConnectorEpollNative.createEventFd();
    }

    private boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll) {
        return XConnectorEpollNative.doEpollIndefinitely(this, epollFd, serverFd, addClientToEpoll);
    }

    private void removeFdFromEpoll(int epollFd, int fd) {
        XConnectorEpollNative.removeFdFromEpoll(epollFd, fd);
    }

    private boolean waitForSocketRead(int clientFd, int shutdownFd) {
        return XConnectorEpollNative.waitForSocketRead(this, clientFd, shutdownFd);
    }

    static {
        System.loadLibrary("winlator");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;
        this.connectorLabel = socketConfig.path + " [" + connectionHandler.getClass().getSimpleName() + "/" + requestHandler.getClass().getSimpleName() + "]";
        int createAFUnixSocket = createAFUnixSocket(socketConfig.path);
        this.serverFd = createAFUnixSocket;
        if (createAFUnixSocket < 0) {
            throw new RuntimeException("Failed to create an AF_UNIX socket.");
        }
        int createEpollFd = createEpollFd();
        this.epollFd = createEpollFd;
        if (createEpollFd < 0) {
            closeTrackedFd(createAFUnixSocket);
            throw new RuntimeException("Failed to create epoll fd.");
        }
        if (!addFdToEpoll(createEpollFd, createAFUnixSocket)) {
            closeTrackedFd(createAFUnixSocket);
            closeTrackedFd(createEpollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }
        int createEventFd = createEventFd();
        this.shutdownFd = createEventFd;
        if (!addFdToEpoll(createEpollFd, createEventFd)) {
            closeTrackedFd(createAFUnixSocket);
            closeTrackedFd(createEventFd);
            closeTrackedFd(createEpollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }
        this.epollThread = new Thread(this, "XConnectorEpoll:" + this.connectorLabel);
    }

    private String logPrefix() {
        return "[" + this.connectorLabel + "]";
    }

    public synchronized void start() {
        Thread thread;
        if (!this.running && (thread = this.epollThread) != null) {
            this.running = true;
            Log.d(TAG, logPrefix() + " Starting connector thread (epollFd=" + this.epollFd + ", serverFd=" + this.serverFd + ", shutdownFd=" + this.shutdownFd + ")");
            thread.start();
        }
    }

    public synchronized void stop() {
        if (this.running && this.epollThread != null) {
            Log.d(TAG, logPrefix() + " Stopping connector thread (connectedClients=" + this.connectedClients.size() + ")");
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
                if (this.running) {
                    Log.e(TAG, logPrefix() + " epoll loop exited unexpectedly; shutting down all X clients (epollFd=" + this.epollFd + ", serverFd=" + this.serverFd + ", shutdownFd=" + this.shutdownFd + ", connectedClients=" + this.connectedClients.size() + ", multithreadedClients=" + this.multithreadedClients + ", monitorClients=" + this.monitorClients + ")");
                }
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
            closeTrackedFd(client.shutdownFd);
        } else {
            this.connectionHandler.handleConnectionShutdown(client);
            removeFdFromEpoll(this.epollFd, client.clientSocket.fd);
        }
        closeTrackedFd(client.clientSocket.fd);
        this.connectedClients.remove(client.clientSocket.fd);
    }

    private void shutdown() {
        while (this.connectedClients.size() > 0) {
            Client client = this.connectedClients.valueAt(this.connectedClients.size() - 1);
            killConnection(client);
        }
        removeFdFromEpoll(this.epollFd, this.serverFd);
        removeFdFromEpoll(this.epollFd, this.shutdownFd);
        closeTrackedFd(this.serverFd);
        closeTrackedFd(this.shutdownFd);
        closeTrackedFd(this.epollFd);
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
