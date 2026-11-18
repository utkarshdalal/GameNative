package com.winlator.widget;

import com.winlator.inputcontrols.Binding;

// lets InputControlsView reach the html5 runtime without importing app.gamenative.* (winlator core stays
// wine-specific).
public interface Html5BindingSink {
    void onBinding(Binding binding, boolean isDown, float offset);
}
