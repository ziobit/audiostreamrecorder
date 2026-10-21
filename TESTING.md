# Verification

## Automated checks performed

44 automated tests pass against the actual recorder and service-worker source,
using mocked Chrome APIs, MediaRecorder, AudioContext, and database operations.
They exercise:

- recording startup and speaker monitoring;
- writing the final audio chunk before final metadata;
- duplicate start and stop requests;
- tab closure and capture-track termination;
- capture denial and unsupported encoders;
- storage exhaustion and encoder failure;
- preserving data when a Save As dialog is cancelled;
- preventing export/deletion of an active recording;
- export Blob URL cleanup, including fast-download races;
- offscreen creation races and service-worker sender handling;
- invoking interrupted-session recovery;
- excluding full source URLs and their query tokens from metadata;
- filename safety, including Thai text and emoji;
- manifest permissions and absence of remote scripts.
- native audibility callbacks, already-playing tabs, mute/unmute, and ignoring
  audio from other tabs;
- empty-file prevention while waiting, cancellation, and cancellation racing a
  delayed capture request;
- preserving the pending request across a simulated service-worker restart;
- navigation cancellation and expired capture permission errors.
- native automatic-stop events, requiring prior audible activity, flushing the
  final chunk, and ignoring unrelated sessions;
- manual/automatic stop races and preventing duplicate automatic downloads;
- independent automatic-start/stop settings, automatic Downloads-folder saves,
  download failure recovery, and worker-restart handling.

JavaScript syntax, manifest/resource paths, and HTML control IDs are also checked.

To rerun the automated tests (optional, developers only), use Node.js 22 or later:

```sh
node --experimental-vm-modules --test tests/recorder.test.mjs
```

Node is not needed to install or use the extension.

## Verification limit

A working native Chrome executable was not available in the build environment,
and the browser download endpoint returned an unavailable page. Therefore the
native tabCapture/offscreen flow, actual IndexedDB persistence, audible output,
Save As dialog, and rendered interface have not been tested end to end here.
Mocked tests do not prove those browser behaviors.

## Manual Chrome acceptance check

Before distributing to subscribers:

1. Load the unpacked extension in desktop Chrome. Confirm there are no manifest
   or service-worker errors on `chrome://extensions`.
2. Open your real audio website, sign in, start playback, and click the extension
   icon. Start a recording. Confirm the timer advances, the REC badge appears,
   and the audio remains audible.
3. Close the popup for at least ten seconds, switch to another tab, then reopen
   the popup. Confirm the recording continued and the original source is shown.
4. Stop and save. Play the downloaded `.webm` in Chrome or VLC. Check that both
   the first and last sounds are present and that the duration is reasonable.
5. Make another recording and cancel Save As. Open **Your recordings** and
   download it again. Confirm that it is playable.
6. Start recording, then close the source tab. Confirm the recording is retained
   in **Your recordings** and the REC badge clears.
7. With only test audio, record for at least ten seconds, reload the extension,
   and open **Your recordings**. Confirm the session is marked Interrupted and
   previously committed audio is available for a best-effort download.
8. Delete one local copy. Confirm it disappears while an already-downloaded file
   remains on disk. Repeat recording once after deletion.
9. Enable **Start recording only when audio is playing** on a paused source tab.
   Click **Wait for audio**, close the popup, and wait at least ten seconds.
   Confirm there is a WAIT badge and no new entry in Your recordings. Start
   playback on the source tab and confirm the badge changes to REC, the timer
   starts, and Stop & save produces playable audio.
10. Repeat with the source tab muted. Confirm it waits until you unmute it.
    Confirm audio from a different tab does not start the pending recording.
11. Cancel while waiting and confirm no empty file appears. Reload the popup and
    confirm the checkbox choice is retained. Disable the checkbox and confirm
    the original immediate-start behavior is restored.
12. Enable both checkboxes, arm the recorder, and close the popup. Play audio on
    the source tab, then pause or end playback. When Chrome's native audible state
    clears, confirm recording stops and a playable file appears in Downloads,
    with a retained copy in Your recordings and no Save As prompt.
13. Enable automatic stop alone on a silent tab. Confirm it remains recording
    until you have first played audio and then stopped playback. Disable automatic
    stop and confirm manual recording continues through silence as before.

For a long-session rollout, also test your expected maximum recording duration
on the users' target operating systems and verify available disk storage.
