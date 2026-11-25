# Changelog: R08 Ring Bridge

All APK releases of the bridge app, **newest first**. Each entry holds the commit message and the
result of testing it on the real ring, phone and glasses.

Releases that change the HID layout (the "Report Map" the glasses read) need the phone to be
**re-paired** in the MemoMind app. The others install as a plain update (from release 5 on, when
the fixed signing key was added).

---

## Release 13: New default gestures

```
Use the working gesture configuration as defaults

- Defaults now match the configuration in daily use:
  slide up/down -> Up/Down, 1 tap -> nothing, 2 taps -> Enter (single
  press), 3 taps -> Escape (back), 4 taps -> Pause/Resume,
  tap then slide up/down -> Left/Right, slide up then tap -> Next track,
  slide down then tap -> Previous track
- Combo buffer default lowered from 900 ms to 500 ms (a measured
  tap->slide gap was 167 ms)
- README and replication guide updated

Defaults only apply to gestures without a saved setting: existing
installs keep their current choices. No HID layout change.
```

**Result:** not tested yet (defaults only).

---

## Release 12: Ring → Music mode button

```
Add "Ring -> Music mode" and "Read ring mode" buttons

After using the ring in another touch mode (e.g. E-book on the Boox),
it had to be switched back to Music mode in QRing. The bridge can now
do it itself over the ring's QRing command channel.

- New RingConfigurator:
  - finds the ring among the phone's paired devices (name filter)
  - connects to the ring's command service (6E40FFF0) and enables
    notifications on 6E400003
  - writes 3B 02 00 01 05 (Music, 5 min sleep), 3B 02 01 00 00
    (gesture off), 3B 02 02 01 01 (wake), then reads 3B 01 00
  - handles the ring dropping the link to apply a new mode: reconnects
    (up to 4 attempts), continues without resending the mode packet,
    and reports success only when the read-back shows mode 1
  - 40 s overall timeout with a hint when the e-reader still holds
    the ring
- UI: new "Ring touch mode" section with both buttons and a status line
- Event log: each step logged as "ring: ..."
- README and replication guide updated

No HID layout change: installs as an update, no re-pairing needed.
```

**Result:** not tested on hardware yet. Packet bytes verified identical to the known-good ones.

---

## Release 11: Ring keeps control of the glasses while the phone is locked

```
Ring keeps control of the glasses while the phone is locked

With the screen off, Android sends media keys to the app that most
recently started audio, not to the Accessibility service. The bridge
claimed the media buttons only once at screen-off, so after the next
song started the music app took them back and ring slides skipped
tracks instead of controlling the glasses.

- MediaKeyCatcher rewritten:
  - claim the media buttons at screen-off (MediaSession + 250 ms silence)
  - re-claim on every AudioPlaybackCallback change while the screen is
    off (ignore our own silence for 700 ms, at most one claim per 3 s)
  - swallow all media keys while locked: ring keys go to the recognizer,
    other keys (headphones, the glasses' own music controls) are ignored
    and logged
  - release on screen-on (session inactive, receiver cleared), so the
    glasses' music controls work normally when the phone is unlocked
- BridgeCore: route screen on/off broadcasts to the catcher
- UI: status shows "off / waiting for screen off / holding media
  buttons"; the switch text explains the locked-phone trade-off
- README and replication guide updated (failure, fix, options A/B/C)

No HID layout change: installs as an update, no re-pairing needed.
```

**Result:** the app works fundamentally OK with this release.

---

## Release 10: Media keys inside the single keyboard report

```
Replace unrecognized extra keys with media keys in the same report

The MemoMind app can only map keys the glasses firmware recognizes.
Keyboard Home/PgUp/PgDn/Menu/Application/Mute/Volume are not among
them. Consumer media keys are (seen from the ring itself).

- Report map: byte 0 unchanged (Up, Down, Left, Right, Enter, Escape,
  Keypad Enter, Space); byte 1 now holds Consumer-page Next 0xB5,
  Previous 0xB6, Play/Pause 0xCD, Mute 0xE2, Volume Up 0xE9,
  Volume Down 0xEA + 2 padding bits, still one report in one Keyboard
  collection
- Actions trimmed to keys the glasses recognize: Up, Down, Left, Right,
  Single press, Back, Next/Previous track, Pause/Resume, Mute,
  Volume up/down (Keypad Enter, Space and held actions removed)
- Defaults: 1 tap = Single press, 2 taps = Next track, 3 taps = Back,
  4 taps = Pause/Resume
- Test buttons for all 12 keys; long-press hold setting removed

HID layout change: re-pair the phone in the MemoMind app.
```

**Result:** connected; keyboard keys kept working; **the media keys did nothing**. The firmware only
recognizes media keys from a Consumer collection, not inside a Keyboard collection.

---

## Release 9: Back to one HID report, keyboard extra keys

```
Back to one HID report; extra keys Home/PgUp/PgDn/Menu/App/Mute/Vol

With a second report the glasses connected but received no keys, so
the bridge returns to a single keyboard report (the structure of the
working builds).

- Report map: one report (ID 1), 2 bytes; byte 0 identical to the
  first working version; byte 1 = keyboard Home 0x4A, Page Up 0x4B,
  Page Down 0x4E, Menu 0x76, Application 0x65, Mute 0x7F,
  Volume Up 0x80, Volume Down 0x81
- Consumer report and its characteristic removed
- New actions and test buttons for the eight extra keys

HID layout change: re-pair the phone in the MemoMind app.
```

**Result:** connected; Up, Down, Left, Right, single press and Back worked; **none of the eight extra
keys did anything** (not recognized by the firmware).

---

## Release 8: Extra keys as a second (Consumer) report

```
Extra keys as media keys the glasses recognize; keyboard report unchanged

Keyboard letters A-H are not recognized by the glasses firmware, so the
MemoMind app can't record them.

- Keyboard report (ID 1) restored to the exact original 1-byte layout
- New Consumer Control report (ID 2) with Next, Previous, Play/Pause,
  Mute, Volume up/down, AC Home, Menu, in its own collection like the
  ring's own report
- Actions "Media: ..." and test buttons for them; Button A-H removed
- Status shows whether the media-key report is subscribed

HID layout change: re-pair the phone in the MemoMind app.
```

**Result:** connected, but **no keys reached the glasses at all** (even the Up test button did
nothing), and re-pairing then failed. Second HID reports are not worth it.

---

## Release 7: Back to the single keyboard report + Button A-H

```
Back to the single keyboard report + Button A-H

The gamepad report is removed completely. Extra mappable keys are added
to the existing keyboard report instead.

- Report map: one report (ID 1), 2 bytes; byte 0 unchanged; byte 1 =
  keyboard keys A-H (0x04-0x0B)
- Actions "Button A" ... "Button H" for any gesture; A/B/C/D test buttons
- Gamepad switch and gamepad code removed

HID layout change: re-pair the phone in the MemoMind app.
```

**Result:** connected; Up, Down, Left, Right, single press and Back worked; **Button A-H were not
recognized by the MemoMind app's recorder** (the firmware doesn't know letters).

---

## Release 6: Gamepad report opt-in, pairing diagnostics

```
Gamepad report opt-in (default = original layout), log link and pairing events

After the gamepad report was added, the glasses found the phone but
could not connect.

- Default HID layout is byte-for-byte the first working version
  (keyboard only); the gamepad report is behind a "Gamepad buttons"
  switch (off by default, needs re-pairing when changed)
- Event log: "link UP/DOWN <addr> (status N)" for every glasses
  connection change, "bond <addr>: PAIRED / PAIRING FAILED" for
  pairing results
- Gamepad actions report "NOT sent: gamepad report is switched off"

HID layout change back to the original: re-pair the phone in the
MemoMind app.
```

**Result:** superseded before full testing (the gamepad approach was dropped).

---

## Release 5: Fixed signing key, foreground service, event log

```
Fixed signing key, foreground service, media button receiver, event log

- Fixed debug keystore committed (app/ringbridge-debug.keystore) and
  used for every build; versionCode = GitHub run number, so new APKs
  install as updates instead of requiring an uninstall
- BridgeService: foreground service (connectedDevice | mediaPlayback)
  with an ongoing notification, so the bridge isn't frozen while the
  phone is locked
- MediaButtonReceiver registered on the media session as a fallback
  route for media buttons
- Event log (last 40 entries): ring keys with path, gestures, sent /
  NOT sent, screen on/off, media-button claims
- Notification permission requested; "Start / restart the bridge"
  also starts the service
- README: updating, locked-phone checks, troubleshooting

One last uninstall needed to switch to the fixed key.
```

**Result:** after the clean uninstall/reinstall the glasses found the phone but couldn't connect
(leftover pairing keys + the gamepad report from release 4).

---

## Release 4: Gamepad buttons, combo buffer

```
Gamepad buttons, combo buffer for tap+slide, gap display

- Second HID report (ID 2): 12-button gamepad + 4 padding bits, with
  its own Report characteristic and subscription
- Actions "Gamepad A ... R3" for any gesture; Pad A/B/X/Y test buttons
- GestureRecognizer: separate combo buffer (default 900 ms) for
  "tap, then slide" and "slide, then tap". The ring only reports a slide
  after recognizing the swipe, so the 350 ms tap window was too short
- Status shows the last measured tap->slide and slide->tap gaps
- README: gamepad, combo buffer, tuning

HID layout change: re-pair the phone in the MemoMind app.
```

**Result:** led to the connection failures of releases 5-6. Gamepad report later removed.

---

## Release 3: Work while the phone is locked (first version)

```
Work while the phone is locked (media session path) + README

With the screen off, Android delivers media keys to the media-button
session instead of the Accessibility service, so the ring stopped
working when the phone was locked.

- MediaKeyCatcher: media session that receives media buttons, passes
  ring keys (device-name filter) to the recognizer, and plays 250 ms of
  silence at screen-off so Android routes media buttons to the bridge
- Partial wake lock after each ring event, so the tap window and held
  keys aren't delayed by sleep
- "Work while the phone is locked" switch (on by default), battery
  optimization button, locked-mode status line
- "Last ring key" shows (screen on) / (screen off)
```

**Result:** not enough on its own: the ring still stopped working while locked (fixed further in
releases 5 and 11).

---

## Release 2: README setup flow

```
README: real setup flow, map actions by recording in the MemoMind app

- Setup now ends with mapping actions in the MemoMind app by recording
  them with the ring (the app has no JSON import)
- Example mapping: double tap -> double press (enter menu),
  triple tap -> long press (back / exit)
- "Restricted setting" tip for enabling Accessibility on sideloaded apps
- Troubleshooting section; JSON input map moved to "Advanced"
```

**Result:** documentation only (no app change).

---

## Release 1: Initial bridge

```
R08 Ring Bridge

Android bridge between the R08 ring and MemoMind One glasses: the ring
(Music touch mode) pairs with the phone, the app counts taps, and the
phone acts as a BLE HID keyboard for the glasses.

- HidPeripheral: GATT server (HID 1812, Device Info, Battery) with one
  keyboard report (ID 1, 1 byte, 8 one-bit keys: Up, Down, Left, Right,
  Enter, Escape, Keypad Enter, Space); encrypted HID characteristics;
  advertises 0x1812 + device name (falls back to the scan response);
  remembers subscribed hosts across reconnects
- RingAccessibilityService: catches key events from the ring only
  (input device name contains "R08") and swallows them
- GestureRecognizer: slide up/down, 1-4 taps, tap+slide, slide+tap;
  only waits when a longer gesture is mapped
- Mapping UI per gesture, tap window, long-press hold, ring name filter,
  Up/Down/Double/Long test buttons
- GitHub Actions workflow builds the debug APK
```

**Result:** **known-good baseline.** Up, Down, Left, Right, single press (Enter) and Back (Escape)
work on the glasses through their built-in keyboard mapping.
