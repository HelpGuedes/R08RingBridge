package com.r08.ringbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

/** Fallback path: media buttons delivered as a broadcast (when no media session is chosen). */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class)
                : (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null) BridgeCore.get(context).handleKey(event, "media receiver");
    }
}
