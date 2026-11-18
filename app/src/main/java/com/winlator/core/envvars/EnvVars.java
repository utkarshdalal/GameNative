package com.winlator.core.envvars;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;

public class EnvVars implements Iterable<String> {
    private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

    public EnvVars() {}

    public EnvVars(String values) {
        putAll(values);
    }

    public void put(String name, Object value) {
        data.put(name, String.valueOf(value));
    }

    public void putAll(String values) {
        if (values == null || values.isEmpty()) return;
        for (String part : splitOnUnescapedSpaces(values)) {
            int index = part.indexOf("=");
            // tolerate stray tokens (legacy data corrupted by old unescaped serializer)
            if (index < 0) continue;
            String name = unescape(part.substring(0, index));
            String value = unescape(part.substring(index + 1));
            data.put(name, value);
        }
    }

    public void putAll(EnvVars envVars) {
        data.putAll(envVars.data);
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

    // canonical persistence form: escape so putAll round-trips losslessly
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

    // for shell composition (env KEY=val ... cmd) — same escape rules
    public String toEscapedString() {
        return toString();
    }

    // for execve envp — values must be raw, no escaping
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

    private static String escape(String s) {
        // escape backslash FIRST so we don't double-escape the slashes we add for spaces
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
