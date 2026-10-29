let creating;
let starting = false;
let waitingChecks = Promise.resolve();
const extensionOrigin = chrome.runtime.getURL('');
const offscreenUrl = chrome.runtime.getURL('offscreen.html');

async function hasOffscreen() {
  return (await chrome.runtime.getContexts({
    contextTypes: ['OFFSCREEN_DOCUMENT'], documentUrls: [offscreenUrl]
  })).length > 0;
}

async function ensureOffscreen() {
  if (creating) return creating;
  if (await hasOffscreen()) return;
  if (!creating) {
    creating = chrome.offscreen.createDocument({
      url: 'offscreen.html',
      reasons: ['USER_MEDIA', 'BLOBS'],
      justification: 'Record the user-selected tab audio and prepare local audio downloads.'
    }).finally(() => { creating = null; });
  }
  return creating;
}

async function recorder(type, data = {}) {
  await ensureOffscreen();
  const response = await chrome.runtime.sendMessage({ target: 'offscreen', type, ...data });
  if (!response?.ok) throw new Error(response?.error || 'The recorder did not respond.');
  return response.data;
}

async function setBadge(state) {
  const text = state.status === 'recording' ? 'REC' : state.status === 'waiting' ? 'WAIT' : state.status === 'starting' ? '…' : state.status === 'stopping' ? '…' : '';
  await chrome.action.setBadgeBackgroundColor({ color: state.status === 'waiting' ? '#a36b19' : '#df3d55' });
  await chrome.action.setBadgeText({ text });
  await chrome.action.setTitle({ title: state.status === 'waiting' ? 'Stream Saver — waiting for audio' : text ? 'Stream Saver — recording controls' : 'Stream Saver' });
}

function checkTabAudio(tabId) {
  // Serialize browser events, including ones arriving during the initial check.
  // The pending request lives in the offscreen document across worker restarts.
  const check = waitingChecks.then(async () => {
    if (!await hasOffscreen()) return;
    let state = await recorder('STATUS');
    if (state.tabId !== tabId) return;
    if (state.status === 'waiting') {
      try {
        const tab = await chrome.tabs.get(tabId);
        // Native Chrome state: equivalent to its speaker indicator, not our meter.
        if (tab.audible !== true || tab.mutedInfo?.muted === true) return;
        // Stream IDs expire in seconds: obtain one only when audio is reported.
        const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: tabId });
        state = await recorder('START', { waitId: state.waitId, streamId, wasAudible: true });
      } catch (error) {
        const note = `Automatic start failed. Open the source tab and start again. ${error.message}`;
        const latest = await recorder('STATUS');
        // Never cancel a newer request because an earlier one failed or was cancelled.
        if (latest.status === 'waiting' && latest.waitId === state.waitId) {
          await recorder('CANCEL_WAIT', { waitId: state.waitId, note });
        } else if (latest.status === 'idle' && !/pending recording was cancelled/i.test(error.message)) {
          await recorder('WAIT_ERROR', { note });
        }
        return;
      }
    }
    if (state.status !== 'recording' || !state.autoStopOnSilence) return;
    const tab = await chrome.tabs.get(tabId).catch(() => null);
    if (!tab || typeof tab.audible !== 'boolean') return;
    const result = await recorder('AUDIO_STATE', { id: state.id, tabId, audible: tab.audible });
    if (!result?.id || !result.bytes) return;
    try {
      await downloadRecording(result.id, false);
      await recorder('AUTO_SAVE_RESULT', {
        id: result.id, error: false,
        note: 'Audio ended. Automatic download started; a local copy remains in Your recordings.'
      });
    } catch (error) {
      await recorder('AUTO_SAVE_RESULT', { id: result.id, error: true, note: error.message });
    }
  });
  waitingChecks = check.catch(() => {});
  return check;
}

async function downloadRecording(id, saveAs = true) {
  const prepared = await recorder('EXPORT', { id });
  try {
    const downloadId = await chrome.downloads.download({
      url: prepared.url, filename: prepared.filename, saveAs, conflictAction: 'uniquify'
    });
    await recorder('TRACK_EXPORT', { key: prepared.key, downloadId });
    // A tiny download can finish before TRACK_EXPORT. Reconcile that race.
    const [item] = await chrome.downloads.search({ id: downloadId });
    if (!item || item.state !== 'in_progress') await recorder('RELEASE_DOWNLOAD', { downloadId });
    return { downloadId };
  } catch (error) {
    await recorder('RELEASE_EXPORT', { key: prepared.key }).catch(() => {});
    throw new Error(`Download was not started. Your recording is still in Recordings. ${error.message}`);
  }
}

async function handle(message, sender) {
  switch (message.type) {
    case 'STATUS': {
      const state = await recorder('STATUS');
      await setBadge(state);
      return state;
    }
    case 'START': {
      if (starting) throw new Error('A recording is already starting.');
      starting = true;
      try {
        const state = await recorder('STATUS');
        if (state.status !== 'idle') throw new Error('Stop the current recording first.');
        // Query the current tab here rather than accepting a URL or tab ID from a page.
        const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
        if (!tab?.id || !/^https?:\/\//i.test(tab.url || '')) {
          throw new Error('Open your audio website, then click the extension icon on that tab.');
        }
        const options = {
          tabId: tab.id,
          title: String(message.title || tab.title || 'Recording').trim().slice(0, 160),
          host: new URL(tab.url).hostname,
          autoStopOnSilence: message.autoStopOnSilence === true,
          diagnosticsEnabled: message.diagnosticsEnabled === true,
          wasAudible: tab.audible === true,
          bitrate: [128000, 192000, 256000].includes(message.bitrate) ? message.bitrate : 192000
        };
        if (message.waitForAudio === true) {
          await recorder('ARM', options);
          // Covers audio already playing and a state change during arming.
          await checkTabAudio(tab.id);
          return recorder('STATUS');
        }
        const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: tab.id });
        const result = await recorder('START', { ...options, streamId });
        // Reconcile an audible change that arrived while capture was starting.
        if (options.autoStopOnSilence) checkTabAudio(tab.id).catch(() => {});
        return result;
      } finally {
        starting = false;
      }
    }
    case 'STOP_SAVE': {
      const result = await recorder('STOP');
      if (result?.autoSaving) return { message: 'Audio ended. This recording is already being saved automatically.' };
      if (result?.cancelled) return { cancelled: true };
      if (!result?.id || !result.bytes) return { warning: 'No audio data was available to download.' };
      try {
        return { id: result.id, ...await downloadRecording(result.id) };
      } catch (error) {
        return { id: result.id, warning: error.message };
      }
    }
    case 'RESET_DIAGNOSTICS': return recorder('RESET_DIAGNOSTICS');
    case 'SET_DIAGNOSTICS': return recorder('SET_DIAGNOSTICS', { enabled: message.enabled === true });
    case 'LIST': return recorder('LIST');
    case 'DELETE': return recorder('DELETE', { id: message.id });
    case 'DOWNLOAD': return downloadRecording(message.id);
    case 'OPEN_RECORDINGS':
      await chrome.tabs.create({ url: chrome.runtime.getURL('recordings.html') });
      return null;
    case 'STATE_CHANGED':
      if (sender.url !== offscreenUrl) throw new Error('Invalid recorder sender.');
      await setBadge(message.state);
      chrome.runtime.sendMessage({ target: 'ui', type: 'STATE_CHANGED', state: message.state }).catch(() => {});
      return null;
    default: throw new Error('Unknown extension command.');
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.target !== 'background') return;
  if (sender.id !== chrome.runtime.id || !sender.url?.startsWith(extensionOrigin)) return;
  const allowed = ['popup.html', 'recordings.html', 'offscreen.html'].map(path => chrome.runtime.getURL(path));
  if (!allowed.includes(sender.url)) return;
  handle(message, sender).then(
    data => sendResponse({ ok: true, data }),
    error => sendResponse({ ok: false, error: error.message || String(error) })
  );
  return true;
});

chrome.tabs.onRemoved.addListener(tabId => {
  hasOffscreen().then(exists => exists && recorder('TAB_CLOSED', { tabId })).catch(() => {});
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === 'loading' || changeInfo.url !== undefined) {
    // A new document may revoke activeTab permission or change the audio source.
    hasOffscreen().then(exists => exists && recorder('CANCEL_WAIT', {
      tabId, note: 'Waiting cancelled because the source page changed. Open that tab and start again.'
    })).catch(() => {});
    return;
  }
  if (changeInfo.audible !== undefined || changeInfo.mutedInfo !== undefined) {
    checkTabAudio(tabId).catch(() => {});
  }
});

chrome.downloads.onChanged.addListener(delta => {
  if (!['complete', 'interrupted'].includes(delta.state?.current)) return;
  hasOffscreen().then(exists => exists && recorder('RELEASE_DOWNLOAD', { downloadId: delta.id })).catch(() => {});
});

chrome.runtime.onStartup.addListener(() => {
  recorder('STATUS').then(setBadge).catch(() => {});
});

