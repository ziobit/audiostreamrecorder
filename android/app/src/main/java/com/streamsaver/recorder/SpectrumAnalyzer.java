package com.streamsaver.recorder;

import java.util.Arrays;

/**
 * Maps Android's packed, signed 8-bit complex FFT to logarithmic musical bands.
 * A shared, slowly released reference keeps the spectrum proportional between
 * bands. This is a relative music display, not a calibrated dBFS meter.
 * @author Marco Vezzale (marcovezzale@gmail.com)
 */
final class SpectrumAnalyzer {
  static final int BAND_COUNT = 64;
  private static final float RANGE_DB = 40f;
  private static final float MIN_REFERENCE = 8f;
  private float[] magnitudes = new float[0];
  private final float[] levels = new float[BAND_COUNT];
  private float reference = MIN_REFERENCE;
  private float lowHz = 50f;
  private float highHz = 16000f;

  void reset() {
    reference = MIN_REFERENCE;
    Arrays.fill(levels, 0f);
  }

  float getLowHz() { return lowHz; }
  float getHighHz() { return highHz; }

  void analyze(byte[] fft, int samplingRateMilliHz, float elapsedSeconds, float[] output) {
    Arrays.fill(output, 0f);
    if (fft == null || fft.length < 16 || (fft.length & 1) != 0 ||
        samplingRateMilliHz <= 0 || output.length != BAND_COUNT) return;

    // Android callbacks report milliHertz. FFT bin k is k * Fs / N.
    float samplingHz = samplingRateMilliHz / 1000f;
    float binHz = samplingHz / fft.length;
    int nyquist = fft.length / 2;
    lowHz = Math.max(40f, binHz);
    highHz = Math.min(16000f, samplingHz / 2f);
    if (highHz <= lowHz) return;
    if (magnitudes.length != nyquist + 1) magnitudes = new float[nyquist + 1];

    // Index 0 is DC; index 1 is the real Nyquist coefficient, not Im(DC).
    magnitudes[0] = 0f; // Do not display a DC offset as musical energy.
    magnitudes[nyquist] = Math.abs((int) fft[1]);
    for (int k = 1; k < nyquist; k++) {
      magnitudes[k] = (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
    }

    double ratio = highHz / lowHz;
    float framePeak = 0f;
    for (int band = 0; band < BAND_COUNT; band++) {
      float first = (float) (lowHz * Math.pow(ratio, band / (double) BAND_COUNT)) / binHz;
      float last = (float) (lowHz * Math.pow(ratio, (band + 1d) / BAND_COUNT)) / binHz;
      // Interpolate narrow low-frequency bands instead of leaving empty bins.
      // For wider bands retain the strongest bin so a narrow tone is not lost.
      float magnitude = Math.max(interpolate(first), interpolate(last));
      for (int k = (int) Math.ceil(first); k <= Math.floor(last) && k <= nyquist; k++) {
        magnitude = Math.max(magnitude, magnitudes[k]);
      }
      levels[band] = magnitude;
      framePeak = Math.max(framePeak, magnitude);
    }

    float seconds = Math.max(0f, Math.min(.25f, elapsedSeconds));
    reference = Math.max(MIN_REFERENCE,
      Math.max(framePeak, reference * (float) Math.exp(-seconds / 1.2f)));
    for (int band = 0; band < BAND_COUNT; band++) {
      float magnitude = levels[band];
      // One FFT quantization step is not enough evidence to amplify into a bar.
      if (magnitude < 2f) continue;
      float relativeDb = 20f * (float) Math.log10(magnitude / reference);
      output[band] = Math.max(0f, Math.min(1f, 1f + relativeDb / RANGE_DB));
    }
  }

  private float interpolate(float bin) {
    int lower = Math.max(0, Math.min(magnitudes.length - 1, (int) bin));
    int upper = Math.min(magnitudes.length - 1, lower + 1);
    float fraction = Math.max(0f, Math.min(1f, bin - lower));
    return magnitudes[lower] + fraction * (magnitudes[upper] - magnitudes[lower]);
  }
}
