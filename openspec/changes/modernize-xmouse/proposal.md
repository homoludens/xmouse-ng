## Why

XMouse remains useful as a local, SSH-based Linux remote, but its Android 12-era build, activity-owned connection, and difficult layout editor make it fragile on current Android devices. Modernizing these foundations should preserve existing configuration while adding hostname/discovery support, reliable app switching, and an approachable grid editor.

## What Changes

- Update the Android build and dependencies for Android 17 / API 37, including edge-to-edge layouts, supported back navigation, local-network permissions, and document-based SSH-key selection.
- Replace obsolete SSH/crypto dependencies, require explicit server trust, and protect saved authentication secrets and backup contents.
- Accept IP addresses, ordinary hostnames, and `.local` names; add on-demand discovery of advertised `_ssh._tcp` services without removing manual entry.
- Move SSH-session ownership out of activities, fix the auto-connect toggle bug, and preserve an established session while the user switches apps. Screen-off persistence, force-stop survival, and indefinite background operation are not guarantees.
- Introduce a Kotlin grid editor with explicit edit mode, drag-and-drop ordering, width resizing, undo/redo, color selection, and named layouts. Keep the ten-column flow model and existing custom-command behavior.
- Preserve hosts, scripts, authentication choices, and legacy layout files through non-destructive, repeatable migrations.
- Adopt Kotlin incrementally at new boundaries; do not rewrite the touchpad, command backends, or every existing screen.
- **BREAKING:** raise the minimum Android version from API 14 to API 26 (Android 8). API 24 / Android 7 was the original selection; on 2026-10-20 the user explicitly approved API 26 after ECDSA failed on API 24 and the unchanged dependency migration passed its focused API 26 crypto/import gate. This is a floor change, not an API 24 compatibility fix. Devices below API 26 retain the legacy release rather than receiving this update.

## Capabilities

### New Capabilities

- `modern-android-runtime`: Current Android interaction, permission, and file-selection behavior while retaining the remote's core controls.
- `secure-ssh`: Modern SSH authentication, explicit host trust, protected secrets, and safe backup behavior.
- `local-host-discovery`: Manual hostname/IP connections and on-demand discovery of advertised local SSH services.
- `connection-continuity`: Activity-independent session ownership, app-switch continuity, safe backgrounding, and recoverable connection failures.
- `layout-editor`: Safe grid editing, named layouts, and preservation of existing layout content and commands.

### Modified Capabilities

None. The project has no existing main capability specifications.

## Impact

- Build: root/app Gradle files, wrapper, AndroidX/Material, Kotlin/Compose, SSH/crypto libraries, and test tooling.
- Runtime: `MainActivity`, `MyConnectionHandler`, settings/host management, manifest, and resources; new session-service, discovery, persistence, and editor components.
- Data: the `contactsManager` SQLite database, preferences, imported SSH keys, and `keyLayoutFile.csv` plus the three `keyFile*.csv` presets. Database upgrades must no longer drop user tables.
- Host: existing OpenSSH plus xdotool/ydotool remain sufficient for manual connections; automatic discovery additionally requires an SSH DNS-SD advertisement, commonly supplied by Avahi.
- Verification: regression tests, device/emulator checks across the approved Android range, and LAN testing with a Linux host. Android tooling will use the user-provided SDK directory through environment variables, not committed machine-specific paths.

## Non-goals

- Whole-app Kotlin/Compose conversion, a replacement desktop daemon, cloud services, or a new transport protocol.
- Pixel-positioned dashboards, touchpad/mouse-button layout editing, button-height editing, macros, or a new action-builder interface.
- Always-on screen-off connections, automatic boot connections, battery-optimization exemptions, or keeping gyro input active in the background.
- App-store publication, signing-key changes, application-ID changes, or a new guarantee of desktop-backend feature parity.
