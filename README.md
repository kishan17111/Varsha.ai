# Varsha – private voice assistant (no API keys)

Say **"Varsha, <command>"**. Speech recognition and replies use Android's built-in engines.
The app has **no INTERNET permission**, so nothing it hears or does can leave the phone.

## Build the APK on GitHub
1. Create a **private** repo and upload this folder's contents (including `.github`).
2. Quick test: **Actions → Build and publish APK → Run workflow**. Download `varsha-apk` (debug-signed) from the run.
3. Signed release (needed for tags): make a keystore once
   `keytool -genkeypair -v -keystore release.jks -alias jarvis -keyalg RSA -keysize 2048 -validity 10000`
   then add repo secrets `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   For the public-release step also add `PUBLIC_REPO_TOKEN` and `PUBLIC_REPO` (`owner/name`).
4. `git tag v1.0 && git push origin v1.0`.

## First run on the phone
1. Install the APK, open Varsha, tap **1–4** and grant everything.
2. Android 13+ greys out the accessibility switch for sideloaded apps: **Settings → Apps → Varsha → ⋮ → Allow restricted settings**, then enable it under Accessibility.
3. Settings → Battery → Varsha → **Unrestricted**, so the listener isn't killed.
4. For offline recognition, download your language in Google app → Settings → Voice → Offline speech recognition.
5. Tap **Start Varsha**.

## How it works
- `VoiceService` – foreground service, listens for the wake word, runs commands.
- `Commands` – sentence → action (intents, media keys, audio, alarms, etc.).
- `VarshaAccessibilityService` – taps, types, swipes; presses *send* in WhatsApp and the first result in YouTube.
- Add your own commands in `Commands.route()`: one `t.cmd("""regex""")?.let { ... }` line each.

## Instagram & contacts
- Contacts: lookup by name for calls, WhatsApp and SMS; also "what's Sam's number", "open contact Sam", "add contact Ali with number …".
- Instagram has no public voice API, so Varsha drives the app through its on-screen English labels. Profile, DM, search, like, follow, comment, next/previous reel. If Instagram changes its layout, adjust the `ig*` functions in `Commands.kt`.

## Voice & screen effect
- Varsha speaks every reply (Android text-to-speech). Tap **Change voice** to cycle installed voices (pick a female one if you like) and **Voice speed** to adjust.
- The orb shows state: blue = waiting for "Varsha", green = listening (reacts to your voice), purple = speaking. With *Display over other apps* allowed, a small floating orb appears over any app while she listens or speaks.
