package com.r08.ringbridge;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Receives key events before apps do. Only events from the ring (device name contains the
 * filter, default "R08") are used and swallowed; everything else passes through untouched.
 * Being a long-lived service, it also keeps the HID bridge running in the background.
 */
public class RingAccessibilityService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        BridgeCore core = BridgeCore.get(this);
        core.accessibilityConnected = true;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            core.startHid();
        }
        core.onHidStateChanged();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        return BridgeCore.get(this).handleKey(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        BridgeCore core = BridgeCore.get(this);
        core.accessibilityConnected = false;
        core.resetRecognizer();
        core.onHidStateChanged();
        super.onDestroy();
    }
}
