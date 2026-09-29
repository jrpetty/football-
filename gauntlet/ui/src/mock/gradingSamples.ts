/**
 * Sample files for the Grading Station demo (?mock=1): one of every type the
 * output viewer supports, generated in the browser (canvas, hand-written
 * encoders) so no binary fixtures bloat the repo — except one short WebM clip.
 */
import { buildStoredZip } from '../components/viewer/zip.ts';
import sampleWebm from './assets/grading-sample.webm?url';

export { sampleWebm };

function b64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export function dataUrl(mime: string, bytes: Uint8Array): string {
  return `data:${mime};base64,${b64(bytes)}`;
}

function fromDataUrl(url: string): Uint8Array {
  const bin = atob(url.split(',')[1] ?? '');
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** "Lighthouse at dusk" — the kind of picture an image-generation test would produce. */
function paint(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const g = c.getContext('2d')!;
  const sky = g.createLinearGradient(0, 0, 0, h);
  sky.addColorStop(0, '#1e1b4b');
  sky.addColorStop(0.55, '#c2410c');
  sky.addColorStop(1, '#fbbf24');
  g.fillStyle = sky;
  g.fillRect(0, 0, w, h);
  g.fillStyle = '#fde68a';
  g.beginPath();
  g.arc(w * 0.7, h * 0.62, h * 0.12, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = '#0f172a';
  g.beginPath();
  g.moveTo(0, h * 0.78);
  for (let x = 0; x <= w; x += w / 12) g.lineTo(x, h * 0.74 + Math.sin(x / 37) * h * 0.03);
  g.lineTo(w, h);
  g.lineTo(0, h);
  g.fill();
  // Lighthouse
  g.fillStyle = '#f8fafc';
  g.beginPath();
  g.moveTo(w * 0.24, h * 0.76);
  g.lineTo(w * 0.27, h * 0.36);
  g.lineTo(w * 0.31, h * 0.36);
  g.lineTo(w * 0.34, h * 0.76);
  g.fill();
  g.fillStyle = '#dc2626';
  for (const y of [0.46, 0.58, 0.7]) g.fillRect(w * 0.255, h * y, w * 0.07, h * 0.035);
  g.fillStyle = '#fef9c3';
  g.fillRect(w * 0.265, h * 0.3, w * 0.05, h * 0.06);
  g.fillStyle = 'rgba(254, 249, 195, 0.35)';
  g.beginPath();
  g.moveTo(w * 0.29, h * 0.33);
  g.lineTo(w * 0.95, h * 0.18);
  g.lineTo(w * 0.95, h * 0.36);
  g.fill();
  return c;
}

/** A frame of the Ember Wing game, as the harness's screenshot would show it. */
export function gameShotUrl(): string {
  const w = 960;
  const h = 600;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const g = c.getContext('2d')!;
  const sky = g.createLinearGradient(0, 0, 0, h);
  sky.addColorStop(0, '#1e1b4b');
  sky.addColorStop(1, '#7c2d12');
  g.fillStyle = sky;
  g.fillRect(0, 0, w, h);
  for (const [x, top, gap] of [[380, 160, 170], [700, 250, 160]] as const) {
    g.fillStyle = '#374151';
    g.fillRect(x, 0, 60, top);
    g.fillRect(x, top + gap, 60, h);
    g.fillStyle = '#f97316';
    g.fillRect(x - 4, top - 12, 68, 12);
    g.fillRect(x - 4, top + gap, 68, 12);
    g.fillStyle = '#38bdf8';
    g.beginPath();
    g.arc(x + 30, top + gap / 2, 8, 0, 7);
    g.fill();
  }
  for (let k = 0; k < 14; k++) {
    g.fillStyle = `rgba(251,191,36,${0.9 - k / 16})`;
    g.beginPath();
    g.arc(250 - k * 10, 300 + Math.sin(k) * 6, 10 - k * 0.6, 0, 7);
    g.fill();
  }
  g.fillStyle = '#ea580c';
  g.beginPath();
  g.arc(268, 300, 16, 0, 7);
  g.fill();
  g.fillStyle = '#fff';
  g.font = 'bold 30px system-ui';
  g.fillText('Score 7   Best 12', 24, 46);
  g.fillStyle = '#1f2937';
  g.fillRect(24, 62, 240, 16);
  g.fillStyle = '#f59e0b';
  g.fillRect(24, 62, 130, 16);
  return c.toDataURL('image/png');
}

export function pngUrl(): string {
  return paint(640, 400).toDataURL('image/png');
}
export function jpegUrl(): string {
  return paint(640, 400).toDataURL('image/jpeg', 0.86);
}
export function webpUrl(): string {
  return paint(640, 400).toDataURL('image/webp', 0.9);
}

/** 24-bit BMP (bottom-up rows, padded to 4 bytes). */
export function bmpBytes(w = 64, h = 40): Uint8Array {
  const row = Math.ceil((w * 3) / 4) * 4;
  const size = 54 + row * h;
  const b = new Uint8Array(size);
  const v = new DataView(b.buffer);
  b[0] = 0x42;
  b[1] = 0x4d;
  v.setUint32(2, size, true);
  v.setUint32(10, 54, true);
  v.setUint32(14, 40, true);
  v.setInt32(18, w, true);
  v.setInt32(22, h, true);
  v.setUint16(26, 1, true);
  v.setUint16(28, 24, true);
  v.setUint32(34, row * h, true);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const o = 54 + (h - 1 - y) * row + x * 3;
      const check = ((x >> 3) + (y >> 3)) % 2;
      b[o] = check ? 238 : 90 + y * 3; // B
      b[o + 1] = check ? 211 : 60 + x; // G
      b[o + 2] = check ? 34 : 200; // R
    }
  return b;
}

/** ICO wrapping a PNG (allowed since Windows Vista). */
export function icoBytes(): Uint8Array {
  const png = fromDataUrl(paint(64, 64).toDataURL('image/png'));
  const b = new Uint8Array(22 + png.length);
  const v = new DataView(b.buffer);
  v.setUint16(2, 1, true);
  v.setUint16(4, 1, true);
  b[6] = 64;
  b[7] = 64;
  v.setUint16(10, 1, true);
  v.setUint16(12, 32, true);
  v.setUint32(14, png.length, true);
  v.setUint32(18, 22, true);
  b.set(png, 22);
  return b;
}

/** A 24×24 four-colour GIF (a pixel-art heart), LZW-encoded with a clear code every two pixels. */
export function gifBytes(): Uint8Array {
  const W = 24;
  const art = ['......................', '....XXXX......XXXX....', '...XOOOOX....XOOOOX...', '..XOOWWOOX..XOOOOOOX..', '..XOWWOOOOXXOOOOOOOX..', '..XOOOOOOOOOOOOOOOOX..', '..XOOOOOOOOOOOOOOOOX..', '...XOOOOOOOOOOOOOOX...', '....XOOOOOOOOOOOOX....', '.....XOOOOOOOOOOX.....', '......XOOOOOOOOX......', '.......XOOOOOOX.......', '........XOOOOX........', '.........XOOX.........', '..........XX..........'];
  const px: number[] = [];
  for (let y = 0; y < W; y++)
    for (let x = 0; x < W; x++) {
      const ch = art[y - 4]?.[x - 1] ?? '.';
      px.push(ch === 'X' ? 1 : ch === 'O' ? 2 : ch === 'W' ? 3 : 0);
    }
  const codes: number[] = [];
  for (let i = 0; i < px.length; i += 2) codes.push(4, px[i]!, ...(i + 1 < px.length ? [px[i + 1]!] : []));
  codes.push(5);
  const data: number[] = [];
  let acc = 0;
  let bits = 0;
  for (const c of codes) {
    acc |= c << bits;
    bits += 3;
    while (bits >= 8) {
      data.push(acc & 0xff);
      acc >>= 8;
      bits -= 8;
    }
  }
  if (bits) data.push(acc & 0xff);
  const out: number[] = [...'GIF89a'].map((c) => c.charCodeAt(0));
  out.push(W, 0, W, 0, 0x81, 0, 0);
  out.push(0x0b, 0x10, 0x20, 0xff, 0xff, 0xff, 0xef, 0x44, 0x44, 0xfe, 0xca, 0xca);
  out.push(0x2c, 0, 0, 0, 0, W, 0, W, 0, 0, 2);
  for (let i = 0; i < data.length; i += 255) {
    const chunk = data.slice(i, i + 255);
    out.push(chunk.length, ...chunk);
  }
  out.push(0, 0x3b);
  return new Uint8Array(out);
}

/** A 1.8-second arpeggio as 16-bit mono WAV. */
export function wavBytes(): Uint8Array {
  const rate = 22050;
  const n = Math.round(rate * 1.8);
  const b = new Uint8Array(44 + n * 2);
  const v = new DataView(b.buffer);
  const w = (o: number, s: string) => [...s].forEach((c, i) => (b[o + i] = c.charCodeAt(0)));
  w(0, 'RIFF');
  v.setUint32(4, 36 + n * 2, true);
  w(8, 'WAVE');
  w(12, 'fmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, 1, true);
  v.setUint32(24, rate, true);
  v.setUint32(28, rate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  w(36, 'data');
  v.setUint32(40, n * 2, true);
  const notes = [523.25, 659.25, 783.99, 1046.5, 783.99, 659.25];
  const per = n / notes.length;
  for (let i = 0; i < n; i++) {
    const k = Math.floor(i / per);
    const t = (i % per) / rate;
    const env = Math.min(1, t * 60) * Math.exp(-t * 5);
    const s = Math.sin(2 * Math.PI * notes[k]! * (i / rate)) * 0.5 + Math.sin(4 * Math.PI * notes[k]! * (i / rate)) * 0.15;
    v.setInt16(44 + i * 2, Math.round(s * env * 26000), true);
  }
  return b;
}

/** A one-page PDF with a title and three lines of text (valid xref table). */
export function pdfBytes(): Uint8Array {
  const text = [
    'BT /F1 26 Tf 60 740 Td (Launch plan: Ember Wing) Tj ET',
    'BT /F1 13 Tf 60 700 Td (1. Ship the web build on Friday.) Tj ET',
    'BT /F1 13 Tf 60 680 Td (2. Record a 30-second trailer.) Tj ET',
    'BT /F1 13 Tf 60 660 Td (3. Post to three game forums.) Tj ET',
    '0.13 0.83 0.93 rg 60 600 480 6 re f',
  ].join('\n');
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Title (Ember Wing launch plan) /Producer (Gauntlet demo) >>',
  ];
  let body = '%PDF-1.4\n';
  const offsets: number[] = [];
  objs.forEach((o, i) => {
    offsets.push(body.length);
    body += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = body.length;
  body += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  body += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new TextEncoder().encode(body);
}

export function zipBytes(): Uint8Array {
  return buildStoredZip([
    { name: 'ember-wing/', content: '' },
    { name: 'ember-wing/index.html', content: '<!doctype html><title>Ember Wing</title><canvas id="c"></canvas><script src="game.js"></script>' },
    { name: 'ember-wing/game.js', content: 'const c = document.getElementById("c");\n// … 380 lines of game code …\n'.repeat(12) },
    { name: 'ember-wing/assets/', content: '' },
    { name: 'ember-wing/assets/flame.png', content: new Uint8Array(1480) },
    { name: 'README.md', content: '# Ember Wing\nA flappy bird that is on fire.\n' },
  ]);
}

export function binaryBytes(): Uint8Array {
  const b = new Uint8Array(1536);
  let x = 2463534242;
  for (let i = 0; i < b.length; i++) {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    b[i] = x & 0xff;
  }
  b.set([0x47, 0x4e, 0x54, 0x4d, 0x44, 0x4c, 0x01, 0x00], 0);
  return b;
}
