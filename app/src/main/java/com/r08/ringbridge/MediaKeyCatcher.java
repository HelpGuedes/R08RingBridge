package com.r08.ringbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Screen-off path. With the screen off, Android delivers media keys to the active media
 * session instead of the Accessibility key filter. This keeps a media session that takes
 * media buttons, passes ring keys (filtered by device name) to the recognizer, and briefly
 * plays silence when the screen turns off so Android routes media buttons to this app.
 */
final class MediaKeyCatcher {
    private static final String TAG = "RingBridgeMedia";

    private final Context context;
    private final BridgeCore core;
    private final Handler handler;
    private MediaSession session;

    MediaKeyCatcher(Context context, BridgeCore core, Handler handler) {
        this.context = context.getApplicationContext();
        this.core = core;
        this.handler = handler;
    }

    boolean isEnabled() {
        return session != null;
    }

    void enable() {
        if (session == null) {
            session = new MediaSession(context, "R08RingBridge");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent intent) {
                    KeyEvent event = extract(intent);
                    if (event != null && core.handleKey(event, "screen off")) return true;
                    return super.onMediaButtonEvent(intent);
                }
            }, handler);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());
            session.setMediaButtonBroadcastReceiver(new ComponentName(context, MediaButtonReceiver.class));
            session.setActive(true);
            core.log("media session created");
        }
        claimMediaButtons();
    }

    void disable() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }

    /** Become the most recent audio player so Android routes media buttons here. */
    void claimMediaButtons() {
        if (session == null) return;
        session.setActive(true);
        try {
            final int rate = 22050;
            final int frames = rate / 4; // 250 ms of silence
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
            core.log("claimed media buttons");
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
