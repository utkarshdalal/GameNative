package com.winlator.xconnector;

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
    // Droppable locks currently held (guarded by lock)
    private int droppableDepth = 0;
    private static final double FP3232_SCALE = 4294967296.0;

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
            else clientSocket.write(buffer);

            buffer.clear();
        }
    }

    public XStreamLock lock() {
        return new OutputStreamLock(false);
    }

    /**
     * Like {@link #lock()}, but for one self-contained message (an event) that may be dropped: when
     * the client isn't reading and its socket has no room, the message is discarded instead of
     * blocking the sender. Events are sent while holding X server locks, often from the UI thread
     * (input injection), so one hung client would otherwise freeze the whole app (ANR).
     */
    public XStreamLock lockDroppable() {
        return new OutputStreamLock(true);
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
        private final boolean droppable;
        // Whatever was buffered before this lock was taken (normally nothing) is never dropped
        private final int startPosition;

        public OutputStreamLock(boolean droppable) {
            lock.lock();
            this.droppable = droppable;
            this.startPosition = buffer.position();
            if (droppable) droppableDepth++;
        }

        @Override
        public void close() throws IOException {
            try {
                // Events take this lock again inside send(); while a droppable lock is held, nested
                // locks leave the flush (or the drop) to it
                if (lock.getHoldCount() > 1 && droppableDepth > 0) return;
                // An in-memory stream (no socket) keeps what was written
                if (clientSocket == null) return;
                if (droppable && startPosition == 0 && ancillaryFd == -1
                        && buffer.position() != 0 && !clientSocket.isWritable()) {
                    buffer.clear();
                }
                else flush();
            }
            finally {
                if (droppable) droppableDepth--;
                lock.unlock();
            }
        }
    }
}
