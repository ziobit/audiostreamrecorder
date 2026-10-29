import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { SourceTextModule, createContext } from 'node:vm';

const source = file => readFile(new URL('../' + file, import.meta.url), 'utf8');
async function load(file) {
  const module = new SourceTextModule(await source(file), { context: createContext({}) });
  await module.link(() => {});
  await module.evaluate();
  return module.namespace;
}
const { SpectrumAccumulator, measuredOutputKbps, FFT_SIZE } = await load('diagnostics.js');
const { cutoffLabel, formatKbps, drawSpectrum } = await load('diagnostics-view.js');

function band(sampleRate, cutoff, level = -35) {
  const values = new Float32Array(FFT_SIZE / 2).fill(-100);
  const step = sampleRate / FFT_SIZE;
  for (let i = Math.ceil(20 / step); i < values.length && i * step <= cutoff; i++) values[i] = level;
  return values;
}
function feed(acc, values, frames = 50) { for (let i = 0; i < frames; i++) acc.add(values); }

for (const sampleRate of [44100, 48000, 96000]) {
  for (const cutoff of [4000, 10000, 16000]) {
    test(`sustained ${cutoff} Hz bandwidth at ${sampleRate} Hz is located to one FFT bin`, () => {
      const acc = new SpectrumAccumulator(sampleRate);
      const values = band(sampleRate, cutoff);
      feed(acc, values, 49);
      assert.equal(acc.snapshot().cutoffHz, null);
      assert.equal(acc.snapshot().status, 'collecting');
      acc.add(values);
      const data = acc.snapshot();
      assert.equal(data.status, 'estimated');
      assert.ok(Math.abs(data.cutoffHz - cutoff) < data.binHz);
      assert.equal(data.spectrum.length, 512);
    });
  }
}

test('silence, invalid bins and subaudible drift do not create a cutoff', () => {
  const acc = new SpectrumAccumulator(48000);
  for (const value of [-Infinity, NaN, Infinity, -90]) {
    const values = new Float32Array(4096).fill(value);
    values[0] = 0; values[1] = -10;
    feed(acc, values);
  }
  const data = acc.snapshot();
  assert.equal(data.frames, 200);
  assert.equal(data.activeFrames, 0);
  assert.equal(data.cutoffHz, null);
  assert.ok(data.spectrum.every(Number.isFinite));
  assert.match(cutoffLabel(data), /Silence/);
  assert.match(cutoffLabel(data, false), /Insufficient active audio/);
  assert.match(cutoffLabel({ ...data, paused: true }), /suspended/);
});

test('a narrow tone is not reported as a broadband cutoff', () => {
  const acc = new SpectrumAccumulator(48000);
  const values = new Float32Array(4096).fill(-100);
  values.fill(-20, 169, 174);
  feed(acc, values);
  assert.equal(acc.snapshot().status, 'sparse');
  assert.equal(acc.snapshot().cutoffHz, null);
});

test('full bandwidth returns no detectable cutoff rather than a source sampling rate', () => {
  const acc = new SpectrumAccumulator(48000);
  feed(acc, band(48000, 24000));
  assert.equal(acc.snapshot().status, 'full-band');
  assert.equal(acc.snapshot().cutoffHz, null);
  assert.match(cutoffLabel(acc.snapshot()), /no clear cutoff/);
});

test('isolated high-frequency bins and a short burst do not inflate accumulated bandwidth', () => {
  const acc = new SpectrumAccumulator(48000);
  const values = band(48000, 10000);
  values[3700] = -10;
  feed(acc, values, 96);
  feed(acc, band(48000, 22000), 4);
  assert.ok(Math.abs(acc.snapshot().cutoffHz - 10000) < acc.binHz);
});

test('quiet frames preserve the estimate but do not count as active evidence', () => {
  const acc = new SpectrumAccumulator(48000);
  feed(acc, band(48000, 16000));
  const cutoff = acc.snapshot().cutoffHz;
  feed(acc, new Float32Array(4096).fill(-Infinity), 100);
  assert.equal(acc.snapshot().activeFrames, 50);
  assert.equal(acc.snapshot().cutoffHz, cutoff);
  assert.equal(acc.snapshot().signalActive, false);
  assert.match(cutoffLabel(acc.snapshot()), /retaining earlier/);
  assert.doesNotMatch(cutoffLabel(acc.snapshot(), false), /Quiet now/);
});

test('relative threshold tolerates level changes, absolute threshold rejects very weak high bands', () => {
  for (const level of [-15, -45, -60]) {
    const acc = new SpectrumAccumulator(48000);
    const values = band(48000, 10000, level);
    values.fill(-90, 2000, 3500);
    feed(acc, values);
    assert.ok(Math.abs(acc.snapshot().cutoffHz - 10000) < acc.binHz);
  }
});

test('display compression preserves narrow peaks and stored summary contains no arrays', () => {
  const acc = new SpectrumAccumulator(48000);
  const values = new Float32Array(4096).fill(-100);
  values[801] = -12.3;
  acc.add(values);
  assert.equal(acc.snapshot().spectrum[100], -12);
  assert.equal(acc.snapshot(false).spectrum, undefined);
  assert.throws(() => acc.add(new Float32Array(128)), /bin count/);
});

test('bitrate uses decimal kbps and rejects missing or too-short observations', () => {
  assert.equal(measuredOutputKbps(48000, 2000), 192);
  for (const [bytes, duration] of [[0, 2000], [-1, 2000], [100, 0], [100, 999], [NaN, 2000], [100, Infinity]]) {
    assert.equal(measuredOutputKbps(bytes, duration), null);
  }
  assert.equal(formatKbps(null), 'Not enough data yet');
  assert.equal(formatKbps(192.01), '192.0 kbps');
});

test('spectrum chart draws finite coordinates and maps cutoff to capture Nyquist', () => {
  const paths = [];
  const labels = [];
  const ctx = {
    clearRect() {}, beginPath() {}, stroke() {}, setLineDash() {},
    moveTo(x, y) { paths.push([x, y]); }, lineTo(x, y) { paths.push([x, y]); },
    fillText(text) { labels.push(text); }
  };
  drawSpectrum({ width: 332, height: 150, getContext: () => ctx }, {
    sampleRate: 48000, spectrum: Array(512).fill(-50), cutoffHz: 12000
  });
  assert.ok(paths.every(point => point.every(Number.isFinite)));
  assert.equal(paths.at(-1)[0], 177.5);
  assert.ok(labels.includes('24.0k'));
});
