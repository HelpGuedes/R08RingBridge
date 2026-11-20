# R08 Ring Bridge (Android)

Phone bridge between a YaWell/Colmi **R08** smart ring and **MemoMind One** glasses.

```
R08 ring --(Bluetooth HID, Music mode)--> Android phone --(BLE HID keyboard / HOGP)--> MemoMind One
                                          Accessibility service + tap recognizer
```

The glasses' input map is stateless (one input, one key), so it cannot count taps. This app
counts them on the phone and sends the glasses the key the gesture should produce.

## Default gestures

| Ring | Glasses |
|---|---|
| Slide up / down | Up / Down |
| 1 tap | Double press (DOUBLE, 58) |
| 2 taps | Single press |
| 3 taps | Back |
| 4 taps | **Long press**: SELECT held 2100 ms (adjustable; or "Hold DOUBLE") |
| Tap+slide, slide+tap | Nothing (assign in the app) |

Every gesture can be reassigned in the app. Unassigned multi-taps add no delay.

## What the phone sends

One HID keyboard report, 8 one-bit keys:

| Bit | HID key (page 7) | Glasses | Needs input map? |
|---|---|---|---|
| 0-3 | Up 0x52, Down 0x51, Left 0x50, Right 0x4F | Up/Down/Left/Right | no (built-in) |
| 4 | Enter 0x28 | Single press | no (built-in) |
| 5 | Escape 0x29 | Back | no (built-in) |
| 6 | Keypad Enter 0x58 | DOUBLE (58) | **yes** |
| 7 | Space 0x2C | SELECT (4), held = long press | **yes** |

`r08_bridge_inputmap.json` adds the last two. Import it in the MemoMind app (Third-party devices)
or send it with `mm_hogp.py ... map r08_bridge_inputmap.json`.

## Build

- **GitHub:** push this folder to a repository. The included workflow builds the APK
  (Actions tab, artifact `R08-Ring-Bridge-debug-APK`).
- **Android Studio:** open the folder, Run. No libraries; Android 12+ (minSdk 31).

## Setup

1. **Ring:** Music touch mode (QRing "Music", or `ring_sniff2.py <ring> --mode 1`).
   Pair it with the phone: Android Settings > Bluetooth > Pair new device > `R08_xxxx`.
2. **Glasses:** remove the ring as their accessory (`mm_hogp.py <glasses> --channel 1 unbond`);
   they accept one bound accessory.
3. **App:** Grant Bluetooth permissions, enable the ring service under Accessibility, start
   HID advertising.
4. **Pair the phone to the glasses:** MemoMind app > Memo Lab > Third-party devices > Scan >
   pick your phone's name. Accept the pairing prompt on the phone. The app shows
   `READY: glasses subscribed`.
5. Import `r08_bridge_inputmap.json` (step above). Use the app's Up/Down/Double/Long test buttons.

## Known unknowns

- Whether the glasses accept the **same phone** as their companion (MemoMind app) and their HID
  accessory at once. If not, a second Android phone works.
- The glasses' long-press threshold. Raise "Long-press hold" if 2100 ms is not enough.
- Long phone Bluetooth names may not fit the advertisement; the app retries with the name in the
  scan response and tells you if advertising fails.
