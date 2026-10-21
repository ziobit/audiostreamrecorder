# Stream Saver v1.2.0

Manifest V3 Chrome extension for recording audio from a tab where you are already signed in. Uses an offscreen document so recording continues after closing the popup.

## Included

- **Start recording only when audio is playing:** waits for Chrome's native tab audibility callback without creating an empty recording.
- **Stop and save when audio finishes:** stops after Chrome reports the previously audible tab as no longer audible, then downloads the recording automatically.
- Both options are independent, off by default, and remembered.
- WebM/Opus audio at a requested 128, 192, or 256 kbps, with continued speaker playback.
- Local recording history, repeat downloads, and best-effort interrupted-recording recovery.
- No additional website login, server changes, remote scripts, or build step.

## Install or update

Download **Stream-Saver-Chrome-Extension-v1.2.0.zip** from the release assets and extract it. In desktop Chrome 116 or later, open `chrome://extensions`, enable Developer mode, choose **Load unpacked**, and select the extracted `stream-saver` folder containing `manifest.json`.

To update an existing installation, replace the files in the same extension folder and click **Reload** while no recording is running.

## Notes

Automatic saves use Chrome's configured Downloads folder. Manual **Stop & save** opens Save As. A local copy remains available in **Your recordings**.

Chrome's `audible` status describes recent sound from the whole tab. Pauses, buffering, and silent passages can trigger automatic stopping, and a short detection delay is possible. The extension does not automatically re-arm afterward.

All 44 automated tests pass with mocked Chrome APIs, media capture, and storage. Native Chrome end-to-end recording has not been verified in the development environment. See `TESTING.md` for manual acceptance checks.
