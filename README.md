# PGPRC Mobile

A Kotlin/Jetpack Compose Android app for **remote packet radio access**: it connects over the
network to a remote machine acting as the modem (AGWPE and KISS-TCP, e.g. a
[Direwolf](https://github.com/wb2osz/direwolf) instance), plus Bluetooth SPP for hardware KISS
TNCs (Mobilinkd/TNC3-style) and USB-serial KISS over OTG.

> **Status:** early scaffold, not yet functional. This is the mobile counterpart to
> [packet-radio](https://github.com/dvano/packet-radio) (the Rust/GTK4 desktop client, "PGPRC")
> — same feature set minus what doesn't apply to a phone (no local Direwolf process management,
> no `AF_AX25` kernel socket), plus Bluetooth/USB-serial KISS transports the desktop app never
> needed.

## Building

Requires a JDK (21+) and the Android SDK (`compileSdk`/`targetSdk` 36, `minSdk` 26).

```sh
./gradlew assembleDebug
./gradlew test    # JVM unit tests for core-model/core-protocol/core-data
```

If `gradlew`/the Gradle wrapper jar isn't present yet, generate it once a system `gradle` is
installed:

```sh
gradle wrapper --gradle-version <installed version>
```

## Module layout

Mirrors the desktop project's 4-crate workspace split:

| Module | Purpose |
|---|---|
| `core-model` | Plain Kotlin data/sealed classes (port config, address book, beacons, etc.) and the `PortEvent`/`PortCommand`/`PortRunner` contract. No Android dependency — pure JVM. |
| `core-protocol` | AGWPE, KISS, and minimal AX.25 UI-frame codecs. Pure JVM, unit-testable without an emulator. |
| `core-transport` | `PortRunner` implementations: AGWPE/KISS-TCP sockets, Bluetooth SPP KISS, USB-serial KISS, Telnet, SSH. |
| `core-data` | Room database + DataStore Preferences + per-node history file storage. |
| `app` | Compose UI, ViewModels, navigation, and the foreground `Service` that keeps connections/beacons alive in the background. |

## Test rig

Same as the desktop project: a local Direwolf instance with AGWPE on `127.0.0.1:8000` and
KISS-TCP on `127.0.0.1:8001` (dummy audio load), callsign `KD3BFP-9`. For an emulator/device
this needs to be reachable over the LAN rather than `127.0.0.1`.

## License

MIT — see [LICENSE](LICENSE).
