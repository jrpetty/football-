/** Inline SVG charts for the static website (no scripts needed to render). */
import type { LeaderboardRow } from '../core/types.ts';
import type { HistoryData } from './types.ts';
import { valueOf } from './history.ts';

export function esc(s: unknown): string {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const r1 = (n: number) => Math.round(n * 10) / 10;

interface Box {
  x1: number;
  x2: number;
  y1: number;
  y2: number;
}

/** Greedy label placement: try the right of the point, then nudge up/down, then the left. */
function placeLabels(points: Array<{ x: number; y: number; text: string }>, bounds: { w: number; h: number; top: number }, charW = 6.8): Array<{ x: number; y: number; anchor: 'start' | 'end'; text: string }> {
  const placed: Box[] = [];
  const out: Array<{ x: number; y: number; anchor: 'start' | 'end'; text: string }> = [];
  const hit = (b: Box) => placed.some((p) => b.x1 < p.x2 && b.x2 > p.x1 && b.y1 < p.y2 && b.y2 > p.y1);
  const order = points.map((p, i) => ({ ...p, i })).sort((a, b) => a.y - b.y);
  for (const p of order) {
    const w = p.text.length * charW;
    let chosen: { x: number; y: number; anchor: 'start' | 'end' } | null = null;
    for (const dy of [0, -13, 13, -26, 26, -39, 39]) {
      for (const side of ['start', 'end'] as const) {
        const x = side === 'start' ? p.x + 8 : p.x - 8;
        const y = p.y + 4 + dy;
        const box = { x1: side === 'start' ? x : x - w, x2: side === 'start' ? x + w : x, y1: y - 11, y2: y + 3 };
        if (box.x2 > bounds.w - 2 || box.x1 < 2 || box.y1 < bounds.top - 6 || box.y2 > bounds.h) continue;
        if (!hit(box)) {
          chosen = { x, y, anchor: side };
          placed.push(box);
          break;
        }
      }
      if (chosen) break;
    }
    if (chosen) out[p.i] = { ...chosen, text: p.text };
  }
  return out;
}

function niceStep(span: number, target: number): number {
  const raw = span / Math.max(1, target);
  const mag = 10 ** Math.floor(Math.log10(raw));
  const norm = raw / mag;
  return (norm >= 5 ? 10 : norm >= 2 ? 5 : norm >= 1 ? 2 : 1) * mag;
}

function fmtUsdTick(v: number): string {
  if (v >= 1) return `$${v >= 10 ? Math.round(v) : v.toFixed(1)}`;
  if (v >= 0.01) return `$${v.toFixed(2)}`;
  if (v >= 0.001) return `$${v.toFixed(3)}`;
  return `$${v.toExponential(0)}`;
}

/** Score (Index) vs average cost per case, log x, with the Pareto frontier. */
export function scatterSvg(rows: LeaderboardRow[], opts: { isBaseline: (r: LeaderboardRow) => boolean; manual: (r: LeaderboardRow) => boolean }): string {
  const pts = rows
    .filter((r) => r.index !== null && !opts.isBaseline(r) && !opts.manual(r) && r.totals.cases > 0 && r.totals.costUsd > 0)
    .map((r) => ({ r, cost: r.totals.costUsd / r.totals.cases, index: r.index! }));
  if (pts.length === 0) return '<p class="muted">No priced API results yet.</p>';
  const W = 820;
  const H = 440;
  const m = { l: 52, r: 24, t: 18, b: 46 };
  const lc = pts.map((p) => Math.log10(p.cost));
  let x0 = Math.floor(Math.min(...lc) * 2) / 2 - 0.25;
  let x1 = Math.ceil(Math.max(...lc) * 2) / 2 + 0.25;
  if (x1 - x0 < 1) {
    x0 -= 0.5;
    x1 += 0.5;
  }
  const ys = pts.map((p) => p.index);
  const yStep = niceStep(Math.max(10, Math.max(...ys) - Math.min(...ys)), 5);
  const y0 = Math.max(0, Math.floor((Math.min(...ys) - yStep / 2) / yStep) * yStep);
  const y1 = Math.min(100, Math.ceil((Math.max(...ys) + yStep / 2) / yStep) * yStep);
  const sx = (c: number) => m.l + ((Math.log10(c) - x0) / (x1 - x0)) * (W - m.l - m.r);
  const sy = (v: number) => H - m.b - ((v - y0) / (y1 - y0 || 1)) * (H - m.t - m.b);
  const parts: string[] = [];
  for (let v = y0; v <= y1 + 1e-9; v += yStep) parts.push(`<line class="grid-line" x1="${m.l}" x2="${W - m.r}" y1="${r1(sy(v))}" y2="${r1(sy(v))}"/><text x="${m.l - 8}" y="${r1(sy(v)) + 4}" text-anchor="end">${r1(v)}</text>`);
  for (let e = Math.ceil(x0); e <= Math.floor(x1); e++) {
    for (const k of [1, 3]) {
      const c = k * 10 ** e;
      const le = Math.log10(c);
      if (le < x0 || le > x1) continue;
      const x = r1(sx(c));
      parts.push(`<line class="grid-line" x1="${x}" x2="${x}" y1="${m.t}" y2="${H - m.b}"/><text x="${x}" y="${H - m.b + 18}" text-anchor="middle">${fmtUsdTick(c)}</text>`);
    }
  }
  parts.push(`<text x="${(m.l + W - m.r) / 2}" y="${H - 6}" text-anchor="middle">Average cost per case (USD, log scale)</text>`);
  parts.push(`<text x="14" y="${(m.t + H - m.b) / 2}" text-anchor="middle" transform="rotate(-90 14 ${(m.t + H - m.b) / 2})">Gauntlet Index</text>`);
  // Pareto frontier: cheapest-first, keep every model that beats all cheaper ones.
  const sorted = pts.slice().sort((a, b) => a.cost - b.cost || b.index - a.index);
  const frontier: typeof pts = [];
  let best = -Infinity;
  for (const p of sorted) if (p.index > best) {
    frontier.push(p);
    best = p.index;
  }
  if (frontier.length > 1) parts.push(`<polyline class="frontier" points="${frontier.map((p) => `${r1(sx(p.cost))},${r1(sy(p.index))}`).join(' ')}"/>`);
  for (const p of pts) {
    const x = r1(sx(p.cost));
    const y = r1(sy(p.index));
    if (p.r.indexCi95) parts.push(`<line x1="${x}" x2="${x}" y1="${r1(sy(p.r.indexCi95[0]))}" y2="${r1(sy(p.r.indexCi95[1]))}" stroke="${esc(p.r.color)}" stroke-opacity="0.45" stroke-width="2"/>`);
    parts.push(`<circle cx="${x}" cy="${y}" r="6.5" fill="${esc(p.r.color)}" stroke="var(--surface)" stroke-width="2"><title>${esc(p.r.label)}: Index ${p.index.toFixed(1)}, $${p.cost.toFixed(4)} per case</title></circle>`);
  }
  const labels = placeLabels(pts.map((p) => ({ x: sx(p.cost), y: sy(p.index), text: p.r.label })), { w: W, h: H - m.b, top: m.t });
  labels.forEach((l) => l && parts.push(`<text class="lbl" x="${r1(l.x)}" y="${r1(l.y)}" text-anchor="${l.anchor}">${esc(l.text)}</text>`));
  return `<div class="chart"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Gauntlet Index against average cost per case">${parts.join('')}</svg></div>`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Metric vs release date, one line per family, labelled points. */
export function historySvg(h: HistoryData, metricLabel: string): string {
  const pts = h.families.flatMap((f) => f.points.map((p) => ({ p, f, v: valueOf(p, h.metric)!, t: Date.parse(p.releaseDate!) })));
  if (!pts.length) return '<p class="muted">No dated models with results yet. Add release dates to your models to draw the history chart.</p>';
  const W = 900;
  const H = 470;
  const m = { l: 52, r: 30, t: 20, b: 46 };
  const ts = pts.map((x) => x.t);
  const pad = Math.max(30 * 86_400_000, (Math.max(...ts) - Math.min(...ts)) * 0.06);
  const t0 = Math.min(...ts) - pad;
  const t1 = Math.max(...ts) + pad * 2.5;
  const vs = pts.map((x) => x.v);
  const yStep = niceStep(Math.max(10, Math.max(...vs) - Math.min(...vs)), 5);
  const y0 = Math.max(0, Math.floor((Math.min(...vs) - yStep / 2) / yStep) * yStep);
  const y1 = Math.min(100, Math.ceil((Math.max(...vs) + yStep / 2) / yStep) * yStep);
  const sx = (t: number) => m.l + ((t - t0) / (t1 - t0)) * (W - m.l - m.r);
  const sy = (v: number) => H - m.b - ((v - y0) / (y1 - y0 || 1)) * (H - m.t - m.b);
  const parts: string[] = [];
  for (let v = y0; v <= y1 + 1e-9; v += yStep) parts.push(`<line class="grid-line" x1="${m.l}" x2="${W - m.r}" y1="${r1(sy(v))}" y2="${r1(sy(v))}"/><text x="${m.l - 8}" y="${r1(sy(v)) + 4}" text-anchor="end">${r1(v)}</text>`);
  // Month ticks every 1, 3, 6 or 12 months depending on span.
  const months = (t1 - t0) / (30.4 * 86_400_000);
  const every = months <= 8 ? 1 : months <= 24 ? 3 : months <= 48 ? 6 : 12;
  const start = new Date(t0);
  let y = start.getUTCFullYear();
  let mo = Math.ceil(start.getUTCMonth() / every) * every;
  let firstTick = true;
  for (let guard = 0; guard < 200; guard++) {
    if (mo >= 12) {
      y += Math.floor(mo / 12);
      mo %= 12;
    }
    const t = Date.UTC(y, mo, 1);
    if (t > t1) break;
    if (t >= t0) {
      const x = r1(sx(t));
      parts.push(`<line class="grid-line" x1="${x}" x2="${x}" y1="${m.t}" y2="${H - m.b}"/><text x="${x}" y="${H - m.b + 18}" text-anchor="middle">${every === 12 || mo === 0 || firstTick ? `${MONTHS[mo]} ${y}` : MONTHS[mo]}</text>`);
      firstTick = false;
    }
    mo += every;
  }
  parts.push(`<text x="${(m.l + W - m.r) / 2}" y="${H - 6}" text-anchor="middle">Release date</text>`);
  parts.push(`<text x="14" y="${(m.t + H - m.b) / 2}" text-anchor="middle" transform="rotate(-90 14 ${(m.t + H - m.b) / 2})">${esc(metricLabel)}</text>`);
  for (const f of h.families) {
    if (f.line.length > 1)
      parts.push(`<polyline points="${f.line.map((p) => `${r1(sx(Date.parse(p.releaseDate!)))},${r1(sy(valueOf(p, h.metric)!))}`).join(' ')}" fill="none" stroke="${esc(f.color)}" stroke-width="2.5" stroke-linejoin="round" stroke-opacity="0.85"/>`);
  }
  for (const x of pts) parts.push(`<circle cx="${r1(sx(x.t))}" cy="${r1(sy(x.v))}" r="6" fill="${esc(x.p.color)}" stroke="var(--surface)" stroke-width="2"><title>${esc(x.p.label)} (${esc(x.p.releaseDate)}): ${x.v.toFixed(1)}</title></circle>`);
  const labels = placeLabels(pts.map((x) => ({ x: sx(x.t), y: sy(x.v), text: x.p.label })), { w: W, h: H - m.b, top: m.t });
  labels.forEach((l) => l && parts.push(`<text class="lbl" x="${r1(l.x)}" y="${r1(l.y)}" text-anchor="${l.anchor}">${esc(l.text)}</text>`));
  const legend = h.families.map((f) => `<span><i class="dot" style="background:${esc(f.color)}"></i>${esc(f.family)}</span>`).join('');
  return `<div class="chart"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(metricLabel)} by release date">${parts.join('')}</svg><div class="legend">${legend}</div></div>`;
}
