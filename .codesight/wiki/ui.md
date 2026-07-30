# UI

> **Navigation aid.** Component inventory and prop signatures extracted via AST. Read the source files before adding props or modifying component logic.

**22 components** (jetpack-compose)

## Client Components

- **BluetoothDevicePicker** — props: selectedAddress, selectedName, address, name — `app/src/main/kotlin/net/packetradio/mobile/ui/ports/BluetoothDevicePicker.kt`
- **PortFormDialog** — props: initial, onDismiss — `app/src/main/kotlin/net/packetradio/mobile/ui/ports/PortFormDialog.kt`
- **KindDropdown** — props: selected, onSelected — `app/src/main/kotlin/net/packetradio/mobile/ui/ports/PortFormDialog.kt`
- **PortsDrawerContent** — props: ports, portStatuses, onTogglePort — `app/src/main/kotlin/net/packetradio/mobile/ui/ports/PortsDrawerContent.kt`
- **PortToggleRow** — props: index, entry, status, isFirst, isLast, onToggle — `app/src/main/kotlin/net/packetradio/mobile/ui/ports/PortsDrawerContent.kt`
- **AdHocUnprotoBar** — props: ports, state, onPortSelected — `app/src/main/kotlin/net/packetradio/mobile/ui/session/AdHocUnprotoBar.kt`
- **AdHocPortDropdown** — props: ports, selected, onPortSelected — `app/src/main/kotlin/net/packetradio/mobile/ui/session/AdHocUnprotoBar.kt`
- **DialDialog** — props: ports, onDismiss — `app/src/main/kotlin/net/packetradio/mobile/ui/session/DialDialog.kt`
- **PortDropdown** — props: ports, selectedId, onSelected — `app/src/main/kotlin/net/packetradio/mobile/ui/session/DialDialog.kt`
- **ClearFocusWhenKeyboardHides** — `app/src/main/kotlin/net/packetradio/mobile/ui/session/Focus.kt`
- **MonitorContent** — props: lines, filter, onFilterChanged — `app/src/main/kotlin/net/packetradio/mobile/ui/session/MonitorContent.kt`
- **SessionScreen** — props: onOpenSettings — `app/src/main/kotlin/net/packetradio/mobile/ui/session/SessionScreen.kt`
- **Scrim** — props: onClick — `app/src/main/kotlin/net/packetradio/mobile/ui/session/SessionScreen.kt`
- **StatusBar** — props: tab — `app/src/main/kotlin/net/packetradio/mobile/ui/session/SessionScreen.kt`
- **SessionTabContent** — props: tab, monitorLines, portConnected, myCall, highlightPrefs, onToggleNodeConnection — `app/src/main/kotlin/net/packetradio/mobile/ui/session/SessionTabContent.kt`
- **MiniMonitor** — props: monitorLines, height, myCall, highlightPrefs, mutedColor, errorColor — `app/src/main/kotlin/net/packetradio/mobile/ui/session/SessionTabContent.kt`
- **MonitorResizeHandle** — props: onDrag — `app/src/main/kotlin/net/packetradio/mobile/ui/session/SessionTabContent.kt`
- **TabsDrawerContent** — props: tabs, ports, frontId, monitorTabId, logTabId, onSelectTab — `app/src/main/kotlin/net/packetradio/mobile/ui/session/TabsDrawerContent.kt`
- **DrawerRow** — props: label, selected, onClick — `app/src/main/kotlin/net/packetradio/mobile/ui/session/TabsDrawerContent.kt`
- **TabDrawerRow** — props: tab, label, selected, onClick — `app/src/main/kotlin/net/packetradio/mobile/ui/session/TabsDrawerContent.kt`
- **SettingsScreen** — props: onBack — `app/src/main/kotlin/net/packetradio/mobile/ui/settings/SettingsScreen.kt`
- **PgprcMobileTheme** — props: darkTheme — `app/src/main/kotlin/net/packetradio/mobile/ui/theme/Theme.kt`

---
_Back to [overview.md](./overview.md)_