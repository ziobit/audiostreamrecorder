# Verification

## Automated checks performed

81 automated tests pass against the actual recorder and service-worker source,
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

The additional diagnostics tests cover synthetic FFT bands at 44.1/48/96 kHz,
silence, non-finite values, sparse tones, full-band signals, transients, weak highs,
accumulated evidence, bounded display data, and output-bitrate arithmetic. Recorder
integration tests cover default-off behavior, live toggle/reset, waiting, suspended
contexts, optional-analyser failures, cleanup on stop/track loss/storage failure,
and committed/final byte accounting. Popup tests execute the real modules with a
minimal DOM/canvas mock, checking controls, reopening, persistence, and labels.

JavaScript syntax and HTML control IDs are checked. FFT fixtures represent
frequency-domain inputs; they do not validate Chrome's native FFT/windowing or
its actual CPU usage.

To rerun the automated tests (optional, developers only), use Node.js 22 or later:

```sh
node --experimental-vm-modules --test tests/*.test.mjs
```

Node is not needed to install or use the extension.

## Verification limit

A native Chrome executable is not available in this development environment.
Native tabCapture/offscreen flow, actual IndexedDB persistence, audible output,
Save As, real FFT behavior and visual layout have not been verified end to end.
Mocked tests do not prove those browser behaviors. The checks below remain manual
acceptance criteria; they are not reported as passed.

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


## Diagnostics acceptance check in Chrome

1. Start recording with diagnostics disabled. Confirm playback, native auto-start/
   stop and saving still work. In offscreen DevTools, verify there is no diagnostics
   interval or 8,192-point analyser; the original 256-point level meter remains.
2. Enable diagnostics while recording. Confirm the AudioContext rate, spectrum
   axes, requested bitrate and output average appear. The output value should
   change at saved-chunk boundaries and need not equal the requested bitrate.
3. Play broadband noise low-pass filtered near 10 kHz for at least 15 seconds.
   Expect an approximate upper band near the filter's transition region; filter
   rolloff and threshold affect the estimate. Repeat at 16 kHz after Reset.
4. Play a pure tone, silence, and very quiet material. Expect insufficient/sparse
   signal or retained earlier evidence, not an asserted original sample rate.
   With broadband content reaching Nyquist, expect no clear cutoff.
5. Close the popup for 15 seconds, reopen it, and verify sample counts continued.
   Reset and toggle off/on; counts should restart, playback and recording should
   continue, and disabling should remove the analysis timer/branch. Compare the
   offscreen document's CPU usage with diagnostics on and off.
6. Stop/save and confirm requested/output bitrate and the compact cutoff summary
   remain visible; no stale live spectrum should appear on the saved recording.
   Check a recording made before 1.3.0 still lists/downloads normally.
7. Repeat with native wait-for-audio, auto-stop, source-tab closure and interrupted
   capture. Confirm no analyser before capture and no leftover timer after stop.
8. Check keyboard focus, checkbox/Reset behavior, all labels and scrolling at the
   popup's normal width and with a long recording title. Inspect Chrome errors.

The suite does not infer original encoding from any synthetic fixture. A real
codec test should compare known original metadata with independently observed
bandwidth and should never expect the extension to recover that metadata.
