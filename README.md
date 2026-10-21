# Stream Saver 1.2.0

A ready-to-load Manifest V3 Chrome extension that records audio from a tab
where the user is already signed in. Plain JavaScript; no build step, npm,
Rust, server changes, external scripts, or additional account required.

## Install

Download the extension ZIP from [GitHub Releases](https://github.com/ziobit/audiostreamrecorder/releases/latest).

1. Extract the ZIP to a permanent folder on your computer.
2. Open `chrome://extensions` in desktop Chrome 116 or later.
3. Turn on **Developer mode** in the top-right corner.
4. Click **Load unpacked** and select the `stream-saver` folder containing
   `manifest.json`.
5. Open Chrome's puzzle-piece Extensions menu and pin **Stream Saver**.

Keep the extracted folder in place. To install an updated version, replace its
files in the same folder and click Reload on the extension card while no
recording is running. This development installation is intended for testing
and personal use. For easy subscriber installation, publish the extension
through the Chrome Web Store; this package does not publish anything.

## Record

1. Open your audio website and sign in normally.
2. Start playback, then click the Stream Saver icon on that same tab.
3. Optionally enter a recording name and choose 128, 192, or 256 kbps.
4. Click **Start recording**. A red **REC** badge appears on the toolbar icon.
5. Close the popup or switch tabs freely; recording continues.
6. Reopen the popup and click **Stop & save**. Choose the destination in
   Chrome's Save As dialog.

The selected bitrate is a requested target; Opus can use a variable bitrate.
192 kbps is a useful default, roughly 86 MB per hour before overhead.

The audio continues playing through the normal output device. No microphone
permission is needed. The extension records the selected tab's mixed audio,
including any additional sounds that page produces. It does not select a
single player within the tab.

## Start only when audio is playing

Enable **Start recording only when audio is playing**, then click **Wait for
audio**. The setting is off by default and your choice is remembered.

This uses Chrome's native `Tab.audible` state and `chrome.tabs.onUpdated`
callback, with `Tab.mutedInfo.muted` checked as well. It does not use custom
volume thresholds or the audio level meter to decide when to start.

- If Chrome already reports the tab as audible and unmuted, recording starts.
- Otherwise an amber **WAIT** badge appears. No capture stream, recorder, or
  empty audio file is created while waiting. The timer remains at zero.
- You can close the popup or switch tabs. The offscreen document retains the
  pending request, and a Chrome tab update wakes the service worker when needed.
- When the selected tab becomes audible and unmuted, recording starts and the
  badge changes to **REC**. Sound from another tab cannot trigger it.
- **Cancel waiting** clears the pending request without saving anything.
- Closing or navigating the source tab cancels a pending request. Arm it again
  on the desired page. A browser restart or extension reload also clears waiting.
- This checkbox controls the initial start. The separate automatic-stop option
  below controls what happens when the tab becomes silent later.

Chrome defines `audible` as having produced sound over the past couple of
seconds, matching its speaker indicator. It is a browser-provided status, not
a sample-accurate playback clock. There can be a short delay at the beginning,
and recently stopped audio can still be reported as audible briefly. Initial
audio before the callback and capture startup is not recovered. This also
describes the whole tab, not one particular player. System speaker volume is
independent of this status.

## Stop and save when audio finishes

Enable **Stop and save when audio finishes** before starting or arming the
recorder. This setting is separate from automatic start, is off by default, and
is remembered. You can use either checkbox independently or both together.

The extension uses Chrome's native `audible: false` update to stop, flush the
last audio chunk, and automatically download the `.webm` to Chrome's configured
Downloads folder. It does not open a Save As dialog for automatic saves. A local
copy remains in **Your recordings**. The manual **Stop & save** button still
opens Save As.

Automatic stop only activates after the tab has been reported as audible, or
was already audible when recording began. Initial silence therefore does not
immediately stop a manually started recording. With both checkboxes enabled,
the extension waits for audio, records it, then stops and downloads when Chrome
reports the tab is no longer audible. It does not automatically re-arm afterward.

**This is a tab-audibility event, not an end-of-track event.** A sufficiently
long pause, silent passage, or buffering gap can also make Chrome report
`audible: false` and trigger saving. Chrome's recent-audio status can add a short
delay and some trailing silence. There is no additional custom silence threshold
or timer. Muting alone does not stop a recording if Chrome still reports the
tab as audible. Closing the source tab retains the recording as described below.

If starting the download fails, the recording remains available for a manual
download in **Your recordings**.

## Your recordings

The **Your recordings** link opens the local recordings page. Each recording
can be downloaded again or deleted. Cancelling a Save As dialog does not erase
the recording. Deleting a local copy does not delete an already-downloaded file.

Audio chunks are committed to IndexedDB about every two seconds. This avoids
collecting the entire recording in a JavaScript array while recording. Exports
assemble a Blob from the stored chunks; very large exports can still use
significant browser resources.

If the source tab closes or Chrome ends capture normally, the extension finishes
the recording and keeps it in **Your recordings**. Open that page to download it.
If Chrome crashes or the extension is reloaded mid-recording, the next startup
marks unfinished sessions **Interrupted**. Previously committed chunks remain
available, but the file can be incomplete and the last seconds can be lost.
Recovery is best effort, not a guarantee against disk failure or Chrome data removal.

Local copies use this Chrome profile's available storage. Storage failure stops
recording and retains earlier committed chunks when possible. Delete unwanted
copies after downloading. Uninstalling the extension or removing its data also
removes those local copies. Downloaded files are independent.

## Output and limitations

- Output: `.webm`, normally with Opus audio. Chrome and compatible players such
  as VLC can play it. This version does not convert to MP3.
- Recording is real time. A one-hour programme takes one hour to record.
- It creates a new recording of playback; it does not copy the original encoded
  MP3/AAC bytes or retrieve earlier audio.
- Keep Chrome running and the source tab playing. Pauses and buffering can
  produce silence. Computer sleep can interrupt or create gaps in the capture.
- The popup can close; the source tab must remain open for continued capture.
- Recording continues across navigation within the selected tab until stopped
  or the tab closes. Stop before navigating to content you do not want recorded.
- Chrome's internal pages cannot be used as sources. Open an HTTP/HTTPS page.
- Some players may need to scan the file before showing its duration or allowing
  accurate seeking. A recovered partial file may have less complete metadata.
- This package targets desktop Chrome. Other browsers and mobile environments
  are not included in the verification scope.

## Authentication and privacy

Your website remains responsible for subscription checks and audio playback.
The extension needs no password, cookie access, API key, server address, or new
login. It captures audio the selected tab is already playing. It does not add
subscription enforcement of its own or restrict recording to a particular domain.

No audio or metadata is uploaded. The extension saves audio, the recording name,
the source hostname (not its full URL or query string), and timestamps locally.
There are no analytics, host permissions, content scripts, or remote libraries.

Permissions:

| Permission | Purpose |
| --- | --- |
| `activeTab` | Access the tab chosen by clicking the extension icon. |
| `tabCapture` | Obtain that tab's audio after the user's action. |
| `offscreen` | Run the audio recorder independently of the popup. |
| `downloads` | Open Save As and monitor this extension's file downloads. |

## Implementation

- `background.js`: popup requests, capture authorization, badge, and downloads.
- `offscreen.html` / `offscreen.js`: MediaRecorder, audio monitoring, and Blob URLs.
- `db.js`: transactional recording metadata and audio chunk storage.
- `popup.html` / `popup.js`: recording controls.
- `recordings.html` / `recordings.js`: local recording history and downloads.
- `common.js` / `style.css`: formatting and shared interface styles.

The service worker is not responsible for retaining the live MediaRecorder.
It can sleep and restart; the offscreen document owns the live session.
The offscreen document uses only the `chrome.runtime` extension API and asks
the service worker to perform operations that need other Chrome APIs.

## Verification

See `TESTING.md` for automated coverage, environment limitations, and a short
manual Chrome acceptance check. No automated test needs your server or login.

## Repository and releases

The source is maintained at [ziobit/audiostreamrecorder](https://github.com/ziobit/audiostreamrecorder).
If you clone the repository, select the repository root when using **Load unpacked**.
Node is used only for the automated tests; the extension itself needs no build step.

The GitHub Actions release workflow runs the test suite, packages the extension,
and publishes the ZIP when `manifest.json` or the release workflow changes on
`main`, or when a matching `v` tag is pushed. Update `manifest.json` and
`RELEASE_NOTES.md` together for each new version. The workflow creates a tag for
that exact commit when needed. Existing releases are left unchanged.

## License

Apache License 2.0. See [LICENSE](LICENSE).

## Official API references

- https://developer.chrome.com/docs/extensions/how-to/web-platform/screen-capture
- https://developer.chrome.com/docs/extensions/reference/api/tabCapture
- https://developer.chrome.com/docs/extensions/reference/api/tabs#property-Tab-audible
- https://developer.chrome.com/docs/extensions/reference/api/offscreen
- https://developer.chrome.com/docs/extensions/reference/api/downloads
- https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder
