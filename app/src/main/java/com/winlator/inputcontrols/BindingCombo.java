package com.winlator.inputcontrols;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class BindingCombo {
    public static final int MAX_BINDINGS = 3;
    public static final int DEFAULT_SEQUENCE_DELAY_MS = 150;
    public static final int MIN_SEQUENCE_DELAY_MS = 80;
    public static final int MAX_SEQUENCE_DELAY_MS = 1000;
    private static final BindingCombo NONE = new BindingCombo(Collections.emptyList(), Mode.SIMULTANEOUS, DEFAULT_SEQUENCE_DELAY_MS);

    private final List<Binding> bindings;
    private final Mode mode;
    private final int sequenceDelayMs;

    public enum Mode {
        SIMULTANEOUS("simultaneous"),
        SEQUENCE("sequence");

        private final String jsonName;

        Mode(String jsonName) {
            this.jsonName = jsonName;
        }

        public String getJsonName() {
            return jsonName;
        }

        public static Mode fromJsonName(String value) {
            return SEQUENCE.jsonName.equals(value) ? SEQUENCE : SIMULTANEOUS;
        }
    }

    private BindingCombo(List<Binding> bindings, Mode mode, int sequenceDelayMs) {
        this.bindings = Collections.unmodifiableList(bindings);
        this.mode = bindings.size() > 1 && mode != null ? mode : Mode.SIMULTANEOUS;
        this.sequenceDelayMs = this.mode == Mode.SEQUENCE
                ? normalizeSequenceDelayMs(sequenceDelayMs)
                : DEFAULT_SEQUENCE_DELAY_MS;
    }

    public static BindingCombo none() {
        return NONE;
    }

    public static BindingCombo of(Binding binding) {
        if (binding == null || binding == Binding.NONE) return NONE;
        ArrayList<Binding> bindings = new ArrayList<>(1);
        bindings.add(binding);
        return new BindingCombo(bindings, Mode.SIMULTANEOUS, DEFAULT_SEQUENCE_DELAY_MS);
    }

    public static BindingCombo fromBindings(List<Binding> values) {
        return fromBindings(values, Mode.SIMULTANEOUS);
    }

    public static BindingCombo fromBindings(List<Binding> values, Mode mode) {
        return fromBindings(values, mode, DEFAULT_SEQUENCE_DELAY_MS);
    }

    public static BindingCombo fromBindings(List<Binding> values, Mode mode, int sequenceDelayMs) {
        if (values == null || values.isEmpty()) return NONE;

        ArrayList<Binding> normalized = new ArrayList<>();
        HashSet<Binding> seen = new HashSet<>();
        for (Binding binding : values) {
            if (binding == null || binding == Binding.NONE || seen.contains(binding)) continue;
            normalized.add(binding);
            seen.add(binding);
            if (normalized.size() == MAX_BINDINGS) break;
        }
        if (normalized.isEmpty()) return NONE;
        if (mode != Mode.SEQUENCE) normalized.sort(Comparator.comparingInt(BindingCombo::sortGroup));
        return new BindingCombo(normalized, mode, sequenceDelayMs);
    }

    public static BindingCombo fromJsonValue(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            JSONArray bindings = object.optJSONArray("bindings");
            if (bindings == null) {
                return of(Binding.fromString(object.optString("binding", Binding.NONE.name())));
            }
            return fromJsonArray(
                    bindings,
                    Mode.fromJsonName(object.optString(
                            "mode",
                            object.optString("bindingMode", Mode.SIMULTANEOUS.getJsonName()))),
                    object.optInt(
                            "sequenceDelayMs",
                            object.optInt(
                                    "bindingDelayMs",
                                    object.optInt("delayMs", DEFAULT_SEQUENCE_DELAY_MS)))
            );
        }
        if (value instanceof JSONArray) {
            return fromJsonArray((JSONArray)value);
        }
        if (value instanceof String) {
            return of(Binding.fromString((String)value));
        }
        return NONE;
    }

    public static BindingCombo fromJsonArray(JSONArray array) {
        return fromJsonArray(array, Mode.SIMULTANEOUS);
    }

    public static BindingCombo fromJsonArray(JSONArray array, Mode mode) {
        return fromJsonArray(array, mode, DEFAULT_SEQUENCE_DELAY_MS);
    }

    public static BindingCombo fromJsonArray(JSONArray array, Mode mode, int sequenceDelayMs) {
        if (array == null) return NONE;
        ArrayList<Binding> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof String) values.add(Binding.fromString((String)value));
        }
        return fromBindings(values, mode, sequenceDelayMs);
    }

    private static int normalizeSequenceDelayMs(int value) {
        return Math.max(MIN_SEQUENCE_DELAY_MS, Math.min(MAX_SEQUENCE_DELAY_MS, value));
    }

    private static int sortGroup(Binding binding) {
        switch (binding) {
            case KEY_CTRL_L:
            case KEY_CTRL_R:
            case KEY_SHIFT_L:
            case KEY_SHIFT_R:
            case KEY_ALT_L:
            case KEY_ALT_R:
                return 0;
            default:
                return 1;
        }
    }

    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    public boolean isSingleBinding() {
        return bindings.size() <= 1;
    }

    public boolean isSequence() {
        return mode == Mode.SEQUENCE;
    }

    public Mode getMode() {
        return mode;
    }

    public int getSequenceDelayMs() {
        return sequenceDelayMs;
    }

    public int size() {
        return bindings.size();
    }

    public Binding getPrimaryBinding() {
        return bindings.isEmpty() ? Binding.NONE : bindings.get(bindings.size() - 1);
    }

    public List<Binding> getBindings() {
        return bindings;
    }

    public boolean contains(Binding binding) {
        return bindings.contains(binding);
    }

    public boolean containsGamepadBinding() {
        for (Binding binding : bindings) if (binding.isGamepad()) return true;
        return false;
    }

    public boolean isGamepadOnly() {
        if (bindings.isEmpty()) return false;
        for (Binding binding : bindings) if (!binding.isGamepad()) return false;
        return true;
    }

    public void writeToJsonObject(JSONObject object) throws JSONException {
        object.put("bindings", toJsonArray());
        if (mode == Mode.SEQUENCE) {
            object.put("mode", mode.getJsonName());
            object.put("sequenceDelayMs", sequenceDelayMs);
        }
    }

    public Object toJsonValue() {
        if (bindings.size() <= 1) return getPrimaryBinding().name();
        if (mode == Mode.SEQUENCE) {
            try {
                JSONObject object = new JSONObject();
                writeToJsonObject(object);
                return object;
            } catch (JSONException e) {
                return toJsonArray();
            }
        }
        return toJsonArray();
    }

    public JSONArray toJsonArray() {
        JSONArray array = new JSONArray();
        for (Binding binding : bindings) array.put(binding.name());
        return array;
    }

    @NonNull
    @Override
    public String toString() {
        if (bindings.isEmpty()) return Binding.NONE.toString();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) builder.append(mode == Mode.SEQUENCE ? " -> " : " + ");
            builder.append(bindings.get(i).toString());
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BindingCombo)) return false;
        BindingCombo other = (BindingCombo)obj;
        return sequenceDelayMs == other.sequenceDelayMs
                && mode == other.mode
                && bindings.equals(other.bindings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindings, mode, sequenceDelayMs);
    }
}
