package com.r08.ringbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String[] GESTURE_LABELS = {
            "Slide up", "Slide down", "1 tap", "2 taps", "3 taps", "4 taps",
            "Tap, then slide up", "Tap, then slide down", "Slide up, then tap", "Slide down, then tap"
    };

    private BridgeCore core;
    private TextView status;
    private TextView eventLog;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        core = BridgeCore.get(this);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(13);
        root.addView(status);

        header("Setup");
        button("1. Grant Bluetooth permissions", new View.OnClickListener() {
            @Override public void onClick(View v) { requestBluetooth(); }
        });
        button("2. Enable the ring service (Accessibility)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        button("3. Start / restart the bridge", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hasBluetooth()) {
                    core.hid.stop();
                    core.startHid();
                    BridgeService.start(MainActivity.this);
                } else requestBluetooth();
            }
        });

        header("Ring touch mode");
        TextView ringHelp = new TextView(this);
        ringHelp.setText("The bridge needs the ring in Music mode. After using the ring in another mode "
                + "(e.g. E-book on an e-reader), turn the e-reader's Bluetooth off and tap the button.");
        root.addView(ringHelp);
        LinearLayout ringRow = new LinearLayout(this);
        ringRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(ringRow);
        Button music = new Button(this);
        music.setText("Ring \u2192 Music mode");
        music.setAllCaps(false);
        music.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!hasBluetooth()) { requestBluetooth(); return; }
                core.setRingMusicMode();
            }
        });
        ringRow.addView(music, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        Button read = new Button(this);
        read.setText("Read ring mode");
        read.setAllCaps(false);
        read.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!hasBluetooth()) { requestBluetooth(); return; }
                core.readRingMode();
            }
        });
        ringRow.addView(read, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch screenOff = new Switch(this);
        screenOff.setText("Work while the phone is locked: the ring always controls the glasses. "
                + "While the screen is off the bridge keeps all media buttons, so the glasses' own music "
                + "controls (next/pause) don't work until you unlock the phone.");
        screenOff.setChecked(core.screenOffMode());
        screenOff.setPadding(0, dp(8), 0, dp(8));
        screenOff.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { core.setScreenOffMode(on); }
        });
        root.addView(screenOff);
        button("4. Battery: set R08 Ring Bridge to \"Unrestricted\" / not optimized", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        header("Test: send to the glasses");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        testButton(row, "Up", Action.UP);
        testButton(row, "Down", Action.DOWN);
        testButton(row, "Left", Action.LEFT);
        testButton(row, "Right", Action.RIGHT);
        LinearLayout padRow = new LinearLayout(this);
        padRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(padRow);
        testButton(padRow, "Single", Action.SINGLE);
        testButton(padRow, "Back", Action.BACK);
        testButton(padRow, "Next", Action.MEDIA_NEXT);
        testButton(padRow, "Prev", Action.MEDIA_PREV);
        LinearLayout padRow2 = new LinearLayout(this);
        padRow2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(padRow2);
        testButton(padRow2, "Play", Action.MEDIA_PLAY_PAUSE);
        testButton(padRow2, "Mute", Action.MEDIA_MUTE);
        testButton(padRow2, "Vol+", Action.MEDIA_VOL_UP);
        testButton(padRow2, "Vol-", Action.MEDIA_VOL_DOWN);

        header("Gesture -> glasses action");
        for (int i = 0; i < GestureRecognizer.GESTURES.length; i++) {
            mappingRow(GESTURE_LABELS[i], GestureRecognizer.GESTURES[i]);
        }

        header("Timing and ring");
        numberSetting("Tap window (ms) - max gap between taps of a multi-tap", "window_ms", 350);
        numberSetting("Combo buffer (ms) - how long a tap waits for a slide (tap then slide), and a slide for a tap", "combo_ms", 500);
        textSetting("Ring device name contains", "ring_filter", "R08");

        header("Event log (newest first)");
        TextView logHelp = new TextView(this);
        logHelp.setText("Lock the phone, use the ring, unlock and read this: it shows whether ring keys "
                + "arrived, which gesture they became and whether they were sent to the glasses.");
        root.addView(logHelp);
        eventLog = new TextView(this);
        eventLog.setTypeface(Typeface.MONOSPACE);
        eventLog.setTextSize(11);
        eventLog.setTextIsSelectable(true);
        root.addView(eventLog);

        TextView help = new TextView(this);
        help.setPadding(0, dp(12), 0, 0);
        help.setText("Ring: Music touch mode, paired with this phone in Android Bluetooth settings.\n"
                + "Glasses: unbond the ring, then pair THIS phone in MemoMind > Memo Lab > Third-party devices, "
                + "and import r08_bridge_inputmap.json there so Double and Long work.");
        root.addView(help);

        core.setUiListener(new Runnable() {
            @Override public void run() { refresh(); }
        });
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        core.setUiListener(null);
        super.onDestroy();
    }

    private void refresh() {
        status.setText("Bluetooth permission: " + (hasBluetooth() ? "yes" : "NO")
                + "\nRing service (Accessibility): " + (core.accessibilityConnected ? "on" : "OFF")
                + "\nBackground service: " + (core.serviceRunning ? "running" : "not running")
                + "\nLocked-phone mode: " + core.lockedModeState()
                + "\nRing touch mode: " + core.ringModeText()
                + "\nGlasses link: " + core.hid.state
                + "\nReady to send: " + (core.hid.isReady() ? "yes" : "no")
                + "\nLast ring key: " + core.lastKey
                + "\nLast gesture: " + core.lastGesture


                + "\nLast gap tap->slide: " + gap(core.lastTapToSlideMs())
                + "   slide->tap: " + gap(core.lastSlideToTapMs()));
        if (eventLog != null) eventLog.setText(core.eventLog());
    }

    private static String gap(long ms) {
        return ms < 0 ? "-" : ms + " ms";
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasBluetooth() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetooth() {
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (hasBluetooth()) {
            core.startHid();
            BridgeService.start(this);
        }
        refresh();
    }

    // ------------------------------------------------------------------ UI builders

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
    }

    private void button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void testButton(LinearLayout row, String text, final Action action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!core.hid.isReady()) {
                    Toast.makeText(MainActivity.this, "Glasses not connected/subscribed yet", Toast.LENGTH_SHORT).show();
                }
                core.perform(action);
            }
        });
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void mappingRow(String label, final String gesture) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(this);
        t.setText(label);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Action.labels());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        s.setSelection(core.actionFor(gesture).ordinal(), false);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                core.setAction(gesture, Action.values()[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        root.addView(row);
    }

    private void numberSetting(String label, final String key, int def) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(core.prefs.getInt(key, def)));
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) return;
                try {
                    int value = Integer.parseInt(e.getText().toString().trim());
                    core.prefs.edit().putInt(key, Math.max(50, Math.min(10000, value))).apply();
                } catch (NumberFormatException ignored) {
                }
            }
        });
        root.addView(e);
    }

    private void textSetting(String label, final String key, String def) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(core.prefs.getString(key, def));
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) core.prefs.edit().putString(key, e.getText().toString().trim()).apply();
            }
        });
        root.addView(e);
    }
}
