package com.winlator.xconnector;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class ClientSocket {
    public final int fd;
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    // A dup of fd, only for poll(): android.system.Os needs a FileDescriptor, not an int
    private ParcelFileDescriptor pollFd;

    /**
     * True when the socket has room to send without blocking (POLLOUT). The socket itself is
     * blocking, so a write to a client that stopped reading (a hung game) would block forever.
     * When the state can't be determined this returns true, i.e. the old blocking behaviour.
     */
    public synchronized boolean isWritable() {
        try {
            if (pollFd == null) pollFd = ParcelFileDescriptor.fromFd(fd);
            StructPollfd pfd = new StructPollfd();
            pfd.fd = pollFd.getFileDescriptor();
            pfd.events = (short) OsConstants.POLLOUT;
            return Os.poll(new StructPollfd[]{pfd}, 0) > 0 && (pfd.revents & OsConstants.POLLOUT) != 0;
        }
        catch (Exception e) {
            return true;
        }
    }

    /** Releases what {@link #isWritable()} opened; call when the connection closes. */
    public synchronized void release() {
        if (pollFd == null) return;
        try {
            pollFd.close();
        }
        catch (IOException ignored) {
        }
        pollFd = null;
    }

    public boolean hasAncillaryFds() {
        return !ancillaryFds.isEmpty();
    }

    public int getAncillaryFd() {
        return hasAncillaryFds() ? ancillaryFds.poll() : -1;
    }

    @Keep
    public void addAncillaryFd(int ancillaryFd) {
        ancillaryFds.add(ancillaryFd);
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = read(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        int bytesWritten = write(fd, data, data.limit());
        if (bytesWritten >= 0) {
            data.position(bytesWritten);
        }
        else throw new IOException("Failed to write data.");
    }

    public int recvAncillaryMsg(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = recvAncillaryMsg(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to receive ancillary messages.");
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        int bytesSent = sendAncillaryMsg(fd, data, data.limit(), ancillaryFd);
        if (bytesSent >= 0) {
            data.position(bytesSent);
        }
        else throw new IOException("Failed to send ancillary messages.");
    }

    private native int read(int fd, ByteBuffer data, int offset, int length);

    private native int write(int fd, ByteBuffer data, int length);

    private native int recvAncillaryMsg(int clientFd, ByteBuffer data, int offset, int length);

    private native int sendAncillaryMsg(int clientFd, ByteBuffer data, int length, int ancillaryFd);
}
