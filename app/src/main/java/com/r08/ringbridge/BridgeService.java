package com.r08.ringbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Foreground service: keeps the bridge running at high priority while the phone is locked
 * (stops the system from freezing the app) and lets it hold media buttons in the background.
 */
public class BridgeService extends Service {
    private static final String CHANNEL = "bridge";
    private static final int NOTIFICATION_ID = 1;

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, BridgeService.class));
        } catch (RuntimeException e) {
            BridgeCore.get(context).log("could not start background service: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ring bridge",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("R08 Ring Bridge")
                .setContentText("Ring → glasses bridge running")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } catch (RuntimeException e) {
            BridgeCore.get(this).log("foreground service refused: " + e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        BridgeCore core = BridgeCore.get(this);
        core.serviceRunning = true;
        core.log("background service running");
        core.startHid();
        core.refreshMediaKeys();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BridgeCore core = BridgeCore.get(this);
        core.serviceRunning = false;
        core.log("background service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
