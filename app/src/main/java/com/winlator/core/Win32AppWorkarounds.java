package com.winlator.core;

import com.winlator.core.envvars.EnvVars;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;
import java.util.Locale;

import timber.log.Timber;

/* loaded from: classes.dex */
public class Win32AppWorkarounds {
    private final XServer xServer;
    private final short taskAffinityMask;
    private final short taskAffinityMaskWoW64;

    private interface DXWrapperConfigWorkaround extends Workaround {
        void setValue(String str, KeyValueSet keyValueSet);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface DXWrapperWorkaround extends Workaround {
        String getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface EnvVarsWorkaround extends Workaround {
        void apply(EnvVars envVars);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface GraphicsDriverWorkaround extends Workaround {
        String getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface ScreenSizeWorkaround extends Workaround {
        String getValue();
    }

    private interface WinComponentsWorkaround extends Workaround {
        void setValue(KeyValueSet keyValueSet);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface WindowWorkaround extends Workaround {
        void apply(Window window);
    }

    private interface Workaround {
    }

    private static class MultiWorkaround implements Workaround {
        private final Workaround[] list;

        public MultiWorkaround(Workaround... list) {
            this.list = list;
        }
    }

    public Win32AppWorkarounds(XServer xServer, int taskAffinityMask, int taskAffinityMaskWoW64) {
        this.xServer = xServer;
        this.taskAffinityMask = (short) taskAffinityMask;
        this.taskAffinityMaskWoW64 = (short) taskAffinityMaskWoW64;
    }

    private void applyWorkaround(Workaround workaround) {
        // Note: Most workarounds that require activity-level changes (env vars, screen size, etc.)
        // are now handled at the container/environment setup level in XServerScreen.kt
        // This class now focuses primarily on window-level workarounds
        if (workaround instanceof WindowWorkaround) {
            // Window workarounds can still be applied here
            // They will be applied in applyWindowWorkarounds when the window is created
        }
        // Other workaround types (EnvVars, ScreenSize, DXWrapper, etc.) are now handled
        // during environment setup phase rather than at window creation time
    }

    public void applyStartupWorkarounds(String className) {
        Workaround workaround = getWorkaroundFor(className);
        if (workaround == null) {
            return;
        }
        if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround) workaround).list) {
                applyWorkaround(workaround2);
            }
            return;
        }
        applyWorkaround(workaround);
    }

    private void setProcessAffinity(Window window, int processAffinity) {
        int processId = window.getProcessId();
        String className = window.getClassName();
        WinHandler winHandler = this.xServer.getWinHandler();
        if (className.equals("steam.exe")) {
            Timber.i("Steam.exe found, not applying affinity!");
            return;
        }
        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        } else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    public void applyWindowWorkarounds(Window window) {
        Workaround workaround = getWorkaroundFor(window.getClassName());
        boolean canApplyProcessAffinity = false;
        if (workaround instanceof WindowWorkaround) {
            ((WindowWorkaround) workaround).apply(window);
        } else if (workaround instanceof MultiWorkaround) {
            Workaround[] workaroundArr = ((MultiWorkaround) workaround).list;
            int length = workaroundArr.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    break;
                }
                Workaround workaround2 = workaroundArr[i];
                if (!(workaround2 instanceof WindowWorkaround)) {
                    i++;
                } else {
                    ((WindowWorkaround) workaround2).apply(window);
                    break;
                }
            }
        }
        int windowGroup = window.getWMHintsValue(Window.WMHints.WINDOW_GROUP);
        if (window.isRenderable() && !window.getClassName().isEmpty() && windowGroup == window.id) {
            canApplyProcessAffinity = true;
        }
        if (canApplyProcessAffinity) {
            int processAffinity = window.isWoW64() ? this.taskAffinityMaskWoW64 : this.taskAffinityMask;
            if (processAffinity != 0) {
                setProcessAffinity(window, processAffinity);
            }
        }
    }

    private Workaround getWorkaroundFor(String className) {
        switch (className.toLowerCase(Locale.ENGLISH)) {
            default:
                return null;
        }
    }
}
