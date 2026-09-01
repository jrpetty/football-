/**
 * Drawing a result card onto a canvas.
 *
 * Canvas rather than SVG-into-an-image, and drawn by hand rather than by
 * rendering DOM. The reasons are all about where this has to work:
 *
 *  - The app ships as one HTML file opened from `file://`. There is no server
 *    to render an image on, so it has to happen in the page.
 *  - The obvious trick — an `<svg>` with a `<foreignObject>` full of styled
 *    HTML, drawn to a canvas — loses webfonts in several browsers and silently
 *    produces a card set in Times New Roman. The fonts here are embedded as
 *    data URLs in the page's own stylesheet, and a 2D context can use them
 *    once `document.fonts.ready` has resolved, which is the one path that
 *    reliably keeps the typeface.
 *
 * Everything about WHAT the card says is in core/benchcard.ts and is tested.
 * This file only positions it.
 */

import type { BenchCard } from '../../core/benchcard.ts';

const W = 1080;
const H = 1350;

const C = {
  bg: '#0a0d12',
  surface: '#10151c',
  line: '#242e3a',
  ink: '#e6ecf3',
  muted: '#9aabbc',
  faint: '#83919f',
  accent: '#8ec1ee',
  good: '#5ec27a',
  bad: '#d4605e',
  spec: '#c2a04c',
};

const MONO = "'IBM Plex Mono', ui-monospace, monospace";
const SANS = "'IBM Plex Sans', ui-sans-serif, system-ui, sans-serif";

/** Break text to a width, returning the lines. Canvas has no wrapping. */
function wrap(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const words = text.split(/\s+/);
  const lines: string[] = [];
  let line = '';
  for (const w of words) {
    const next = line ? `${line} ${w}` : w;
    if (ctx.measureText(next).width > maxWidth && line) {
      lines.push(line);
      line = w;
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines;
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * Draw the card and hand back a PNG blob.
 *
 * `scale` of 2 gives a 2160x2700 image, which is what a phone wants and what
 * survives being recompressed by a social network.
 */
export async function drawBenchCard(card: BenchCard, scale = 2): Promise<Blob | null> {
  const canvas = document.createElement('canvas');
  canvas.width = W * scale;
  canvas.height = H * scale;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.scale(scale, scale);

  // Without this the first draw uses a fallback face — the embedded subset has
  // not finished decoding when the button is pressed.
  try {
    await (document as Document & { fonts?: FontFaceSet }).fonts?.ready;
  } catch {
    // No Font Loading API. The draw still works, in whatever face is available.
  }

  const PAD = 64;
  ctx.fillStyle = C.bg;
  ctx.fillRect(0, 0, W, H);

  /* -- brand row --------------------------------------------------------- */
  ctx.save();
  ctx.translate(PAD, PAD);
  ctx.strokeStyle = C.ink;
  ctx.globalAlpha = 0.5;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.arc(14, 14, 11.5, 0, Math.PI * 2);
  ctx.stroke();
  ctx.globalAlpha = 1;
  ctx.strokeStyle = C.accent;
  ctx.lineWidth = 2.6;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(14, 14);
  ctx.lineTo(19, 9);
  ctx.stroke();
  ctx.fillStyle = C.accent;
  ctx.beginPath();
  ctx.arc(14, 14, 2.4, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  ctx.font = `500 19px ${MONO}`;
  ctx.fillStyle = C.ink;
  ctx.textBaseline = 'middle';
  ctx.fillText('R I G C H E C K', PAD + 42, PAD + 15);

  ctx.font = `13px ${MONO}`;
  ctx.fillStyle = C.faint;
  ctx.textAlign = 'right';
  ctx.fillText(card.at.slice(0, 10).toUpperCase(), W - PAD, PAD + 15);
  ctx.textAlign = 'left';

  let y = PAD + 96;

  /* -- what was rendering ------------------------------------------------ */
  ctx.font = `11.5px ${MONO}`;
  ctx.fillStyle = C.faint;
  ctx.fillText('T H I S   M A C H I N E   I S   R E N D E R I N G   O N', PAD, y);
  y += 40;

  ctx.font = `600 52px ${SANS}`;
  ctx.fillStyle = card.deviceClass === 'software' ? C.bad : C.ink;
  for (const line of wrap(ctx, card.device, W - PAD * 2)) {
    ctx.fillText(line, PAD, y);
    y += 60;
  }
  y += 6;

  if (card.softwareWarning) {
    ctx.font = `17px ${SANS}`;
    ctx.fillStyle = C.bad;
    for (const line of wrap(ctx, card.softwareWarning, W - PAD * 2).slice(0, 3)) {
      ctx.fillText(line, PAD, y);
      y += 25;
    }
    y += 14;
  }

  /* -- the measured figures, two to a row -------------------------------- */
  const cols = 2;
  const gap = 14;
  const cw = (W - PAD * 2 - gap * (cols - 1)) / cols;
  const chh = 108;
  const stats = card.stats.slice(0, 8);
  stats.forEach((s, i) => {
    const cx = PAD + (i % cols) * (cw + gap);
    const cy = y + Math.floor(i / cols) * (chh + gap);
    ctx.fillStyle = C.surface;
    roundRect(ctx, cx, cy, cw, chh, 12);
    ctx.fill();
    ctx.strokeStyle = C.line;
    ctx.lineWidth = 1;
    ctx.stroke();

    ctx.font = `11.5px ${MONO}`;
    ctx.fillStyle = C.faint;
    ctx.fillText(s.label.toUpperCase(), cx + 20, cy + 24);

    // Shrink to fit rather than overflow the box. The content is not fully
    // predictable — a renderer name, a figure with a suffix — and a value that
    // runs off the edge of a card is worse than one set two points smaller.
    let size = 34;
    ctx.font = `600 ${size}px ${SANS}`;
    while (size > 18 && ctx.measureText(s.value).width > cw - 40) {
      size -= 2;
      ctx.font = `600 ${size}px ${SANS}`;
    }
    ctx.fillStyle = s.tone === 'good' ? C.good : s.tone === 'bad' ? C.bad : C.ink;
    ctx.fillText(s.value, cx + 20, cy + 60);

    if (s.note) {
      ctx.font = `13.5px ${MONO}`;
      ctx.fillStyle = C.muted;
      const [first] = wrap(ctx, s.note, cw - 40);
      ctx.fillText(first, cx + 20, cy + 88);
    }
  });
  y += Math.ceil(stats.length / cols) * (chh + gap) + 16;

  /* -- findings, if the run found anything ------------------------------- */
  const footerTop = H - PAD - 120;
  const showing = card.findings.length ? card.findings : card.limits;
  const heading = card.findings.length
    ? 'W H A T   T H E   R U N   F O U N D'
    : 'W H A T   T H I S   C A N N O T   T E L L   Y O U';
  {
    ctx.font = `11.5px ${MONO}`;
    ctx.fillStyle = C.faint;
    ctx.fillText(heading, PAD, y);
    y += 30;
    ctx.font = `18px ${SANS}`;
    for (const f of showing) {
      const lines = wrap(ctx, f, W - PAD * 2 - 18);
      if (y + lines.length * 26 > footerTop - 20) break;
      ctx.fillStyle = card.findings.length ? C.spec : C.line;
      ctx.fillRect(PAD, y - 12, 3, lines.length * 26 - 4);
      ctx.fillStyle = C.muted;
      for (const line of lines) {
        ctx.fillText(line, PAD + 18, y);
        y += 26;
      }
      y += 12;
    }
  }

  /* -- the provenance line, which never gets dropped for space ----------- */
  ctx.strokeStyle = C.line;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(PAD, footerTop);
  ctx.lineTo(W - PAD, footerTop);
  ctx.stroke();

  ctx.font = `16px ${SANS}`;
  ctx.fillStyle = C.faint;
  let fy = footerTop + 30;
  for (const line of wrap(ctx, card.footer, W - PAD * 2)) {
    ctx.fillText(line, PAD, fy);
    fy += 24;
  }

  return new Promise((resolve) => canvas.toBlob((b) => resolve(b), 'image/png'));
}

/** Save the card. A no-op if the browser refused to produce one. */
export async function saveBenchCard(card: BenchCard): Promise<boolean> {
  const blob = await drawBenchCard(card);
  if (!blob) return false;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `rigcheck-${card.at.slice(0, 10)}.png`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Revoked on a timer rather than immediately: some browsers have not started
  // reading the blob by the time the click handler returns.
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return true;
}
