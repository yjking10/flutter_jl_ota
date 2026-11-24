# WaveNoter local changes

Based on [flutter_jl_ota 1.0.4](https://pub.dev/packages/flutter_jl_ota/versions/1.0.4),
with the upstream license and native SDK artifacts preserved. WaveNoter uses
this fork through a Git dependency pinned to a commit in its `pubspec.yaml`.

The `ios/` and `lib/` directories are unchanged from the published package. The
Android fixes align BLE permission checks with the host app's `neverForLocation`
configuration, report scan failures, and bound the wait for an OTA connection.
Callbacks are registered before scanning; cancelled, failed, or completed
requests release their manager so a later attempt gets fresh state.

Device authentication remains enabled. BLE connection events only start the
SDK handshake; OTA starts after the SDK's `onConnection(CONNECTION_OK)` event,
with authentication confirmed and cached device information available. SDK
readiness is dispatched after initialization returns, and repeated BLE states
are ignored so they cannot restart authentication. SDK initialization errors
are reported while preparing, and the startup deadline remains active until the
SDK is ready. There is no extra device query after `startOTA()`.

Android 12 and later require scan and connect permissions, without an additional
location check. Android 10/11 require fine location; Android 6–9 accept coarse or
fine location. The host remains responsible for runtime permission requests.
See [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

Run native regression tests from the host application's `android/` directory:

```sh
./gradlew :flutter_jl_ota:testDebugUnitTest
```

Robolectric is a test-only dependency. These tests exercise permission checks,
startup lifecycle, and SDK readiness without writing firmware to a physical
device. The readiness tests run the real Java SDK and adapter while replacing
the JNI handshake and BLE hardware. A hardware test is still needed for the
complete authentication exchange, firmware transfer, and reboot/reconnect.
