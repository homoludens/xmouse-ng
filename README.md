# XMouse-NG

Use an Android phone or tablet to control a Linux desktop over SSH.

XMouse-NG **2.1.0 (26)** keeps the original `com.stripe1.xmouse` application identity and Android 8/API 26 minimum. The native interface has three tabs: **Touchpad**, **Actions**, and **Scripts**. The shared inline text composer and explicit Send are available on Touchpad and Actions, and hidden (without losing the in-memory draft) on Scripts. Actions uses a ten-column title-only grid that retains saved order, widths, and colors. The computer header opens Computers; the navigation drawer contains computer management and settings. Actions can also show read-only Common, Navigate, Media, and F1–F12 presets without changing saved layouts. See [release and upgrade guidance](docs/releasing-xmouse-ng.md) for signing compatibility, data preservation, and validation limits.

## Features

- Control the desktop with the on-screen keyboard and mouse; send volume-key shortcuts and other special keys.
- Create custom keyboard buttons and run saved scripts or custom SSH commands.
- On Touchpad, tap the clipboard control in the five-button quick-key row to send plain text from Android's clipboard to the connected Linux desktop clipboard.
- Free with no ads.

## Type text

Use the shared **Type on computer** composer from **Touchpad** or **Actions**. It accepts multiline text and paste; tap the adjacent **Send** button or the keyboard's **Send** action to send nonblank text while connected. Unsent text is shared in memory across tabs, including while hidden on **Scripts**, and is not saved as a draft.

## Requirements

- Android 8.0 (API 26) or later. Android 7 (API 24–25) and earlier remain on the legacy release. This minimum is not a claim that every supported Android version or device has been tested.
- A Linux host reachable by SSH, with an OpenSSH server and the existing `xdotool` (X11) or `ydotool` (Wayland) command setup. XMouse-NG sends commands directly over SSH; it needs no XMouse-specific replacement daemon or cloud account.
- For clipboard sharing, the Linux user needs `wl-copy` on a usable Wayland desktop, or `xclip`/`xsel` on X11. XMouse-NG does not install clipboard tools; a usable desktop session/display must be available.

Clipboard sharing is a deliberate, one-way action: on **Touchpad**, tap **Send clipboard to computer** while connected to send the current plain-text clip. XMouse-NG does not monitor the clipboard in the background, send clipboard text as simulated typing, or replay it after a disconnect. Empty, non-text, and over-64-KiB clips are not sent.

## Saved scripts

Open the **Scripts** tab to review saved SSH scripts. Navigation to Scripts never runs a script; tap its explicit **Run** action to execute it. Use **Add script** or **Manage** to open the existing script manager, and use a row's **Edit** action to update a saved script. The shared text composer is hidden on this tab, but its unsent in-memory draft remains available when returning to Touchpad or Actions.

## Connect to a host

Open **Computers** from the computer header, then **Add computer** to enter a hostname or IP address, SSH port, username, and password or key credentials. Host discovery is optional; manually entered endpoints do not need an SSH service advertisement. On Android 17 (API 37), grant **Local network access** when prompted for LAN connections or discovery.

On first connection, XMouse-NG shows the SSH host-key fingerprint before sending credentials or commands. Compare the displayed SHA-256 fingerprint with one obtained from the computer owner through an independent, trusted channel, then accept only if it matches. Reject or cancel if you cannot verify it. A changed key blocks the connection; use the separate replacement review only after independently verifying the new fingerprint. Replacing trust does not reconnect—you must connect again.

## Private keys and restored credentials

For key authentication, open **hamburger → Authentication & SSH settings** and enable **Use an SSH Key**. For an encrypted key, set **Optional Key Passphrase** first, then tap **Private Key** and choose the file with Android's document picker. XMouse-NG keeps a protected app-private copy, so the source document can later be moved or removed.

An old external key path may no longer be readable, and a device-key loss or backup restore may leave host metadata without usable secrets. You may need to select the private key again or re-enter passwords/passphrases. Secret bytes are not included in XMouse-NG's metadata backup, and there is no plaintext credential backup. If XMouse-NG offers a one-time credential re-entry prompt, the value is used only for that connection and is not saved. To save credentials again, edit the computer in **Computers**, or use **hamburger → Authentication & SSH settings** for global key/passphrase controls, as applicable. A blank replacement password on an existing computer means unchanged.

## Keep a session while switching apps

Start a session from XMouse-NG while it is visible. With the device awake and the Android process, network, and SSH host still available, the foreground session can remain active while you switch apps; returning to XMouse-NG reattaches to it. Use the prominent **Disconnect** button in the app header, or **Open controls** and **Disconnect** in the session notification when available. On Android 13 (API 33) and later, notification permission controls whether those notification actions appear. If permission is denied, Android still discloses the foreground service in Task Manager, and the session remains controllable with the header **Disconnect** button.

Use **Disconnect** from the app header or notification when finished. Screen-off, extended background, process termination, and force-stop can interrupt a session; indefinite connectivity or automatic restart is not promised.

## Existing keyboard layouts

On upgrade, each valid legacy active layout and each of the three preset CSV files is migrated into its own named layout. The original CSV files remain unchanged as recovery sources. If a source is unreadable or malformed, XMouse-NG warns and retains it rather than silently replacing it with defaults.

Use the **Actions** layout picker to choose one of four read-only virtual presets—Common, Navigate, Media, or F1–F12—or a saved layout. Presets dispatch fixed supported key/xdotool commands and do not create or modify repository data. Profiles are identified independently of their names, so duplicate names are allowed; existing custom widths, colors, commands, and saved order remain unchanged. Select **Actions → Edit** to enter the advanced ten-column editor, where **Add action** creates a custom button; the Actions landing page does not expose action creation.

In the advanced editor, switch between **Visual** and **XML source**. XML is a versioned text representation of a saved layout profile—not Android view XML—and can edit the profile name plus ordered action IDs, titles, opaque commands, widths, and RGB/ARGB colors. Validate and Format do not write a draft; **Apply & return** validates the whole document and makes one recoverable draft edit. Review it in Visual, then use **Save changes** to persist the profile. For example:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<layout version="1" id="profile-id" name="My layout" columns="10">
  <button id="action-1" width="3" color="#80A1B2C3">
    <title>Close window</title>
    <command>xdotool key alt+F4</command>
  </button>
</layout>
```

The root ID must remain the ID being edited. XML 1.0 and only this version-1 layout schema are accepted; XML 1.1 declarations, DTDs and entity declarations/references beyond XML's built-in and numeric references, processing instructions, namespaces, XInclude, and unknown elements/attributes are rejected. XML comments inside the layout are preserved by Format but are annotations, not saved profile data. Text values are escaped for markup, while command content (including whitespace and line endings) is not trimmed or interpreted. Invalid XML remains in memory until explicitly reset/discarded; the validated profile and recoverable draft are not overwritten by typing, validation, or formatting. On process death, the validated recoverable draft can be reopened, but an unapplied in-memory XML buffer is not promised to survive.

## Optional: find computers with Avahi

**Computers → Find computers** discovers only hosts advertising the `_ssh._tcp` DNS-SD service. Running SSH alone does not guarantee that a host advertises this service. Discovery requires the phone and host to share a LAN where multicast DNS-SD is allowed; guest-network isolation, VPNs, or blocked multicast can prevent results. Manual hostname/IP entry remains available.

On Debian or Ubuntu, install and start Avahi if you want to advertise SSH:

```sh
sudo apt install avahi-daemon
sudo systemctl enable --now avahi-daemon
```

As root, create `/etc/avahi/services/ssh.service` with the SSH listening port (22 below):

```xml
<?xml version="1.0" standalone="no"?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">%h</name>
  <service>
    <type>_ssh._tcp</type>
    <port>22</port>
  </service>
</service-group>
```

Change `22` if SSH listens on another port. Save the file, then restart Avahi:

```sh
sudo systemctl restart avahi-daemon
```

This advertisement helps discovery only; it does not provide credentials or replace host-key verification.

## Upgrades and rollback

Back up app metadata/layout recovery files before upgrading and retain credentials separately; metadata backup does not contain usable secrets. Install in place only over an installation signed with the same compatible release key. A differently signed original/debug build cannot be updated in place with this APK; do not uninstall or clear data just to bypass that rejection. The modern release migrates app data forward. Do not treat installing a legacy APK over migrated data as a safe rollback: no reverse database/data migration is provided. See the [release guide](docs/releasing-xmouse-ng.md) before proceeding.

## Support

Like this project? Donate to me via BitCoin using this address: 17DaqbcEEG3Hn5jBv3sRjPTUUCW1eBp1Wg
