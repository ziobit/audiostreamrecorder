# Stream Saver for Android (prototype v1.0.0)

Native Java/Android app based on the desktop Chrome Stream Saver extension. Uses Android 10+ AudioPlaybackCapture API; records the authorized device playback mix (not an individual Chrome tab) to AAC .m4a in Music/StreamSaver.

## Features
- 128/192/256 kbps stereo AAC recording, 48 kHz
- Editable filename, timer, level meter, saved file size
- Optional wait for audible playback and auto stop after 8 seconds of silence
- Foreground recording notification with stop/save button
- Saved recordings list with playback, sharing and delete
- No remote uploads, networking, external Java libraries or account

## Build
The GitHub Actions workflow on branch android-native-apk builds an installable signed debug APK and uploads it as a workflow artifact. Locally: JDK 17, Android SDK 35 and Gradle 8.10.2, then run `cd android && gradle assembleDebug`.

## Usage
1. Start audio playback in Chrome or another app.
2. Open Stream Saver, select the quality, then tap Start Recording.
3. Approve Record Audio permission (the app does not capture the microphone) and Android's media-projection consent prompt. For Android 14+, choose the entire screen when offered.
4. Return to the source app. When finished, use the Stop & Save notification action.
5. Find audio in Music/StreamSaver or the recordings list.

## Limitations
App-level capture policy/DRM can prevent audio capture, including in Chrome. Audio is not decrypted or downloaded. Playback capture is permitted only for sources that allow it. This app captures all permitted playback on the device, not one selected tab. Automatic silence detection uses a volume threshold and can stop during long pauses. The Android source has not yet been hardware-tested against Chrome. An unclean shutdown may leave an incomplete .m4a.
