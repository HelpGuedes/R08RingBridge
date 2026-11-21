package com.r08.ringbridge;

/**
 * What the bridge sends to the glasses: one button in one of the bridge's two HID reports.
 *
 * Report 1 (keyboard): arrows, Enter = single press, Escape = back (glasses defaults), plus
 * Keypad Enter and Space.
 * Report 2 (media keys): Next, Previous, Play/Pause, Mute, Volume up/down, Home, Menu. The
 * glasses recognize these as keys, so the MemoMind app can record them for any action.
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
    MEDIA_NEXT("Media: Next", 2, 0, false),
    MEDIA_PREV("Media: Previous", 2, 1, false),
    MEDIA_PLAY_PAUSE("Media: Play/Pause", 2, 2, false),
    MEDIA_MUTE("Media: Mute", 2, 3, false),
    MEDIA_VOL_UP("Media: Volume up", 2, 4, false),
    MEDIA_VOL_DOWN("Media: Volume down", 2, 5, false),
    MEDIA_HOME("Media: Home", 2, 6, false),
    MEDIA_MENU("Media: Menu", 2, 7, false);

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
