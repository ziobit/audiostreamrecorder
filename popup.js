import { rpc, formatDuration, formatBytes } from './common.js';
import { formatKbps, cutoffLabel, drawSpectrum } from './diagnostics-view.js';

const $ = id => document.getElementById(id);
let state = { status: 'idle' };
let tab = null;
let busy = false;
let refreshing = false;
let connected = false;
let shownNotice = '';
let diagnosticsPreference = localStorage.getItem('diagnosticsEnabled') === 'true';

function notice(text, error = false) {
  $('notice').textContent = text;
  $('notice').className = error ? 'notice warning' : 'notice';
  $('notice').hidden = !text;
}

function render() {
  const active = state.status !== 'idle';
  const recording = state.status === 'recording';
  const waiting = state.status === 'waiting';
  $('source-label').textContent = waiting ? 'WAITING FOR THIS TAB' : active ? 'RECORDING TAB' : 'CURRENT TAB';
  $('source-title').textContent = active ? state.title : tab?.title || 'Open your audio website';
  $('source-host').textContent = active ? state.host : /^https?:\/\//i.test(tab?.url || '') ? new URL(tab.url).hostname : 'Select an HTTP or HTTPS page to record.';
  $('status-text').textContent = ({ idle: 'Ready to record', waiting: 'Waiting for audio', starting: 'Starting…', recording: 'Recording', stopping: 'Finishing…' })[state.status];
  $('status-pill').classList.toggle('live', active && !waiting);
  $('status-pill').classList.toggle('waiting', waiting);
  $('timer').textContent = formatDuration(state.elapsedMs);
  $('size').textContent = formatBytes(state.bytes);
  $('level').style.width = `${Math.round((state.level || 0) * 100)}%`;
  $('level-meter').setAttribute('aria-valuenow', Math.round((state.level || 0) * 100));
  $('recording-hint').textContent = waiting ? 'Waiting for Chrome’s audio status. No audio file yet.' : active && state.autoStopOnSilence ? 'Will stop and save when Chrome reports audio ended.' : active ? 'Recording continues when this popup closes.' : $('wait-for-audio').checked ? 'Arm the recorder, then start playback in this tab.' : 'Play audio in this tab, then press Record.';
  $('settings').hidden = active;
  $('record').hidden = active;
  $('record').textContent = $('wait-for-audio').checked ? '● Wait for audio' : '● Start recording';
  $('record').disabled = busy || !connected || !/^https?:\/\//i.test(tab?.url || '');
  $('stop').hidden = !active;
  $('stop').disabled = busy || !(recording || waiting);
  $('stop').textContent = waiting ? 'Cancel waiting' : '■ Stop & save';
  $('stop').classList.toggle('waiting-button', waiting);
  if (state.notice && state.notice !== shownNotice) {
    shownNotice = state.notice;
    notice(state.notice, state.noticeIsError !== false);
  }
  renderDiagnostics();
  const last = state.lastResult;
  $('last-recording').hidden = active || !last?.bytes;
  if (last) {
    $('last-title').textContent = last.title;
    $('last-details').textContent = `${formatDuration(last.durationMs)} · ${formatBytes(last.bytes)}${last.note ? ' · ' + last.note : ''}`;
    $('download-last').disabled = busy;
  }
}

function renderDiagnostics() {
  const active = state.status !== 'idle';
  const enabled = active ? state.diagnosticsEnabled === true : diagnosticsPreference;
  $('diagnostics-enabled').checked = enabled;
  $('diagnostics-enabled').disabled = busy || !connected || ['starting', 'stopping'].includes(state.status);
  const record = active ? state : state.lastResult;
  const data = active ? (enabled ? state.diagnostics : null) : state.lastResult?.diagnostics;
  $('bitrate-details').hidden = !record;
  $('requested-bitrate').textContent = record?.bitrate ? formatKbps(record.bitrate / 1000) : 'Unavailable';
  $('output-bitrate').textContent = formatKbps(record?.outputKbps);
  $('diagnostics-error').hidden = !state.diagnosticsError;
  $('diagnostics-error').textContent = state.diagnosticsError || '';
  $('diagnostics-details').hidden = !enabled && !data;
  $('diagnostics-context').textContent = data
    ? `${active ? 'Capture' : 'Last recording'} AudioContext: ${data.sampleRate.toLocaleString()} Hz · ${data.fftSize.toLocaleString()}-point FFT (${data.binHz.toFixed(1)} Hz/bin)`
    : state.status === 'waiting' ? 'Waiting for recording to start; no audio captured yet.' : 'Measurements are available during recording.';
  $('diagnostics-cutoff').textContent = cutoffLabel(data, active);
  $('diagnostics-samples').textContent = data ? `${data.activeFrames} active / ${data.frames} sampled frames · 5 samples/second when running` : '';
  const hasSpectrum = Boolean(data?.spectrum?.length);
  $('spectrum').hidden = !hasSpectrum;
  $('spectrum-caption').hidden = !hasSpectrum;
  if (hasSpectrum) drawSpectrum($('spectrum'), data);
  $('reset-diagnostics').hidden = !enabled || state.status !== 'recording';
  $('reset-diagnostics').disabled = busy;
}

async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    state = await rpc('STATUS');
    connected = true;
    render();
  } catch (error) {
    connected = false;
    notice(error.message, true);
    render();
  } finally {
    refreshing = false;
  }
}

async function act(action) {
  if (busy) return;
  busy = true;
  notice('');
  render();
  try { await action(); } catch (error) { notice(error.message, true); }
  finally { busy = false; await refresh(); }
}

$('record').addEventListener('click', () => act(async () => {
  const bitrate = Number($('quality').value);
  localStorage.setItem('bitrate', String(bitrate));
  shownNotice = '';
  const waitForAudio = $('wait-for-audio').checked;
  localStorage.setItem('waitForAudio', String(waitForAudio));
  const autoStopOnSilence = $('auto-stop').checked;
  localStorage.setItem('autoStopOnSilence', String(autoStopOnSilence));
  state = await rpc('START', { title: $('recording-name').value, bitrate, waitForAudio, autoStopOnSilence, diagnosticsEnabled: diagnosticsPreference });
  render();
}));

$('diagnostics-enabled').addEventListener('change', () => {
  const enabled = $('diagnostics-enabled').checked;
  act(async () => {
    if (state.status !== 'idle') state = await rpc('SET_DIAGNOSTICS', { enabled });
    diagnosticsPreference = enabled;
    localStorage.setItem('diagnosticsEnabled', String(enabled));
    render();
  });
});

$('reset-diagnostics').addEventListener('click', () => act(async () => {
  state = await rpc('RESET_DIAGNOSTICS');
}));

$('stop').addEventListener('click', () => act(async () => {
  const result = await rpc('STOP_SAVE');
  notice(result.cancelled ? 'Waiting cancelled. No recording was created.' : result.message || result.warning || 'Recording saved locally. Choose where to download it.', Boolean(result.warning));
}));

$('download-last').addEventListener('click', () => act(async () => {
  await rpc('DOWNLOAD', { id: state.lastResult.id });
  notice('Download started. A local copy remains in Your recordings.');
}));

$('open-recordings').addEventListener('click', async () => {
  try { await rpc('OPEN_RECORDINGS'); window.close(); }
  catch (error) { notice(error.message, true); }
});

chrome.runtime.onMessage.addListener(message => {
  if (message?.target === 'ui' && message.type === 'STATE_CHANGED') {
    state = message.state;
    render();
  }
});

const bitrate = localStorage.getItem('bitrate');
if (['128000', '192000', '256000'].includes(bitrate)) $('quality').value = bitrate;
$('wait-for-audio').checked = localStorage.getItem('waitForAudio') === 'true';
$('wait-for-audio').addEventListener('change', () => {
  localStorage.setItem('waitForAudio', String($('wait-for-audio').checked));
  render();
});
$('auto-stop').checked = localStorage.getItem('autoStopOnSilence') === 'true';
$('auto-stop').addEventListener('change', () => {
  localStorage.setItem('autoStopOnSilence', String($('auto-stop').checked));
  render();
});
try {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  $('recording-name').placeholder = tab?.title || 'Use the tab title';
} catch (error) { notice(error.message, true); }
await refresh();
setInterval(refresh, 1000);

