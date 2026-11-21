# Stream Saver for Android (prototype v1.1.0)

Native Java/Android app based on the desktop Chrome Stream Saver extension. Uses Android 10+ AudioPlaybackCapture API; records the authorized device playback mix (not an individual Chrome tab) to AAC .m4a in Music/StreamSaver.

## Features
- 128/192/256 kbps stereo AAC recording, 48 kHz
- Editable filename, timer, level meter, saved file size
- Optional wait for audible playback and auto stop after 8 seconds of silence
- Foreground recording notification with stop/save button
- Rename saved audio files from the recordings library (with duplicate-name validation and Android write permission handling)
- Native Winamp-inspired player for saved files: play/pause, seek, previous/next, shuffle and repeat
- Live audio-reactive visualization via Android's MediaPlayer audio session (spectrum, oscilloscope and neon tunnel) and full-screen immersive effects
- Saved recordings list with built-in playback, sharing and delete
- No remote uploads, networking, external Java libraries or account

## Build
The GitHub Actions workflow on branch android-native-apk builds an installable signed debug APK and uploads it as a workflow artifact. Locally: JDK 17, Android SDK 35 and Gradle 8.10.2, then run `cd android && gradle assembleDebug`.

## Usage
1. Start audio playback in Chrome or another app.
2. Open Stream Saver, select the quality, then tap Start Recording.
3. Approve Record Audio permission (the app does not capture the microphone) and Android's media-projection consent prompt. For Android 14+, choose the entire screen when offered.
4. Return to the source app. When finished, use the Stop & Save notification action.
5. Find audio in Music/StreamSaver or the recordings list.
6. Tap **Play** for the in-app music player and **FULLSCREEN VISUAL EFFECTS** for immersive animations. Tap effects to switch among spectrum, oscilloscope and tunnel.
7. Tap **Rename** beside a saved recording to change its filename, preserving the .m4a extension and encoded audio.

## Limitations
App-level capture policy/DRM can prevent audio capture, including in Chrome. Audio is not decrypted or downloaded. Playback capture is permitted only for sources that allow it. This app captures all permitted playback on the device, not one selected tab. Automatic silence detection uses a volume threshold and can stop during long pauses. The Android source has not yet been hardware-tested against Chrome or all Android audio effect implementations. Live visualizer audio analysis requires Android's RECORD_AUDIO permission; when unavailable, the effects remain animated but are labeled as such.
Debug APK builds from separate GitHub Actions runners may be signed with different ephemeral debug certificates. If Android rejects an in-place update because its signing key differs, uninstall the earlier debug APK before installing the new one; recorded .m4a files in shared Music/StreamSaver normally persist, but Android may need renewed media permissions for files created by a previous installation. An unclean shutdown may leave an incomplete .m4a.
