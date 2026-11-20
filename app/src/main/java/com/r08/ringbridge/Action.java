package com.r08.ringbridge;

/**
 * What the bridge sends to the glasses: one button in one of the bridge's two HID reports.
 *
 * Report 1 (keyboard): bits 0-5 use keys the glasses map by default (arrows, Enter = single
 * press, Esc = back). Bits 6 (Keypad Enter) and 7 (Space) are meant to be mapped in the
 * MemoMind app (e.g. to double press / long press).
 * Report 2 (gamepad): buttons 1-12, which the glasses see as gamepad buttons (BTN_A ...).
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
    PAD_A("Gamepad A (button 1)", 2, 0, false),
    PAD_B("Gamepad B (button 2)", 2, 1, false),
    PAD_X("Gamepad X (button 3)", 2, 2, false),
    PAD_Y("Gamepad Y (button 4)", 2, 3, false),
    PAD_L1("Gamepad L1 (button 5)", 2, 4, false),
    PAD_R1("Gamepad R1 (button 6)", 2, 5, false),
    PAD_L2("Gamepad L2 (button 7)", 2, 6, false),
    PAD_R2("Gamepad R2 (button 8)", 2, 7, false),
    PAD_SELECT("Gamepad Select (button 9)", 2, 8, false),
    PAD_START("Gamepad Start (button 10)", 2, 9, false),
    PAD_THUMBL("Gamepad L3 (button 11)", 2, 10, false),
    PAD_THUMBR("Gamepad R3 (button 12)", 2, 11, false);

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
