# Stream Saver for Android · v1.2.0

**Author:** Marco Vezzale  
**Contact:** marcovezzale@gmail.com

Native Java app for Android 10 and later. Record permitted device playback, save it
locally, and play it inside Stream Saver with fullscreen music visualizations.
Recordings are stored in **Music/StreamSaver/**. The desktop Chrome extension has
its own version and format support; these features describe the Android app.

## Record as MP3 or M4A

1. Select **MP3 · .mp3** or **AAC · .m4a** under **Save format**.
2. Choose 128, 192, or 256 kbps and enter a recording name.
3. Tap **Start recording** and approve Android's audio/projection permissions.
4. Return to Chrome or another permitted playback source.
5. Tap **Stop & save** in Stream Saver or its recording notification.

MP3 is the initial default; the app remembers the selected format and quality.
Both formats encode directly from the captured 48 kHz stereo PCM. MP3 recording
does not make an intermediate AAC file. The existing wait-for-audio and
eight-second silence-stop options work with either format.

Android's RECORD_AUDIO permission is required by playback capture and live
visualization. This app does not open the microphone. Some source applications
prohibit capture; the app cannot override their policy. Capture records the
permitted device playback mix, rather than one selected Chrome tab.

## Convert an existing recording

Tap **Save as MP3** under an M4A recording, choose the bitrate, and tap **Save**.
A foreground export notification shows progress and provides **Cancel**. You can
leave the screen while export runs. The original remains intact; a separate
MP3 copy appears in the same folder. Duplicate filenames receive a numeric suffix.

Existing AAC recordings must be decoded and encoded again, so conversion is
lossy and does not increase source quality. Choose MP3 before recording to avoid
that additional conversion. Cancelled or failed conversions remove incomplete
destinations and keep the original.

Both formats support **Play**, **Rename**, **Share**, and **Delete**. Renaming
preserves the actual extension. Sharing uses the matching MIME type. Files still
being recorded or converted are hidden until ready.

## Internal player and visualizations

The Winamp-inspired player supports play/pause, seeking, previous/next, shuffle,
repeat, spectrum, oscilloscope, and neon tunnel effects. **Fullscreen visual
effects** hides the system bars and keeps the screen awake. Tap the visualization
to show or hide fullscreen controls; Back exits fullscreen.

The spectrum has 64 logarithmic bands. Android's actual sampling rate determines
the frequency mapping, from the first resolvable musical frequency (usually
about 43–47 Hz with a 1024-point capture) up to 16 kHz or Nyquist, whichever is
lower. Frequency labels show the current range.

A shared adaptive reference maps strong peaks to the top of the drawable area
and preserves relative differences between bands over a 40 dB display range.
Attack/release smoothing and peak markers make changes readable. The oscilloscope
and tunnel use the same full-area scaling principle. This is an automatically
normalized music visualization, not a calibrated dBFS or loudness meter.

All bands respond to their corresponding audio frequencies. Audio without
high-frequency energy will not generate invented treble activity. Silence is
not amplified into moving bars. Unavailable or stale live analysis is explicitly
labeled and the audio measurements remain inactive.

## Author and third-party metadata

The APK description and custom metadata identify Marco Vezzale and
marcovezzale@gmail.com. The recording screen and **About & licenses** show the
same details. MP3 ID3 encoder/software tags identify Stream Saver and its author;
they do not assign the author as the artist or composer of recorded audio.

MP3 encoding uses the unmodified Jump3r 1.0.5 Java port of LAME. Its original
contributors, LGPL 2.1-or-later license, and exact corresponding source archive
are included in the APK. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
The app remains offline and has no INTERNET permission.

## Build and verification

Requirements: JDK 17, Android SDK 35, Gradle 8.10.2. From this directory:

```sh
gradle --no-daemon testDebugUnitTest assembleDebug
python3 tools/verify_mp3.py app/build/mp3-verification
```

Independent audio verification requires FFmpeg and ffprobe. Tests cover spectrum
scaling, placement across all bands, silence/DC/Nyquist handling, MP3 finalization,
duration headers, metadata, partial frames, and storage errors. Generated MP3
fixtures are independently decoded to verify sample rate, channels, bitrate,
exact decoded duration, amplitude, stereo separation, and silence.

The GitHub Actions workflow runs these checks and uploads a signed debug APK.
Gradle includes the pinned encoder source JAR as an asset during preBuild.
No Java Sound desktop wrapper is called on Android.

## Device validation and debug builds

Automated encoder and spectrum tests do not replace device testing of capture
permissions, codecs, foreground services, storage, and audio effects.

Separate GitHub Actions debug builds can have different signing certificates.
Try installing normally; if Android rejects the signature, back up recordings
before uninstalling the previous debug build. Shared Music/StreamSaver files
normally remain, but a new install can need renewed media permissions.

Force-stopping the app or losing power during recording can leave an incomplete
file. Conversion always retains the source recording.

## License

Application source: Apache License 2.0, see [../LICENSE](../LICENSE).
Third-party encoder: its separate license and attribution are preserved.
