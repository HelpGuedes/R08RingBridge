package com.r08.ringbridge;

/**
 * What the bridge sends to the glasses: one bit of the single 2-byte keyboard report.
 *
 * All of these are keys the glasses firmware recognizes, so the MemoMind app can map them to any
 * "AI glasses action" (Double click, Open AI chat, Open quick menu, Turn off screen, ...):
 *  - keyboard: arrows, Enter (single press), Escape (back)
 *  - media (Consumer page): Next/Previous track, Play/Pause, Mute, Volume up/down
 */
enum Action {
    NONE("Nothing", -1, false),
    UP("Up", 0, false),
    DOWN("Down", 1, false),
    LEFT("Left", 2, false),
    RIGHT("Right", 3, false),
    SINGLE("Single press (Enter)", 4, false),
    BACK("Back (Escape)", 5, false),
    MEDIA_NEXT("Next track", 8, false),
    MEDIA_PREV("Previous track", 9, false),
    MEDIA_PLAY_PAUSE("Pause/Resume", 10, false),
    MEDIA_MUTE("Mute", 11, false),
    MEDIA_VOL_UP("Volume up", 12, false),
    MEDIA_VOL_DOWN("Volume down", 13, false);

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
