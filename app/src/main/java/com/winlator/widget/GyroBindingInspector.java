package com.winlator.widget;

import app.gamenative.data.GyroSettings;

import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;

final class GyroBindingInspector {
    private GyroBindingInspector() {}

    static boolean hasUsableBinding(ControlsProfile profile, int activationMode) {
        if (activationMode == GyroSettings.ACTIVATION_ALWAYS) return true;
        if (profile == null) return false;

        for (ControlElement element : profile.getElements()) {
            for (int i = 0; i < element.getBindingCount(); i++) {
                if (element.getBindingComboAt(i).contains(Binding.GYRO_MODIFIER)) return true;
            }
        }
        for (ExternalController controller : profile.getControllers()) {
            for (ExternalControllerBinding binding : controller.getControllerBindings()) {
                if (binding.getBindingCombo().contains(Binding.GYRO_MODIFIER)) return true;
            }
        }
        return false;
    }

    static boolean hasModifierBinding(ControlsProfile profile) {
        return profile != null && hasUsableBinding(profile, GyroSettings.ACTIVATION_TOGGLE);
    }
}
