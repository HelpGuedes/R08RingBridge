package com.r08.ringbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The phone as a BLE HID keyboard (HOGP device) for the MemoMind glasses.
 *
 * ONE input report (ID 1), 2 bytes, one Keyboard collection. A single report is required: with
 * a second report the glasses connected but received no keys.
 *   byte 0 (unchanged from the first working version; arrows/Enter/Escape are recognized):
 *     bit0 Up 0x52, bit1 Down 0x51, bit2 Left 0x50, bit3 Right 0x4F,
 *     bit4 Enter 0x28, bit5 Escape 0x29, bit6 Keypad Enter 0x58, bit7 Space 0x2C
 *   byte 1, Consumer-page media keys (recognized by the glasses firmware, seen from the ring):
 *     bit8 Next 0xB5, bit9 Previous 0xB6, bit10 Play/Pause 0xCD, bit11 Mute 0xE2,
 *     bit12 Volume Up 0xE9, bit13 Volume Down 0xEA, bits 14-15 padding
 */
final class HidPeripheral {
    private static final String TAG = "RingBridgeHid";

    static UUID uuid16(int v) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v));
    }

    static final UUID HID_SERVICE = uuid16(0x1812);
    static final UUID HID_INFO = uuid16(0x2A4A);
    static final UUID REPORT_MAP = uuid16(0x2A4B);
    static final UUID HID_CONTROL_POINT = uuid16(0x2A4C);
    static final UUID REPORT = uuid16(0x2A4D);
    static final UUID PROTOCOL_MODE = uuid16(0x2A4E);
    static final UUID REPORT_REFERENCE = uuid16(0x2908);
    static final UUID CCCD = uuid16(0x2902);
    static final UUID DIS_SERVICE = uuid16(0x180A);
    static final UUID MANUFACTURER_NAME = uuid16(0x2A29);
    static final UUID PNP_ID = uuid16(0x2A50);
    static final UUID BATTERY_SERVICE = uuid16(0x180F);
    static final UUID BATTERY_LEVEL = uuid16(0x2A19);

    static final byte KEYBOARD_REPORT_ID = 1;
    static final byte[] REPORT_MAP_BYTES = {
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            (byte) 0xA1, 0x01,      // Collection (Application)
            (byte) 0x85, KEYBOARD_REPORT_ID, // Report ID (1)
            0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
            0x09, 0x52,             //   Usage (Up Arrow)        bit0
            0x09, 0x51,             //   Usage (Down Arrow)      bit1
            0x09, 0x50,             //   Usage (Left Arrow)      bit2
            0x09, 0x4F,             //   Usage (Right Arrow)     bit3
            0x09, 0x28,             //   Usage (Enter)           bit4
            0x09, 0x29,             //   Usage (Escape)          bit5
            0x09, 0x58,             //   Usage (Keypad Enter)    bit6
            0x09, 0x2C,             //   Usage (Space)           bit7
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            0x75, 0x01,             //   Report Size (1)
            (byte) 0x95, 0x08,      //   Report Count (8)
            (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
            0x05, 0x0C,             //   Usage Page (Consumer)
            0x09, (byte) 0xB5,      //   Usage (Scan Next Track)     bit8
            0x09, (byte) 0xB6,      //   Usage (Scan Previous Track) bit9
            0x09, (byte) 0xCD,      //   Usage (Play/Pause)          bit10
            0x09, (byte) 0xE2,      //   Usage (Mute)                bit11
            0x09, (byte) 0xE9,      //   Usage (Volume Increment)    bit12
            0x09, (byte) 0xEA,      //   Usage (Volume Decrement)    bit13
            (byte) 0x95, 0x06,      //   Report Count (6)
            (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
            (byte) 0x95, 0x02,      //   Report Count (2)
            (byte) 0x81, 0x03,      //   Input (Constant) padding
            (byte) 0xC0             // End Collection
    };

    interface Listener {
        void onHidStateChanged();

        void onHidLog(String message);
    }

    private final Context context;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothManager manager;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic keyboardReport;
    private final Map<BluetoothGattCharacteristic, byte[]> values = new IdentityHashMap<>();
    private final ArrayDeque<BluetoothGattService> pendingServices = new ArrayDeque<>();
    private final Set<BluetoothDevice> connected = new HashSet<>();
    private final Set<BluetoothDevice> subscribed = new HashSet<>();
    private boolean advertising;
    private boolean nameInScanResponse;
    private long busyUntil;
    volatile String state = "stopped";

    HidPeripheral(Context context, Listener listener, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.prefs = prefs;
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        if (server != null) {
            if (connected.isEmpty()) startAdvertising();
            return;
        }
        try {
            manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setState("Bluetooth is off");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            server = manager.openGattServer(context, callback);
            if (server == null || advertiser == null) {
                setState("This phone cannot act as a BLE peripheral");
                return;
            }
            pendingServices.clear();
            pendingServices.add(buildHidService());
            pendingServices.add(buildDeviceInfoService());
            pendingServices.add(buildBatteryService());
            addNextService();
        } catch (SecurityException e) {
            setState("Missing Bluetooth permission");
        }
    }

    void stop() {
        try {
            if (advertiser != null && advertising) advertiser.stopAdvertising(advertiseCallback);
            if (server != null) server.close();
        } catch (SecurityException ignored) {
        }
        advertising = false;
        server = null;
        connected.clear();
        subscribed.clear();
        setState("stopped");
    }

    boolean isReady() {
        return !subscribed.isEmpty();
    }

    // ------------------------------------------------------------------ sending keys

    /** Press one key (bit 0-13) for holdMs, then release. Presses never overlap. */
    void sendKey(final int bit, final int holdMs) {
        if (bit < 0 || bit > 13) return;
        long now = SystemClock.uptimeMillis();
        long pressAt = Math.max(now, busyUntil);
        long releaseAt = pressAt + Math.max(30, holdMs);
        busyUntil = releaseAt + 60;
        handler.postAtTime(new Runnable() {
            @Override public void run() {
                int v = 1 << bit;
                notifyReport(new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)});
            }
        }, pressAt);
        handler.postAtTime(new Runnable() {
            @Override public void run() { notifyReport(new byte[]{0, 0}); }
        }, releaseAt);
    }

    @SuppressWarnings("deprecation")
    private void notifyReport(byte[] value) {
        BluetoothGattCharacteristic ch = keyboardReport;
        if (server == null || ch == null) {
            if (listener != null) listener.onHidLog("NOT sent: HID server not running");
            return;
        }
        values.put(ch, value);
        Set<BluetoothDevice> targets = new HashSet<>(subscribed);
        boolean press = value[0] != 0 || value[1] != 0;
        if (listener != null && press) {
            listener.onHidLog(targets.isEmpty() ? "NOT sent: glasses not subscribed" : "sent key to glasses");
        }
        for (BluetoothDevice d : targets) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    server.notifyCharacteristicChanged(d, ch, false, value);
                } else {
                    ch.setValue(value);
                    server.notifyCharacteristicChanged(d, ch, false);
                }
            } catch (SecurityException e) {
                setState("Missing Bluetooth permission");
            }
        }
    }

    // ------------------------------------------------------------------ GATT database

    private BluetoothGattCharacteristic chr(UUID uuid, int props, int perms, byte[] value) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid, props, perms);
        values.put(c, value);
        return c;
    }

    private BluetoothGattService buildHidService() {
        BluetoothGattService s = new BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(chr(HID_INFO, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
                new byte[]{0x11, 0x01, 0x00, 0x02})); // HID 1.11, country 0, normally connectable
        s.addCharacteristic(chr(REPORT_MAP, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, REPORT_MAP_BYTES));
        s.addCharacteristic(chr(HID_CONTROL_POINT, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED, new byte[]{0}));
        s.addCharacteristic(chr(PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
                new byte[]{0x01})); // report protocol

        keyboardReport = inputReportCharacteristic(new byte[]{0, 0});
        s.addCharacteristic(keyboardReport);
        return s;
    }

    private BluetoothGattCharacteristic inputReportCharacteristic(byte[] initial) {
        BluetoothGattCharacteristic c = chr(REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, initial);
        c.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED));
        c.addDescriptor(new BluetoothGattDescriptor(REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED));
        return c;
    }

    private BluetoothGattService buildDeviceInfoService() {
        BluetoothGattService s = new BluetoothGattService(DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(chr(MANUFACTURER_NAME, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ, "R08 Ring Bridge".getBytes()));
        s.addCharacteristic(chr(PNP_ID, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
                new byte[]{0x02, 0x34, 0x12, 0x08, 0x00, 0x01, 0x00}));
        return s;
    }

    private BluetoothGattService buildBatteryService() {
        BluetoothGattService s = new BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(chr(BATTERY_LEVEL, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ, new byte[]{100}));
        return s;
    }

    private void addNextService() {
        BluetoothGattService next = pendingServices.poll();
        try {
            if (next != null) {
                server.addService(next); // continue in onServiceAdded
            } else {
                startAdvertising();
            }
        } catch (SecurityException e) {
            setState("Missing Bluetooth permission");
        }
    }

    // ------------------------------------------------------------------ advertising

    private void startAdvertising() {
        if (advertiser == null || advertising) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        // The glasses' HOGP scan needs a non-empty name AND service 0x1812. Put the name in the main
        // advertisement (passive scans see it); fall back to the scan response if it does not fit.
        AdvertiseData.Builder main = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HID_SERVICE))
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(!nameInScanResponse);
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(nameInScanResponse)
                .build();
        try {
            advertiser.startAdvertising(settings, main.build(), scanResponse, advertiseCallback);
            setState("starting advertising...");
        } catch (SecurityException e) {
            setState("Missing Bluetooth permission");
        }
    }

    private void stopAdvertising() {
        if (advertiser == null || !advertising) return;
        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (SecurityException ignored) {
        }
        advertising = false;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            setState("advertising as a HID keyboard: pair it from MemoMind > Third-party devices");
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !nameInScanResponse) {
                nameInScanResponse = true;
                startAdvertising();
                return;
            }
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true;
                return;
            }
            setState("advertising failed (error " + errorCode + "); if 1, shorten the phone's Bluetooth name");
        }
    };

    // ------------------------------------------------------------------ GATT server callbacks

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            handler.post(new Runnable() {
                @Override public void run() { addNextService(); }
            });
        }

        @Override
        public void onConnectionStateChange(final BluetoothDevice device, final int status, final int newState) {
            if (listener != null) {
                listener.onHidLog("link " + (newState == BluetoothProfile.STATE_CONNECTED ? "UP" : "DOWN")
                        + " " + device.getAddress() + " (status " + status + ")");
            }
            handler.post(new Runnable() {
                @Override public void run() {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected.add(device);
                        if (wasSubscribed(device)) subscribed.add(device); // bonded hosts may skip the CCCD write
                        stopAdvertising();
                        setState("connected: " + describe(device));
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connected.remove(device);
                        subscribed.remove(device);
                        setState("disconnected, advertising again");
                        if (connected.isEmpty()) startAdvertising();
                    }
                }
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            byte[] v = values.get(characteristic);
            respond(device, requestId, offset, v);
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            byte[] v;
            if (CCCD.equals(descriptor.getUuid())) {
                v = subscribed.contains(device) ? new byte[]{1, 0} : new byte[]{0, 0};
            } else if (REPORT_REFERENCE.equals(descriptor.getUuid())) {
                v = new byte[]{KEYBOARD_REPORT_ID, 0x01}; // input report
            } else {
                v = new byte[0];
            }
            respond(device, requestId, offset, v);
        }

        @Override
        public void onDescriptorWriteRequest(final BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                             boolean responseNeeded, int offset, final byte[] value) {
            if (CCCD.equals(descriptor.getUuid())) {
                final boolean on = value != null && value.length > 0 && (value[0] & 0x01) != 0;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (on) subscribed.add(device); else subscribed.remove(device);
                        rememberSubscription(device, on);
                        setState(on ? "READY: glasses subscribed (" + describe(device) + ")"
                                : "glasses unsubscribed");
                    }
                });
            }
            if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                                                 boolean responseNeeded, int offset, byte[] value) {
            if (value != null) values.put(characteristic, value); // protocol mode / control point
            if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
        }
    };

    private void respond(BluetoothDevice device, int requestId, int offset, byte[] v) {
        if (v == null) v = new byte[0];
        if (offset > v.length) {
            sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            return;
        }
        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(v, offset, v.length));
    }

    private void sendResponse(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        try {
            if (server != null) server.sendResponse(device, requestId, status, offset, value);
        } catch (SecurityException e) {
            Log.w(TAG, "sendResponse without permission", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean wasSubscribed(BluetoothDevice d) {
        Set<String> s = prefs.getStringSet("subscribed_hosts", new HashSet<String>());
        return s.contains(d.getAddress());
    }

    private void rememberSubscription(BluetoothDevice d, boolean on) {
        Set<String> s = new HashSet<>(prefs.getStringSet("subscribed_hosts", new HashSet<String>()));
        if (on) s.add(d.getAddress()); else s.remove(d.getAddress());
        prefs.edit().putStringSet("subscribed_hosts", s).apply();
    }

    private String describe(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null ? "" : n + " ") + d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private void setState(String s) {
        boolean changed = !s.equals(state);
        state = s;
        Log.i(TAG, s);
        if (listener != null) {
            if (changed) listener.onHidLog("glasses link: " + s);
            listener.onHidStateChanged();
        }
    }
}
