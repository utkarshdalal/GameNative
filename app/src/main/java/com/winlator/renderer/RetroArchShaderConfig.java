package com.winlator.renderer;

/**
 * Immutable value object describing the RetroArch shader state persisted on a container.
 * The fourth constructor argument is the shader description (kept empty by the UI).
 */
public class RetroArchShaderConfig {
    private final boolean enabled;
    private final String presetPath;
    private final String presetName;
    private final String description;
    private final String relativePath;

    public RetroArchShaderConfig(boolean enabled, String presetPath, String presetName, String description, String relativePath) {
        this.enabled = enabled;
        this.presetPath = presetPath != null ? presetPath : "";
        this.presetName = presetName != null ? presetName : "";
        this.description = description != null ? description : "";
        this.relativePath = relativePath != null ? relativePath : "";
    }

    public boolean getEnabled() {
        return enabled;
    }

    public String getPresetPath() {
        return presetPath;
    }

    public String getPresetName() {
        return presetName;
    }

    public String getDescription() {
        return description;
    }

    public String getRelativePath() {
        return relativePath;
    }
}
