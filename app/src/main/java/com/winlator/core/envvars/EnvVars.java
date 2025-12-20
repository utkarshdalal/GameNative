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

    public static String stripWhitespace(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                if (sb != null) sb.append(c);
            } else if (sb == null) {
                sb = new StringBuilder(s.length());
                sb.append(s, 0, i);
            }
        }
        return (sb == null) ? s : sb.toString();
    }
    
    public void putAll(String values) {
        if (values == null) return;
        values = values.trim();
        if (values.isEmpty()) return;

        // tolerate garbage input, but never crash
        String[] parts = values.split("\\s+");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int index = part.indexOf('=');
            if (index <= 0) continue;
            data.put(part.substring(0, index), part.substring(index + 1));
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

    @NonNull
    @Override
    public String toString() {
        return String.join(" ", toStringArray());
    }

    public String toEscapedString() {
        String result = "";
        for (String key : data.keySet()) {
            if (!result.isEmpty()) result += " ";
            String value = data.get(key);
            result += key+"="+value.replace(" ", "\\ ");
        }
        return result;
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
}
