package com.streamsaver.recorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpectrumAnalyzerTest {
  private static final int N = 1024;

  private float[] analyze(SpectrumAnalyzer analyzer, byte[] fft, int rateHz) {
    float[] result = new float[SpectrumAnalyzer.BAND_COUNT];
    analyzer.analyze(fft, rateHz * 1000, 1f / 30f, result);
    return result;
  }

  private float max(float[] values) {
    float maximum = 0;
    for (float value : values) maximum = Math.max(maximum, value);
    return maximum;
  }

  @Test public void maximumComplexInputReachesFullHeight() {
    byte[] fft = new byte[N];
    fft[40] = (byte) -128;
    fft[41] = (byte) -128;
    assertEquals(1f, max(analyze(new SpectrumAnalyzer(), fft, 48000)), .0001f);
  }

  @Test public void broadbandAudioDrivesEveryBandAcrossTheScreen() {
    byte[] fft = new byte[N];
    for (int bin = 1; bin < N / 2; bin++) {
      fft[2 * bin] = 50;
      fft[2 * bin + 1] = -50;
    }
    float[] levels = analyze(new SpectrumAnalyzer(), fft, 48000);
    for (int band = 0; band < levels.length; band++) {
      assertEquals("Broadband signal must reach band " + band, 1f, levels[band], .0001f);
    }
  }

  @Test public void toneSweepCanReachEveryLogarithmicBand() {
    for (int rateHz : new int[]{44100, 48000, 96000}) {
      float low = Math.max(40f, rateHz / (float) N);
      float high = Math.min(16000f, rateHz / 2f);
      for (int band = 0; band < SpectrumAnalyzer.BAND_COUNT; band++) {
        double center = low * Math.pow(high / low, (band + .5) / SpectrumAnalyzer.BAND_COUNT);
        int bin = (int) Math.round(center * N / rateHz);
        byte[] fft = new byte[N];
        fft[2 * bin] = 120;
        float[] levels = analyze(new SpectrumAnalyzer(), fft, rateHz);
        assertTrue("Tone should reach band " + band + " at " + rateHz + " Hz", levels[band] > .8f);
      }
    }
  }

  @Test public void highFrequencyToneMapsToTrebleWithActualSampleRate() {
    for (int rateHz : new int[]{44100, 48000}) {
      SpectrumAnalyzer analyzer = new SpectrumAnalyzer();
      int bin = Math.round(12000f * N / rateHz);
      byte[] fft = new byte[N];
      fft[2 * bin] = 120;
      float[] levels = analyze(analyzer, fft, rateHz);
      int expected = (int) (Math.log((bin * rateHz / (float) N) / analyzer.getLowHz()) /
        Math.log(analyzer.getHighHz() / analyzer.getLowHz()) * SpectrumAnalyzer.BAND_COUNT);
      int actual = 0;
      for (int i = 1; i < levels.length; i++) if (levels[i] > levels[actual]) actual = i;
      assertTrue("12 kHz belongs near the right edge", actual > 55);
      assertTrue("Use Fs/N and milliHertz conversion", Math.abs(expected - actual) <= 1);
    }
  }

  @Test public void dcIsIgnoredAndNyquistUsesItsSpecialPackedPosition() {
    byte[] dcOnly = new byte[N];
    dcOnly[0] = 127;
    assertEquals(0f, max(analyze(new SpectrumAnalyzer(), dcOnly, 32000)), 0f);
    byte[] nyquist = new byte[N];
    nyquist[1] = 127;
    float[] levels = analyze(new SpectrumAnalyzer(), nyquist, 32000);
    assertEquals(1f, levels[levels.length - 1], .0001f);
    assertEquals(0f, levels[0], 0f);
  }

  @Test public void lowerSampleRatesClampTheAxisToNyquist() {
    SpectrumAnalyzer analyzer = new SpectrumAnalyzer();
    byte[] fft = new byte[N];
    fft[1] = 100;
    float[] levels = analyze(analyzer, fft, 8000);
    assertEquals(4000f, analyzer.getHighHz(), .001f);
    assertEquals(1f, levels[63], .001f);
  }

  @Test public void silenceAndQuantizationNoiseAreNeverInventedIntoBands() {
    SpectrumAnalyzer analyzer = new SpectrumAnalyzer();
    byte[] loud = new byte[N];
    loud[40] = 100;
    analyze(analyzer, loud, 48000);
    for (int frame = 0; frame < 100; frame++) {
      assertEquals(0f, max(analyze(analyzer, new byte[N], 48000)), 0f);
    }
    byte[] oneStep = new byte[N];
    for (int i = 2; i < oneStep.length; i++) oneStep[i] = 1;
    assertEquals(0f, max(analyze(analyzer, oneStep, 48000)), 0f);
  }

  @Test public void adaptiveReferenceMakesQuietAndLoudMusicUseFullHeight() {
    for (byte amplitude : new byte[]{8, 16, 64, 120}) {
      byte[] fft = new byte[N];
      fft[40] = amplitude;
      assertEquals(1f, max(analyze(new SpectrumAnalyzer(), fft, 48000)), .0001f);
    }
  }

  @Test public void sharedGainPreservesDifferencesBetweenFrequencyBands() {
    byte[] fft = new byte[N];
    fft[40] = 96;
    fft[400] = 12;
    SpectrumAnalyzer analyzer = new SpectrumAnalyzer();
    float[] levels = analyze(analyzer, fft, 48000);
    double quietHz = 200 * 48000d / N;
    int quietBand = (int) (Math.log(quietHz / analyzer.getLowHz()) /
      Math.log(analyzer.getHighHz() / analyzer.getLowHz()) * SpectrumAnalyzer.BAND_COUNT);
    assertEquals(1f, max(levels), .0001f);
    assertEquals(1f + 20f * (float) Math.log10(12f / 96f) / 40f, levels[quietBand], .0001f);
  }
}
