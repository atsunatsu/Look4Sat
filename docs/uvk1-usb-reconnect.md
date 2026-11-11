# UV-K1 USB-C CDC / Android auto-reconnect

The Android app can open USB CDC only after the operating system enumerates it. A charging LED is not evidence of enumeration.

## Implemented

- Process-scope USB attach/detach notifications in `MainApplication` and raw VID/PID diagnostics (`usb/hotplug`).
- On attach, match only the configured, enabled UV-K1 / UV-K5 V3 USB CAT radio by VID/PID (device ID may change after re-enumeration).
- Require a single matching device, Android USB permission, no active tracking and PTT OFF before reconnecting.
- Reuse the existing native CDC driver and L4S DTR + HELLO/OPEN handshake.
- Never enable tracking, PTT or FT4 transmission automatically after reconnect.

## Test matrix

| Scenario | Expected |
| --- | --- |
| Direct C-to-C, Android has no USB device | No attempted CAT connection; diagnose hardware |
| Hub enumerates 36B7:FFFF and permission granted | Existing manual connection works |
| Unplug and reconnect same selected device, USB permission available | `usb/hotplug`, `usb/reconnect` events followed by L4S handshake |
| Unplug, new permission required | No silent connection; grant permission in Settings |
| Unrelated USB storage or serial adapter | No automatic radio reconnect |
| App is transmitting or actively tracking | No new automatic connection attempt |

Note: Android permission may be per physical attachment. A physically unplugged device may need the user's explicit USB permission again. `ACTION_USB_DEVICE_ATTACHED` is an Android system event and cannot be synthesized by a missing/undetected physical USB device.

Run `./gradlew :core:data:testDebugUnitTest :app:compileDebugKotlin` before publishing a test APK.
