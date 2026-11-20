package com.r08.ringbridge;

import android.os.Handler;
import android.os.SystemClock;

/**
 * Turns the ring's single events (tap, slide up, slide down) into gestures:
 *   slide_up, slide_down, tap1..tap4, tap_slide_up, tap_slide_down, slide_up_tap, slide_down_tap
 *
 * Two timings:
 *  - tap window: maximum gap between the taps of one multi-tap.
 *  - combo buffer: how long a tap waits for a following slide (and a slide for a following tap).
 *    The ring only reports a slide after it has recognized the swipe, so this needs to be longer
 *    than the tap window. It is only used when a combo gesture is actually assigned.
 */
final class GestureRecognizer {

    static final String[] GESTURES = {
            "slide_up", "slide_down", "tap1", "tap2", "tap3", "tap4",
            "tap_slide_up", "tap_slide_down", "slide_up_tap", "slide_down_tap"
    };
    static final int MAX_TAPS = 4;
    private static final long BOUNCE_MS = 40;

    interface Sink {
        void onGesture(String gesture);
    }

    interface Config {
        long windowMs();

        long comboMs();

        boolean isMapped(String gesture);
    }

    private final Handler handler;
    private final Sink sink;
    private final Config config;

    private int taps;
    private long lastTapAt = -100000;
    private long lastSlideAt = -100000;
    private String pendingSlide; // "up"/"down" waiting for a possible tap

    /** Last measured gaps, for tuning (ms, -1 = none yet). */
    volatile long lastTapToSlideMs = -1;
    volatile long lastSlideToTapMs = -1;

    private final Runnable resolveTaps = new Runnable() {
        @Override public void run() { flushTaps(); }
    };
    private final Runnable resolveSlide = new Runnable() {
        @Override public void run() { flushSlide(); }
    };

    GestureRecognizer(Handler handler, Sink sink, Config config) {
        this.handler = handler;
        this.sink = sink;
        this.config = config;
    }

    void onTap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastSlideAt < 3000) lastSlideToTapMs = now - lastSlideAt;
        if (pendingSlide != null) {
            String dir = pendingSlide;
            handler.removeCallbacks(resolveSlide);
            pendingSlide = null;
            sink.onGesture("slide_" + dir + "_tap");
            return;
        }
        if (taps > 0 && now - lastTapAt < BOUNCE_MS) return; // contact bounce
        if (taps > 0 && now - lastTapAt > config.windowMs()) flushTaps(); // too late to extend the multi-tap
        handler.removeCallbacks(resolveTaps);
        taps++;
        lastTapAt = now;
        boolean moreTaps = taps < MAX_TAPS && wantsMoreTapsAfter(taps);
        boolean combo = tapComboMapped();
        if (!moreTaps && !combo) {
            flushTaps();
            return;
        }
        long wait = moreTaps ? config.windowMs() : 0;
        if (combo) wait = Math.max(wait, config.comboMs());
        handler.postDelayed(resolveTaps, wait);
    }

    void onSlide(boolean up) {
        long now = SystemClock.uptimeMillis();
        String dir = up ? "up" : "down";
        if (now - lastTapAt < 3000) lastTapToSlideMs = now - lastTapAt;
        lastSlideAt = now;
        flushSlide();
        if (taps > 0) {
            String combo = "tap_slide_" + dir;
            if (config.isMapped(combo) && now - lastTapAt <= config.comboMs()) {
                handler.removeCallbacks(resolveTaps);
                taps = 0;
                sink.onGesture(combo);
                return;
            }
            flushTaps(); // taps first, then the slide on its own
        }
        if (config.isMapped("slide_" + dir + "_tap")) {
            pendingSlide = dir;
            handler.postDelayed(resolveSlide, config.comboMs());
        } else {
            sink.onGesture("slide_" + dir);
        }
    }

    void reset() {
        handler.removeCallbacks(resolveTaps);
        handler.removeCallbacks(resolveSlide);
        taps = 0;
        pendingSlide = null;
    }

    private boolean wantsMoreTapsAfter(int count) {
        for (int k = count + 1; k <= MAX_TAPS; k++) {
            if (config.isMapped("tap" + k)) return true;
        }
        return false;
    }

    private boolean tapComboMapped() {
        return config.isMapped("tap_slide_up") || config.isMapped("tap_slide_down");
    }

    private void flushTaps() {
        handler.removeCallbacks(resolveTaps);
        if (taps == 0) return;
        int n = Math.min(taps, MAX_TAPS);
        taps = 0;
        sink.onGesture("tap" + n);
    }

    private void flushSlide() {
        handler.removeCallbacks(resolveSlide);
        if (pendingSlide == null) return;
        String dir = pendingSlide;
        pendingSlide = null;
        sink.onGesture("slide_" + dir);
    }
}
