/**
 * "Precise SVG Illustration": requirement guides and viewer-side measurements.
 *
 * The guides are the numbered requirements of each case prompt drawn as dashed
 * target marks (where a hand must point, where a bar must sit). The
 * measurements are read from the model's SVG code by the UI, for display only:
 * the score still comes from the recorded automatic checks and the judges.
 * Pure (a tiny tag scanner instead of the DOM) so it is unit-tested in Node.
 */

export interface Guide {
  kind: 'ray' | 'rect' | 'line' | 'label';
  x1?: number;
  y1?: number;
  x2?: number;
  y2?: number;
  x?: number;
  y?: number;
  w?: number;
  h?: number;
  text?: string;
}

export interface Measurement {
  label: string;
  target: string;
  measured: string;
  ok: boolean | null;
}

export interface SvgCaseSpec {
  viewBox: [number, number];
  guides: Guide[];
  measure: (els: SvgElement[]) => Measurement[];
}

export interface SvgElement {
  tag: string;
  attrs: Record<string, string>;
  /** Text content (for <text>). */
  text: string;
  /** Rotation/translation inherited from the element and its <g> parents. */
  transform: Affine;
}

type Affine = [number, number, number, number, number, number];
const IDENTITY: Affine = [1, 0, 0, 1, 0, 0];
const mul = (m: Affine, n: Affine): Affine => [
  m[0] * n[0] + m[2] * n[1],
  m[1] * n[0] + m[3] * n[1],
  m[0] * n[2] + m[2] * n[3],
  m[1] * n[2] + m[3] * n[3],
  m[0] * n[4] + m[2] * n[5] + m[4],
  m[1] * n[4] + m[3] * n[5] + m[5],
];

/** Parses rotate(a [cx cy]), translate(x [y]) and matrix(...) lists. Unknown parts are ignored. */
export function parseTransform(t: string | undefined): Affine {
  let m: Affine = IDENTITY;
  if (!t) return m;
  for (const part of t.matchAll(/(rotate|translate|matrix|scale)\s*\(([^)]*)\)/g)) {
    const v = part[2]!.split(/[\s,]+/).filter(Boolean).map(Number);
    let n: Affine = IDENTITY;
    if (part[1] === 'translate') n = [1, 0, 0, 1, v[0] ?? 0, v[1] ?? 0];
    else if (part[1] === 'scale') n = [v[0] ?? 1, 0, 0, v[1] ?? v[0] ?? 1, 0, 0];
    else if (part[1] === 'matrix' && v.length === 6) n = v as Affine;
    else if (part[1] === 'rotate') {
      const a = ((v[0] ?? 0) * Math.PI) / 180;
      const [cx, cy] = [v[1] ?? 0, v[2] ?? 0];
      const cos = Math.cos(a);
      const sin = Math.sin(a);
      n = [cos, sin, -sin, cos, cx - cos * cx + sin * cy, cy - sin * cx - cos * cy];
    }
    m = mul(m, n);
  }
  return m;
}

export const apply = (m: Affine, x: number, y: number): [number, number] => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];

/** Flat list of drawing elements with inherited transforms (no DOM needed). */
export function scanSvg(svg: string): SvgElement[] {
  const out: SvgElement[] = [];
  const stack: Affine[] = [IDENTITY];
  const re = /<(\/?)([a-zA-Z]+)((?:\s+[^\s=>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/?)>/g;
  let m: RegExpExecArray | null;
  let lastText: SvgElement | null = null;
  let lastEnd = 0;
  while ((m = re.exec(svg)) !== null) {
    const [, close, tag, attrText, self] = m;
    if (lastText && !close) lastText = null;
    if (close) {
      if (tag === 'text' && lastText) lastText.text = svg.slice(lastEnd, m.index).replace(/<[^>]*>/g, '').trim();
      if (tag === 'g' && stack.length > 1) stack.pop();
      lastText = null;
      continue;
    }
    const attrs: Record<string, string> = {};
    for (const a of attrText!.matchAll(/([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) attrs[a[1]!] = a[2] ?? a[3] ?? '';
    const t = mul(stack[stack.length - 1]!, parseTransform(attrs.transform));
    if (tag === 'g') {
      if (!self) stack.push(t);
      continue;
    }
    const el: SvgElement = { tag: tag!.toLowerCase(), attrs, text: '', transform: t };
    out.push(el);
    if (el.tag === 'text' && !self) {
      lastText = el;
      lastEnd = m.index + m[0].length;
    }
  }
  return out;
}

const n = (v: string | undefined) => (v === undefined ? NaN : parseFloat(v));
const fmtDeg = (d: number) => `${Math.round(d * 100) / 100}°`;
const fillOf = (e: SvgElement) => (e.attrs.fill ?? /fill\s*:\s*([^;]+)/.exec(e.attrs.style ?? '')?.[1] ?? '').trim().toLowerCase();
const strokeOf = (e: SvgElement) => (e.attrs.stroke ?? /stroke\s*:\s*([^;]+)/.exec(e.attrs.style ?? '')?.[1] ?? '').trim().toLowerCase();

/** Clockwise angle from 12 o'clock of the vector (dx, dy) in SVG axes. */
export function clockAngle(dx: number, dy: number): number {
  return ((Math.atan2(dx, -dy) * 180) / Math.PI + 360) % 360;
}
const angleGap = (a: number, b: number) => {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
};
const isRed = (c: string) => /^(red|crimson|#f00|#ff0000|#e53935|#dc2626|#d32f2f|#c62828|#ef4444|#b91c1c|#e11d48)$/.test(c) || /^#(?:[d-f][0-9a-f])(?:[0-4][0-9a-f])(?:[0-4][0-9a-f])$/.test(c);

function clockHands(els: SvgElement[]): Measurement[] {
  const hands = els
    .filter((e) => e.tag === 'line')
    .map((e) => {
      const a = apply(e.transform, n(e.attrs.x1) || 0, n(e.attrs.y1) || 0);
      const b = apply(e.transform, n(e.attrs.x2) || 0, n(e.attrs.y2) || 0);
      const near = (p: [number, number]) => Math.hypot(p[0] - 200, p[1] - 200) <= 8;
      if (!near(a) && !near(b)) return null;
      const [from, to] = near(a) ? [a, b] : [b, a];
      return { angle: clockAngle(to[0] - from[0], to[1] - from[1]), len: Math.hypot(to[0] - from[0], to[1] - from[1]), red: isRed(strokeOf(e)) };
    })
    .filter((h): h is { angle: number; len: number; red: boolean } => !!h && h.len > 20);
  if (hands.length === 0) return [{ label: 'Clock hands', target: '3 hands from the centre', measured: 'no <line> starts at (200, 200)', ok: false }];
  const second = hands.find((h) => h.red) ?? null;
  const rest = hands.filter((h) => h !== second).sort((a, b) => a.len - b.len);
  const hour = rest[0] ?? null;
  const minute = rest.length > 1 ? rest[rest.length - 1]! : null;
  const row = (label: string, target: number, h: { angle: number } | null): Measurement =>
    h ? { label, target: fmtDeg(target), measured: fmtDeg(h.angle), ok: angleGap(h.angle, target) <= 1 } : { label, target: fmtDeg(target), measured: 'not found', ok: false };
  return [row('Hour hand (shortest)', 304.25, hour), row('Minute hand (longest black)', 51, minute), row('Second hand (red)', 180, second)];
}

const BAR_X = [90, 180, 270, 360, 450];
const BAR_H = [240, 140, 300, 80, 180];
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'];

function barChart(els: SvgElement[]): Measurement[] {
  const bars = els
    .filter((e) => e.tag === 'rect')
    .map((e) => {
      const [x, y] = apply(e.transform, n(e.attrs.x) || 0, n(e.attrs.y) || 0);
      return { x, y, w: n(e.attrs.width), h: n(e.attrs.height), fill: fillOf(e) };
    })
    .filter((r) => r.w >= 40 && r.w <= 80 && r.h > 0 && r.h < 440 && Math.abs(r.y + r.h - 450) <= 3)
    .sort((a, b) => a.x - b.x);
  return DAYS.map((d, i) => {
    const b = bars.find((r) => Math.abs(r.x - BAR_X[i]!) <= 20);
    if (!b) return { label: `${d} bar`, target: `x ${BAR_X[i]}, ${BAR_H[i]} px tall`, measured: 'not found', ok: false };
    const colour = i === 2 ? '#e4572e' : '#29335c';
    const ok = Math.abs(b.x - BAR_X[i]!) <= 1 && Math.abs(b.h - BAR_H[i]!) <= 1 && Math.abs(b.w - 60) <= 1 && b.fill === colour;
    return { label: `${d} bar`, target: `x ${BAR_X[i]} · ${BAR_H[i]} px · ${colour.toUpperCase()}`, measured: `x ${Math.round(b.x)} · ${Math.round(b.h)} px · ${b.fill.toUpperCase() || 'no fill'}`, ok };
  });
}

const PIECES: Array<[string, string, string]> = [
  ['♔', 'e1', 'White king'],
  ['♖', 'h1', 'White rook'],
  ['♙', 'd4', 'White pawn'],
  ['♚', 'e8', 'Black king'],
  ['♛', 'd8', 'Black queen'],
  ['♟', 'f7', 'Black pawn'],
];

function squareOf(x: number, y: number): string {
  const file = Math.floor(x / 50);
  const rank = 8 - Math.floor((y - 10) / 50);
  if (file < 0 || file > 7 || rank < 1 || rank > 8) return 'off the board';
  return `${String.fromCharCode(97 + file)}${rank}`;
}

function chess(els: SvgElement[]): Measurement[] {
  const texts = els.filter((e) => e.tag === 'text');
  const rows = PIECES.map(([glyph, sq, name]): Measurement => {
    const t = texts.find((e) => e.text.includes(glyph));
    if (!t) return { label: `${name} ${glyph}`, target: sq, measured: 'missing', ok: false };
    const [x, y] = apply(t.transform, n(t.attrs.x) || 0, n(t.attrs.y) || 0);
    const at = squareOf(x, y);
    return { label: `${name} ${glyph}`, target: sq, measured: at, ok: at === sq };
  });
  const a1 = els.find((e) => e.tag === 'rect' && Math.abs(n(e.attrs.x) - 0) < 1 && Math.abs(n(e.attrs.y) - 350) < 1 && Math.abs(n(e.attrs.width) - 50) < 1);
  const a1Fill = a1 ? fillOf(a1) : '';
  rows.push({ label: 'a1 square colour', target: 'dark #B58863', measured: a1 ? a1Fill.toUpperCase() : 'not found', ok: a1 ? a1Fill === '#b58863' : null });
  return rows;
}

const deg = (d: number) => ((d - 90) * Math.PI) / 180;
const ray = (angle: number, len: number, text: string): Guide => ({ kind: 'ray', x1: 200, y1: 200, x2: 200 + len * Math.cos(deg(angle)), y2: 200 + len * Math.sin(deg(angle)), text });
const sq = (s: string) => ({ x: (s.charCodeAt(0) - 97) * 50, y: (8 - Number(s[1])) * 50 });

/** Guides and measurements per case of visual.svg-illustration (keyed by case id). */
export const SVG_CASES: Record<string, SvgCaseSpec> = {
  v01: {
    viewBox: [512, 512],
    guides: [
      { kind: 'rect', x: 0, y: 0, w: 256, h: 256, text: 'Moon must be in here' },
      { kind: 'line', x1: 0, y1: 256, x2: 512, y2: 256, text: 'Stars above this line' },
    ],
    measure: () => [],
  },
  v02: {
    viewBox: [400, 400],
    guides: [ray(304.25, 100, 'hour 304.25°'), ray(51, 150, 'minute 51°'), ray(180, 168, 'second 180°')],
    measure: clockHands,
  },
  v03: {
    viewBox: [600, 500],
    guides: [
      { kind: 'line', x1: 60, y1: 450, x2: 580, y2: 450, text: 'baseline y = 450' },
      ...BAR_X.map((x, i): Guide => ({ kind: 'rect', x, y: 450 - BAR_H[i]!, w: 60, h: BAR_H[i]!, text: DAYS[i] })),
    ],
    measure: barChart,
  },
  v04: {
    viewBox: [400, 400],
    guides: PIECES.map(([glyph, s]): Guide => ({ kind: 'rect', ...sq(s), w: 50, h: 50, text: `${glyph} ${s}` })),
    measure: chess,
  },
};

export function svgCaseSpec(testId: string, caseId: string): SvgCaseSpec | null {
  return testId.startsWith('visual.svg-illustration') ? (SVG_CASES[caseId] ?? null) : null;
}
