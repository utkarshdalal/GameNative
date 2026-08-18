package com.winlator.inputcontrols;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RadialMenu {
    public static final int MAX_SLOTS = 12;
    public static final String DEFAULT_ID = "default";

    private String id = DEFAULT_ID;
    private String name = "Radial Menu";
    private final ArrayList<Slot> slots = new ArrayList<>();
    private final List<Slot> immutableSlots = Collections.unmodifiableList(slots);

    public static class Slot {
        private String label = "";
        private Binding binding = Binding.NONE;

        public Slot() {
        }

        public Slot(String label, Binding binding) {
            setLabel(label);
            setBinding(binding);
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label != null ? label : "";
        }

        public Binding getBinding() {
            return binding;
        }

        public void setBinding(Binding binding) {
            this.binding = binding != null ? binding : Binding.NONE;
        }

        public boolean isEnabled() {
            return binding != Binding.NONE;
        }

        public String getDisplayLabel() {
            return !label.isEmpty() ? label : binding.toString();
        }

        public JSONObject toJSONObject() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("label", label);
            object.put("binding", binding.name());
            return object;
        }

        public static Slot fromJSONObject(JSONObject object) {
            Slot slot = new Slot();
            slot.setLabel(object.optString("label", ""));
            slot.setBinding(Binding.fromString(object.optString("binding", Binding.NONE.name())));
            return slot;
        }
    }

    public static RadialMenu createDefault() {
        RadialMenu menu = new RadialMenu();
        menu.addSlot(new Slot("Inventory", Binding.KEY_I));
        menu.addSlot(new Slot("Map", Binding.KEY_M));
        menu.addSlot(new Slot("Journal", Binding.KEY_J));
        menu.addSlot(new Slot("Quick Save", Binding.KEY_F5));
        menu.addSlot(new Slot("Quick Load", Binding.KEY_F9));
        menu.addSlot(new Slot("Escape", Binding.KEY_ESC));
        menu.addSlot(new Slot("Tab", Binding.KEY_TAB));
        menu.addSlot(new Slot("Keyboard", Binding.SHOW_KEYBOARD));
        return menu;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isEmpty() ? id : DEFAULT_ID;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null && !name.isEmpty() ? name : "Radial Menu";
    }

    public List<Slot> getSlots() {
        return immutableSlots;
    }

    public List<Slot> getEnabledSlots() {
        ArrayList<Slot> enabled = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.isEnabled()) enabled.add(slot);
        }
        return enabled;
    }

    public void addSlot(Slot slot) {
        if (slot != null && slots.size() < MAX_SLOTS) slots.add(slot);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("name", name);

            JSONArray slotsJson = new JSONArray();
            for (Slot slot : slots) slotsJson.put(slot.toJSONObject());
            object.put("slots", slotsJson);
            return object;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public static RadialMenu fromJSONObject(JSONObject object) {
        RadialMenu menu = new RadialMenu();
        if (object == null) return menu;

        menu.setId(object.optString("id", DEFAULT_ID));
        menu.setName(object.optString("name", "Radial Menu"));
        JSONArray slotsJson = object.optJSONArray("slots");
        if (slotsJson != null) {
            for (int i = 0; i < slotsJson.length() && i < MAX_SLOTS; i++) {
                JSONObject slotObject = slotsJson.optJSONObject(i);
                if (slotObject != null) menu.addSlot(Slot.fromJSONObject(slotObject));
            }
        }
        return menu;
    }
}
