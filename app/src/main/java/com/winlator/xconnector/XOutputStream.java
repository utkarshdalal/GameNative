package com.winlator.xconnector;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;
    private static final double FP3232_SCALE = 4294967296.0;
    // A healthy client can keep the socket full for a few milliseconds while it drains a large
    // reply, so the UI thread waits this long once before it starts dropping. While stalled it
    // checks without waiting, so a flood of queued touch events cannot add up to an ANR.
    private static final int UI_STALL_GRACE_MS = 50;
    private boolean uiStalled;
    private int droppedOnUiThread;

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        buffer.putLong(value);
    }

    public void writeFP3232(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("FP3232 value must be finite");
        }

        long fixed = Math.round(value * FP3232_SCALE);

        int integral = (int) (fixed >> 32);
        int frac = (int) fixed;

        // FP3232 is a struct { int32_t integral; uint32_t frac; } in X11.
        writeInt(integral);
        writeInt(frac);
    }

    public void writeFP3232(int integerPart, long fractionalPart) {
        if (fractionalPart < 0L || fractionalPart > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("fractionalPart must be in range 0 .. 0xFFFFFFFF");
        }

        writeInt(integerPart);
        writeInt((int) fractionalPart);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        buffer.put(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    private void flush() throws IOException {
        if (buffer.position() != 0) {
            buffer.flip();

            if (ancillaryFd != -1) {
                clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                ancillaryFd = -1;
            }
            else if (!canWriteFromUiThread()) {
                // Input events are sent from the UI thread. A client that has stopped reading
                // (a game stalled in a load, or hung) has a full socket buffer, and write()
                // would park the UI thread until Android raised an ANR. The client is tens of
                // seconds behind at that point, so this event is dropped instead.
                if (droppedOnUiThread++ % 1000 == 0) {
                    Log.w("XOutputStream", "fd " + clientSocket.fd + " is not reading; dropped "
                            + (droppedOnUiThread) + " UI-thread writes so far");
                }
            }
            else clientSocket.write(buffer);

            buffer.clear();
        }
    }

    // Called with lock held.
    private boolean canWriteFromUiThread() {
        if (!Looper.getMainLooper().isCurrentThread()) return true;
        if (!clientSocket.isWritable()) {
            // First sign of a stall: give a healthy client one short grace period to drain a
            // large reply. A poll with a timeout cannot be used here (see ClientSocket), so this
            // is a plain sleep followed by a second instant check.
            if (uiStalled) return false;
            SystemClock.sleep(UI_STALL_GRACE_MS);
            if (!clientSocket.isWritable()) {
                uiStalled = true;
                return false;
            }
        }
        uiStalled = false;
        return true;
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() + length).order(buffer.order());
        buffer.rewind();
        newBuffer.put(buffer).position(position);
        buffer = newBuffer;
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
        }
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            }
            finally {
                lock.unlock();
            }
        }
    }
}
