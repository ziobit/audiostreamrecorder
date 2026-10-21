export function formatDuration(ms) {
  const seconds = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return [h, m, s].map(value => String(value).padStart(2, '0')).join(':');
}

export function formatBytes(bytes) {
  if (!bytes) return '0 KB';
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1073741824) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1073741824).toFixed(2)} GB`;
}

export function formatDate(timestamp) {
  const date = new Date(timestamp);
  const pad = value => String(value).padStart(2, '0');
  return `${pad(date.getDate())}-${pad(date.getMonth() + 1)}-${date.getFullYear()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function recordingFilename(recording) {
  const cleaned = String(recording.title || 'Recording')
    .replace(/[<>:"/\\|?*\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ').trim().replace(/^[. ]+|[. ]+$/g, '') || 'Recording';
  const characters = Array.from(cleaned).slice(0, 80);
  const encoder = new TextEncoder();
  while (encoder.encode(characters.join('')).length > 150) characters.pop();
  return `Stream Saver - ${formatDate(recording.startedAt).replace(':', '-')} - ${characters.join('')}.webm`;
}

export async function rpc(type, data = {}) {
  const response = await chrome.runtime.sendMessage({ target: 'background', type, ...data });
  if (!response?.ok) throw new Error(response?.error || 'The extension did not respond. Please reopen it.');
  return response.data;
}
