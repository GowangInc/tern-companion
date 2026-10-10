# Tern Companion

Control [Stencil’s Tern](https://stencil.so/tern/) from an Android phone. This is an independent companion, not an official Stencil app.

The Android app talks to `tern_bridge.py` on your computer. The bridge uses Tern’s public CLI, not its private network protocol.

## What it does

- Save multiple computers with encrypted credentials.
- Pair with a camera QR scan or a saved QR image.
- Group live terminal blocks by session. Hide Flow/background sessions until you enable the toggle below the list.
- Read clean OMP chats with Markdown, image previews, file cards and collapsed technical activity; ordinary terminal blocks retain their terminal view.
- Write multiline messages and submit with **Send**. Use Android’s native text-selection menu to paste without submitting.
- Attach images or files through the system picker, including multi-select and image-only messages.
- Open **Tools** for Enter, Ctrl+C, Escape, Tab, arrow keys and **Ctrl+P** for compatible model cycling.
- Check GitHub for stable updates, download the APK and open Android’s installer for confirmation.

## Requirements

- Android 8.0 or newer. A camera is optional because saved QR images work offline.
- A computer with Python 3.10+ and Stencil’s Tern CLI. `tern ls --json` must work there.
- A phone-reachable bridge address. Use private Tailscale, Tailscale Funnel HTTPS or a trusted LAN.

Install **Tern-Companion.apk** from the [latest release](https://github.com/GowangInc/tern-companion/releases/latest). Android will ask you to allow installation from your browser or file manager.

The public app ID is `io.github.gowanginc.terncompanion`. Earlier local builds use a different ID. The public app installs separately, so add your computers again once.

## 1. Start the bridge on the computer

Download this repository to each computer you want to control. Run the bridge from its directory, keeping `conversation.py` and `phone_uploads.py` beside `tern_bridge.py`. On macOS, transcript discovery uses the system `/usr/sbin/lsof`; Linux uses `/proc`.

### Private Tailscale access

Install Tailscale on the computer and phone, and connect both to the same tailnet.

```sh
python3 tern_bridge.py --host YOUR_TAILSCALE_IP --port 9260
```

Replace `YOUR_TAILSCALE_IP` with the computer’s IPv4 address from `tailscale ip -4`.

### Public HTTPS through Tailscale Funnel

The phone does not need Tailscale for this route. Funnel must be enabled for your tailnet.

```sh
python3 tern_bridge.py --host 127.0.0.1 --port 9260
```

In another terminal, expose only that loopback bridge:

```sh
tailscale funnel --bg --https=8443 http://127.0.0.1:9260
```

Use the HTTPS hostname printed by Tailscale. Do not disable TLS checks or expose Tern’s daemon directly.

### Trusted LAN

```sh
python3 tern_bridge.py --host YOUR_LAN_IP --port 9260
```

Allow port 9260 through your computer’s firewall only for the trusted network. Plain HTTP exposes the token and terminal traffic on that network.

Without `--host`, the bridge binds to `127.0.0.1`. A phone cannot reach that address directly.

Keep the bridge running while using the app. The first start creates `~/.tern/bridge-token`. The bridge prints its location, never its value.

## 2. Generate a pairing QR

The QR contains credentials that grant control of your computer. Keep it private. Never upload it to this repository, an issue or a public image service.

Install the optional QR encoder in a virtual environment outside synced folders:

```sh
python3 -m venv ~/.local/share/tern-qr/venv
~/.local/share/tern-qr/venv/bin/python -m pip install -r requirements-qr.txt
```

For private Tailscale or LAN access:

```sh
python3 tern_qr.py --name "My PC" --address YOUR_PRIVATE_IP --port 9260
```

For Funnel:

```sh
python3 tern_qr.py --name "My PC" \
  --address https://YOUR_COMPUTER.YOUR_TAILNET.ts.net --port 8443
```

The generator reads the existing bridge token. It does not rotate it or send it anywhere. The default output is `~/Desktop/Tern-Companion-QR.png`, with private file permissions. Existing files remain unchanged. New outputs receive a numeric suffix.

## 3. Pair and connect on Android

1. Tap **Scan host QR** and point the camera at the QR on your computer.
2. Alternatively, copy the PNG to your phone and tap **Load QR image**.
3. Check the computer name and address in **Save this PC?**.
4. Save the computer, then tap **Connect**.
5. Open a terminal block and use its controls.

Saved-image import uses Android’s file picker. It does not request access to all your files. The app stores saved credentials with Android Keystore AES-GCM and disables Android backup.

### Chat attachments

In an active OMP conversation, tap **＋**, then **Images** or **Files**. The system picker supports multiple selections. Review thumbnails and file cards above the composer; **✕** removes a pending item. **Send** submits the selected files with your message, or by themselves.

Limits: eight attachments per message, 20 MiB per file. PNG, JPEG and WebP have chat previews; other files display their name, type and size. Originals are stored privately under `~/.tern/uploads/` on the selected computer and passed through OMP’s existing native `@file` mentions. Uploads and previews require the bridge token and the exact active OMP session; another session cannot retrieve them. Files remain stored after sending.

Failed uploads or unconfirmed sends keep the draft and pending files. Check the PC before retrying an unconfirmed send. A newly launched OMP pane needs its first text message to create a transcript before files can be attached.

For manual pairing, tap **Add a PC**. Supply the name, hostname or IP, separate port and bridge token. Do not put a path, query, embedded port or password in the address field.

| Route | Address | Port | Cloudflare fields |
| --- | --- | --- | --- |
| Private Tailscale or LAN | Private IPv4 or tailnet hostname | 9260 | Leave blank |
| Tailscale Funnel | `https://COMPUTER.TAILNET.ts.net` | 8443 | Leave blank |
| Cloudflare Access | `https://YOUR_HOSTNAME` | Usually 443 | Both required |

Cloudflare Access is optional. Configure its tunnel and service token separately. Supply both Access credentials as well as the bridge token. The app does not forward credentials through redirects.

## Settings

Tap **Settings** on the PC-list screen, or **Aa** in a live terminal’s app bar. Adjust **Terminal text size** from 12–24sp (default 16sp). The preview and open terminal resize immediately, without reloading the capture or clearing your draft. **Done**, Back or dismissing the dialog saves the choice on the phone; **Reset** returns to 16sp.

Android’s system font scale also applies. The header, composer and other native controls continue to use the system setting.

## App updates

Tap **App updates** on the computer-list screen. The app checks the latest published stable release from this repository without authentication.

When an update exists, tap **Download update**, then **Install update**. Android may open **Install unknown apps** settings. Allow Tern Companion, return to the app and tap **Resume install**. Confirm the update in Android’s installer.

The updater rejects an unexpected package, different signing certificate, older version, missing APK, unsafe download URL or incomplete download. Settings remain in place during an update. Keep the same release signing key for future versions.

## Troubleshooting

- **Cannot connect:** verify the bridge is running, the address is reachable and the separate port is correct.
- **HTTP 401:** generate a new QR from the current bridge token and pair again.
- **No sessions:** run `tern ls --json` on the computer. The bridge must run as the user who owns those sessions.
- **Send not confirmed:** update `tern_bridge.py`, `conversation.py` and `phone_uploads.py`, then restart the bridge. OMP sends use one bracketed paste plus a literal carriage return, and success requires a newly persisted user message. If acceptance times out, the text and files stay on the phone; check the desktop before retrying.
- **Screen differs from desktop:** update all three bridge files. OMP conversations come from the foreground process’s exact session transcript, including version-renamed OMP executables; other terminal blocks use Tern’s `capture --surfaces` support.
- **Model does not cycle:** Ctrl+P reaches the terminal. Its meaning depends on the terminal program’s key bindings.
- **Camera unavailable:** use **Load QR image**. Camera behavior still needs checking on each physical phone model.
- **Update certificate mismatch:** use the official release. Do not uninstall the app to bypass the check.

To revoke a copied QR, stop the bridge, replace its token, restart it and generate new pairing files. Old QR files will no longer authenticate.

## Build and test

Use JDK 21 and Android SDK platform/build-tools 35. Set `ANDROID_HOME` to your SDK directory or configure `sdk.dir` in a local `local.properties` file.

```sh
./gradlew assembleDebug
python3 checks/run_all.py
```

The host suite checks bridge behavior, QR generation, host trust boundaries and update policy. For real Android QR/image and session-grouping checks, connect a disposable emulator or test device:

```sh
python3 checks/run_all.py --sdk "$ANDROID_HOME" --serial YOUR_ADB_SERIAL
```

The Android checks run against the built APK with synthetic data. They do not read your saved pairing files or change saved computers. The runner reports when Android checks were not run.

For the font-settings UI regression, use an isolated test device with the app installed:

```sh
python3 checks/check_terminal_settings.py --serial YOUR_ADB_SERIAL
```

This exercises the native slider, preview, persistence and reset, restores the original font size, and force-stops the app. It does not change saved PCs or open backend sessions.

A release APK must retain the publisher’s signing identity. Local debug APKs use a different signer and cannot receive production-signed updates in place.

## 1.5.0 changes

Fixed version-renamed OMP process detection so active conversations no longer fall back to a raw terminal dump. The chat surface uses readable user/assistant messages, grouped collapsible activity, a compact **Tools** menu and a pinned composer.

Added Android image/file picking, multi-select, pending thumbnails and file cards, authenticated session-bound uploads, chat image previews and image-only sends. Files travel with the accepted native OMP prompt; failed sends preserve text and attachments. Deploy all three Python bridge files together and restart the bridge on every computer.

Verified on Android 15: real image + text-file delivery, byte-for-byte original retrieval, an OMP reply identifying the image and reading the file, picker cancellation and multi-select, image-only delivery, exact 4,097-character rejection with preserved text/file/error, recovery after rejection, and saved font-size settings. Host/native boundary checks cover process identity, session isolation, upload framing/limits, acceptance, Markdown and attachment safety.

## 1.4.5 changes

OMP conversations now display separate user and assistant messages with Markdown headings, lists, emphasis and code blocks. Tool output and injected context are collapsed, not deleted; hidden/system messages and thinking are not displayed. Raw HTML cannot run scripts or load remote resources.

Long conversations use **Earlier** and **Latest** pages rather than dropping older messages. Pages preserve whole messages; the 1MiB capture ceiling remains. The bridge’s `POST /capture` with `format: "conversation"` returns `messages`, `start`, `end` and `total`; optional `before` selects the preceding page. Terminal controls, font settings, scrollback and keyboard-aware shortcuts remain available.

**Send** preserves multiline prompts atomically and confirms OMP acceptance before clearing the draft. Capture refreshes no longer erase failed-send errors. Update both bridge files on every computer and restart the bridge; the 1.4.5 phone client requires this updated capture contract. A real Android Send, persisted prompt, formatted OMP reply, history navigation and native boundary checks were verified on Android 15.


## 1.4.4 changes

Added saved terminal text-size settings with a native accessible slider, live preview and reset. Open them from the PC list or the terminal’s **Aa** button. Text resizing preserves the capture, history and tail-follow intent; keyboard-aware shortcuts and the native composer are unchanged.

## 1.4.3 changes

The terminal screen uses a quieter Android-style hierarchy: a compact app bar, one uncluttered paper reading surface, 16px monospace text with system font scaling, consistent 16dp side spacing, a native 16sp multiline field and one prominent **Send** action. Secondary shortcuts have at least 48dp touch targets and disappear while the soft keyboard is visible; they return when it closes. Keyboard visibility and safe areas use AndroidX IME insets, not input focus or guessed keyboard heights. Reading history and **↓ Latest** remain available.

Design references: [Android accessibility and touch targets](https://developer.android.com/guide/topics/ui/accessibility/apps), [Material typography and color roles](https://developer.android.com/develop/ui/compose/designsystems/material3#typography), and [Android software keyboard/insets](https://developer.android.com/develop/ui/views/layout/sw-keyboard). These principles are applied to the existing native Views; no Compose migration or new UI dependency.

## 1.4.2 changes

The terminal uses a warm paper-and-ink linework theme with fine registration arcs and a ruled reading margin. Desktop colors become contrast-checked sage, coral, blue, violet and ochre inks with pale highlight fields rather than white text on a dark surface. Long lines wrap to the phone width; explicit line breaks, emphasis and full scrollback remain intact. Scrolling up preserves your reading position during refresh, **↓ Latest** restores tail-following, and the multiline composer stays above the keyboard.

## 1.4.1 changes

Terminal redesign: the live screen is now a full-height terminal pane like the desktop — scrollback included, desktop colors preserved, wide lines kept as columns with touch scrolling (pinch to zoom). A pinned composer above the keyboard keeps standard Android text editing: Enter inserts a newline, **Send ▸** submits, and drafts survive failed sends. Scrolling up pauses tail-following without losing your place; **↓ Latest** jumps back. Key shortcuts moved to one horizontal strip (Ctrl+P, Ctrl+C, Esc, Tab, Enter, arrows). New sessions now start **OMP**, the same agent interface as the desktop.

For diagnosis, the app logs to `adb logcat -s TernCompanion`: version, screen transitions, and per-request method/endpoint/status/duration/length — never tokens, payloads, or terminal contents.

## 1.4.0 changes

In-app Back navigation: terminal → sessions → PC list, exit only from the PC list. Multiline composer that grows with your draft; Enter inserts a newline and Send submits it with Enter. New-session action on the sessions screen (uses the bridge's `/new`). Live screen now follows the active Tern surface (restart any bridge still running pre-1.3 code).

## 1.3.0 changes

Portrait-aware, rotating linework scanner. More restrained linework on cards. Compact session groups and a background toggle. Ctrl+P shortcut. One-action Send submission. Active plugin-screen capture. Signed GitHub updates. Expanded runnable checks.

## License

Original companion and bridge code: [MIT](LICENSE). Tern belongs to Stencil. Dependencies and trademarks retain their respective rights.
