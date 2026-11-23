# Android changes

## 1.2.0

- Add Marco Vezzale and marcovezzale@gmail.com to app metadata, About, source
  attribution, and MP3 software/encoding tags.
- Record directly to MP3 or AAC/M4A at 128, 192, or 256 kbps.
- Save a separate MP3 copy of existing M4A audio with progress and cancellation.
- Preserve the actual extension when renaming and share the correct MIME type.
- Fix the visualizer height ceiling; add 64 logarithmic bands using the real
  sample rate, shared automatic scaling, and readable frequency labels.
- Improve waveform/tunnel scaling, smoothing, and unavailable-analysis handling.
- Add readiness and long-track seeking guards; keep fullscreen awake.
- Finalize captured audio when the capture source stops unexpectedly.
- Add spectrum/encoder tests and independent MP3 decode verification.

## 1.1.0

- Rename saved recordings.
- Internal player and fullscreen music effects.

## 1.0.0

- Initial Android playback capture app saving AAC/M4A.
