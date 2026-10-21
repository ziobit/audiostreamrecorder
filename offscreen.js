import * as db from './db.js';
import { recordingFilename } from './common.js';

let current = null;
let waiting = null;
let lastResult = null;
let lastNotice = '';
let lastNoticeIsError = true;
const exports = new Map();
const ready = db.recoverRecordings();
// Attach a rejection handler immediately; every message still receives this failure.
ready.catch(() => {});

function snapshot() {
  if (!current && waiting) {
    return { ...waiting, status: 'waiting', elapsedMs: 0, bytes: 0, level: 0 };
  }
  if (!current) return { status: 'idle', lastResult, notice: lastNotice, noticeIsError: lastNoticeIsError };
  let level = 0;
  if (current.analyser) {
    const samples = new Uint8Array(current.analyser.fftSize);
    current.analyser.getByteTimeDomainData(samples);
    const energy = samples.reduce((sum, sample) => sum + ((sample - 128) / 128) ** 2, 0);
    level = Math.min(1, Math.sqrt(energy / samples.length) * 4);
  }
  return {
    status: current.status, id: current.id, tabId: current.tabId,
    title: current.title, host: current.host, bytes: current.bytes,
    elapsedMs: current.startTick ? (current.stopTick || performance.now()) - current.startTick : 0,
    level, bitrate: current.bitrate, note: current.note,
    autoStopOnSilence: current.autoStopOnSilence, heardAudio: current.heardAudio
  };
}

function announce() {
  chrome.runtime.sendMessage({ target: 'background', type: 'STATE_CHANGED', state: snapshot() }).catch(() => {});
}

async function cleanUp(session) {
  if (session.stream) session.stream.getTracks().forEach(track => track.stop());
  if (session.source) session.source.disconnect();
  if (session.context && session.context.state !== 'closed') await session.context.close().catch(() => {});
}

async function finalize(session) {
  if (session.finalizing) return;
  session.finalizing = true;
  session.status = 'stopping';
  session.stopTick ||= performance.now();
  announce();
  await session.writes;
  let result;
  try {
    result = await db.updateRecording(session.id, {
      status: session.failure ? 'interrupted' : 'complete',
      finishedAt: Date.now(),
      // When storage fails, only describe the audio that actually reached disk.
      durationMs: session.failure ? session.savedDurationMs : session.stopTick - session.startTick,
      note: session.failure || session.note || ''
    });
  } catch (error) {
    // Prior committed chunks can still be exported even if this final update failed.
    result = await db.getRecording(session.id).catch(() => null);
    if (result) {
      result.status = 'interrupted';
      result.note = 'Could not finalize local storage. Reopen Chrome to recover saved audio.';
    }
  } finally {
    await cleanUp(session);
    lastResult = result;
    if (current === session) current = null;
    session.resolveFinished(result);
    announce();
  }
}

function stopRecording(note = '') {
  if (waiting) {
    waiting = null;
    lastNotice = note;
    lastNoticeIsError = true;
    announce();
    return Promise.resolve({ cancelled: true });
  }
  const session = current;
  if (!session) return Promise.resolve(null);
  if (note && !session.note) session.note = note;
  if (session.status === 'starting') throw new Error('The recording is still starting. Please try again in a moment.');
  if (session.status !== 'stopping') {
    session.status = 'stopping';
    session.stopTick = performance.now();
    announce();
    if (session.recorder.state !== 'inactive') session.recorder.stop();
    // onstop also fires for source loss; it follows the final dataavailable event.
  }
  return session.finished;
}

async function startRecording(options) {
  if (current) throw new Error('A recording is already running.');
  if (options.waitId) {
    if (!waiting || waiting.waitId !== options.waitId) throw new Error('This pending recording was cancelled.');
    options = { ...waiting, streamId: options.streamId, wasAudible: options.wasAudible === true };
    waiting = null;
  } else if (waiting) {
    throw new Error('Cancel the pending recording first.');
  }
  lastNotice = '';
  const mimeType = ['audio/webm;codecs=opus', 'audio/webm'].find(type => MediaRecorder.isTypeSupported(type));
  if (!mimeType) throw new Error('This Chrome build cannot record WebM audio. Please update Chrome.');
  const session = {
    id: crypto.randomUUID(), status: 'starting', tabId: options.tabId,
    title: options.title, host: options.host, bitrate: options.bitrate,
    autoStopOnSilence: options.autoStopOnSilence === true, heardAudio: options.wasAudible === true,
    bytes: 0, index: 0, savedDurationMs: 0, writes: Promise.resolve(), note: '', failure: ''
  };
  session.finished = new Promise(resolve => { session.resolveFinished = resolve; });
  current = session;
  announce();
  try {
    session.stream = await navigator.mediaDevices.getUserMedia({
      audio: { mandatory: { chromeMediaSource: 'tab', chromeMediaSourceId: options.streamId } },
      video: false
    });
    if (!session.stream.getAudioTracks().length) throw new Error('Chrome did not provide an audio track.');
    session.context = new AudioContext();
    session.source = session.context.createMediaStreamSource(session.stream);
    session.analyser = session.context.createAnalyser();
    session.analyser.fftSize = 256;
    session.source.connect(session.analyser);
    session.analyser.connect(session.context.destination);
    await session.context.resume();
    session.recorder = new MediaRecorder(session.stream, { mimeType, audioBitsPerSecond: options.bitrate });
    await db.createRecording({
      id: session.id, title: session.title, host: session.host,
      startedAt: Date.now(), finishedAt: null, durationMs: 0,
      bytes: 0, chunkCount: 0, status: 'recording',
      mimeType: session.recorder.mimeType || mimeType, bitrate: options.bitrate, note: ''
    });
    session.created = true;
    session.recorder.ondataavailable = event => {
      if (!event.data.size) return;
      const index = session.index++;
      const durationMs = (session.stopTick || performance.now()) - session.startTick;
      session.writes = session.writes.then(async () => {
        if (session.storageFailed) return;
        const record = await db.appendChunk(session.id, index, event.data, durationMs);
        session.bytes = record.bytes;
        session.savedDurationMs = record.durationMs;
      }).catch(error => {
        session.storageFailed = true;
        session.failure = error.name === 'QuotaExceededError'
          ? 'Local storage is full. Recording stopped; earlier saved audio is retained.'
          : `Local storage failed. Recording stopped: ${error.message}`;
        // Do not await stop from this queue: finalize itself awaits the queue.
        stopRecording().catch(() => {});
      });
    };
    session.recorder.onerror = event => {
      session.note = `Audio recorder error: ${event.error?.message || 'capture was interrupted'}`;
      session.recorderFailed = true;
      stopRecording().catch(() => {});
    };
    session.recorder.onstop = () => {
      if (session.recorderFailed && !session.failure) session.failure = session.note;
      finalize(session).catch(() => {});
    };
    session.stream.getAudioTracks().forEach(track => {
      track.onended = () => stopRecording('The source tab closed or Chrome ended capture.').catch(() => {});
    });
    session.startTick = performance.now();
    session.recorder.start(2000);
    session.status = 'recording';
    announce();
    return snapshot();
  } catch (error) {
    await cleanUp(session);
    if (session.created) await db.deleteRecording(session.id).catch(() => {});
    current = null;
    session.resolveFinished(null);
    announce();
    throw new Error(`Could not start recording. ${error.message}`);
  }
}

function releaseExport(key) {
  const item = exports.get(key);
  if (item) URL.revokeObjectURL(item.url);
  exports.delete(key);
}

async function handle(message) {
  await ready;
  switch (message.type) {
    case 'STATUS': return snapshot();
    case 'ARM':
      if (current || waiting) throw new Error('A recording is already active or waiting.');
      lastNotice = '';
      waiting = {
        waitId: crypto.randomUUID(), tabId: message.tabId, title: message.title,
        host: message.host, bitrate: message.bitrate,
        autoStopOnSilence: message.autoStopOnSilence === true
      };
      announce();
      return snapshot();
    case 'CANCEL_WAIT':
      if (waiting && (!message.waitId || message.waitId === waiting.waitId) &&
          (message.tabId === undefined || message.tabId === waiting.tabId)) {
        return stopRecording(message.note || '');
      }
      return null;
    case 'WAIT_ERROR':
      // Failed asynchronous starts are visible even after the popup closes.
      if (!current && !waiting) {
        lastNotice = message.note;
        lastNoticeIsError = true;
        announce();
      }
      return null;
    case 'START': return startRecording(message);
    case 'STOP':
      if (current?.autoSaving) return { autoSaving: true };
      return stopRecording();
    case 'AUDIO_STATE':
      if (current?.id !== message.id || current?.tabId !== message.tabId ||
          current.status !== 'recording' || !current.autoStopOnSilence) return null;
      if (message.audible === true) current.heardAudio = true;
      if (message.audible === false && current.heardAudio) {
        current.autoSaving = true;
        return stopRecording('Chrome reported that the tab stopped producing audio.');
      }
      return null;
    case 'AUTO_SAVE_RESULT':
      if (lastResult?.id === message.id) {
        lastNotice = message.note;
        lastNoticeIsError = message.error === true;
        announce();
      }
      return null;
    case 'TAB_CLOSED':
      if (waiting?.tabId === message.tabId) {
        return stopRecording('Waiting cancelled because the source tab was closed.');
      }
      if (current?.tabId === message.tabId && current.status !== 'starting') {
        return stopRecording('The source tab was closed.');
      }
      return null;
    case 'LIST': return { recordings: await db.listRecordings(), state: snapshot() };
    case 'DELETE':
      if (current?.id === message.id) throw new Error('Stop this recording before deleting it.');
      if ([...exports.values()].some(item => item.id === message.id)) throw new Error('Wait for this download to finish before deleting it.');
      await db.deleteRecording(message.id);
      if (lastResult?.id === message.id) lastResult = null;
      return null;
    case 'EXPORT': {
      if (current?.id === message.id) throw new Error('Stop recording before downloading.');
      const { recording, blob } = await db.getRecordingBlob(message.id);
      const key = crypto.randomUUID();
      const url = URL.createObjectURL(blob);
      exports.set(key, { url, id: message.id });
      return { key, url, filename: recordingFilename(recording) };
    }
    case 'TRACK_EXPORT': {
      const item = exports.get(message.key);
      if (item) item.downloadId = message.downloadId;
      return null;
    }
    case 'RELEASE_EXPORT': releaseExport(message.key); return null;
    case 'RELEASE_DOWNLOAD':
      for (const [key, item] of exports) if (item.downloadId === message.downloadId) releaseExport(key);
      return null;
    default: throw new Error('Unknown recorder command.');
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.target !== 'offscreen' || sender.id !== chrome.runtime.id) return;
  // Some Chrome contexts omit the service worker's sender URL.
  // No content scripts or external message endpoints are registered.
  if (sender.tab || (sender.url && sender.url !== chrome.runtime.getURL('background.js'))) return;
  handle(message).then(
    data => sendResponse({ ok: true, data }),
    error => sendResponse({ ok: false, error: error.message || String(error) })
  );
  return true;
});
