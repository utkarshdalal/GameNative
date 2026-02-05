package com.winlator.fexcore;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import com.winlator.PrefManager;
import com.winlator.core.envvars.EnvVars;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

import app.gamenative.R;
import timber.log.Timber;

public abstract class FEXCorePresetManager {
    public static EnvVars getEnvVars(Context context, String id) {
        EnvVars envVars = new EnvVars();

        if (id.equals(FEXCorePreset.STABILITY)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "0");
        }
        else if (id.equals(FEXCorePreset.COMPATIBILITY)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.INTERMEDIATE)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.PERFORMANCE)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.EXTREME)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");

            // Based from PERFORMANCE
            envVars.put("FEX_SMALLTSCSCALE", "1");
            envVars.put("FEX_VOLATILEMETADATA", "1");
        }
        else if (id.equals(FEXCorePreset.DENUVO)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");

            // Based from EXTREME
            envVars.put("FEX_SMALLTSCSCALE", "1");
            envVars.put("FEX_VOLATILEMETADATA", "1");
            envVars.put("FEX_SMCCHECKS", "full");
            envVars.put("FEX_HIDEHYPERVISORBIT", "1");
        }
        else if (id.startsWith(FEXCorePreset.CUSTOM)) {
            for (String[] preset : customPresetsIterator(context)) {
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }

        return envVars;
    }

    public static ArrayList<FEXCorePreset> getPresets(Context context) {
        ArrayList<FEXCorePreset> presets = new ArrayList<>();
        presets.add(new FEXCorePreset(FEXCorePreset.STABILITY, context.getString(R.string.stability)));
        presets.add(new FEXCorePreset(FEXCorePreset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new FEXCorePreset(FEXCorePreset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new FEXCorePreset(FEXCorePreset.PERFORMANCE, context.getString(R.string.performance)));
        presets.add(new FEXCorePreset(FEXCorePreset.EXTREME, context.getString(R.string.extreme)));
        presets.add(new FEXCorePreset(FEXCorePreset.DENUVO, context.getString(R.string.denuvo)));
        for (String[] preset : customPresetsIterator(context)) presets.add(new FEXCorePreset(preset[0], preset[1]));
        return presets;
    }

    public static FEXCorePreset getPreset(Context context, String id) {
        for (FEXCorePreset preset : getPresets(context)) if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(Context context) {
        PrefManager.init(context);
        final String customPresetsStr = PrefManager.getString("fexcore_custom_presets", "");
        final String[] customPresets = customPresetsStr.split(",");
        final int[] index = {0};
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] < customPresets.length && !customPresetsStr.isEmpty();
            }

            @Override
            public String[] next() {
                return customPresets[index[0]++].split("\\|");
            }
        };
    }

    public static int getNextPresetId(Context context) {
        int maxId = 0;
        for (String[] preset : customPresetsIterator(context)) {
            maxId = Math.max(maxId, Integer.parseInt(preset[0].replace(FEXCorePreset.CUSTOM + "-", "")));
        }
        return maxId + 1;
    }

    public static String editPreset(Context context, String id, String name, EnvVars envVars) {
        String key = "fexcore_custom_presets";
        PrefManager.init(context);
        String customPresetsStr = PrefManager.getString(key, "");
        String presetId = id;

        if (presetId != null) {
            String[] customPresets = customPresetsStr.split(",");
            for (int i = 0; i < customPresets.length; i++) {
                String[] preset = customPresets[i].split("\\|");
                if (preset[0].equals(presetId)) {
                    customPresets[i] = presetId + "|" + name + "|" + envVars.toString();
                    break;
                }
            }
            customPresetsStr = String.join(",", customPresets);
        } else {
            presetId = FEXCorePreset.CUSTOM + "-" + getNextPresetId(context);
            String preset = presetId + "|" + name + "|" + envVars.toString();
            customPresetsStr += (!customPresetsStr.isEmpty() ? "," : "") + preset;
        }
        try {
            PrefManager.putString(key, customPresetsStr).get();
        } catch (Exception e) {
            Timber.e("Failed to edit preset: " + e);
        }
        return presetId;
    }

    public static String duplicatePreset(Context context, String id) {
        ArrayList<FEXCorePreset> presets = getPresets(context);
        FEXCorePreset originPreset = null;
        for (FEXCorePreset preset : presets) {
            if (preset.id.equals(id)) {
                originPreset = preset;
                break;
            }
        }
        if (originPreset == null) return null;

        String newName;
        for (int i = 1; ; i++) {
            newName = originPreset.name + " (" + i + ")";
            boolean found = false;
            for (FEXCorePreset preset : presets) {
                if (preset.name.equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        return editPreset(context, null, newName, getEnvVars(context, originPreset.id));
    }

    public static void removePreset(Context context, String id) {
        String key = "fexcore_custom_presets";
        PrefManager.init(context);
        String oldCustomPresetsStr = PrefManager.getString(key, "");
        String newCustomPresetsStr = "";

        String[] customPresets = oldCustomPresetsStr.split(",");
        for (int i = 0; i < customPresets.length; i++) {
            String[] preset = customPresets[i].split("\\|");
            if (!preset[0].equals(id))
                newCustomPresetsStr += (!newCustomPresetsStr.isEmpty() ? "," : "") + customPresets[i];
        }

        PrefManager.putString(key, newCustomPresetsStr);
    }

    public static void loadSpinner(String prefix, Spinner spinner, String selectedId) {
        Context context = spinner.getContext();
        ArrayList<FEXCorePreset> presets = getPresets(context);

        int selectedPosition = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(selectedId)) {
                selectedPosition = i;
                break;
            }
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, presets));
        spinner.setSelection(selectedPosition);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        SpinnerAdapter adapter = spinner.getAdapter();
        int selectedPosition = spinner.getSelectedItemPosition();
        if (adapter != null && adapter.getCount() > 0 && selectedPosition >= 0) {
            return ((FEXCorePreset) adapter.getItem(selectedPosition)).id;
        } else return FEXCorePreset.COMPATIBILITY;
    }
}
