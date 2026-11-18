package com.winlator.xenvironment.components;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.core.content.ContextCompat;

import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Exposes the Android microphone to Wine/Proton as a real PulseAudio *source*.
 *
 * How it works
 * ------------
 * {@link PulseAudioComponent} loads PulseAudio's stock {@code module-pipe-source}, which creates a
 * FIFO and registers a capture device ("source") that reads raw PCM from it. This component captures
 * from the Android microphone with {@link AudioRecord} and feeds that PCM into the FIFO.
 *
 * Wine's {@code winepulse.drv} enumerates every PulseAudio source it finds, so the microphone shows
 * up inside the container as a normal Windows recording device with no Wine-side patching needed.
 *
 * Safety / performance notes
 * --------------------------
 * - The FIFO is opened with {@code O_NONBLOCK}: if nothing inside the container is recording, the
 *   pipe fills up and writes fail fast with EAGAIN. We simply drop the chunk. We never block, never
 *   grow an unbounded buffer and never accumulate latency.
 * - Chunks are 20 ms (1920 bytes) which is below {@code PIPE_BUF} (4096), so every write is atomic:
 *   there is no risk of handing PulseAudio a torn frame.
 * - All work happens on one dedicated thread at {@code THREAD_PRIORITY_AUDIO}. It is driven by
 *   {@link AudioRecord#read} which blocks in the kernel, so it costs ~0% CPU and cannot stutter the
 *   game's render/emulation threads.
 * - Capture is fully released while the app is backgrounded (see {@link #pause()}/{@link #resume()}),
 *   so the mic indicator and the power cost only exist while the game is actually in the foreground.
 * - If the {@code RECORD_AUDIO} permission is missing, or {@code module-pipe-source.so} is not
 *   shipped, this component silently does nothing: audio output is never affected.
 */
public class MicrophoneComponent extends EnvironmentComponent {
    private static final String TAG = "MicrophoneComponent";

    /** Must stay in sync with the module arguments written by {@link PulseAudioComponent}. */
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_COUNT = 1;

    private static final int BYTES_PER_FRAME = 2 * CHANNEL_COUNT; // s16le
    private static final int CHUNK_FRAMES = SAMPLE_RATE / 50;     // 20 ms
    private static final int CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME; // 1920 (< PIPE_BUF)

    /** How long we wait for PulseAudio to create the FIFO before giving up. */
    private static final long FIFO_WAIT_TIMEOUT_MS = 10_000;
    private static final long FIFO_POLL_INTERVAL_MS = 100;

    private static final int[] INPUT_PRESETS = {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, // echo cancel + noise suppression when available
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT
    };

    private final File fifoFile;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile Thread worker;

    public MicrophoneComponent(File fifoFile) {
        this.fifoFile = fifoFile;
    }

    @Override
    public void start() {
        if (fifoFile == null) return;
        if (!running.compareAndSet(false, true)) return;

        paused.set(false);

        Thread thread = new Thread(this::captureLoop, "GN-MicrophoneBridge");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        Timber.tag(TAG).d("Started (fifo=%s)", fifoFile);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Timber.tag(TAG).d("Stopped");
    }

    /** Releases the microphone while the activity is in the background. */
    public void pause() {
        paused.set(true);
    }

    /** Re-acquires the microphone when the activity comes back to the foreground. */
    public void resume() {
        paused.set(false);
    }

    private boolean hasPermission() {
        Context context = environment != null ? environment.getContext() : null;
        if (context == null) return false;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        if (!hasPermission()) {
            Timber.tag(TAG).w("RECORD_AUDIO permission not granted, microphone bridge disabled");
            running.set(false);
            return;
        }

        FileDescriptor fd = awaitAndOpenFifo();
        if (fd == null) {
            running.set(false);
            return;
        }

        AudioRecord recorder = null;
        final byte[] buffer = new byte[CHUNK_BYTES];
        long droppedChunks = 0;

        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (paused.get()) {
                    recorder = releaseRecorder(recorder);
                    sleep(150);
                    continue;
                }

                if (recorder == null) {
                    recorder = createRecorder();
                    if (recorder == null) {
                        // Another app may hold exclusive access; back off instead of spinning.
                        sleep(1000);
                        continue;
                    }
                }

                int read = recorder.read(buffer, 0, CHUNK_BYTES);
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE
                        || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        // Typically a device disconnect (USB/BT mic unplugged). Rebuild the stream.
                        Timber.tag(TAG).w("AudioRecord.read() failed (%d), reopening", read);
                        recorder = releaseRecorder(recorder);
                        sleep(250);
                    }
                    continue;
                }

                if (!writeChunk(fd, buffer, read)) {
                    // Nobody inside the container is recording right now, the pipe is full.
                    if ((++droppedChunks % 500) == 0) {
                        Timber.tag(TAG).d("No PulseAudio consumer, dropped %d chunks so far", droppedChunks);
                    }
                }
            }
        } catch (Throwable t) {
            Timber.tag(TAG).e(t, "Microphone bridge terminated");
        } finally {
            releaseRecorder(recorder);
            closeQuietly(fd);
            running.set(false);
        }
    }

    /**
     * PulseAudio creates the FIFO from {@code module-pipe-source}'s init. Wait for it, then open the
     * write end non-blocking. The module keeps the FIFO open O_RDWR, so opening never blocks and we
     * can never see EOF/EPIPE while the server is alive.
     */
    private FileDescriptor awaitAndOpenFifo() {
        long deadline = System.currentTimeMillis() + FIFO_WAIT_TIMEOUT_MS;

        while (running.get() && System.currentTimeMillis() < deadline) {
            if (fifoFile.exists()) {
                try {
                    return Os.open(fifoFile.getAbsolutePath(),
                        OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.ENXIO && e.errno != OsConstants.ENOENT) {
                        Timber.tag(TAG).e("Unable to open mic FIFO: %s", e.getMessage());
                        return null;
                    }
                    // Reader side not ready yet, retry.
                }
            }
            if (!sleep(FIFO_POLL_INTERVAL_MS)) return null;
        }

        Timber.tag(TAG).w("PulseAudio mic source FIFO never appeared (%s); is module-pipe-source.so shipped?",
            fifoFile);
        return null;
    }

    /** @return true when the whole chunk was handed to PulseAudio, false when it was dropped. */
    private boolean writeChunk(FileDescriptor fd, byte[] buffer, int length) throws ErrnoException {
        int offset = 0;
        while (offset < length) {
            try {
                int written = Os.write(fd, buffer, offset, length - offset);
                if (written <= 0) return false;
                offset += written;
            } catch (ErrnoException e) {
                // On Linux EAGAIN == EWOULDBLOCK: the pipe is full, nothing is recording.
                if (e.errno == OsConstants.EAGAIN) return false;
                if (e.errno == OsConstants.EINTR) continue;
                throw e;
            } catch (InterruptedIOException e) {
                return false;
            }
        }
        return true;
    }

    private AudioRecord createRecorder() {
        int minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) minBufferSize = CHUNK_BYTES * 4;
        int bufferSize = Math.max(minBufferSize, CHUNK_BYTES * 4);

        for (int source : INPUT_PRESETS) {
            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording();
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        Timber.tag(TAG).d("Capturing at %d Hz mono (audioSource=%d)", SAMPLE_RATE, source);
                        return recorder;
                    }
                }
            } catch (SecurityException e) {
                Timber.tag(TAG).w("RECORD_AUDIO denied while opening AudioRecord");
                releaseRecorder(recorder);
                return null;
            } catch (Exception e) {
                Timber.tag(TAG).d("AudioRecord source %d unavailable: %s", source, e.getMessage());
            }
            releaseRecorder(recorder);
        }

        Timber.tag(TAG).w("No usable microphone input could be opened");
        return null;
    }

    private AudioRecord releaseRecorder(AudioRecord recorder) {
        if (recorder == null) return null;
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        return null;
    }

    private void closeQuietly(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}


