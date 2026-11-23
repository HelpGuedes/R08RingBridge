package com.r08.ringbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sets or reads the R08's touch mode over its QRing UART service (the same 0x3B commands QRing uses):
 *   set:  3B 02 00 <appType> <sleepMin>,  gesture off 3B 02 01 00 00,  wake 3B 02 02 <appType> 01
 *   read: 3B 01 00  -> reply 3B 01 00 <appType> <sleepMin> ...
 * The ring drops the BLE link right after a mode change, so the sequence reconnects and continues,
 * and only reports success when the read-back shows the requested mode.
 */
final class RingConfigurator {
    static final int MODE_MUSIC = 1;
    private static final int SLEEP_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 4;
    private static final long OVERALL_TIMEOUT_MS = 40000;

    private static final UUID UART_SERVICE = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    interface Listener {
        void onRingLog(String message);

        void onRingMode(int appType);
    }

    private final Context context;
    private final Handler handler;
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private final List<byte[]> queue = new ArrayList<>();
    private int next;            // index of the next packet to send
    private int targetMode = -1; // -1 = read only
    private int attempts;
    private boolean busy;

    private final Runnable timeout = new Runnable() {
        @Override public void run() { finish("ring didn't answer in time. Is it connected to the phone, not the e-reader?"); }
    };

    RingConfigurator(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
    }

    boolean isBusy() {
        return busy;
    }

    /** Switch the ring to the given touch mode (1 = Music) and verify it. */
    void setMode(int appType, String ringNameFilter) {
        if (busy) return;
        queue.clear();
        queue.add(packet(0x3B, 0x02, 0x00, appType, SLEEP_MINUTES));
        queue.add(packet(0x3B, 0x02, 0x01, 0x00, 0x00));
        queue.add(packet(0x3B, 0x02, 0x02, appType, 0x01));
        queue.add(packet(0x3B, 0x01, 0x00));
        targetMode = appType;
        start(ringNameFilter, "setting ring to mode " + appType + (appType == MODE_MUSIC ? " (Music)" : ""));
    }

    /** Read the ring's current touch mode. */
    void readMode(String ringNameFilter) {
        if (busy) return;
        queue.clear();
        queue.add(packet(0x3B, 0x01, 0x00));
        targetMode = -1;
        start(ringNameFilter, "reading ring mode");
    }

    // ------------------------------------------------------------------ sequence

    private void start(String filter, String what) {
        BluetoothDevice ring = findRing(filter);
        if (ring == null) {
            log("ring not found among this phone's paired devices (name containing \"" + filter + "\")");
            return;
        }
        busy = true;
        next = 0;
        attempts = 0;
        log(what);
        handler.postDelayed(timeout, OVERALL_TIMEOUT_MS);
        connect(ring);
    }

    private BluetoothDevice findRing(String filter) {
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return null;
            String f = filter == null ? "" : filter.trim().toLowerCase();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n != null && !f.isEmpty() && n.toLowerCase().contains(f)) return d;
            }
        } catch (SecurityException e) {
            log("missing Bluetooth permission");
        }
        return null;
    }

    private void connect(final BluetoothDevice ring) {
        attempts++;
        // After a drop the mode packet was most likely applied (the drop is how the ring applies it):
        // continue with the rest; the final read-back decides whether a full retry is needed.
        if (attempts > 1 && next == 0 && targetMode >= 0) next = 1;
        if (attempts > MAX_ATTEMPTS) {
            finish("gave up after " + MAX_ATTEMPTS + " connection attempts");
            return;
        }
        try {
            gatt = ring.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            finish("missing Bluetooth permission");
        }
    }

    private void sendNext() {
        if (gatt == null || writeChar == null) return;
        if (next >= queue.size()) return; // waiting for the read-back reply
        byte[] p = queue.get(next);
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(writeChar, p, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        == BluetoothGatt.GATT_SUCCESS;
            } else {
                ok = legacyWrite(p);
            }
            if (!ok) log("write " + (next + 1) + " not accepted yet, will retry after reconnect");
        } catch (SecurityException e) {
            finish("missing Bluetooth permission");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean legacyWrite(byte[] p) {
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writeChar.setValue(p);
        return gatt.writeCharacteristic(writeChar);
    }

    @SuppressWarnings("deprecation")
    private boolean enableNotifications(BluetoothGattCharacteristic notify) {
        gatt.setCharacteristicNotification(notify, true);
        BluetoothGattDescriptor d = notify.getDescriptor(CCCD);
        if (d == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS;
        }
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        return gatt.writeDescriptor(d);
    }

    private void onReply(byte[] v) {
        if (v == null || v.length < 5 || (v[0] & 0xFF) != 0x3B || v[1] != 0x01 || v[2] != 0x00) return;
        int mode = v[3] & 0xFF;
        listener.onRingMode(mode);
        if (targetMode < 0) {
            finish(null);
            log("ring touch mode = " + mode + (mode == MODE_MUSIC ? " (Music)" : ""));
        } else if (mode == targetMode) {
            finish(null);
            log("ring is now in mode " + mode + (mode == MODE_MUSIC ? " (Music). Taps and slides go to the bridge" : ""));
        } else {
            log("ring reports mode " + mode + ", expected " + targetMode + ": sending again");
            next = 0;
            sendNext();
        }
    }

    private void finish(String error) {
        handler.removeCallbacks(timeout);
        if (error != null) log("ring mode: " + error);
        busy = false;
        closeGatt();
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        if (g == null) return;
        try {
            g.disconnect();
            g.close();
        } catch (SecurityException ignored) {
        }
    }

    // ------------------------------------------------------------------ GATT callbacks (binder threads)

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, final int newState) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (!busy || g != gatt) return;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g.discoverServices();
                        } catch (SecurityException e) {
                            finish("missing Bluetooth permission");
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // Expected right after a mode change: reconnect and continue.
                        final BluetoothDevice ring = g.getDevice();
                        closeGatt();
                        log("ring dropped the link (normal after a mode change), reconnecting");
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { if (busy) connect(ring); }
                        }, 1500);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int status) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (!busy || g != gatt) return;
                    BluetoothGattService s = g.getService(UART_SERVICE);
                    BluetoothGattCharacteristic w = s == null ? null : s.getCharacteristic(UART_WRITE);
                    BluetoothGattCharacteristic n = s == null ? null : s.getCharacteristic(UART_NOTIFY);
                    if (w == null || n == null) {
                        finish("this ring has no QRing command service");
                        return;
                    }
                    writeChar = w;
                    try {
                        if (!enableNotifications(n)) sendNext(); // continue even if the CCCD write is refused
                    } catch (SecurityException e) {
                        finish("missing Bluetooth permission");
                    }
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            handler.post(new Runnable() {
                @Override public void run() { if (busy && g == gatt) sendNext(); }
            });
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (!busy || g != gatt) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) next++;
                    sendNext();
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, final byte[] value) {
            handler.post(new Runnable() {
                @Override public void run() { if (busy) onReply(value); }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return; // handled by the method above
            final byte[] value = c.getValue();
            handler.post(new Runnable() {
                @Override public void run() { if (busy) onReply(value); }
            });
        }
    };

    // ------------------------------------------------------------------ helpers

    static byte[] packet(int... head) {
        byte[] b = new byte[16];
        int sum = 0;
        for (int i = 0; i < head.length && i < 15; i++) {
            b[i] = (byte) head[i];
            sum += head[i] & 0xFF;
        }
        b[15] = (byte) sum;
        return b;
    }

    private void log(String m) {
        listener.onRingLog(m);
    }
}
