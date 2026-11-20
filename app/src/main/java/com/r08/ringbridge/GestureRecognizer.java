package com.r08.ringbridge;

import android.os.Handler;
import android.os.SystemClock;

/**
 * Turns the ring's single events (tap, slide up, slide down) into gestures:
 *   slide_up, slide_down, tap1..tap4, tap_slide_up, tap_slide_down, slide_up_tap, slide_down_tap
 *
 * Taps are counted while they keep arriving within the tap window. The recognizer only
 * waits when a longer gesture is actually mapped, so unmapped combos add no delay.
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

        boolean isMapped(String gesture);
    }

    private final Handler handler;
    private final Sink sink;
    private final Config config;

    private int taps;
    private long lastTapAt;
    private String pendingSlide; // "up"/"down" waiting for a possible tap
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
        if (pendingSlide != null) {
            String dir = pendingSlide;
            handler.removeCallbacks(resolveSlide);
            pendingSlide = null;
            sink.onGesture("slide_" + dir + "_tap");
            return;
        }
        if (taps > 0 && now - lastTapAt < BOUNCE_MS) return; // ignore contact bounce
        handler.removeCallbacks(resolveTaps);
        taps++;
        lastTapAt = now;
        if (taps >= MAX_TAPS || !wantsMoreAfter(taps)) {
            flushTaps();
        } else {
            handler.postDelayed(resolveTaps, config.windowMs());
        }
    }

    void onSlide(boolean up) {
        String dir = up ? "up" : "down";
        flushSlide();
        if (taps > 0) {
            String combo = "tap_slide_" + dir;
            if (config.isMapped(combo)) {
                handler.removeCallbacks(resolveTaps);
                taps = 0;
                sink.onGesture(combo);
                return;
            }
            flushTaps(); // taps first, then the slide on its own
        }
        if (config.isMapped("slide_" + dir + "_tap")) {
            pendingSlide = dir;
            handler.postDelayed(resolveSlide, config.windowMs());
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

    private boolean wantsMoreAfter(int count) {
        for (int k = count + 1; k <= MAX_TAPS; k++) {
            if (config.isMapped("tap" + k)) return true;
        }
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
