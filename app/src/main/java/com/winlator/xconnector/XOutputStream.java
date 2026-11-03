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
    private volatile boolean abandoned;                                  // set under lock, read by the drain
    private boolean closeOnDrainExit;                                    // guarded by lock

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

            if (failed) {
                ancillaryFd = -1;
                buffer.clear();
                throw new IOException("Failed to write data.");
            }

            if (ancillaryFd != -1) {
                // The fd travels with this message and callers close it right after the lock is
                // released, so it cannot be deferred: queued bytes must go first. Waiting releases
                // the lock, so the message and fd are snapshotted before another writer can reuse
                // the shared buffer.
                int fd = ancillaryFd;
                ancillaryFd = -1;
                ByteBuffer message = copyRemaining();
                buffer.clear();
                awaitDrained();
                clientSocket.sendAncillaryMsg(message, fd);
                return;
            }

            if (pending.isEmpty()) {
                clientSocket.send(buffer, true);
                if (buffer.hasRemaining()) enqueueRemaining();
            }
            else enqueueRemaining();

            buffer.clear();
        }
    }

    private ByteBuffer copyRemaining() {
        ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining()).order(buffer.order());
        copy.put(buffer).flip();
        return copy;
    }

    // Called with lock held.
    private void enqueueRemaining() throws IOException {
        int remaining = buffer.remaining();
        if (pendingBytes + remaining > MAX_PENDING_BYTES) {
            // Dropping bytes would desynchronise the X stream, and a client that has not read
            // this much output is gone for practical purposes: fail the stream so the request
            // handler closes the connection.
            Log.w(TAG, "fd " + clientSocket.fd + " has not read " + MAX_PENDING_BYTES
                    + " queued bytes; failing the stream");
            abandonLocked();
            failed = true;
            buffer.clear();
            throw new IOException("Failed to write data.");
        }
        pending.addLast(copyRemaining());
        pendingBytes += remaining;
        if (!draining) {
            draining = true;
            DRAINER.execute(this::drainPending);
        }
    }

    // Lock is held by the caller; await() releases it while the drain runs.
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

    /**
     * Stops queued output for a connection that is being closed. Returns true when the drain
     * thread still holds a send in flight: it then owns the fd and closes it when the send
     * returns, so the number cannot be reused by a new client underneath it. When false the
     * caller closes the fd as usual.
     */
    public boolean abandon() {
        lock.lock();
        try {
            boolean inFlight = draining;
            abandonLocked();
            failed = true;
            if (inFlight) closeOnDrainExit = true;
            return inFlight;
        } finally {
            lock.unlock();
        }
    }

    // Called with lock held.
    private void abandonLocked() {
        abandoned = true;
        pending.clear();
        pendingBytes = 0;
        drained.signalAll();
    }

    private void drainPending() {
        try {
            while (true) {
                ByteBuffer head;
                lock.lock();
                try {
                    head = abandoned ? null : pending.peekFirst();
                    if (head == null) return;
                } finally {
                    lock.unlock();
                }

                // Blocking sends without the lock: other threads keep appending behind this chunk.
                try {
                    while (head.hasRemaining()) {
                        if (abandoned) return;
                        clientSocket.send(head, false);
                    }
                } catch (IOException e) {
                    lock.lock();
                    try {
                        failed = true;
                        pending.clear();
                        pendingBytes = 0;
                    } finally {
                        lock.unlock();
                    }
                    return;
                }

                lock.lock();
                try {
                    if (pending.peekFirst() == head) {
                        pending.pollFirst();
                        pendingBytes -= head.capacity();
                    }
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            boolean close;
            lock.lock();
            try {
                draining = false;
                close = closeOnDrainExit;
                closeOnDrainExit = false;
                drained.signalAll();
            } finally {
                lock.unlock();
            }
            if (close) XConnectorEpoll.closeFd(clientSocket.fd);
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
