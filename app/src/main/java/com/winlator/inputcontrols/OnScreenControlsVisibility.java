package com.winlator.inputcontrols;

/** Per-container launch default, independent of the session toggle, controls profile and mouse input mode. */
public enum OnScreenControlsVisibility {
    AUTOMATIC("automatic"),
    SHOWN("shown"),
    HIDDEN("hidden");

    private final String value;

    OnScreenControlsVisibility(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static OnScreenControlsVisibility fromValue(String value) {
        for (OnScreenControlsVisibility visibility : values()) {
            if (visibility.value.equals(value)) return visibility;
        }
        return AUTOMATIC;
    }
}
