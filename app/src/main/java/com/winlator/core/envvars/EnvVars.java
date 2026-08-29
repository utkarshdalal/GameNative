package com.winlator.core.envvars;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class EnvVars implements Iterable<String> {
    private static final String VK_INSTANCE_LAYERS = "VK_INSTANCE_LAYERS";
    private static final String VK_LOADER_LAYERS_ENABLE = "VK_LOADER_LAYERS_ENABLE";
    private static final String VK_LOADER_DEBUG = "VK_LOADER_DEBUG";

    private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

    public EnvVars() {}

    public EnvVars(String values) {
        putAll(values);
    }

    public void put(String name, Object value) {
        String stringValue = String.valueOf(value);
        data.put(name, stringValue);

        // Bridge explicit Vulkan-layer selection across loader generations. Older
        // Wine/Vulkan loader builds honor VK_INSTANCE_LAYERS while loader 1.3.234+
        // also provides VK_LOADER_LAYERS_ENABLE. Keep both so applications do not
        // depend on a particular loader revision or GPU vendor.
        if (VK_INSTANCE_LAYERS.equals(name)) {
            mirrorLegacyVulkanLayersToModernFilter(stringValue);
            enableVulkanLoaderDiagnosticsForExplicitLayers(stringValue);
        }
    }

    public void putAll(String values) {
        if (values == null || values.isEmpty()) return;
        for (String part : splitOnUnescapedSpaces(values)) {
            int index = part.indexOf("=");
            // tolerate stray tokens (legacy data corrupted by old unescaped serializer)
            if (index < 0) continue;
            String name = unescape(part.substring(0, index));
            String value = unescape(part.substring(index + 1));
            put(name, value);
        }
    }

    public void putAll(EnvVars envVars) {
        if (envVars == this) return;
        for (Map.Entry<String, String> entry : envVars.data.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public String get(String name) {
        return data.getOrDefault(name, "");
    }

    public void remove(String name) {
        data.remove(name);
    }

    public boolean has(String name) {
        return data.containsKey(name);
    }

    public void clear() {
        data.clear();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String key : data.keySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(escape(key)).append('=').append(escape(data.get(key)));
        }
        return sb.toString();
    }

    public String toEscapedString() {
        return toString();
    }

    public String[] toStringArray() {
        String[] stringArray = new String[data.size()];
        int index = 0;
        for (String key : data.keySet()) stringArray[index++] = key+"="+data.get(key);
        return stringArray;
    }

    @NonNull
    @Override
    public Iterator<String> iterator() {
        return data.keySet().iterator();
    }

    private void mirrorLegacyVulkanLayersToModernFilter(String legacyLayers) {
        Set<String> enabledLayers = new LinkedHashSet<>();
        addSeparatedValues(enabledLayers, data.get(VK_LOADER_LAYERS_ENABLE), ",");
        addSeparatedValues(enabledLayers, legacyLayers, "[:;]");
        if (!enabledLayers.isEmpty()) {
            data.put(VK_LOADER_LAYERS_ENABLE, String.join(",", enabledLayers));
        }
    }

    private void enableVulkanLoaderDiagnosticsForExplicitLayers(String legacyLayers) {
        if (legacyLayers == null || legacyLayers.trim().isEmpty() || data.containsKey(VK_LOADER_DEBUG)) return;
        // Keep this concise enough for production compatibility reports. The loader
        // reports manifest discovery, layer loading and interface failures without the
        // very noisy full "all" trace.
        data.put(VK_LOADER_DEBUG, "error,warn,layer");
    }

    private static void addSeparatedValues(Set<String> target, String values, String separatorRegex) {
        if (values == null || values.isEmpty()) return;
        for (String value : values.split(separatorRegex)) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) target.add(trimmed);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(" ", "\\ ");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static java.util.List<String> splitOnUnescapedSpaces(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
            } else if (c == ' ') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
