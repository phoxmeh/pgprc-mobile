# PGPRC Mobile

A Kotlin/Jetpack Compose Android app for **remote packet radio access** — the mobile counterpart to
[packet-radio](https://github.com/dvano/packet-radio) (the Rust/GTK4 desktop client, "PGPRC").

Connects to a wide range of modems and TNCs:

- **AGWPE** — network socket to Direwolf, UZ7HO SoundModem, or similar host software
- **KISS (TCP)** — raw KISS framing over a TCP socket
- **Bluetooth KISS** — SPP/RFCOMM to Mobilinkd, TNC3, and similar classic Bluetooth TNCs
- **BLE KISS** — Nordic UART Service (NUS) GATT profile; targets [lora-kiss-tnc](https://github.com/dvano/lora-kiss-tnc) and compatible LoRa BLE TNCs
- **USB-serial KISS** — OTG-attached serial KISS TNCs
- **USB Audio/PTT** — AFSK soft-modem (Bell 202 1200 baud / HF 300 baud) for Digirig and similar USB sound card + RTS-PTT interfaces
- **Telnet** — raw terminal session to a BBS or node

Features include AX.25 connected-mode sessions, NET/ROM routing, heard-station log, address book (import/export, QRZ lookup), scheduled beacons, and a Monitor view with callsign-highlight filtering.

> **Status:** under active development. Core transports and UI are working; some rough edges remain.

## Building

Requires a JDK (21+) and the Android SDK (`compileSdk`/`targetSdk` 36, `minSdk` 26).

```sh
./gradlew assembleDebug
./gradlew installDebug   # sideload to a connected device or emulator
./gradlew test           # JVM unit tests for core-model/core-protocol/core-data
```

## Module layout

| Module | Purpose |
|---|---|
| `core-model` | Plain Kotlin data/sealed classes (port config, address book, beacons, etc.) and the `PortEvent`/`PortCommand`/`PortRunner` contract. No Android dependency — pure JVM. |
| `core-protocol` | AGWPE, KISS, AX.25, and NET/ROM codecs. Pure JVM, unit-testable without an emulator. |
| `core-modem` | AFSK soft-modem: Bell 202 / HF 300 modulator and demodulator, HDLC framing, NRZI encoding. |
| `core-transport` | `PortRunner` implementations: AGWPE, KISS-TCP, Bluetooth SPP KISS, BLE NUS KISS, USB-serial KISS, USB Audio/PTT, Telnet. |
| `core-data` | Room database, DataStore preferences, address book repository, NET/ROM node table, QRZ client. |
| `app` | Compose UI, ViewModels, navigation, and the foreground `Service` that keeps connections and beacons alive in the background. |

## License

MIT — see [LICENSE](LICENSE).
