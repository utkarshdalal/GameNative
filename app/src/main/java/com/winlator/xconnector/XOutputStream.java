package com.winlator.xconnector;

import com.winlator.xserver.XServer;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    private static final String TAG = "XOutputStream";
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;
    private static final double FP3232_SCALE = 4294967296.0;

    // Writes never block the calling thread. Input events are sent from the UI thread, and a
    // client that stops reading (a game stalled in a load) filled its socket buffer and parked
    // the UI thread in write() until Android raised an ANR. A send that would block is queued
    // here, in order, and a drain thread finishes it with blocking sends; later flushes append
    // behind it so the byte stream stays ordered.
    private static final long MAX_PENDING_BYTES = 16L * 1024 * 1024;
    private static final ExecutorService DRAINER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "XOutputStreamDrain");
        t.setDaemon(true);
        return t;
    });
    private final ArrayDeque<ByteBuffer> pending = new ArrayDeque<>();   // guarded by lock
    private final Condition drained = lock.newCondition();
    private long pendingBytes;                                           // guarded by lock
    private boolean draining;                                            // guarded by lock
    private boolean failed;                                              // guarded by lock
    private boolean overflowLogged;

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

    // Called with lock held.
    private void flush() throws IOException {
        if (buffer.position() != 0) {
            buffer.flip();

            if (failed) throw new IOException("Failed to write data.");

            if (ancillaryFd != -1) {
                // The fd travels with this message and callers close it right after the lock is
                // released, so it cannot be deferred: wait for queued bytes to go first.
                awaitDrained();
                try {
                    clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                } finally {
                    ancillaryFd = -1;
                }
            }
            else if (pending.isEmpty()) {
                clientSocket.send(buffer, true);
                if (buffer.hasRemaining()) enqueueRemaining();
            }
            else enqueueRemaining();

            buffer.clear();
        }
    }

    private void enqueueRemaining() {
        int remaining = buffer.remaining();
        if (pendingBytes + remaining > MAX_PENDING_BYTES) {
            // The client has not read for a very long time; keep the UI thread free rather
            // than grow without bound. Dropped bytes are logged once per stream.
            if (!overflowLogged) {
                overflowLogged = true;
                Log.w(TAG, "fd " + clientSocket.fd + " is not reading; dropping output beyond "
                        + MAX_PENDING_BYTES + " queued bytes");
            }
            return;
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(remaining).order(buffer.order());
        copy.put(buffer).flip();
        pending.addLast(copy);
        pendingBytes += remaining;
        if (!draining) {
            draining = true;
            DRAINER.execute(this::drainPending);
        }
    }

    // Lock is held by the caller (an ancillary send); await() releases it while the drain runs.
    private void awaitDrained() throws IOException {
        boolean interrupted = false;
        while (draining && !failed) {
            try {
                drained.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failed) throw new IOException("Failed to write data.");
    }

    private void drainPending() {
        while (true) {
            ByteBuffer head;
            lock.lock();
            try {
                head = pending.peekFirst();
                if (head == null) {
                    draining = false;
                    drained.signalAll();
                    return;
                }
            } finally {
                lock.unlock();
            }

            // Blocking send without the lock: other threads keep appending behind this chunk.
            try {
                clientSocket.sendFully(head);
            } catch (IOException e) {
                lock.lock();
                try {
                    failed = true;
                    pending.clear();
                    pendingBytes = 0;
                    draining = false;
                    drained.signalAll();
                } finally {
                    lock.unlock();
                }
                return;
            }

            lock.lock();
            try {
                pending.pollFirst();
                pendingBytes -= head.capacity();
            } finally {
                lock.unlock();
            }
        }
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
