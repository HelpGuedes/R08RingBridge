# R08 Ring Bridge (Android)

Use a YaWell/Colmi **R08** smart ring as a controller for **MemoMind One** glasses, with
multi-tap gestures (double, triple, quadruple tap) that the glasses cannot recognize on their own.

```
R08 ring --(Bluetooth, Music mode)--> Android phone --(Bluetooth HID keyboard)--> MemoMind One
                                      R08 Ring Bridge app:
                                      reads the ring, counts taps, sends one key per gesture
```

The ring only sends single events (one tap, one slide). The glasses map one input to one
action and cannot count taps. This app counts them on the phone and sends the glasses a
different keyboard key for each gesture.

## How it fits together

There are two layers:

1. **R08 Ring Bridge app (phone): ring gesture → keyboard key**
2. **MemoMind app: keyboard key → glasses action.** You set this up by recording, using the ring.

### What each ring gesture sends (bridge defaults)

| Ring gesture | Key the bridge sends | Glasses without any mapping |
|---|---|---|
| Slide up / down | Up / Down arrow | Up / Down |
| 1 tap | Enter | Single click |
| 2 taps | Next track (media key) | Next track (re-map it in MemoMind, e.g. to Double click) |
| 3 taps | Escape | Back |
| 4 taps | Pause/Resume (media key) | Pause/Resume (re-map it, e.g. to Open AI chat) |
| Tap + slide, slide + tap | (unassigned) | - |

The bridge only offers keys the glasses firmware recognizes, so every one of them can be recorded in
the MemoMind app and given any "AI glasses action" (Single/Double click, Back, Open AI chat, Open
quick menu, Turn off screen, ...):

- keyboard: Up, Down, Left, Right, Enter (single press), Escape (back)
- media keys: Next track, Previous track, Pause/Resume, Mute, Volume up, Volume down

All of them live in **one** HID report (byte 0 = keyboard keys, byte 1 = media keys). A second HID
report broke the connection with the glasses in earlier versions, so don't add one.

### Example mapping (the setup this project was built with)

| Ring | Mapped in the MemoMind app to | Result on the glasses |
|---|---|---|
| Slide up / down | (built-in, no mapping needed) | Move up / down |
| **Double tap** | Double press | Enter / open menu |
| **Triple tap** | Long press | Back / exit |

## Setup

### 1. Install the app

Download the APK from this repository's **Actions** tab (latest successful run →
artifact `R08-Ring-Bridge-debug-APK` → `app-debug.apk`), copy it to the phone and install it
(allow "Install unknown apps"). Android 12 or newer.

**Updating:** builds are signed with the fixed key in `app/ringbridge-debug.keystore`, so a new
APK installs over the old one as an update and keeps your settings. (Builds from before this key
was added were signed with a random key: uninstall those once.)

### 2. Connect the ring to the phone

1. Put the ring in **Music** touch mode (QRing → Touch control → Music).
2. **Force-stop QRing** afterwards (or unbind the ring in QRing). Otherwise QRing reconnects to the
   ring and can change its mode. Turn off Bluetooth on other devices that use the ring (e.g. an
   e-reader).
3. Android **Settings → Bluetooth → Pair new device → `R08_xxxx`**.

### 3. Set up the bridge app

1. Open **R08 Ring Bridge** → **Grant Bluetooth permissions**.
2. **Enable the ring service:** Settings → Accessibility → R08 Ring Bridge → On.
   If Android says **"Restricted setting"**: Settings → Apps → R08 Ring Bridge → ⋮ (top right) →
   **Allow restricted settings**, then enable it again.
3. **Start / restart the bridge.** This starts the HID advertising and a background service with a
   small permanent notification ("Ring → glasses bridge running"), which keeps the bridge alive
   while the phone is locked. Allow notifications when asked.
4. Check the status at the top: slide the ring and **Last ring key** should show something like
   `KEYCODE_MEDIA_NEXT from R08_xxxx (screen on)`. If it stays `-`, set **Ring device name contains**
   to part of the name the ring has in Android's Bluetooth settings.
5. **Battery:** tap "Battery: set R08 Ring Bridge to Unrestricted" and set the app to
   *Unrestricted* / *Don't optimize*, so the phone does not pause it while locked.

### 4. Connect the phone to the glasses

1. If the ring was previously connected directly to the glasses, remove it first: the glasses keep
   only one third-party device.
2. **MemoMind app → Memo Lab → Third-party devices → Scan** → select **your phone's name**.
3. Accept the Bluetooth pairing prompt on the phone. The bridge app shows
   `READY: glasses subscribed`.
4. Choose a device type, then **Confirm and Continue**.

### 5. Map the actions you want, using the ring

In the MemoMind app's mapping screen, for each glasses action you want:

1. Start recording for that action.
2. Do the ring gesture you want for it (for example a **double tap** for double press, a
   **triple tap** for long press).
3. Save.

Slides already move up and down through the glasses' built-in keyboard support. They only need
mapping if you want them to do something else.

The bridge app's **Up / Down / Double / Long** test buttons send the same keys as the ring
gestures, which is handy for testing without the ring.

## Using it with the phone locked

With the screen off, Android sends media keys to the "media button" app (normally your music
player) instead of the Accessibility service. The **"Work while the phone is locked"** switch
(on by default) handles this: the bridge runs as a foreground service, keeps a media session and a
media button receiver, and when the screen turns off it plays a quarter second of silence so
Android routes media buttons to it. Only keys from the ring are used; they show up as
`(screen off)` or `(media receiver)` in the event log.

**Checking it:** lock the phone, use the ring, unlock and open the app. The **Event log** shows,
in order: `screen off`, `claimed media buttons`, then for each ring event `key ...`,
`gesture ... -> ...` and `sent key to glasses`. Where the chain stops tells you what failed:

| Last entry you see | Meaning |
|---|---|
| `screen off` but no `key ...` | The ring's keys did not reach the app while locked (another app took the media buttons, or the system dropped them) |
| `key ...` but no `gesture ...` | Only possible with combos assigned: the gesture is still waiting for the buffer |
| `NOT sent: glasses not subscribed` | The glasses' link to the phone dropped |
| `sent key to glasses` but nothing happens | The glasses received it; check the MemoMind mapping |

Side effect: while the screen is off, your headphones' play/pause/next buttons also go to the
bridge, which ignores them. If you start music in another app after the screen is already off,
that app takes the media buttons back until the screen turns off again. Turn the switch off if
you prefer the old behavior (ring works only with the screen on).

## Settings in the bridge app

| Setting | Default | What it does |
|---|---|---|
| Tap window | 350 ms | Maximum gap between taps of one multi-tap |
| Combo buffer | 900 ms | How long a tap waits for a slide ("tap, then slide") and a slide for a tap ("slide, then tap"). Only used when such a combo is assigned |
| Long-press hold | 2100 ms | How long held actions keep the key down |
| Ring device name contains | `R08` | Only input devices with this in their name are captured; everything else passes through |
| Work while the phone is locked | on | Takes media buttons while the screen is off (see above) |

## Troubleshooting

- **Taps or slides also control the phone's music:** the Accessibility service is off, or the ring's
  name does not match the filter.
- **Ring does nothing while the phone is locked:** check that the notification "Ring → glasses
  bridge running" is there, the "Work while the phone is locked" switch is on and the app's
  battery setting is *Unrestricted*. Then read the event log (see "Using it with the phone
  locked").
- **Ring stops responding after a while:** QRing (or another device) took over the ring. Force-stop
  QRing and reconnect the ring in Android's Bluetooth settings.
- **Glasses don't see the phone when scanning:** press "Start / restart HID advertising". If the
  status says advertising failed with error 1, shorten the phone's Bluetooth name.
- **A multi-tap comes out as separate taps:** tap faster, or raise the tap window.
- **"Tap, then slide" comes out as a tap and a separate slide:** the ring only reports a slide
  after it has recognized the swipe, so the slide arrives well after the tap. The status line
  **Last gap tap->slide** shows the measured gap: set the **Combo buffer** above your typical gap
  (e.g. gap 700 ms → buffer 900-1000 ms). Note that while a tap-then-slide combo is assigned,
  a single tap waits for the buffer before it fires.
- **Glasses find the phone but don't connect** (often after reinstalling or after an update that
  changed the bridge's keys): leftover pairing keys. In nRF Connect → BONDED, delete the bond of the glasses' BLE
  entry (the MemoMind One entry that is *not* the glasses' main address), remove the phone in
  MemoMind's third-party devices, then pair again with the bridge app open. The event log shows
  `link UP/DOWN (status ...)` and `bond ...: PAIRED / PAIRING FAILED` lines for each attempt.

## Advanced: input map without the MemoMind app

`r08_bridge_inputmap.json` maps Keypad Enter → DOUBLE (58) and Space → SELECT (4, so a held Space
becomes a long press). It can be sent from a PC with the GM/SPP tools from this project
(`mm_hogp.py <glasses> --channel 1 map r08_bridge_inputmap.json`), with the MemoMind app
force-stopped. The MemoMind app may replace it when it reconnects, so recording the mapping in the
MemoMind app (step 5) is the recommended way.

## Build from source

- **GitHub:** pushing to `main` runs `.github/workflows/build-apk.yml` and uploads the APK as an
  artifact.
- **Android Studio:** open the folder and Run. No external libraries.
