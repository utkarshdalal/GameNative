package com.winlator.widget;

import com.winlator.inputcontrols.Binding;

// minimal bridge interface so InputControlsView can dispatch overlay button presses
// to the html5 runtime without importing app.gamenative.* (winlator core stays
// wine-specific per project package-split rule). implementation lives kotlin-side
// in app.gamenative.html5.input.* and forwards to Html5InputSynthesizer.
public interface Html5BindingSink {
    void onBinding(Binding binding, boolean isDown, float offset);
}
