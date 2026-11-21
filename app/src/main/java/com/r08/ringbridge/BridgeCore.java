package com.r08.ringbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** App-wide singleton: ring events -> gestures -> actions -> HID keyboard reports to the glasses. */
final class BridgeCore implements HidPeripheral.Listener, GestureRecognizer.Sink, GestureRecognizer.Config {

    private static BridgeCore instance;

    static synchronized BridgeCore get(Context context) {
        if (instance == null) instance = new BridgeCore(context.getApplicationContext());
        return instance;
    }

    final SharedPreferences prefs;
    final HidPeripheral hid;
    private final GestureRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaKeyCatcher mediaKeys;
    private final PowerManager.WakeLock wakeLock;

    volatile boolean accessibilityConnected;
    volatile boolean serviceRunning;
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    volatile String lastKey = "-";
    volatile String lastGesture = "-";
    private Runnable uiListener;

    private BridgeCore(Context app) {
        prefs = app.getSharedPreferences("ring_bridge", Context.MODE_PRIVATE);
        hid = new HidPeripheral(app, this, prefs);
        recognizer = new GestureRecognizer(handler, this, this);
        mediaKeys = new MediaKeyCatcher(app, this, handler);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RingBridge:gesture");
        wakeLock.setReferenceCounted(false);
        // When the screen turns off, media keys stop reaching the Accessibility service:
        // take over media buttons so the ring keeps working while the phone is locked.
        BroadcastReceiver screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    logBond(intent);
                    return;
                }
                boolean off = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
                log(off ? "screen off" : "screen on");
                if (off && screenOffMode() && (accessibilityConnected || serviceRunning)) {
                    wakeLock.acquire(2000); // let the media-button claim finish
                    mediaKeys.enable();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(screenOff, filter);
        }
    }

    // ------------------------------------------------------------------ settings

    static Action defaultAction(String gesture) {
        switch (gesture) {
            case "slide_up": return Action.UP;
            case "slide_down": return Action.DOWN;
            case "tap1": return Action.DOUBLE;
            case "tap2": return Action.SINGLE;
            case "tap3": return Action.BACK;
            case "tap4": return Action.LONG_PRESS;
            default: return Action.NONE;
        }
    }

    Action actionFor(String gesture) {
        return Action.parse(prefs.getString("map_" + gesture, null), defaultAction(gesture));
    }

    void setAction(String gesture, Action action) {
        prefs.edit().putString("map_" + gesture, action.name()).apply();
    }

    int holdMs() { return prefs.getInt("hold_ms", 2100); }

    String ringFilter() { return prefs.getString("ring_filter", "R08"); }

    boolean screenOffMode() { return prefs.getBoolean("screen_off_mode", true); }

    void setScreenOffMode(boolean on) {
        prefs.edit().putBoolean("screen_off_mode", on).apply();
        refreshMediaKeys();
        notifyUi();
    }

    @SuppressWarnings("deprecation")
    private void logBond(Intent intent) {
        android.bluetooth.BluetoothDevice d = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
        int st = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1);
        int prev = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
        String name = st == android.bluetooth.BluetoothDevice.BOND_BONDED ? "PAIRED"
                : st == android.bluetooth.BluetoothDevice.BOND_BONDING ? "pairing..."
                : (prev == android.bluetooth.BluetoothDevice.BOND_BONDING ? "PAIRING FAILED" : "unpaired");
        log("bond " + (d == null ? "?" : d.getAddress()) + ": " + name);
    }

    void refreshMediaKeys() {
        if (screenOffMode() && (accessibilityConnected || serviceRunning)) mediaKeys.enable();
        else mediaKeys.disable();
    }

    boolean screenOffActive() { return mediaKeys.isEnabled(); }

    /** Called when the Accessibility service starts or stops. */
    void onServiceState(boolean connected) {
        accessibilityConnected = connected;
        log(connected ? "ring service (Accessibility) on" : "ring service (Accessibility) OFF");
        refreshMediaKeys();
        if (!connected) recognizer.reset();
        notifyUi();
    }

    @Override public long windowMs() { return prefs.getInt("window_ms", 350); }

    @Override public long comboMs() { return prefs.getInt("combo_ms", 900); }

    long lastTapToSlideMs() { return recognizer.lastTapToSlideMs; }

    long lastSlideToTapMs() { return recognizer.lastSlideToTapMs; }

    @Override public boolean isMapped(String gesture) { return actionFor(gesture) != Action.NONE; }

    // ------------------------------------------------------------------ ring input

    /** Called by the accessibility service for every key event. Returns true to swallow it. */
    boolean handleKey(KeyEvent event) {
        return handleKey(event, "screen on");
    }

    /** Ring keys from either path (Accessibility = screen on, media session = screen off). */
    boolean handleKey(KeyEvent event, String path) {
        InputDevice device = event.getDevice();
        String name = device == null ? "" : device.getName();
        String filter = ringFilter().trim();
        if (filter.isEmpty() || name == null || !name.toLowerCase().contains(filter.toLowerCase())) {
            return false; // not the ring: leave it alone
        }
        int kind = classify(event.getKeyCode());
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            // keep the CPU awake long enough for the tap window and any held key to finish
            wakeLock.acquire(Math.max(windowMs(), comboMs()) + holdMs() + 1500);
            lastKey = KeyEvent.keyCodeToString(event.getKeyCode()) + " from " + name + " (" + path + ")";
            log("key " + KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "") + " (" + path + ")");
            if (kind == 1) recognizer.onTap();
            else if (kind == 2) recognizer.onSlide(true);
            else if (kind == 3) recognizer.onSlide(false);
            notifyUi();
        }
        return true; // swallow all ring keys so they do not also control the phone's media
    }

    /** 1 = tap, 2 = slide up, 3 = slide down, 0 = other. Same groups as R08 Access Bridge. */
    static int classify(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
                return 1;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                return 2;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 3;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------ output

    @Override
    public void onGesture(String gesture) {
        Action a = actionFor(gesture);
        lastGesture = gesture + " -> " + a.label;
        log("gesture " + gesture + " -> " + a.label);
        perform(a);
        notifyUi();
    }

    void perform(Action a) {
        if (a == Action.NONE) return;
        hid.sendKey(a.report, a.bit, a.held ? holdMs() : 90);
    }

    void startHid() { hid.start(); }

    void resetRecognizer() { recognizer.reset(); }

    // ------------------------------------------------------------------ UI plumbing

    void setUiListener(Runnable r) { uiListener = r; }

    @Override public void onHidStateChanged() { notifyUi(); }

    @Override public void onHidLog(String message) { log(message); }

    /** Event log shown in the app (newest first), for checking what happens while locked. */
    void log(String message) {
        synchronized (events) {
            events.addFirst(clock.format(new Date()) + "  " + message);
            while (events.size() > 40) events.removeLast();
        }
        notifyUi();
    }

    String eventLog() {
        StringBuilder b = new StringBuilder();
        synchronized (events) {
            for (String e : events) b.append(e).append('\n');
        }
        return b.length() == 0 ? "(nothing yet)" : b.toString();
    }

    private void notifyUi() {
        final Runnable r = uiListener;
        if (r != null) handler.post(r);
    }
}
