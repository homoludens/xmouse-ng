# XMouse

Use an Android phone or tablet to control a Linux desktop over SSH.

## Features

- Control the desktop with the on-screen keyboard and mouse; send volume-key shortcuts and other special keys.
- Create custom keyboard buttons and run saved scripts or custom SSH commands.
- Tap **Send clipboard to Linux** to send plain text from Android's clipboard to the connected Linux desktop clipboard.
- Free with no ads.

## Requirements

- Android 8.0 (API 26) or later. Android 7 (API 24–25) and earlier remain on the legacy release. This minimum is not a claim that every supported Android version or device has been tested.
- A Linux host reachable by SSH, with an OpenSSH server and the existing `xdotool` (X11) or `ydotool` (Wayland) command setup. XMouse sends commands directly over SSH; it needs no XMouse-specific replacement daemon or cloud account.
- For clipboard sharing, the Linux user needs `wl-copy` on a usable Wayland desktop, or `xclip`/`xsel` on X11. XMouse does not install clipboard tools; a usable desktop session/display must be available.

Clipboard sharing is a deliberate, one-way action: tap the toolbar button while connected to send the current plain-text clip. XMouse does not monitor the clipboard in the background, send clipboard text as simulated typing, or replay it after a disconnect. Empty, non-text, and over-64-KiB clips are not sent.

## Connect to a host

In **Manage Hosts**, enter a hostname or IP address, SSH port, username, and password or key credentials. Host discovery is optional; manually entered endpoints do not need an SSH service advertisement. On Android 17 (API 37), grant **Local network access** when prompted for LAN connections or discovery.

On first connection, XMouse shows the SSH host-key fingerprint before sending credentials or commands. Compare the displayed SHA-256 fingerprint with one obtained from the computer owner through an independent, trusted channel, then accept only if it matches. Reject or cancel if you cannot verify it. A changed key blocks the connection; use the separate replacement review only after independently verifying the new fingerprint. Replacing trust does not reconnect—you must connect again.

## Private keys and restored credentials

For key authentication, enable **Use an SSH Key** in **Settings**. For an encrypted key, set **Optional Key Passphrase** first, then tap **Private Key** and choose the file with Android's document picker. XMouse keeps a protected app-private copy, so the source document can later be moved or removed.

An old external key path may no longer be readable, and a device-key loss or backup restore may leave host metadata without usable secrets. You may need to select the private key again or re-enter passwords/passphrases. Secret bytes are not included in XMouse's metadata backup, and there is no plaintext credential backup. If XMouse offers a one-time credential re-entry prompt, the value is used only for that connection and is not saved. To save credentials again, update the password in **Manage Hosts**, set the passphrase in **Settings**, or re-import the key in **Settings**, as applicable.

## Keep a session while switching apps

Start a session from XMouse while it is visible. With the device awake and the Android process, network, and SSH host still available, the foreground session can remain active while you switch apps; returning to XMouse reattaches to it. The session notification provides **Open controls** and **Disconnect**. On Android 13 (API 33) and later, notification permission controls whether those actions appear in the notification drawer. If permission is denied, Android still discloses the foreground service in Task Manager, and **Session controls** in XMouse still provides an in-app Disconnect option.

Use **Disconnect** from the notification or **Session controls** when finished. Screen-off, extended background, process termination, and force-stop can interrupt a session; indefinite connectivity or automatic restart is not promised.

## Existing keyboard layouts

On upgrade, each valid legacy active layout and each of the three preset CSV files is migrated into its own named layout. The original CSV files remain unchanged as recovery sources. If a source is unreadable or malformed, XMouse warns and retains it rather than silently replacing it with defaults.

Open **Manage layouts** from the main menu to create and name layouts, rename or duplicate any profile, select/load it for normal controls, or delete it after confirmation. Profiles are identified independently of their names, so duplicate names are allowed; the manager lists all profiles, including the migrated active layout and all three presets, without a three-layout limit. At least one layout must remain. Choose **Edit Keyboard Layout** from the main menu to edit the currently selected layout.

## Optional: find computers with Avahi

**Manage Hosts → Find computers** discovers only hosts advertising the `_ssh._tcp` DNS-SD service. Running SSH alone does not guarantee that a host advertises this service. Discovery requires the phone and host to share a LAN where multicast DNS-SD is allowed; guest-network isolation, VPNs, or blocked multicast can prevent results. Manual hostname/IP entry remains available.

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

The modern release migrates app data forward. Do not treat installing a legacy APK over migrated data as a safe rollback: no reverse database/data migration is provided.

## Support

Like this project? Donate to me via BitCoin using this address: 17DaqbcEEG3Hn5jBv3sRjPTUUCW1eBp1Wg
