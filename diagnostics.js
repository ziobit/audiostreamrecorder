// Signal observations only: this module never guesses a source codec or bitrate.
export const FFT_SIZE = 8192;
export const SAMPLE_INTERVAL_MS = 200;
export const MIN_ACTIVE_FRAMES = 50;
const FLOOR_DB = -100;
const SILENCE_DB = -65;
const ABSOLUTE_THRESHOLD_DB = -80;
const RELATIVE_THRESHOLD_DB = 50;
const MIN_OCCUPANCY = 0.1;
const DISPLAY_BINS = 512;

export function measuredOutputKbps(bytes, durationMs) {
  // kb/s = bytes * 8 / milliseconds; tiny recordings are dominated by headers.
  if (!Number.isFinite(bytes) || !Number.isFinite(durationMs) || bytes <= 0 || durationMs < 1000) return null;
  return bytes * 8 / durationMs;
}

export class SpectrumAccumulator {
  constructor(sampleRate, fftSize = FFT_SIZE) {
    this.sampleRate = sampleRate;
    this.fftSize = fftSize;
    this.binHz = sampleRate / fftSize;
    this.hits = new Float64Array(fftSize / 2);
    this.frames = 0;
    this.activeFrames = 0;
    this.signalActive = false;
    this.spectrum = [];
    this.estimate = { status: 'collecting', cutoffHz: null };
  }

  add(values) {
    if (values.length !== this.hits.length) throw new Error('Unexpected FFT bin count.');
    this.frames++;
    let peak = FLOOR_DB;
    // Ignore DC and sub-20 Hz drift for the bandwidth estimate.
    const first = Math.max(1, Math.ceil(20 / this.binHz));
    for (let i = first; i < values.length; i++) {
      if (Number.isFinite(values[i])) peak = Math.max(peak, values[i]);
    }
    this.spectrum = Array.from({ length: DISPLAY_BINS }, (_, index) => {
      const start = Math.floor(index * values.length / DISPLAY_BINS);
      const end = Math.floor((index + 1) * values.length / DISPLAY_BINS);
      let db = FLOOR_DB;
      for (let i = start; i < end; i++) if (Number.isFinite(values[i])) db = Math.max(db, values[i]);
      return Math.round(Math.min(0, db));
    });
    this.signalActive = peak > SILENCE_DB;
    if (!this.signalActive) return;
    this.activeFrames++;
    const threshold = Math.max(ABSOLUTE_THRESHOLD_DB, peak - RELATIVE_THRESHOLD_DB);
    for (let i = first; i < values.length; i++) {
      if (Number.isFinite(values[i]) && values[i] >= threshold) this.hits[i]++;
    }
    if (this.activeFrames < MIN_ACTIVE_FRAMES) return;
    const required = Math.ceil(this.activeFrames * MIN_OCCUPANCY);
    let occupied = 0;
    let run = 0;
    let highest = 0;
    for (let i = first; i < this.hits.length; i++) {
      if (this.hits[i] >= required) {
        occupied++;
        run++;
        // Reject isolated bins; a supporting band must be at least three bins wide.
        if (run >= 3) highest = i;
      } else {
        run = 0;
      }
    }
    if (occupied < 128 || !highest) {
      this.estimate = { status: 'sparse', cutoffHz: null };
    } else if (highest * this.binHz >= this.sampleRate / 2 * 0.98) {
      this.estimate = { status: 'full-band', cutoffHz: null };
    } else {
      this.estimate = { status: 'estimated', cutoffHz: highest * this.binHz };
    }
  }

  snapshot(includeSpectrum = true) {
    return {
      sampleRate: this.sampleRate, fftSize: this.fftSize, binHz: this.binHz,
      frames: this.frames, activeFrames: this.activeFrames,
      signalActive: this.signalActive, ...this.estimate,
      ...(includeSpectrum ? { spectrum: this.spectrum } : {})
    };
  }
}
