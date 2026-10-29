import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { SourceTextModule, createContext } from 'node:vm';

const source = file => readFile(new URL('../' + file, import.meta.url), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
async function popupHarness(initial = { status: 'idle' }, preferences = {}) {
  let state = initial;
  let notify;
  const calls = [];
  const nodes = new Map();
  const storage = new Map(Object.entries(preferences));
  const canvasCalls = [];
  const ctx = Object.fromEntries(['clearRect', 'beginPath', 'moveTo', 'lineTo', 'stroke', 'fillText', 'setLineDash'].map(name => [name, (...args) => canvasCalls.push([name, ...args])]));
  for (const [, id] of (await source('popup.html')).matchAll(/\bid="([^"]+)"/g)) {
    nodes.set(id, {
      hidden: false, disabled: false, textContent: '', checked: false, value: id === 'quality' ? '192000' : '',
      style: {}, classList: { toggle() {} }, listeners: {}, width: 332, height: 150,
      setAttribute() {}, addEventListener(event, callback) { this.listeners[event] = callback; },
      getContext() { return ctx; }
    });
  }
  const chrome = {
    tabs: { async query() { return [{ title: 'Audio', url: 'https://example.test' }]; } },
    runtime: {
      onMessage: { addListener(fn) { notify = fn; } },
      async sendMessage(message) {
        calls.push(message);
        if (message.type === 'SET_DIAGNOSTICS') state = { ...state, diagnosticsEnabled: message.enabled };
        return { ok: true, data: state };
      }
    }
  };
  const context = createContext({
    console, chrome, URL, document: { getElementById(id) { assert.ok(nodes.has(id), `Missing HTML element ${id}`); return nodes.get(id); } },
    window: { close() {} }, setInterval() {},
    localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value) }
  });
  const common = new SourceTextModule(await source('common.js'), { context });
  const view = new SourceTextModule(await source('diagnostics-view.js'), { context });
  const popup = new SourceTextModule(await source('popup.js'), { context });
  await popup.link(name => ({ './common.js': common, './diagnostics-view.js': view })[name]);
  await popup.evaluate();
  return {
    nodes, calls, storage, canvasCalls,
    update(next) { state = next; notify({ target: 'ui', type: 'STATE_CHANGED', state }); },
    async click(id, event = 'click') { nodes.get(id).listeners[event](); await flush(); await flush(); }
  };
}
const live = {
  status: 'recording', title: 'Audio', bitrate: 192000, outputKbps: 173.25, diagnosticsEnabled: true,
  diagnostics: { sampleRate: 48000, fftSize: 8192, binHz: 48000 / 8192, activeFrames: 50, frames: 60,
    status: 'estimated', signalActive: true, cutoffHz: 16000, spectrum: Array(512).fill(-40) }
};

test('fresh popup defaults diagnostics off and enabling idle is remembered for START', async () => {
  const h = await popupHarness();
  assert.equal(h.nodes.get('diagnostics-enabled').checked, false);
  assert.equal(h.nodes.get('diagnostics-details').hidden, true);
  assert.equal(h.canvasCalls.length, 0);
  h.nodes.get('diagnostics-enabled').checked = true;
  await h.click('diagnostics-enabled', 'change');
  assert.equal(h.storage.get('diagnosticsEnabled'), 'true');
  await h.click('record');
  assert.equal(h.calls.find(call => call.type === 'START').diagnosticsEnabled, true);
});

test('reopening popup uses active session state and renders capture/output measurements', async () => {
  const h = await popupHarness(live);
  assert.equal(h.nodes.get('diagnostics-enabled').checked, true);
  assert.equal(h.nodes.get('spectrum').hidden, false);
  assert.match(h.nodes.get('diagnostics-context').textContent, /48,000 Hz/);
  assert.match(h.nodes.get('diagnostics-cutoff').textContent, /16.0 kHz/);
  assert.equal(h.nodes.get('requested-bitrate').textContent, '192.0 kbps');
  assert.equal(h.nodes.get('output-bitrate').textContent, '173.3 kbps');
  assert.ok(h.canvasCalls.length > 0);
  await h.click('reset-diagnostics');
  assert.equal(h.calls.some(call => call.type === 'RESET_DIAGNOSTICS'), true);
});

test('live toggle sends control and hides graph; waiting and completion display without FFT arrays', async () => {
  const h = await popupHarness(live);
  h.nodes.get('diagnostics-enabled').checked = false;
  await h.click('diagnostics-enabled', 'change');
  assert.equal(h.calls.find(call => call.type === 'SET_DIAGNOSTICS').enabled, false);
  assert.equal(h.nodes.get('spectrum').hidden, true);
  h.update({ status: 'waiting', diagnosticsEnabled: true });
  assert.match(h.nodes.get('diagnostics-context').textContent, /no audio captured/);
  const { spectrum, ...summary } = live.diagnostics;
  h.update({ status: 'idle', lastResult: { ...live, durationMs: 12000, bytes: 260000, diagnostics: summary } });
  assert.equal(h.nodes.get('spectrum').hidden, true);
  assert.match(h.nodes.get('diagnostics-context').textContent, /Last recording/);
  assert.equal(h.nodes.get('output-bitrate').textContent, '173.3 kbps');
});

test('diagnostics errors leave stop control enabled and older recordings remain renderable', async () => {
  const h = await popupHarness({ ...live, diagnosticsEnabled: false, diagnostics: null, diagnosticsError: 'Diagnostics unavailable' });
  assert.equal(h.nodes.get('diagnostics-error').hidden, false);
  assert.equal(h.nodes.get('stop').disabled, false);
  h.update({ status: 'idle', lastResult: { title: 'Old recording', bytes: 500, durationMs: 2000 } });
  assert.equal(h.nodes.get('spectrum').hidden, true);
  assert.equal(h.nodes.get('diagnostics-error').hidden, true);
});
