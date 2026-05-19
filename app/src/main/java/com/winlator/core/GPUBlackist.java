package com.winlator.core;

import android.os.Build;

public class GPUBlackist {

    public static boolean isTurnipBlacklisted() {
        if ((Build.MANUFACTURER.compareToIgnoreCase("OCULUS") == 0) ||
                (Build.MANUFACTURER.compareToIgnoreCase("META") == 0)) {
            return switch (Build.PRODUCT) {
                //Quest 1
                case "monterey", "vr_monterey" -> false;
                //Quest 2, Quest Pro
                case "hollywood", "seacliff" -> false;
                //Quest 3, Quest 3s
                case "eureka", "stinson", "panther" -> true;
                //future devices
                default -> true;
            };
        }
        return false;
    }
}
