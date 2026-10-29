export function formatKbps(value) {
  return Number.isFinite(value) && value > 0 ? `${value.toFixed(1)} kbps` : 'Not enough data yet';
}

export function cutoffLabel(data, live = true) {
  if (!data) return 'Start recording to measure audio.';
  if (!live && data.status === 'collecting') return `Insufficient active audio for a cutoff estimate (${data.activeFrames}/50 samples).`;
  const labels = {
    collecting: data.signalActive ? `Collecting active audio (${data.activeFrames}/50 samples)…` : 'Silence or very quiet audio; waiting for signal.',
    sparse: 'Signal too narrow or sparse for a useful cutoff estimate.',
    'full-band': 'Energy reaches the capture bandwidth limit; no clear cutoff.',
    estimated: `≈ ${((data.cutoffHz || 0) / 1000).toFixed(1)} kHz observed cutoff (estimate)`
  };
  if (live && data.paused) return 'AudioContext suspended; measurements paused.';
  const paused = live && data.activeFrames >= 50 && !data.signalActive ? ' Quiet now; retaining earlier measurements.' : '';
  return (labels[data.status] || 'Waiting for measurements…') + paused;
}

export function drawSpectrum(canvas, data) {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const width = canvas.width;
  const height = canvas.height;
  const left = 35;
  const right = width - 12;
  const top = 12;
  const bottom = height - 25;
  const plotWidth = right - left;
  ctx.clearRect(0, 0, width, height);
  ctx.font = '10px system-ui, sans-serif';
  ctx.lineWidth = 1;
  for (const db of [0, -50, -100]) {
    const y = top + (-db / 100) * (bottom - top);
    ctx.strokeStyle = '#dde5e9';
    ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke();
    ctx.fillStyle = '#687984'; ctx.textAlign = 'right'; ctx.fillText(String(db), left - 5, y + 3);
  }
  const nyquist = data.sampleRate / 2;
  ctx.fillStyle = '#687984';
  for (let i = 0; i <= 4; i++) {
    const x = left + plotWidth * i / 4;
    ctx.textAlign = i === 0 ? 'left' : i === 4 ? 'right' : 'center';
    ctx.fillText(`${(nyquist * i / 4000).toFixed(1)}k`, x, height - 7);
  }
  ctx.strokeStyle = '#138472';
  ctx.beginPath();
  for (let i = 0; i < data.spectrum.length; i++) {
    const x = left + plotWidth * i / data.spectrum.length;
    const y = top + Math.max(0, Math.min(100, -data.spectrum[i])) / 100 * (bottom - top);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  }
  ctx.stroke();
  if (data.cutoffHz) {
    const x = left + data.cutoffHz / nyquist * plotWidth;
    ctx.strokeStyle = '#d83751';
    ctx.setLineDash([4, 3]); ctx.beginPath(); ctx.moveTo(x, top); ctx.lineTo(x, bottom); ctx.stroke(); ctx.setLineDash([]);
  }
}
