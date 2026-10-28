XMouse-NG PRIVACY POLICY
Last updated: 28 October 2026

ABOUT
XMouse-NG is an open-source Android app maintained in the homoludens/xmouse-ng project. The app has no project account service and does not use a project-operated server to sync app data. Its declared app dependencies contain no advertising or analytics SDK, and app code does not send automatic usage telemetry to the project. Android, network providers, GitHub, and remote computers may process their own system, network, or issue records under their own policies.

DATA STORED ON YOUR DEVICE
XMouse-NG keeps app data in its Android app storage. Depending on the features you use, this can include saved computer names, hostnames or addresses, ports, usernames, discovered-service names, approved SSH host identities, preferences, keyboard layouts, saved action layouts and commands, saved script names and commands, and creation or update timestamps. Recent command feedback and the layout editor's undo/redo history are temporary; XMouse-NG does not send a persistent command-history feed to the project. Script output is shown in the app while you use it and is copied to Android's system clipboard only if you choose Copy.

SSH AND REMOTE COMPUTERS
When you connect, XMouse-NG opens an SSH connection to a computer and port you selected. It uses the selected username and your chosen password or SSH-key authentication. The remote computer receives the SSH input you deliberately send, such as pointer or key actions, text after Send, custom actions, and scripts you run; its responses may be displayed in XMouse-NG. Your SSH server and its administrator control their own authentication and logging. XMouse-NG does not operate those accounts or receive the connection through a project server. Imported private-key bytes stay in the app's protected storage; public-key authentication does not send the private key itself.

CLIPBOARD
The Send clipboard to computer control reads plain text from Android's clipboard only after you tap it while XMouse-NG is in the foreground and an SSH session is ready. It sends that text one way to the selected Linux computer over SSH. The app does not monitor or synchronize the clipboard in the background. Separately, if you tap Copy on script output, XMouse-NG places that remote output on Android's system clipboard, where Android and other apps may make it available according to platform behavior.

DISCOVERY AND NETWORK LOOKUPS
If you choose Find computers, XMouse-NG starts local SSH-service discovery on your network and stops discovery when that search is closed or leaves the foreground. Service names and connection details advertised on that local network may be visible to devices on the same network. When you connect using a hostname, Android's network resolver may also make DNS requests. These are network operations for the computer you chose, not project analytics.

PROTECTED CREDENTIALS AND ANDROID BACKUP
Stored passwords, passphrases, and imported private-key bytes are encrypted with AES-GCM using a key held by Android Keystore and are stored in the app's private no-backup directory. Device or hardware-backed protection depends on Android and the device; XMouse-NG does not promise hardware-backed keys. If Android backup or device transfer is enabled, the app's rules allow only a generated safe metadata snapshot and the legacy keyboard-layout/preset files. The snapshot can contain saved host and script metadata, selected settings, and credential-presence markers, but not password, passphrase, or private-key bytes or secret references. Raw database and shared-preference files and the no-backup credential directory are excluded by the app's rules. Android or a backup provider may retain allowed backup copies under its own settings and policies.

LOGS, RETENTION, AND DELETION
The app's own diagnostic messages use fixed or categorical values and are designed not to include passwords, passphrases, private-key bytes, hostnames, typed text, custom commands, script output, or clipboard contents. This does not control Android system logs, third-party platform components, or logs kept by your SSH server. XMouse-NG does not send your saved records to the project for retention. Saved items remain in the app until you remove them or clear the app's local data. To clear local app data, use Android Settings for XMouse-NG or uninstall it. This is not a promise of forensic erasure, and Android-managed backup copies may remain or later be restored according to your backup settings and provider's policy. The project has no copy of your app data to delete remotely.

CONTACT
For project questions, use the public issue tracker: https://github.com/homoludens/xmouse-ng/issues. Issues are public. Do not post passwords, passphrases, private keys, host addresses, logs, clipboard text, or other personal or sensitive information.
