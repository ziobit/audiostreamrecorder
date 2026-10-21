import { rpc, formatDuration, formatBytes } from './common.js';

const $ = id => document.getElementById(id);
let state = { status: 'idle' };
let tab = null;
let busy = false;
let refreshing = false;
let connected = false;
let shownNotice = '';

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
  const last = state.lastResult;
  $('last-recording').hidden = active || !last?.bytes;
  if (last) {
    $('last-title').textContent = last.title;
    $('last-details').textContent = `${formatDuration(last.durationMs)} · ${formatBytes(last.bytes)}${last.note ? ' · ' + last.note : ''}`;
    $('download-last').disabled = busy;
  }
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
  state = await rpc('START', { title: $('recording-name').value, bitrate, waitForAudio, autoStopOnSilence });
  render();
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
