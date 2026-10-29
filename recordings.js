import { rpc, formatDuration, formatBytes, formatDate } from './common.js';

import { formatKbps, cutoffLabel } from './diagnostics-view.js';

const $ = id => document.getElementById(id);
let busy = false;
let loading = false;

function notice(text, error = false) {
  $('notice').textContent = text;
  $('notice').className = error ? 'notice warning' : 'notice';
  $('notice').hidden = !text;
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function perform(action) {
  if (busy) return;
  busy = true;
  document.querySelectorAll('button').forEach(button => { button.disabled = true; });
  notice('');
  try { await action(); } catch (error) { notice(error.message, true); }
  finally { busy = false; await load(); }
}

async function load() {
  if (loading || busy) return;
  loading = true;
  try {
    const { recordings, state } = await rpc('LIST');
    const fragment = document.createDocumentFragment();
    let total = 0;
    for (const recording of recordings) {
      total += recording.bytes;
      const active = recording.id === state.id;
      const interrupted = recording.status === 'interrupted';
      const row = element('article', 'recording-row');
      const mark = element('div', 'audio-mark', '♫');
      mark.setAttribute('aria-hidden', 'true');
      const content = element('div', 'recording-content');
      const heading = element('div', 'recording-heading');
      heading.append(element('h3', '', recording.title));
      const badge = element('span', active ? 'tag live-tag' : interrupted ? 'tag interrupted-tag' : 'tag', active ? 'Recording' : interrupted ? 'Interrupted' : 'Saved');
      heading.append(badge);
      content.append(heading);
      content.append(element('p', 'recording-meta', `${formatDate(recording.startedAt)} · ${formatDuration(recording.durationMs)} · ${formatBytes(recording.bytes)} · ${recording.host}`));
      if (recording.outputKbps != null) content.append(element('p', 'recording-meta',
        `Requested: ${formatKbps(recording.bitrate / 1000)} · Output average: ${formatKbps(recording.outputKbps)} (approximate, includes WebM overhead)`));
      if (recording.diagnostics) content.append(element('p', 'recording-meta',
        `Capture AudioContext: ${recording.diagnostics.sampleRate.toLocaleString()} Hz · ${cutoffLabel(recording.diagnostics, false)} Source encoding unknown.`));
      if (recording.note) content.append(element('p', 'recording-note', recording.note));
      const actions = element('div', 'row-actions');
      const download = element('button', 'button small', 'Download');
      download.type = 'button';
      download.disabled = active || !recording.bytes;
      download.addEventListener('click', () => perform(async () => {
        await rpc('DOWNLOAD', { id: recording.id });
        notice('Download started. Your local copy has been kept.');
      }));
      const remove = element('button', 'button small danger-ghost', 'Delete');
      remove.type = 'button';
      remove.disabled = active;
      remove.addEventListener('click', () => {
        if (!confirm(`Delete the local copy of “${recording.title}”? Files already downloaded will remain on your computer.`)) return;
        perform(async () => { await rpc('DELETE', { id: recording.id }); notice('Local copy deleted.'); });
      });
      actions.append(download, remove);
      row.append(mark, content, actions);
      fragment.append(row);
    }
    $('recordings').replaceChildren(fragment);
    $('summary').textContent = `${recordings.length} recording${recordings.length === 1 ? '' : 's'} · ${formatBytes(total)} stored`;
    $('empty').hidden = recordings.length > 0;
  } catch (error) { notice(error.message, true); }
  finally { loading = false; $('refresh').disabled = false; }
}

$('refresh').addEventListener('click', load);
chrome.runtime.onMessage.addListener(message => {
  if (message?.target === 'ui') load();
});
await load();
setInterval(load, 5000);

