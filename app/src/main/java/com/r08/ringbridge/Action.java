package com.r08.ringbridge;

/**
 * What the bridge sends to the glasses: one button in one of the bridge's two HID reports.
 *
 * One keyboard report. Bits 0-5 use keys the glasses map by default (arrows, Enter = single
 * press, Esc = back). Bit 6 (Keypad Enter), bit 7 (Space) and bits 8-15 (Button A-H = keys
 * A-H) have no default meaning: map them in the MemoMind app to the actions you want.
 */
enum Action {
    NONE("Nothing", 0, -1, false),
    UP("Up", 1, 0, false),
    DOWN("Down", 1, 1, false),
    LEFT("Left", 1, 2, false),
    RIGHT("Right", 1, 3, false),
    SINGLE("Single press (Enter)", 1, 4, false),
    BACK("Back (Escape)", 1, 5, false),
    DOUBLE("Keypad Enter (map: double press)", 1, 6, false),
    LONG_PRESS("Space, held (map: long press)", 1, 7, true),
    LONG_DOUBLE("Keypad Enter, held", 1, 6, true),
    BTN_A("Button A", 1, 8, false),
    BTN_B("Button B", 1, 9, false),
    BTN_C("Button C", 1, 10, false),
    BTN_D("Button D", 1, 11, false),
    BTN_E("Button E", 1, 12, false),
    BTN_F("Button F", 1, 13, false),
    BTN_G("Button G", 1, 14, false),
    BTN_H("Button H", 1, 15, false);

    final String label;
    final int report;
    final int bit;
    final boolean held;

    Action(String label, int report, int bit, boolean held) {
        this.label = label;
        this.report = report;
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
