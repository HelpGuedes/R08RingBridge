package com.r08.ringbridge;

/**
 * What the bridge sends to the glasses. Each action presses one bit of the bridge's
 * HID keyboard report (see HidPeripheral.REPORT_MAP).
 *
 * Bits 0-5 use keys the glasses map by default (arrows, Enter = single press, Esc = back).
 * Bits 6 and 7 need the input map r08_bridge_inputmap.json on the glasses:
 *   Keypad Enter (0x58) -> DOUBLE (58), Space (0x2C) -> SELECT (4, duration-aware = long press when held).
 */
enum Action {
    NONE("Nothing", -1, false),
    UP("Up", 0, false),
    DOWN("Down", 1, false),
    LEFT("Left", 2, false),
    RIGHT("Right", 3, false),
    SINGLE("Single press", 4, false),
    BACK("Back", 5, false),
    DOUBLE("Double press", 6, false),
    LONG_PRESS("Long press (hold SELECT)", 7, true),
    LONG_DOUBLE("Hold DOUBLE (58)", 6, true);

    final String label;
    final int bit;
    final boolean held;

    Action(String label, int bit, boolean held) {
        this.label = label;
        this.bit = bit;
        this.held = held;
    }

    static String[] labels() {
        Action[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = all[i].label;
        return out;
    }

    static Action parse(String name, Action fallback) {
        if (name == null) return fallback;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
