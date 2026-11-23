package com.r08.ringbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Locked-phone path, "ring always controls the glasses" (option C).
 *
 * With the screen off, Android delivers media keys to the media-button session (the app that
 * most recently started audio) instead of the Accessibility key filter. While the screen is off
 * this class holds that slot:
 *  - claims it when the screen turns off (a quarter second of silence makes this app the most
 *    recent audio player),
 *  - claims it again every time another app starts or changes audio (e.g. the next song), because
 *    that app would otherwise take the slot back,
 *  - swallows every media key: ring keys go to the recognizer, all others (headphones, the
 *    glasses' own music controls) are ignored and logged.
 * When the screen turns on it releases the slot: Accessibility handles the ring again and the
 * glasses' music controls work normally.
 */
final class MediaKeyCatcher {
    private static final String TAG = "RingBridgeMedia";
    private static final long OWN_SILENCE_MS = 700;    // callbacks inside this window are our own silence
    private static final long MIN_CLAIM_GAP_MS = 3000; // never claim more often than this
    private static final long CLAIM_DELAY_MS = 400;    // let the other player start before re-claiming

    private final Context context;
    private final BridgeCore core;
    private final Handler handler;
    private final AudioManager audio;
    private final PowerManager power;
    private MediaSession session;
    private boolean enabled;
    private boolean screenOff;
    private long lastClaimAt = -100000;

    private final Runnable claimRunnable = new Runnable() {
        @Override public void run() {
            if (enabled && screenOff) claim("another app started audio");
        }
    };

    private final AudioManager.AudioPlaybackCallback playbackCallback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            if (!enabled || !screenOff) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastClaimAt < OWN_SILENCE_MS) return; // our own silence starting/stopping
            long delay = Math.max(CLAIM_DELAY_MS, lastClaimAt + MIN_CLAIM_GAP_MS - now);
            handler.removeCallbacks(claimRunnable);
            handler.postDelayed(claimRunnable, delay);
        }
    };

    MediaKeyCatcher(Context context, BridgeCore core, Handler handler) {
        this.context = context.getApplicationContext();
        this.core = core;
        this.handler = handler;
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.power = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isHolding() {
        return session != null && session.isActive();
    }

    /** Turn the locked-phone path on. Claims immediately if the screen is already off. */
    void enable() {
        if (enabled) return;
        enabled = true;
        audio.registerAudioPlaybackCallback(playbackCallback, handler);
        core.log("locked-phone mode on");
        if (!power.isInteractive()) onScreenOff();
    }

    void disable() {
        if (!enabled) return;
        enabled = false;
        handler.removeCallbacks(claimRunnable);
        audio.unregisterAudioPlaybackCallback(playbackCallback);
        release();
        if (session != null) {
            session.release();
            session = null;
        }
        core.log("locked-phone mode off");
    }

    void onScreenOff() {
        screenOff = true;
        if (enabled) claim("screen off");
    }

    void onScreenOn() {
        screenOff = false;
        handler.removeCallbacks(claimRunnable);
        if (enabled) release();
    }

    // ------------------------------------------------------------------ internals

    private void ensureSession() {
        if (session != null) return;
        session = new MediaSession(context, "R08RingBridge");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = extract(intent);
                if (event == null) return true;
                if (!core.handleKey(event, "screen off") && event.getAction() == KeyEvent.ACTION_DOWN) {
                    core.log("ignored " + KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "")
                            + " (not from the ring; phone locked)");
                }
                return true; // option C: the bridge keeps every media key while the phone is locked
            }
        }, handler);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
    }

    /** Take the media-button slot: active session + receiver + a short burst of silence. */
    private void claim(String why) {
        ensureSession();
        session.setMediaButtonBroadcastReceiver(new ComponentName(context, MediaButtonReceiver.class));
        session.setActive(true);
        lastClaimAt = SystemClock.uptimeMillis();
        playSilence();
        core.log("claimed media buttons (" + why + ")");
    }

    /** Give the slot back (screen on): media keys return to the music app / glasses controls. */
    private void release() {
        if (session == null || !session.isActive()) return;
        session.setActive(false);
        session.setMediaButtonBroadcastReceiver(null);
        core.log("released media buttons (screen on)");
    }

    private void playSilence() {
        try {
            final int rate = 22050;
            final int frames = rate / 4; // 250 ms
            final AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            track.write(new short[frames], 0, frames);
            track.play();
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    track.release();
                }
            }, 400);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not play silence", e);
            core.log("could not claim media buttons: " + e.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation")
    private static KeyEvent extract(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }
}
