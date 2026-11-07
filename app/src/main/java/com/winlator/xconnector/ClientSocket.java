package com.winlator.xconnector;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class ClientSocket {
    public static class WouldBlockException extends IOException {}

    public final int fd;
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
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
        else if (bytesWritten == -2) throw new WouldBlockException();
        else throw new IOException("Failed to write data.");
    }

    private ParcelFileDescriptor pollFd;

    /**
     * True when a write would not block right now. For an AF_UNIX stream socket POLLOUT means
     * less than a quarter of the send buffer is in use, so a short X event (32 bytes) always goes
     * out whole. Uses a dup of the fd so the public Os.poll API can be used; {@link #closePollFd()}
     * releases it. The timeout must stay 0: BlockGuard runs the StrictMode network check for any
     * other value, which throws NetworkOnMainThreadException on the UI thread.
     */
    public boolean isWritable() {
        try {
            if (pollFd == null) pollFd = ParcelFileDescriptor.fromFd(fd);
            StructPollfd pfd = new StructPollfd();
            pfd.fd = pollFd.getFileDescriptor();
            pfd.events = (short) OsConstants.POLLOUT;
            int res = Os.poll(new StructPollfd[] {pfd}, 0);
            if (res < 0) return true;   // let write() report the error as before
            return (pfd.revents & OsConstants.POLLOUT) != 0;
        } catch (Exception e) {
            if (pollFailures++ % 1000 == 0) Log.w("ClientSocket", "poll on fd " + fd + " failed", e);
            return true;                // same: fall through to the blocking write
        }
    }

    private int pollFailures;

    public void closePollFd() {
        ParcelFileDescriptor p = pollFd;
        pollFd = null;
        if (p != null) {
            try {
                p.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
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
