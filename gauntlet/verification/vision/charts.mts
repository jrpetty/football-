// "Read the Chart": bar, pie, line, stacked, dual-axis and grouped charts generated from seeded data.
// Every answer is computed from the same data arrays that draw the chart.
import { createRng } from '../../src/core/rng.ts';
import type { Rng } from '../../src/core/types.ts';
import { FONT, r1, svgDoc, text, type ImageSpec } from './lib.mts';

export interface VisionCase {
  id: string;
  prompt: string;
  expected: unknown;
  notes: string;
  image: ImageSpec;
}

const W = 1200;
const H = 800;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const ONE = 'Give exactly one answer. If you give more than one answer, it will be marked wrong.';
const INT = 'Give the final answer as a single integer (digits only, no words or units).';

function title(t: string, sub?: string): string {
  return text(W / 2, 48, t, { size: 28, anchor: 'middle', weight: 700, fill: '#111827' }) + (sub ? text(W / 2, 80, sub, { size: 18, anchor: 'middle', fill: '#4b5563' }) : '');
}

/** Horizontal gridlines + left axis labels. Returns [svg, y(value)]. */
function yAxis(opts: { x0: number; x1: number; y0: number; y1: number; min: number; max: number; step: number; labelEvery?: number; unit?: string; side?: 'left' | 'right'; label?: string; grid?: boolean }): [string, (v: number) => number] {
  const { x0, x1, y0, y1, min, max, step } = opts;
  const y = (v: number) => y0 - ((v - min) / (max - min)) * (y0 - y1);
  let s = '';
  const every = opts.labelEvery ?? step;
  for (let v = min; v <= max + 1e-9; v += step) {
    if (opts.grid !== false) s += `<line x1="${x0}" y1="${r1(y(v))}" x2="${x1}" y2="${r1(y(v))}" stroke="${v === min ? '#6b7280' : '#d1d5db'}" stroke-width="${v === min ? 2 : 1}"/>`;
    if (Math.abs(((v - min) / every) - Math.round((v - min) / every)) < 1e-9) {
      const lx = opts.side === 'right' ? x1 + 12 : x0 - 12;
      s += text(lx, y(v) + 6, `${v}${opts.unit ?? ''}`, { size: 16, anchor: opts.side === 'right' ? 'start' : 'end', fill: '#374151' });
    }
  }
  if (opts.label) {
    const lx = opts.side === 'right' ? x1 + 78 : x0 - 78;
    s += text(lx, (y0 + y1) / 2, opts.label, { size: 17, anchor: 'middle', fill: '#374151', rotate: opts.side === 'right' ? 90 : -90 });
  }
  return [s, y];
}

function legend(items: Array<{ label: string; color: string; kind?: 'box' | 'line-circle' | 'line-square' }>, x: number, y: number): string {
  let s = '';
  let cx = x;
  for (const it of items) {
    if (it.kind === 'line-circle' || it.kind === 'line-square') {
      s += `<line x1="${cx}" y1="${y}" x2="${cx + 36}" y2="${y}" stroke="${it.color}" stroke-width="4"/>`;
      s += it.kind === 'line-circle' ? `<circle cx="${cx + 18}" cy="${y}" r="7" fill="${it.color}"/>` : `<rect x="${cx + 11}" y="${y - 7}" width="14" height="14" fill="${it.color}"/>`;
      cx += 46;
    } else {
      s += `<rect x="${cx}" y="${y - 10}" width="22" height="20" rx="3" fill="${it.color}"/>`;
      cx += 30;
    }
    s += text(cx, y + 6, it.label, { size: 18, fill: '#1f2937' });
    cx += it.label.length * 10.5 + 34;
  }
  return s;
}

function reroll<T>(rng: Rng, make: (rng: Rng) => T | null): T {
  for (let i = 0; i < 10000; i++) {
    const v = make(rng);
    if (v !== null) return v;
  }
  throw new Error('generator could not satisfy its constraints');
}

// c01 ─ bar chart, largest month-over-month increase
function barIncrease(): VisionCase {
  const data = reroll(createRng(11001), (rng) => {
    const v: number[] = [rng.int(160, 260)];
    for (let i = 1; i < 12; i++) v.push(v[i - 1]! + rng.int(-70, 75));
    if (v.some((x) => x < 60 || x > 560)) return null;
    const inc = v.slice(1).map((x, i) => x - v[i]!);
    const sorted = inc.slice().sort((a, b) => b - a);
    if (sorted[0]! - sorted[1]! < 4 || sorted[1]! < sorted[0]! - 15) return null; // unique, but a close runner-up
    const best = inc.indexOf(sorted[0]!) + 1;
    if (v.indexOf(Math.max(...v)) === best) return null; // the tallest bar is not the answer
    return { v, best, inc: sorted[0]! };
  });
  const x0 = 110, x1 = W - 50, y0 = H - 90, y1 = 120;
  const [axis, y] = yAxis({ x0, x1, y0, y1, min: 0, max: 600, step: 100, label: 'Rentals' });
  const slot = (x1 - x0) / 12;
  let bars = '';
  data.v.forEach((val, i) => {
    const bx = x0 + slot * i + slot * 0.18;
    const bw = slot * 0.64;
    bars += `<rect x="${r1(bx)}" y="${r1(y(val))}" width="${r1(bw)}" height="${r1(y0 - y(val))}" fill="#2563EB" rx="3"/>`;
    bars += text(bx + bw / 2, y(val) - 10, String(val), { size: 18, anchor: 'middle', weight: 600, fill: '#111827' });
    bars += text(bx + bw / 2, y0 + 30, MONTHS[i]!, { size: 18, anchor: 'middle', fill: '#374151' });
  });
  const svg = svgDoc(W, H, title('Monthly bike rentals — Harbour Street station', 'Rentals per month, 2025') + axis + bars);
  const m = data.best;
  return {
    id: 'c01',
    prompt: `The image is a bar chart of monthly bike rentals at one station. Which month had the largest increase in rentals compared with the month before it, and how large was that increase?\n\nAnswer with the month's three-letter abbreviation followed by the increase, separated by a space (for example: OCT 25). ${ONE}`,
    expected: [`${MONTHS[m]!.toUpperCase()} ${data.inc}`, `${MONTH_NAMES[m]} ${data.inc}`, ...(m === 8 ? [`SEPT ${data.inc}`] : [])],
    notes: `[standard] Values (Jan..Dec): ${data.v.join(', ')}. Month-over-month changes: ${data.v.slice(1).map((x, i) => `${MONTHS[i + 1]} ${x - data.v[i]! >= 0 ? '+' : ''}${x - data.v[i]!}`).join(', ')}. Largest increase ${MONTHS[m]} (+${data.inc}); the runner-up is within 15 and the tallest bar is a different month. Generated by verification/vision/charts.mts (seed 11001).`,
    image: { file: 'chart-c01.png', svg, width: W, height: H },
  };
}

// c02 ─ pie chart, largest slice minus the two smallest combined (in dollars)
function pieBudget(): VisionCase {
  const names = ['Rent', 'Groceries', 'Transport', 'Utilities', 'Savings', 'Leisure'];
  const colors = ['#2563EB', '#F59E0B', '#10B981', '#EF4444', '#8B5CF6', '#64748B'];
  const p = reroll(createRng(11002), (rng) => {
    const cut = [0, ...Array.from({ length: 5 }, () => rng.int(1, 99)).sort((a, b) => a - b), 100];
    const parts = cut.slice(1).map((c, i) => c - cut[i]!);
    if (parts.some((x) => x < 5) || new Set(parts).size !== 6 || Math.max(...parts) > 40) return null;
    return parts;
  });
  const total = 3600;
  const sorted = p.slice().sort((a, b) => a - b);
  const answer = (total / 100) * (sorted[5]! - sorted[0]! - sorted[1]!);
  if (answer <= 0) throw new Error('pie answer must be positive');
  const cx = W / 2 - 40, cy = 450, r = 260;
  let s = '';
  let a = -90;
  p.forEach((pct, i) => {
    const a0 = (a * Math.PI) / 180;
    const a1 = ((a + pct * 3.6) * Math.PI) / 180;
    const large = pct > 50 ? 1 : 0;
    s += `<path d="M ${cx} ${cy} L ${r1(cx + r * Math.cos(a0))} ${r1(cy + r * Math.sin(a0))} A ${r} ${r} 0 ${large} 1 ${r1(cx + r * Math.cos(a1))} ${r1(cy + r * Math.sin(a1))} Z" fill="${colors[i]}" stroke="#ffffff" stroke-width="3"/>`;
    const mid = ((a + pct * 1.8) * Math.PI) / 180;
    const lx = cx + (r + 22) * Math.cos(mid);
    const ly = cy + (r + 22) * Math.sin(mid);
    const tx = cx + (r + 60) * Math.cos(mid);
    const ty = cy + (r + 60) * Math.sin(mid);
    s += `<line x1="${r1(cx + (r - 4) * Math.cos(mid))}" y1="${r1(cy + (r - 4) * Math.sin(mid))}" x2="${r1(lx)}" y2="${r1(ly)}" stroke="#6b7280" stroke-width="1.5"/>`;
    s += text(tx, ty + 7, `${names[i]} ${pct}%`, { size: 21, anchor: Math.cos(mid) >= 0 ? 'start' : 'end', weight: 600, fill: '#111827' });
    a += pct * 3.6;
  });
  const svg = svgDoc(W, H, title('Household budget', `Total spending: $${total.toLocaleString('en-US')} per month`) + s);
  return {
    id: 'c02',
    prompt: `The image is a pie chart of a household's monthly budget. How many dollars per month more is spent on the largest category than on the two smallest categories combined?\n\n${INT} ${ONE}`,
    expected: answer,
    notes: `[standard] Slices (clockwise from 12 o'clock): ${p.map((x, i) => `${names[i]} ${x}%`).join(', ')}. Total $${total}; largest ${sorted[5]}% = $${36 * sorted[5]!}, two smallest ${sorted[0]}% + ${sorted[1]}% = $${36 * (sorted[0]! + sorted[1]!)}; difference $${answer}. Generated by verification/vision/charts.mts (seed 11002).`,
    image: { file: 'chart-c02.png', svg, width: W, height: H },
  };
}

// c03 ─ two-series line chart, months where A > B
function lineTemps(): VisionCase {
  const data = reroll(createRng(11003), (rng) => {
    const a: number[] = [];
    const b: number[] = [];
    const ampA = rng.int(8, 11), ampB = rng.int(5, 8), baseA = rng.int(12, 15), baseB = rng.int(13, 16);
    for (let i = 0; i < 12; i++) {
      const season = -Math.cos(((i + 0.5) / 12) * 2 * Math.PI);
      a.push(2 * Math.round((baseA + ampA * season + rng.int(-2, 2)) / 2));
      b.push(2 * Math.round((baseB + ampB * season + rng.int(-2, 2)) / 2));
    }
    if ([...a, ...b].some((v) => v < -4 || v > 30)) return null;
    if (a.some((v, i) => Math.abs(v - b[i]!) < 2)) return null;
    const warmer = a.filter((v, i) => v > b[i]!).length;
    let crossings = 0;
    for (let i = 1; i < 12; i++) if (a[i]! > b[i]! !== a[i - 1]! > b[i - 1]!) crossings++;
    if (warmer < 4 || warmer > 8 || crossings < 2) return null;
    return { a, b, warmer };
  });
  const x0 = 110, x1 = W - 60, y0 = H - 110, y1 = 130;
  const [axis, y] = yAxis({ x0, x1, y0, y1, min: -6, max: 32, step: 2, labelEvery: 4, unit: '°', label: 'Average temperature (°C)' });
  const slot = (x1 - x0) / 12;
  const x = (i: number) => x0 + slot * (i + 0.5);
  let s = '';
  MONTHS.forEach((m, i) => (s += text(x(i), y0 + 30, m, { size: 18, anchor: 'middle', fill: '#374151' })));
  const series = (vals: number[], color: string, marker: 'circle' | 'square') => {
    let out = `<polyline points="${vals.map((v, i) => `${r1(x(i))},${r1(y(v))}`).join(' ')}" fill="none" stroke="${color}" stroke-width="4" stroke-linejoin="round"/>`;
    vals.forEach((v, i) => (out += marker === 'circle' ? `<circle cx="${r1(x(i))}" cy="${r1(y(v))}" r="7" fill="${color}"/>` : `<rect x="${r1(x(i) - 7)}" y="${r1(y(v) - 7)}" width="14" height="14" fill="${color}"/>`));
    return out;
  };
  s += series(data.b, '#F59E0B', 'square') + series(data.a, '#2563EB', 'circle');
  s += legend([{ label: 'Northport', color: '#2563EB', kind: 'line-circle' }, { label: 'Southbay', color: '#F59E0B', kind: 'line-square' }], W / 2 - 150, H - 38);
  const svg = svgDoc(W, H, title('Average monthly temperature', 'Two coastal towns, 2025') + axis + s);
  return {
    id: 'c03',
    prompt: `The image is a line chart of the average monthly temperature in two towns. Every data point sits exactly on a horizontal gridline (the gridlines are 2 °C apart). In how many months was Northport strictly warmer than Southbay?\n\n${INT} ${ONE}`,
    expected: data.warmer,
    notes: `[standard] Northport: ${data.a.join(', ')}. Southbay: ${data.b.join(', ')}. The two series never tie and always differ by at least one gridline (2 °C). Northport warmer in ${data.warmer} months (${data.a.map((v, i) => (v > data.b[i]! ? MONTHS[i] : null)).filter(Boolean).join(', ')}). Generated by verification/vision/charts.mts (seed 11003).`,
    image: { file: 'chart-c03.png', svg, width: W, height: H },
  };
}

// c04 ─ stacked bars without value labels, sum of the middle segment
function stackedServices(): VisionCase {
  const quarters = ['Q1 24', 'Q2 24', 'Q3 24', 'Q4 24', 'Q1 25', 'Q2 25', 'Q3 25', 'Q4 25'];
  const data = reroll(createRng(11004), (rng) => {
    const hw = quarters.map(() => 10 * rng.int(3, 7));
    const sv = quarters.map(() => 10 * rng.int(1, 6));
    const sw = quarters.map(() => 10 * rng.int(2, 6));
    if (hw.some((v, i) => v + sv[i]! + sw[i]! > 170)) return null;
    if (new Set(sv).size < 4) return null;
    return { hw, sv, sw };
  });
  const x0 = 110, x1 = W - 60, y0 = H - 110, y1 = 130;
  const [axis, y] = yAxis({ x0, x1, y0, y1, min: 0, max: 180, step: 10, labelEvery: 20, label: 'Revenue ($ thousands)' });
  const slot = (x1 - x0) / 8;
  const colors = { hw: '#2563EB', sv: '#F59E0B', sw: '#10B981' };
  let s = '';
  quarters.forEach((q, i) => {
    const bx = x0 + slot * i + slot * 0.2;
    const bw = slot * 0.6;
    let base = 0;
    for (const key of ['hw', 'sv', 'sw'] as const) {
      const v = data[key][i]!;
      s += `<rect x="${r1(bx)}" y="${r1(y(base + v))}" width="${r1(bw)}" height="${r1(y(base) - y(base + v))}" fill="${colors[key]}" stroke="#ffffff" stroke-width="1.5"/>`;
      base += v;
    }
    s += text(bx + bw / 2, y0 + 30, q, { size: 18, anchor: 'middle', fill: '#374151' });
  });
  s += legend([{ label: 'Hardware', color: colors.hw }, { label: 'Services', color: colors.sv }, { label: 'Software', color: colors.sw }], W / 2 - 200, H - 38);
  const sum = data.sv.reduce((a, b) => a + b, 0);
  const svg = svgDoc(W, H, title('Quarterly revenue by product line', 'Stacked: Hardware at the bottom, Services in the middle, Software on top') + axis + s);
  return {
    id: 'c04',
    prompt: `The image is a stacked bar chart of quarterly revenue in thousands of dollars. Every segment boundary lies exactly on a gridline (gridlines every 10). What is the total Services revenue over all eight quarters, in thousands of dollars?\n\n${INT} ${ONE}`,
    expected: sum,
    notes: `[hard] Hardware: ${data.hw.join(', ')}. Services: ${data.sv.join(', ')}. Software: ${data.sw.join(', ')}. Services total = ${sum}. No value labels: each Services segment must be read as the difference of two gridline positions. Generated by verification/vision/charts.mts (seed 11004).`,
    image: { file: 'chart-c04.png', svg, width: W, height: H },
  };
}

// c05 ─ dual axis: bars on the left axis, line on the right axis
function dualAxis(): VisionCase {
  const data = reroll(createRng(11005), (rng) => {
    const rain = MONTHS.map(() => 10 * rng.int(2, 19));
    const temp = MONTHS.map((_, i) => 3 * Math.max(0, Math.min(10, Math.round((15 - 11 * Math.cos(((i + 0.5) / 12) * 2 * Math.PI) + rng.int(-3, 3)) / 3))));
    const sr = rain.slice().sort((a, b) => a - b);
    if (sr[11]! - sr[10]! < 20 || sr[1]! - sr[0]! < 20) return null;
    const wet = rain.indexOf(sr[11]!);
    const dry = rain.indexOf(sr[0]!);
    const diff = Math.abs(temp[wet]! - temp[dry]!);
    if (diff < 6) return null;
    // Trap: reading the wettest month's temperature off the rainfall axis gives a different number.
    return { rain, temp, wet, dry, diff };
  });
  const x0 = 120, x1 = W - 130, y0 = H - 110, y1 = 130;
  const [left, yl] = yAxis({ x0, x1, y0, y1, min: 0, max: 200, step: 20, label: 'Rainfall (mm) — bars' });
  const [right, yr] = yAxis({ x0, x1, y0, y1, min: 0, max: 30, step: 3, side: 'right', grid: false, unit: '°', label: 'Temperature (°C) — line' });
  const slot = (x1 - x0) / 12;
  const x = (i: number) => x0 + slot * (i + 0.5);
  let s = '';
  data.rain.forEach((v, i) => {
    s += `<rect x="${r1(x(i) - slot * 0.3)}" y="${r1(yl(v))}" width="${r1(slot * 0.6)}" height="${r1(y0 - yl(v))}" fill="#93C5FD" stroke="#2563EB" stroke-width="1.5"/>`;
    s += text(x(i), y0 + 30, MONTHS[i]!, { size: 18, anchor: 'middle', fill: '#374151' });
  });
  s += `<polyline points="${data.temp.map((v, i) => `${r1(x(i))},${r1(yr(v))}`).join(' ')}" fill="none" stroke="#DC2626" stroke-width="4"/>`;
  data.temp.forEach((v, i) => (s += `<circle cx="${r1(x(i))}" cy="${r1(yr(v))}" r="7" fill="#DC2626"/>`));
  s += legend([{ label: 'Rainfall (left axis)', color: '#93C5FD' }, { label: 'Temperature (right axis)', color: '#DC2626', kind: 'line-circle' }], W / 2 - 250, H - 38);
  const svg = svgDoc(W, H, title('Climate of Port Alder', 'Monthly rainfall and average temperature') + left + right + s);
  return {
    id: 'c05',
    prompt: `The image is a climate chart with two vertical axes: rainfall bars use the left axis and the temperature line uses the right axis. Every temperature point sits exactly on a gridline. By how many degrees Celsius does the average temperature of the wettest month differ from the average temperature of the driest month?\n\nGive the (non-negative) difference as a single integer (digits only, no words or units). ${ONE}`,
    expected: data.diff,
    notes: `[hard] Rainfall mm: ${data.rain.join(', ')}. Temperature °C: ${data.temp.join(', ')}. Wettest ${MONTHS[data.wet]} (${data.rain[data.wet]} mm, ${data.temp[data.wet]} °C); driest ${MONTHS[data.dry]} (${data.rain[data.dry]} mm, ${data.temp[data.dry]} °C); |difference| = ${data.diff}. Wettest/driest months lead the runner-up by at least 20 mm. Generated by verification/vision/charts.mts (seed 11005).`,
    image: { file: 'chart-c05.png', svg, width: W, height: H },
  };
}

// c06 ─ grouped horizontal bars: largest percentage growth (rounded)
function groupedGrowth(): VisionCase {
  const regions = ['Northfield', 'Riverside', 'Eastgate', 'Hillcrest', 'Lakeside'];
  const years = ['2023', '2024', '2025'];
  const data = reroll(createRng(11006), (rng) => {
    const v = regions.map(() => {
      const a = rng.int(60, 300);
      const b = Math.round(a * (0.85 + rng.next() * 0.4));
      const c = Math.round(b * (0.85 + rng.next() * 0.45));
      return [a, b, c];
    });
    if (v.some((r) => r.some((x) => x > 420))) return null;
    const growth = v.map(([a, , c]) => ((c! - a!) / a!) * 100);
    const sorted = growth.slice().sort((a, b) => b - a);
    if (sorted[0]! - sorted[1]! < 3) return null;
    const best = growth.indexOf(sorted[0]!);
    const frac = sorted[0]! - Math.floor(sorted[0]!);
    if (Math.abs(frac - 0.5) < 0.15) return null;
    const abs = v.map(([a, , c]) => c! - a!);
    if (abs.indexOf(Math.max(...abs)) === best) return null; // largest absolute growth is a different region
    return { v, best, pct: Math.round(sorted[0]!), growth };
  });
  const x0 = 190, x1 = W - 90, top = 130, bottom = H - 100;
  const max = 450;
  const xv = (v: number) => x0 + (v / max) * (x1 - x0);
  let s = '';
  for (let g = 0; g <= max; g += 50) {
    s += `<line x1="${r1(xv(g))}" y1="${top}" x2="${r1(xv(g))}" y2="${bottom}" stroke="${g === 0 ? '#6b7280' : '#e5e7eb'}" stroke-width="${g === 0 ? 2 : 1}"/>`;
    s += text(xv(g), bottom + 26, String(g), { size: 16, anchor: 'middle', fill: '#374151' });
  }
  const colors = ['#CBD5E1', '#60A5FA', '#1D4ED8'];
  const band = (bottom - top) / regions.length;
  regions.forEach((name, i) => {
    const by = top + band * i + band * 0.12;
    const bh = (band * 0.76) / 3;
    s += text(x0 - 14, by + band * 0.4, name, { size: 19, anchor: 'end', weight: 600, fill: '#111827' });
    data.v[i]!.forEach((val, k) => {
      const yy = by + bh * k;
      s += `<rect x="${x0}" y="${r1(yy + 2)}" width="${r1(xv(val) - x0)}" height="${r1(bh - 4)}" fill="${colors[k]}"/>`;
      s += text(xv(val) + 6, yy + bh / 2 + 5, String(val), { size: 14, fill: '#111827' });
    });
  });
  s += legend(years.map((y, k) => ({ label: y, color: colors[k]! })), W / 2 - 150, H - 36);
  const svg = svgDoc(W, H, title('Active members by region', 'Members at year end, 2023–2025') + s);
  const name = regions[data.best]!;
  return {
    id: 'c06',
    prompt: `The image is a grouped bar chart of club members per region for 2023, 2024 and 2025 (each bar is labelled with its value). Which region had the largest percentage growth in members from 2023 to 2025, and what was that growth, rounded to the nearest whole percent?\n\nAnswer with the region name followed by the percentage as a whole number, separated by a space (for example: Westbrook 17). ${ONE}`,
    expected: [`${name} ${data.pct}`, `${name} ${data.pct}%`],
    notes: `[hard] Values (2023, 2024, 2025): ${regions.map((r, i) => `${r} ${data.v[i]!.join('/')}`).join('; ')}. Growth 2023→2025: ${regions.map((r, i) => `${r} ${data.growth[i]!.toFixed(2)}%`).join(', ')}. Winner ${name} (${data.growth[data.best]!.toFixed(2)}% → ${data.pct}); runner-up at least 3 points behind, rounding not near .5, and the largest absolute gain is a different region. Generated by verification/vision/charts.mts (seed 11006).`,
    image: { file: 'chart-c06.png', svg, width: W, height: H },
  };
}

export function buildCharts(): VisionCase[] {
  return [barIncrease(), pieBudget(), lineTemps(), stackedServices(), dualAxis(), groupedGrowth()];
}

export { FONT };
