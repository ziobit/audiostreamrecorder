// Runs the actual recorder and service-worker modules with Chrome/media mocks.
// These tests check state/error handling, not native Chrome capture or IndexedDB.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { SourceTextModule, SyntheticModule, createContext } from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = async file => readFile(new URL('../' + file, import.meta.url), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const ID = 'test-extension';
const ROOT = `chrome-extension://${ID}/`;

function channel(listener, message, sender) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Message was not answered: ' + message.type)), 2000);
    const handled = listener(message, sender, response => { clearTimeout(timer); resolve(response); });
    if (handled !== true) { clearTimeout(timer); reject(new Error('Message was rejected: ' + message.type)); }
  });
}

async function recorderHarness(options = {}) {
  let listener;
  const records = new Map(options.records || []);
  const chunks = new Map();
  const notices = [];
  const events = [];
  const captures = [];
  const blobUrls = new Set();
  let nextBlob = 0;
  let failedWrites = false;
  const db = {
    async recoverRecordings() {
      events.push('recover');
      for (const record of records.values()) if (record.status === 'recording') record.status = 'interrupted';
    },
    async createRecording(record) { records.set(record.id, { ...record }); chunks.set(record.id, []); },
    async getRecording(id) { return records.get(id); },
    async listRecordings() { return [...records.values()]; },
    async deleteRecording(id) { records.delete(id); chunks.delete(id); },
    async updateRecording(id, fields) {
      events.push('final-metadata');
      const record = records.get(id);
      Object.assign(record, fields);
      return { ...record };
    },
    async appendChunk(id, index, blob, durationMs) {
      if (options.writeDelay) await new Promise(resolve => setTimeout(resolve, options.writeDelay));
      if (options.failAfter !== undefined && chunks.get(id).length >= options.failAfter) {
        failedWrites = true;
        throw new DOMException('Quota exceeded', 'QuotaExceededError');
      }
      assert.equal(failedWrites, false, 'No writes should follow a storage failure');
      events.push('chunk:' + index);
      chunks.get(id).push(blob);
      const record = records.get(id);
      record.bytes += blob.size;
      record.chunkCount++;
      record.durationMs = durationMs;
      return { ...record };
    },
    async getRecordingBlob(id) {
      const recording = records.get(id);
      if (!recording) throw new Error('Recording is missing');
      if (recording.status === 'recording') throw new Error('Still recording');
      return { recording, blob: new Blob(chunks.get(id), { type: recording.mimeType }) };
    }
  };
  class Recorder {
    static isTypeSupported() { return !options.unsupported; }
    constructor(stream, settings) { this.stream = stream; this.mimeType = settings.mimeType; this.state = 'inactive'; captures.push(this); }
    start(timeslice) { this.timeslice = timeslice; this.state = 'recording'; }
    data(text) { this.ondataavailable?.({ data: new Blob([text]) }); }
    stop() {
      assert.notEqual(this.state, 'inactive');
      this.state = 'inactive';
      queueMicrotask(() => { this.data('final'); this.onstop?.(); });
    }
    error() {
      this.state = 'inactive';
      this.onerror?.({ error: new Error('Device stopped') });
      queueMicrotask(() => { this.data('tail'); this.onstop?.(); });
    }
  }
  const track = { onended: null, stopped: false, stop() { this.stopped = true; } };
  const stream = { getAudioTracks: () => [track], getTracks: () => [track] };
  class AudioContext {
    state = 'suspended';
    destination = {};
    createMediaStreamSource() { return { connect() { events.push('monitor-source'); }, disconnect() {} }; }
    createAnalyser() { return { fftSize: 256, connect() { events.push('monitor-speakers'); }, getByteTimeDomainData(array) { array.fill(128); } }; }
    async resume() { events.push('monitor-resumed'); this.state = 'running'; }
    async close() { events.push('context-closed'); this.state = 'closed'; }
  }
  const chrome = { runtime: {
    id: ID, getURL: path => ROOT + path,
    onMessage: { addListener(fn) { listener = fn; } },
    async sendMessage(message) { notices.push(message); return { ok: true }; }
  } };
  const context = createContext({
    chrome, console, Blob, DOMException, performance, crypto: webcrypto, queueMicrotask, TextEncoder,
    MediaRecorder: Recorder, AudioContext,
    navigator: { mediaDevices: { async getUserMedia(constraints) {
      assert.equal(constraints.video, false);
      assert.equal(constraints.audio.mandatory.chromeMediaSource, 'tab');
      if (options.denyCapture) throw new Error('Permission denied');
      return stream;
    } } },
    URL: {
      createObjectURL() { const url = 'blob:' + (++nextBlob); blobUrls.add(url); return url; },
      revokeObjectURL(url) { blobUrls.delete(url); }
    }
  });
  const dbModule = new SyntheticModule(Object.keys(db), function () {
    for (const [name, value] of Object.entries(db)) this.setExport(name, value);
  }, { context });
  const commonModule = new SourceTextModule(await source('common.js'), { context });
  const module = new SourceTextModule(await source('offscreen.js'), { context });
  await module.link(name => name === './db.js' ? dbModule : commonModule);
  await module.evaluate();
  async function raw(type, data = {}, sender = { id: ID, url: ROOT + 'background.js' }) {
    return channel(listener, { target: 'offscreen', type, ...data }, sender);
  }
  async function cmd(type, data = {}) {
    const response = await raw(type, data);
    if (!response.ok) throw new Error(response.error);
    return response.data;
  }
  const start = () => cmd('START', { streamId: 'single-use-id', tabId: 42, title: 'My audio', host: 'example.test', bitrate: 192000 });
  return { cmd, raw, start, records, chunks, captures, notices, events, track, blobUrls };
}

async function workerHarness(options = {}) {
  let listener;
  let onChanged;
  let onRemoved;
  let onUpdated;
  let contexts = options.initialState ? 1 : 0;
  let creates = 0;
  let starts = 0;
  let exportKey = 0;
  let state = options.initialState || { status: 'idle' };
  let capturesRequested = 0;
  const nativeTab = {
    id: 42, title: 'Audio page', url: options.tabUrl || 'https://example.test/player?token=private',
    audible: options.audible || false, mutedInfo: { muted: options.muted || false }
  };
  const recorderCalls = [];
  const badges = [];
  const downloads = [];
  const chrome = {
    runtime: {
      id: ID, getURL: path => ROOT + path,
      async getContexts() { return contexts ? [{}] : []; },
      onMessage: { addListener(fn) { listener = fn; } },
      onStartup: { addListener() {} },
      async sendMessage(message) {
        if (message.target === 'ui') return;
        recorderCalls.push(message);
        let data;
        switch (message.type) {
          case 'STATUS': data = state; break;
          case 'ARM': state = { ...message, status: 'waiting', waitId: 'waiting-1' }; data = state; break;
          case 'START':
            if (message.waitId && (state.status !== 'waiting' || state.waitId !== message.waitId)) {
              return { ok: false, error: 'This pending recording was cancelled.' };
            }
            starts++;
            state = {
              status: 'recording', id: 'r1', tabId: message.tabId || state.tabId,
              autoStopOnSilence: message.autoStopOnSilence ?? state.autoStopOnSilence,
              heardAudio: message.wasAudible === true
            };
            data = state; break;
          case 'AUDIO_STATE':
            data = null;
            if (state.status !== 'recording' || state.id !== message.id || !state.autoStopOnSilence) break;
            if (message.audible === true) state.heardAudio = true;
            if (message.audible === false && state.heardAudio) {
              data = { id: state.id, bytes: 100 };
              state = { status: 'idle' };
            }
            break;
          case 'AUTO_SAVE_RESULT': state.notice = message.note; state.noticeIsError = message.error; data = null; break;
          case 'STOP':
            data = state.status === 'waiting' ? { cancelled: true } : { id: 'r1', bytes: 100 };
            state = { status: 'idle' }; break;
          case 'CANCEL_WAIT':
            if (state.status === 'waiting' && (!message.waitId || message.waitId === state.waitId) &&
                (message.tabId === undefined || message.tabId === state.tabId)) {
              state = { status: 'idle', notice: message.note };
            }
            data = null; break;
          case 'EXPORT': data = { key: ++exportKey, url: 'blob:test', filename: 'audio.webm' }; break;
          default: data = null;
        }
        return { ok: true, data };
      }
    },
    offscreen: { async createDocument(settings) {
      assert.equal(settings.url, 'offscreen.html');
      assert.deepEqual(Array.from(settings.reasons), ['USER_MEDIA', 'BLOBS']);
      creates++; await flush(); contexts = 1;
    } },
    action: {
      async setBadgeBackgroundColor() {}, async setBadgeText(value) { badges.push(value.text); }, async setTitle() {}
    },
    tabs: {
      async query() { return [nativeTab]; },
      async get(id) { assert.equal(id, 42); return nativeTab; },
      async create() {}, onRemoved: { addListener(fn) { onRemoved = fn; } },
      onUpdated: { addListener(fn) { onUpdated = fn; } }
    },
    tabCapture: { async getMediaStreamId() {
      capturesRequested++;
      if (options.captureError) throw new Error('Capture permission expired');
      if (options.captureGate) await options.captureGate;
      return 'stream-id';
    } },
    downloads: {
      async download(settings) { downloads.push(settings); if (options.cancelDownload) throw new Error('User canceled'); return 7; },
      async search() { return [{ id: 7, state: options.downloadState || 'in_progress' }]; },
      onChanged: { addListener(fn) { onChanged = fn; } }
    }
  };
  const context = createContext({ chrome, URL, console });
  const module = new SourceTextModule(await source('background.js'), { context });
  await module.link(() => { throw new Error('Unexpected import'); });
  await module.evaluate();
  const raw = (type, data = {}) => channel(listener, { target: 'background', type, ...data }, { id: ID, url: ROOT + 'popup.html' });
  return {
    raw, recorderCalls, badges, nativeTab, downloads,
    onChanged: (...args) => onChanged(...args), onRemoved: (...args) => onRemoved(...args),
    onUpdated: (...args) => onUpdated(...args),
    creates: () => creates, starts: () => starts, capturesRequested: () => capturesRequested
  };
}

test('recording uses tab audio, monitors speakers, and persists final chunk before metadata', async () => {
  const h = await recorderHarness({ writeDelay: 10 });
  const state = await h.start();
  assert.equal(state.status, 'recording');
  assert.equal(h.captures[0].timeslice, 2000);
  h.captures[0].data('first');
  h.captures[0].data('second');
  const record = await h.cmd('STOP');
  assert.equal(record.status, 'complete');
  assert.equal(record.bytes, 16);
  assert.equal(record.chunkCount, 3);
  assert.equal((await h.cmd('STATUS')).status, 'idle');
  assert.ok(h.events.includes('monitor-speakers'));
  assert.ok(h.events.indexOf('chunk:2') < h.events.indexOf('final-metadata'));
  assert.equal(h.track.stopped, true);
});

test('duplicate starts are rejected without damaging the running recorder', async () => {
  const h = await recorderHarness();
  await h.start();
  await assert.rejects(h.start(), /already running/);
  assert.equal(h.captures.length, 1);
  await h.cmd('STOP');
});

test('source tab closure finalizes the correct recording only', async () => {
  const h = await recorderHarness();
  await h.start();
  await h.cmd('TAB_CLOSED', { tabId: 99 });
  assert.equal((await h.cmd('STATUS')).status, 'recording');
  const result = await h.cmd('TAB_CLOSED', { tabId: 42 });
  assert.equal(result.note, 'The source tab was closed.');
  assert.equal(result.status, 'complete');
});

test('Chrome ending the captured track also retains the recording', async () => {
  const h = await recorderHarness();
  await h.start();
  h.track.onended();
  await flush();
  const state = await h.cmd('STATUS');
  assert.equal(state.status, 'idle');
  assert.ok(state.lastResult.bytes > 0);
});

test('a failed capture cleans up and permits a new start attempt', async () => {
  const h = await recorderHarness({ denyCapture: true });
  await assert.rejects(h.start(), /Permission denied/);
  assert.equal((await h.cmd('STATUS')).status, 'idle');
  assert.equal(h.records.size, 0);
  await assert.rejects(h.start(), /Permission denied/);
});

test('unsupported encoder reports a clear error before capturing', async () => {
  const h = await recorderHarness({ unsupported: true });
  await assert.rejects(h.start(), /update Chrome/);
  assert.equal(h.captures.length, 0);
});

test('storage exhaustion stops and keeps earlier committed audio', async () => {
  const h = await recorderHarness({ failAfter: 1 });
  await h.start();
  h.captures[0].data('saved');
  await flush();
  h.captures[0].data('lost');
  await flush();
  await flush();
  const state = await h.cmd('STATUS');
  assert.equal(state.status, 'idle');
  assert.equal(state.lastResult.status, 'interrupted');
  assert.equal(state.lastResult.bytes, 5);
  assert.match(state.lastResult.note, /storage is full/);
});

test('encoder failure still flushes its last available audio', async () => {
  const h = await recorderHarness({ writeDelay: 5 });
  await h.start();
  h.captures[0].data('first');
  h.captures[0].error();
  const record = await h.cmd('STOP');
  assert.equal(record.status, 'interrupted');
  assert.equal(record.bytes, 9);
  assert.equal(record.chunkCount, 2);
});

test('stopping twice shares finalization and never writes metadata twice', async () => {
  const h = await recorderHarness({ writeDelay: 10 });
  await h.start();
  const [a, b] = await Promise.all([h.cmd('STOP'), h.cmd('STOP')]);
  assert.equal(a.id, b.id);
  assert.equal(h.events.filter(item => item === 'final-metadata').length, 1);
});

test('active recordings cannot be deleted or exported', async () => {
  const h = await recorderHarness();
  const { id } = await h.start();
  await assert.rejects(h.cmd('DELETE', { id }), /Stop/);
  await assert.rejects(h.cmd('EXPORT', { id }), /Stop/);
  await h.cmd('STOP');
});

test('export URLs survive until download completion and local audio is retained', async () => {
  const h = await recorderHarness();
  await h.start();
  const record = await h.cmd('STOP');
  const exported = await h.cmd('EXPORT', { id: record.id });
  await h.cmd('TRACK_EXPORT', { key: exported.key, downloadId: 7 });
  assert.equal(h.blobUrls.size, 1);
  await assert.rejects(h.cmd('DELETE', { id: record.id }), /download/);
  await h.cmd('RELEASE_DOWNLOAD', { downloadId: 7 });
  assert.equal(h.blobUrls.size, 0);
  assert.equal(h.records.size, 1);
  await h.cmd('DELETE', { id: record.id });
  assert.equal(h.records.size, 0);
});

test('a canceled export releases its temporary URL without deleting audio', async () => {
  const h = await recorderHarness();
  await h.start();
  const record = await h.cmd('STOP');
  const exported = await h.cmd('EXPORT', { id: record.id });
  await h.cmd('RELEASE_EXPORT', { key: exported.key });
  assert.equal(h.blobUrls.size, 0);
  assert.equal(h.records.size, 1);
});

test('service-worker messages work when Chrome omits sender.url', async () => {
  const h = await recorderHarness();
  const response = await h.raw('STATUS', {}, { id: ID });
  assert.equal(response.ok, true);
});

test('on a new recorder document, unfinished metadata is recovered before LIST', async () => {
  const h = await recorderHarness({ records: [['old', { id: 'old', status: 'recording', bytes: 100 }]] });
  const result = await h.cmd('LIST');
  assert.equal(result.recordings[0].status, 'interrupted');
  assert.equal(h.events[0], 'recover');
});

test('worker creates one offscreen document for concurrent requests', async () => {
  const h = await workerHarness();
  await Promise.all([h.raw('STATUS'), h.raw('STATUS')]);
  assert.equal(h.creates(), 1);
});

test('worker prevents simultaneous starts and sends only hostname, not URL tokens', async () => {
  const h = await workerHarness();
  const results = await Promise.all([h.raw('START', { bitrate: 999 }), h.raw('START')]);
  assert.equal(results.filter(result => result.ok).length, 1);
  assert.equal(h.starts(), 1);
  const message = h.recorderCalls.find(item => item.type === 'START');
  assert.equal(message.host, 'example.test');
  assert.equal(message.bitrate, 192000);
  assert.equal(JSON.stringify(message).includes('private'), false);
});

test('Chrome internal pages are rejected before capture', async () => {
  const h = await workerHarness({ tabUrl: 'chrome://extensions/' });
  const response = await h.raw('START');
  assert.equal(response.ok, false);
  assert.match(response.error, /audio website/);
  assert.equal(h.starts(), 0);
});

test('canceling Save As preserves recording and returns a useful message', async () => {
  const h = await workerHarness({ cancelDownload: true });
  const response = await h.raw('STOP_SAVE');
  assert.equal(response.ok, true);
  assert.equal(response.data.id, 'r1');
  assert.match(response.data.warning, /still in Recordings/);
  assert.ok(h.recorderCalls.some(item => item.type === 'RELEASE_EXPORT'));
});

test('worker reconciles downloads that finish before their IDs are registered', async () => {
  const h = await workerHarness({ downloadState: 'complete' });
  const response = await h.raw('DOWNLOAD', { id: 'r1' });
  assert.equal(response.ok, true);
  assert.ok(h.recorderCalls.some(item => item.type === 'TRACK_EXPORT'));
  assert.ok(h.recorderCalls.some(item => item.type === 'RELEASE_DOWNLOAD'));
});

test('download completion after worker startup releases offscreen Blob URLs', async () => {
  const h = await workerHarness();
  await h.raw('STATUS');
  h.onChanged({ id: 7, state: { current: 'complete' } });
  await flush();
  assert.ok(h.recorderCalls.some(item => item.type === 'RELEASE_DOWNLOAD' && item.downloadId === 7));
});

test('manifest has only local resources and the four necessary permissions', async () => {
  const manifest = JSON.parse(await source('manifest.json'));
  assert.equal(manifest.manifest_version, 3);
  assert.equal(manifest.minimum_chrome_version, '116');
  assert.deepEqual(manifest.permissions, ['activeTab', 'tabCapture', 'offscreen', 'downloads']);
  assert.equal(manifest.host_permissions, undefined);
  assert.equal(manifest.content_scripts, undefined);
  assert.equal(manifest.externally_connectable, undefined);
  for (const file of ['popup.html', 'recordings.html', 'offscreen.html']) {
    const html = await source(file);
    assert.equal(/<script[^>]*src=["']https?:/i.test(html), false);
    assert.equal(/\sonclick=/i.test(html), false);
  }
});

test('download filenames handle Thai, emoji, slashes and Windows-reserved punctuation', async () => {
  const context = createContext({ TextEncoder });
  const module = new SourceTextModule(await source('common.js'), { context });
  await module.link(() => {});
  await module.evaluate();
  for (const title of ['../a:b?c*<>|"\\/\n', 'เพลง'.repeat(80), '🎵'.repeat(100)]) {
    const filename = module.namespace.recordingFilename({ title, startedAt: Date.now() });
    assert.equal(/[<>:"/\\|?*\u0000-\u001f]/.test(filename), false);
    assert.ok(new TextEncoder().encode(filename).length < 255);
    assert.ok(filename.endsWith('.webm'));
    assert.equal(filename.includes('\ufffd'), false);
  }
});

const pendingOptions = { tabId: 42, title: 'Waiting audio', host: 'example.test', bitrate: 128000 };

test('arming creates no recorder, stream, or stored recording', async () => {
  const h = await recorderHarness();
  const state = await h.cmd('ARM', pendingOptions);
  assert.equal(state.status, 'waiting');
  assert.equal(state.elapsedMs, 0);
  assert.equal(h.captures.length, 0);
  assert.equal(h.records.size, 0);
  assert.equal(h.events.includes('monitor-source'), false);
  const stopped = await h.cmd('STOP');
  assert.equal(stopped.cancelled, true);
  assert.equal((await h.cmd('STATUS')).status, 'idle');
  assert.equal(h.records.size, 0);
});

test('native trigger consumes the matching pending request and keeps its options', async () => {
  const h = await recorderHarness();
  const { waitId } = await h.cmd('ARM', pendingOptions);
  const state = await h.cmd('START', { waitId, streamId: 'fresh-id' });
  assert.equal(state.status, 'recording');
  assert.equal(state.title, pendingOptions.title);
  assert.equal(state.bitrate, 128000);
  await h.cmd('STOP');
});

test('a stale sound callback cannot start after cancellation or replace a newer wait', async () => {
  const h = await recorderHarness();
  const first = await h.cmd('ARM', pendingOptions);
  await h.cmd('STOP');
  const second = await h.cmd('ARM', pendingOptions);
  await assert.rejects(h.cmd('START', { waitId: first.waitId, streamId: 'old-id' }), /cancelled/);
  assert.equal((await h.cmd('STATUS')).waitId, second.waitId);
  assert.equal(h.captures.length, 0);
  await h.cmd('STOP');
});

test('closing the source tab cancels waiting without an empty recording', async () => {
  const h = await recorderHarness();
  await h.cmd('ARM', pendingOptions);
  await h.cmd('TAB_CLOSED', { tabId: 99 });
  assert.equal((await h.cmd('STATUS')).status, 'waiting');
  await h.cmd('TAB_CLOSED', { tabId: 42 });
  const state = await h.cmd('STATUS');
  assert.equal(state.status, 'idle');
  assert.match(state.notice, /closed/);
  assert.equal(h.records.size, 0);
});

test('cancelling a wait does not return or download the preceding recording', async () => {
  const h = await recorderHarness();
  await h.start();
  const previous = await h.cmd('STOP');
  await h.cmd('ARM', pendingOptions);
  const result = await h.cmd('STOP');
  assert.equal(result.cancelled, true);
  assert.equal(result.id, undefined);
  assert.equal((await h.cmd('STATUS')).lastResult.id, previous.id);
  assert.equal(await h.cmd('STOP'), null);
});

test('checkbox off preserves immediate recording even when native tab is silent', async () => {
  const h = await workerHarness();
  assert.equal((await h.raw('START', { waitForAudio: false })).ok, true);
  assert.equal(h.starts(), 1);
});

test('checkbox on waits with no capture request until Chrome reports audible', async () => {
  const h = await workerHarness();
  const response = await h.raw('START', { waitForAudio: true });
  assert.equal(response.data.status, 'waiting');
  assert.equal(h.capturesRequested(), 0);
  await h.raw('STATUS');
  assert.equal(h.badges.at(-1), 'WAIT');
  h.onUpdated(99, { audible: true });
  await flush();
  assert.equal(h.starts(), 0);
  h.nativeTab.audible = true;
  h.onUpdated(42, { audible: true });
  await flush();
  assert.equal(h.starts(), 1);
  assert.equal(h.capturesRequested(), 1);
  h.onUpdated(42, { audible: true });
  await flush();
  assert.equal(h.starts(), 1);
});

test('already-audible native state starts immediately when arming', async () => {
  const h = await workerHarness({ audible: true });
  const response = await h.raw('START', { waitForAudio: true });
  assert.equal(response.data.status, 'recording');
  assert.equal(h.starts(), 1);
});

test('a muted audible tab waits for Chrome to report unmute', async () => {
  const h = await workerHarness({ audible: true, muted: true });
  await h.raw('START', { waitForAudio: true });
  assert.equal(h.starts(), 0);
  h.nativeTab.mutedInfo.muted = false;
  h.onUpdated(42, { mutedInfo: { muted: false } });
  await flush();
  assert.equal(h.starts(), 1);
});

test('callback handling survives worker restart with a pending offscreen request', async () => {
  const h = await workerHarness({ audible: true, initialState: { ...pendingOptions, status: 'waiting', waitId: 'retained' } });
  h.onUpdated(42, { audible: true });
  await flush();
  assert.equal(h.starts(), 1);
  assert.equal(h.creates(), 0);
});

test('cancel-wait does not issue a download', async () => {
  const h = await workerHarness();
  await h.raw('START', { waitForAudio: true });
  const result = await h.raw('STOP_SAVE');
  assert.equal(result.data.cancelled, true);
  assert.equal(h.recorderCalls.some(call => call.type === 'EXPORT'), false);
});

test('a navigation cancels the pending source without starting capture', async () => {
  const h = await workerHarness();
  await h.raw('START', { waitForAudio: true });
  h.onUpdated(42, { status: 'loading' });
  await flush();
  const result = await h.raw('STATUS');
  assert.equal(result.data.status, 'idle');
  assert.match(result.data.notice, /page changed/);
  assert.equal(h.starts(), 0);
});

test('expired capture permission leaves an actionable notice and cancels waiting', async () => {
  const h = await workerHarness({ audible: true, captureError: true });
  const result = await h.raw('START', { waitForAudio: true });
  assert.equal(result.data.status, 'idle');
  assert.match(result.data.notice, /start again/);
  assert.equal(h.starts(), 0);
});

test('cancel racing an audible callback prevents the delayed capture from starting', async () => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const h = await workerHarness({ captureGate: gate });
  await h.raw('START', { waitForAudio: true });
  h.nativeTab.audible = true;
  h.onUpdated(42, { audible: true });
  await flush();
  assert.equal(h.capturesRequested(), 1);
  await h.raw('STOP_SAVE');
  release();
  await flush();
  assert.equal(h.starts(), 0);
});

test('automatic stop requires prior native audibility and flushes the final chunk', async () => {
  const h = await recorderHarness();
  const state = await h.cmd('START', { ...pendingOptions, streamId: 'id', autoStopOnSilence: true });
  const event = { id: state.id, tabId: 42 };
  assert.equal(await h.cmd('AUDIO_STATE', { ...event, audible: false }), null);
  assert.equal((await h.cmd('STATUS')).status, 'recording');
  await h.cmd('AUDIO_STATE', { ...event, audible: true });
  h.captures[0].data('first');
  const result = await h.cmd('AUDIO_STATE', { ...event, audible: false });
  assert.equal(result.bytes, 10);
  assert.equal(result.status, 'complete');
  assert.equal((await h.cmd('STATUS')).status, 'idle');
});

test('auto-stop ignores other sessions and is disabled unless requested', async () => {
  const h = await recorderHarness();
  const first = await h.start();
  await h.cmd('AUDIO_STATE', { id: first.id, tabId: 42, audible: true });
  await h.cmd('AUDIO_STATE', { id: first.id, tabId: 42, audible: false });
  assert.equal((await h.cmd('STATUS')).status, 'recording');
  await h.cmd('STOP');
  const second = await h.cmd('START', { ...pendingOptions, streamId: 'id', autoStopOnSilence: true, wasAudible: true });
  assert.equal(await h.cmd('AUDIO_STATE', { id: first.id, tabId: 42, audible: false }), null);
  assert.equal(await h.cmd('AUDIO_STATE', { id: second.id, tabId: 99, audible: false }), null);
  assert.equal((await h.cmd('STATUS')).status, 'recording');
  await h.cmd('STOP');
});

test('manual stop racing auto-stop does not claim a second download', async () => {
  const h = await recorderHarness({ writeDelay: 10 });
  const state = await h.cmd('START', { ...pendingOptions, streamId: 'id', autoStopOnSilence: true, wasAudible: true });
  const auto = h.cmd('AUDIO_STATE', { id: state.id, tabId: 42, audible: false });
  await flush();
  assert.equal((await h.cmd('STOP')).autoSaving, true);
  await auto;
});

test('both checkboxes wait for native sound then auto-download on native silence', async () => {
  const h = await workerHarness();
  await h.raw('START', { waitForAudio: true, autoStopOnSilence: true });
  h.nativeTab.audible = true;
  h.onUpdated(42, { audible: true });
  await flush();
  assert.equal(h.starts(), 1);
  assert.equal(h.downloads.length, 0);
  h.nativeTab.audible = false;
  h.onUpdated(42, { audible: false });
  await flush();
  assert.equal(h.downloads.length, 1);
  assert.equal(h.downloads[0].saveAs, false);
  h.onUpdated(42, { audible: false });
  await flush();
  assert.equal(h.downloads.length, 1);
});

test('auto-stop alone does not save initial silence and waits for the first audible cycle', async () => {
  const h = await workerHarness();
  await h.raw('START', { autoStopOnSilence: true });
  await flush();
  assert.equal(h.downloads.length, 0);
  h.nativeTab.audible = true;
  h.onUpdated(42, { audible: true });
  await flush();
  h.nativeTab.audible = false;
  h.onUpdated(42, { audible: false });
  await flush();
  assert.equal(h.downloads.length, 1);
});

test('mute changes alone do not auto-stop a tab that Chrome still considers audible', async () => {
  const h = await workerHarness({ audible: true });
  await h.raw('START', { autoStopOnSilence: true });
  h.nativeTab.mutedInfo.muted = true;
  h.onUpdated(42, { mutedInfo: { muted: true } });
  await flush();
  assert.equal(h.downloads.length, 0);
  assert.equal((await h.raw('STATUS')).data.status, 'recording');
});

test('automatic download failure reports recovery information without deleting audio', async () => {
  const h = await workerHarness({ audible: true, cancelDownload: true });
  await h.raw('START', { autoStopOnSilence: true });
  await flush();
  h.nativeTab.audible = false;
  h.onUpdated(42, { audible: false });
  await flush();
  const status = await h.raw('STATUS');
  assert.equal(status.data.noticeIsError, true);
  assert.match(status.data.notice, /still in Recordings/);
  assert.equal(h.recorderCalls.some(call => call.type === 'DELETE'), false);
});

test('native auto-stop callback works after a worker restart', async () => {
  const h = await workerHarness({ initialState: {
    status: 'recording', id: 'r1', tabId: 42, autoStopOnSilence: true, heardAudio: true
  } });
  h.onUpdated(42, { audible: false });
  await flush();
  assert.equal(h.downloads.length, 1);
  assert.equal(h.creates(), 0);
});
