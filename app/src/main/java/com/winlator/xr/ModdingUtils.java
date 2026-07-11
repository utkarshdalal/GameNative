/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.xr;

import android.content.Context;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

public class ModdingUtils {

    private static final TarCompressorUtils.Type PKG_TYPE = TarCompressorUtils.Type.ZSTD;
    private static final String[] RESHADE_DIRECTX_CLONES = {"d3d10.dll", "d3d11.dll", "d3d12.dll"};
    private static final String RESHADE_DIRECTX_DLL = "dxgi.dll";
    private static final String RESHADE_DIRECTX_PKG = "reshade-directx.tzst";
    private static final String RESHADE_PLUGINS_PKG = "reshade-plugins.tzst";
    private static final String TRACKIR_DESTIONATION = "/sdcard/Download/Winlator";
    private static final String TRACKIR_PATH = "D:\\Winlator\\opentrack_wxr\\opentrack.exe";
    private static final String TRACKIR_PKG = "opentrack_wxr.tzst";
    private static final String TAG = "ModdingUtils";

    public static File getLocalDir(ImageFs imageFs, Container container) {
        char drive = 'A';
        File root = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_" + drive);

        for (String[] it : Container.drivesIterator(container.getDrives())) {
            if (it[0].compareToIgnoreCase(drive + "") == 0) {
                root = new File(it[1]);
            }
        }

        File[] files = root.listFiles();
        return files.length == 1 ? files[0] : root;
    }

    public static String getRuntimeForTrackIR() {
        return TRACKIR_PATH;
    }

    public static void unpackTrackIR(Context context) {
        File dst = new File(TRACKIR_DESTIONATION);
        if (TarCompressorUtils.isExtracted(PKG_TYPE, context, TRACKIR_PKG, dst) != TarCompressorUtils.Status.FULL) {
            Log.i(TAG, "Extracting TrackIR to " + dst.getAbsolutePath());
            TarCompressorUtils.extract(PKG_TYPE, context, TRACKIR_PKG, dst);
        }
    }

    public static void updateReshade(Context context, File dst, boolean useReshade, boolean forceDXGI) {
        boolean hasExe = false;
        for (File file : dst.listFiles()) {
            if (file.getAbsolutePath().endsWith(".exe"))
                hasExe = true;
            if (!file.isDirectory())
                continue;
            updateReshade(context, file, useReshade, forceDXGI);
        }

        if (hasExe) {
            updateReshadePlugins(context, useReshade, dst);
            updateReshadeDirectX(context, useReshade, forceDXGI, dst);
        }
    }

    private static void updateReshadeDirectX(Context context, boolean useReshade, boolean forceDXGI, File dst) {
        // Add or remove Reshade files
        TarCompressorUtils.Status extracted;
        extracted = TarCompressorUtils.isExtracted(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
        if (extracted != TarCompressorUtils.Status.PARTIAL) {
            if (useReshade) {
                Log.i(TAG, "Extracting reshade to " + dst.getAbsolutePath());
                TarCompressorUtils.extract(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
                if (forceDXGI || isUsingDXGI(dst)) {
                    deleteClones(dst, RESHADE_DIRECTX_CLONES);
                } else {
                    cloneFile(new File(dst, RESHADE_DIRECTX_DLL), RESHADE_DIRECTX_CLONES);
                }
            } else {
                Log.i(TAG, "Removing reshade from " + dst.getAbsolutePath());
                TarCompressorUtils.remove(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
                deleteClones(dst, RESHADE_DIRECTX_CLONES);
            }
        }

        // Log current status
        extracted = TarCompressorUtils.isExtracted(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
        Log.i(TAG, "Reshade isExtracted=" + extracted);
    }

    private static void updateReshadePlugins(Context context, boolean useReshade, File dst) {
        if (useReshade) {
            Log.i(TAG, "Extracting reshade to " + dst.getAbsolutePath());
            TarCompressorUtils.extract(PKG_TYPE, context, RESHADE_PLUGINS_PKG, dst);
        } else {
            Log.i(TAG, "Removing reshade from " + dst.getAbsolutePath());
            TarCompressorUtils.remove(PKG_TYPE, context, RESHADE_PLUGINS_PKG, dst);
        }
    }

    private static void cloneFile(File file, String[] names) {
        File dir = file.getParentFile();
        for (String name : names) {
            FileUtils.copy(file, new File(dir, name));
        }
    }

    private static void deleteClones(File dir, String[] names) {
        for (String name : names) {
            new File(dir, name).delete();
        }
    }

    private static boolean isUsingDXGI(File dst) {
        return locateUE(dst) || locateUnity(dst);
    }

    private static boolean locateUE(File dst) {
        File[] files = dst.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (locateUE(file)) {
                        return true;
                    }
                }
            }
        }
        return dst.getAbsolutePath().endsWith("Binaries/Win64");
    }

    private static boolean locateUnity(File dst) {
        File[] files = dst.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    boolean result = locateUnity(file);
                    if (result) {
                        return true;
                    }
                } else if (file.getAbsolutePath().endsWith("UnityEngine.dll")) {
                    return true;
                }
            }
        }
        return false;
    }
}
