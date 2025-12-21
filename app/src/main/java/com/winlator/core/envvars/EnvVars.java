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
        final StringBuilder token = new StringBuilder(values.length());
        final int n = values.length();

        for (int i = 0; i < n; i++) {
            char ch = values.charAt(i);

            if (ch == '\\' && (i + 1) < n && values.charAt(i + 1) == ' ') {
                token.append(' ');
                i++;
                continue;
            }

            if (ch == ' ') {
                parseAndPutToken(token);
                token.setLength(0);
                continue;
            }

            token.append(ch);
        }

        parseAndPutToken(token);
    }

    private void parseAndPutToken(StringBuilder token) {
        if (token.length() == 0) return;
        final String part = token.toString();
        final int index = part.indexOf('=');
        if (index <= 0) return;

        final String name = part.substring(0, index);
        final String value = part.substring(index + 1);
        data.put(name, value);
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
        StringBuilder sb = new StringBuilder();
        for (String key : data.keySet()) {
            if (sb.length() != 0) sb.append(' ');
            String value = data.get(key);
            String k = key == null ? "" : key.replace(" ", "\\ ");
            String v = value == null ? "" : value.replace(" ", "\\ ");
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
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
