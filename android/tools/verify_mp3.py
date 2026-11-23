#!/usr/bin/env python3
"""Independently decode the Java encoder fixtures with FFmpeg and check the audio.

Author: Marco Vezzale <marcovezzale@gmail.com>
Run after testDebugUnitTest: python3 tools/verify_mp3.py app/build/mp3-verification
Requires ffmpeg and ffprobe on PATH; no Python dependencies.
"""
import array
import json
import math
from pathlib import Path
import subprocess
import sys


def run(*args):
  return subprocess.check_output(args, stderr=subprocess.PIPE)


def amplitude(samples, frequency, rate):
  real = imag = 0.0
  for index, sample in enumerate(samples):
    angle = 2 * math.pi * frequency * index / rate
    real += sample * math.cos(angle)
    imag += sample * math.sin(angle)
  return 2 * math.hypot(real, imag) / len(samples)


def verify(directory):
  cases = [
    ("stereo-48k-192.mp3", 48000, 2, 192000, 2),
    ("mono-44k-128.mp3", 44100, 1, 128000, 1),
    ("silence-48k-128.mp3", 48000, 2, 128000, 1),
  ]
  for name, rate, channels, bitrate, seconds in cases:
    path = directory / name
    probe = json.loads(run("ffprobe", "-v", "error", "-show_streams",
        "-show_format", "-of", "json", str(path)))
    stream = probe["streams"][0]
    assert stream["codec_name"] == "mp3", (name, stream)
    assert int(stream["sample_rate"]) == rate, (name, stream)
    assert int(stream["channels"]) == channels, (name, stream)
    assert int(stream["bit_rate"]) == bitrate, (name, stream)
    raw = run("ffmpeg", "-v", "error", "-i", str(path), "-f", "s16le",
        "-acodec", "pcm_s16le", "-")
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder != "little":
      samples.byteswap()
    # A finalized LAME header permits exact delay/padding trimming.
    assert len(samples) == rate * channels * seconds, (name, len(samples))
    if name.startswith("silence"):
      assert max(map(abs, samples), default=0) <= 2, name
    else:
      for channel in range(channels):
        # Use exactly 0.5 s from the center, away from boundary transients.
        begin = rate // 4
        data = samples[(begin * channels + channel):
            ((begin + rate // 2) * channels):channels]
        expected = 997.0 if channel == 0 else 3997.0
        unwanted = 3997.0 if channel == 0 else 997.0
        expected_amplitude = 15000 if channel == 0 else 10000
        wanted = amplitude(data, expected, rate)
        leak = amplitude(data, unwanted, rate)
        assert expected_amplitude * 0.80 < wanted < expected_amplitude * 1.10, \
            (name, channel, wanted)
        assert leak < wanted * 0.02, (name, channel, wanted, leak)
    tags = probe["format"].get("tags", {})
    encoded_by = tags.get("encoded_by", "")
    assert "Marco Vezzale" in encoded_by, (name, tags)
    assert "marcovezzale@gmail.com" in encoded_by, (name, tags)
    assert "artist" not in tags, (name, tags)
    print(f"PASS {name}: MP3, {rate} Hz, {channels} channel(s), "
        f"{bitrate // 1000} kbps, exact {seconds}s, decoded signal verified")


if __name__ == "__main__":
  verify(Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/mp3-verification"))
