package com.winlator.core.envvars;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Launch logs get shared for support, so the values of these variables are masked wherever an
 * environment is logged. Add any new credential or account identifier here.
 */
public final class EnvVarRedaction {
    private static final HashSet<String> REDACTED = new HashSet<>(Arrays.asList(
        "STEAMHOST_TOKEN",
        "STEAMHOST_ACCOUNT",
        "STEAMHOST_STEAMID64",
        "SteamUser",
        "SteamAppUser",
        "STEAMID",
        "AMAZON_GAMES_FUEL_ENTITLEMENT_ID"
    ));

    private EnvVarRedaction() {}

    public static boolean isRedacted(String name) {
        return REDACTED.contains(name);
    }

    @NonNull
    public static String redact(EnvVars envVars) {
        StringBuilder sb = new StringBuilder();
        for (String key : envVars) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(key).append('=');
            sb.append(isRedacted(key) ? "<redacted>" : envVars.get(key));
        }
        return sb.toString();
    }

    @NonNull
    public static String redact(String envVars) {
        return redact(new EnvVars(envVars));
    }

    @NonNull
    public static String redact(String[] envp) {
        if (envp == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < envp.length; i++) {
            if (i > 0) sb.append(", ");
            String entry = envp[i];
            int eq = entry == null ? -1 : entry.indexOf('=');
            if (eq > 0 && isRedacted(entry.substring(0, eq))) {
                sb.append(entry, 0, eq + 1).append("<redacted>");
            } else {
                sb.append(entry);
            }
        }
        return sb.append(']').toString();
    }
}
