package app.gamenative.display;

import com.winlator.container.Container;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

import timber.log.Timber;

/**
 * The gamescope session's app-side endpoint: starts the wayland injector and drives the
 * command queue's applicability.
 */
public class GamescopeServerComponent extends EnvironmentComponent {

    /** How long to wait for gamescope to answer before giving up on the start re-apply. */
    private static final int START_POLL_TRIES = 120;
    private static final long START_POLL_MS = 250;

    private final File xdgRunDir;
    private volatile boolean stopped;

    public GamescopeServerComponent(File filesDir, ImageFs imageFs, Container container) {
        // Gamescope mode: no winlator X11 server (gamescope spawns its own XWayland); start the wayland
        // injector, stopped in exit().
        GamescopeCommandQueue.beginSession(
            GamescopeSettings.gamescopeDir(filesDir),
            GamescopeSettings.x11DisplayPath(imageFs.getRootDir()),
            GamescopeSettings.fromContainer(container)
        );

        xdgRunDir = new File(imageFs.getRootDir().getPath(), "tmp/xdg-run");
    }

    @Override
    public void start() {
        int rc = GameNativeDrm.nativeInjectorStart(xdgRunDir.getAbsolutePath(), "gamescope-0");
        if (rc != 0) {
            Timber.w("GameNativeDrm.nativeInjectorStart failed: $rc");
            return;
        }
        // The injector connects to gamescope's wayland socket a moment after it starts; a command sent
        // before then is dropped.
        Thread waiter = new Thread(this::awaitConnected, "gamescope-reapply");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void awaitConnected() {
        for (int i = 0; i < START_POLL_TRIES && !stopped; i++) {
            if (gamescopeAnswered()) {
                GamescopeCommandQueue.setApplicable(true);
                return;
            }
            try {
                Thread.sleep(START_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Whether gamescope has answered display_info (connector or features present). */
    private static boolean gamescopeAnswered() {
        String info = GameNativeDrm.nativeDisplayInfo();
        if (info == null || info.isEmpty()) return false;
        for (String field : info.split(";")) {
            int eq = field.indexOf('=');
            if (eq < 0) continue;
            String key = field.substring(0, eq);
            String value = field.substring(eq + 1);
            if (key.equals("features") && !value.equals("0x0")) return true;
            if (key.equals("connector") && !value.isEmpty()) return true;
        }
        return false;
    }

    @Override
    public void stop() {
        stopped = true;
        GamescopeCommandQueue.reset();
        GameNativeDrm.nativeInjectorStop();
    }

    public void pause() {
        // Not applicable while gamescope is frozen: setters only update the
        // desired state, and the resume re-apply sends the whole set.
        GamescopeCommandQueue.setApplicable(false);
        GameNativeDrm.nativeInjectorSetSuspended(true);
    }

    public void resume() {
        // Injector may touch the socket again: mark applicable, which re-applies
        // the full desired state so a command lost to the freeze cannot stick.
        GameNativeDrm.nativeInjectorSetSuspended(false);
        GamescopeCommandQueue.setApplicable(true);
    }
}
