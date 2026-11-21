package com.r08.ringbridge;

/**
 * What the bridge sends to the glasses: one button in one of the bridge's two HID reports.
 *
 * One keyboard report (bits 0-15). Arrows, Enter = single press, Escape = back are glasses
 * defaults. Bits 8-15 are extra keyboard keys (Home, Page up/down, Menu, Application, Mute,
 * Volume up/down) for recording in the MemoMind app.
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
    KEY_HOME("Home", 1, 8, false),
    KEY_PAGE_UP("Page up", 1, 9, false),
    KEY_PAGE_DOWN("Page down", 1, 10, false),
    KEY_MENU("Menu", 1, 11, false),
    KEY_APPLICATION("Application key", 1, 12, false),
    KEY_MUTE("Mute", 1, 13, false),
    KEY_VOL_UP("Volume up", 1, 14, false),
    KEY_VOL_DOWN("Volume down", 1, 15, false);

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
