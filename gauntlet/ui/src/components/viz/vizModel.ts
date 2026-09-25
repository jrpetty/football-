/**
 * Pure data helpers behind the long-context, drawing and picture visuals.
 * They turn a recorded result (score detail + replay) into what the views draw.
 * No React and no DOM, so they are unit-tested in test/visual-pass-long-context.test.ts.
 *
 * Honesty rule: everything here is derived from recorded data. When a field an
 * older result lacks is needed, the model says so (`has…` flags) and the view
 * shows a "not recorded" state instead of guessing.
 */

export type Tone = 'good' | 'bad' | 'warn' | 'neutral';

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v);
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** `replay.visual.data` when its kind matches. */
export function visualOf(replay: unknown, kind: string): Obj | null {
  if (!isObj(replay) || !isObj(replay.visual) || replay.visual.kind !== kind) return null;
  return isObj(replay.visual.data) ? replay.visual.data : null;
}

// ───────────────────────────── Needle in a Haystack ─────────────────────────────

export type NeedleOutcome = 'found' | 'decoy' | 'old-value' | 'hedged' | 'wrong' | 'blank';

export interface Passage {
  before?: string;
  text: string;
  after?: string;
  depth: number | null;
}

export interface NeedleView {
  id: string;
  /** Question number. */
  n: number;
  kind: string;
  kindLabel: string;
  topic: string;
  question: string;
  expected: string;
  answer: string | null;
  outcome: NeedleOutcome;
  /** Where the needle becomes answerable (its deepest part), % of the document. */
  depth: number;
  depths: number[];
  decoyDepths: number[];
  parts: Passage[] | null;
  decoys: Passage[] | null;
}

export interface NeedleZone {
  label: string;
  from: number;
  to: number;
  found: number;
  total: number;
}

export interface NeedleModel {
  words: number | null;
  needles: NeedleView[];
  correct: number;
  total: number;
  zones: NeedleZone[];
  /** Passage text was recorded (runs from test v1.1.1 / hard v1.0.1 on). */
  hasPassages: boolean;
}

export const NEEDLE_KIND_LABEL: Record<string, string> = {
  single: 'single fact',
  'multi-hop': '2-hop chain',
  'three-hop': '3-hop chain',
  aggregate: 'sum',
  superseded: 'corrected value',
};

export const OUTCOME_LABEL: Record<NeedleOutcome, string> = {
  found: 'Found',
  decoy: 'Took the decoy',
  'old-value': 'Used the old value',
  hedged: 'Hedged',
  wrong: 'Wrong',
  blank: 'Gave up',
};

export const OUTCOME_TONE: Record<NeedleOutcome, Tone> = { found: 'good', decoy: 'warn', 'old-value': 'warn', hedged: 'bad', wrong: 'bad', blank: 'bad' };

function outcomeOf(n: Obj): NeedleOutcome {
  if (n.correct === true) return 'found';
  if (n.hedged === true) return 'hedged';
  if (n.trap === 'superseded') return 'old-value';
  if (n.trap === 'distractor') return 'decoy';
  const a = str(n.answer);
  if (!a || /^\s*(unknown|n\/a|none|not found)\b/i.test(a)) return 'blank';
  return 'wrong';
}

function passages(v: unknown): Passage[] | null {
  if (!Array.isArray(v)) return null;
  return v.filter(isObj).map((p) => ({ before: str(p.before) ?? undefined, text: str(p.text) ?? '', after: str(p.after) ?? undefined, depth: num(p.depth) }));
}

export function needleModel(detail: unknown, visual?: Obj | null): NeedleModel | null {
  if (!isObj(detail) || !Array.isArray(detail.needles)) return null;
  const vis = new Map<string, Obj>();
  for (const v of arr(visual?.needles)) if (isObj(v) && typeof v.id === 'string') vis.set(v.id, v);
  const needles: NeedleView[] = detail.needles.filter(isObj).map((n, i) => {
    const id = str(n.id) ?? `Q${i + 1}`;
    const v = vis.get(id);
    const kind = str(n.kind) ?? 'single';
    return {
      id,
      n: Number(id.replace(/\D/g, '')) || i + 1,
      kind,
      kindLabel: NEEDLE_KIND_LABEL[kind] ?? kind,
      topic: str(n.topic) ?? '',
      question: str(n.question) ?? '',
      expected: str(n.expected) ?? String(n.expected ?? ''),
      answer: str(n.answer),
      outcome: outcomeOf(n),
      depth: num(n.depth) ?? 0,
      depths: arr(n.depths).map(Number).filter(Number.isFinite),
      decoyDepths: arr(n.distractorDepths).map(Number).filter(Number.isFinite),
      parts: v ? passages(v.parts) : null,
      decoys: v ? passages(v.decoys) : null,
    };
  });
  return {
    words: num(detail.words) ?? num(visual?.words),
    needles,
    correct: needles.filter((n) => n.outcome === 'found').length,
    total: needles.length,
    zones: needleZones(needles),
    hasPassages: needles.some((n) => !!n.parts?.length && !!n.parts[0]!.text),
  };
}

/** Found-rate in the first, middle and last third of the document ("who reads to the end?"). */
export function needleZones(needles: NeedleView[]): NeedleZone[] {
  return [
    { label: 'Start', from: 0, to: 100 / 3 },
    { label: 'Middle', from: 100 / 3, to: 200 / 3 },
    { label: 'End', from: 200 / 3, to: 100.0001 },
  ].map((z) => {
    const inZone = needles.filter((n) => n.depth >= z.from && n.depth < z.to);
    return { ...z, found: inZone.filter((n) => n.outcome === 'found').length, total: inZone.length };
  });
}

/** The needle a replay frame is about (frames are labelled "64% · 2-hop · Q7"). */
export function needleForFrame(m: NeedleModel, label: string | undefined): NeedleView | undefined {
  const q = label?.match(/\bQ(\d+)\b/);
  return q ? m.needles.find((n) => n.n === Number(q[1])) : undefined;
}

const pct = (x: number) => `${Math.round(x)}%`;
const quote = (s: string | null, max = 60) => {
  const t = (s ?? '').replace(/\s+/g, ' ').trim();
  return `“${t.length > max ? `${t.slice(0, max - 1)}…` : t}”`;
};

export function needleHeadline(n: NeedleView): { title: string; tone: Tone } {
  switch (n.outcome) {
    case 'found':
      return { title: `Found it ${pct(n.depth)} of the way in: ${quote(n.answer)}`, tone: 'good' };
    case 'decoy':
      return { title: `Took the look-alike decoy: said ${quote(n.answer)}`, tone: 'warn' };
    case 'old-value':
      return { title: `Used the old value: the text corrected it later`, tone: 'warn' };
    case 'hedged':
      return { title: `Hedged between answers, so it scores zero`, tone: 'bad' };
    case 'blank':
      return { title: `Gave up: the answer was ${pct(n.depth)} of the way in`, tone: 'bad' };
    default:
      return { title: `Wrong: said ${quote(n.answer)}`, tone: 'bad' };
  }
}

/** Word position for a depth, e.g. "word 28,900 of 45,210". */
export function wordAt(depth: number, words: number | null): string {
  if (!words) return `${pct(depth)} in`;
  return `word ${Math.round((depth / 100) * words).toLocaleString('en-US')} of ${words.toLocaleString('en-US')}`;
}

// ───────────────────────────── Chain of Whispers ─────────────────────────────

export type LaneStatus = 'kept' | 'changed' | 'lost';

export interface WhisperColumn {
  /** 0 = the source story, then one per rewrite. */
  round: number;
  kind: 'source' | 'summary' | 'story';
  cycle: number;
  label: string;
  short: string;
  words: number | null;
  limit: number | null;
  truncated: boolean;
  failed: boolean;
  text: string | null;
  trace: Record<string, { status: LaneStatus; sentence?: string }> | null;
}

export interface WhisperFact {
  id: string;
  label: string;
  canonical: string;
  survived: boolean;
}

export interface WhispersModel {
  facts: WhisperFact[];
  columns: WhisperColumn[];
  /** status[fact][column]. */
  status: LaneStatus[][];
  total: number;
  survived: number;
  /** Round texts and the per-fact trace were recorded (v1.1.1 / hard v1.0.1 on). */
  hasTrace: boolean;
}

export function whispersModel(detail: unknown, visual?: Obj | null): WhispersModel | null {
  if (!isObj(detail) || !Array.isArray(detail.facts) || !Array.isArray(detail.rounds)) return null;
  const facts: WhisperFact[] = detail.facts.filter(isObj).map((f) => ({ id: str(f.id) ?? '', label: str(f.label) ?? '', canonical: str(f.canonical) ?? '', survived: f.survived === true }));
  const vRounds = new Map<number, Obj>();
  for (const r of arr(visual?.rounds)) if (isObj(r) && typeof r.round === 'number') vRounds.set(r.round, r);
  const traceOf = (v: unknown) => (isObj(v) ? (v as WhisperColumn['trace']) : null);
  const source = isObj(visual?.source) ? visual!.source : null;
  const story = isObj(detail.story) ? detail.story : {};
  const columns: WhisperColumn[] = [
    { round: 0, kind: 'source', cycle: 0, label: 'Original story', short: 'Source', words: num(story.words), limit: null, truncated: false, failed: false, text: str(source?.text), trace: traceOf(source?.trace) },
    ...detail.rounds.filter(isObj).map((r): WhisperColumn => {
      const round = num(r.round) ?? 0;
      const kind = r.kind === 'summary' ? 'summary' : 'story';
      const cycle = num(r.cycle) ?? 0;
      const v = vRounds.get(round);
      return {
        round,
        kind,
        cycle,
        label: `Cycle ${cycle} · ${kind === 'summary' ? 'Summary' : 'Story'}`,
        short: `${cycle}${kind === 'summary' ? 'S' : 'E'}`,
        words: num(r.words),
        limit: num(r.limit),
        truncated: r.truncated === true,
        failed: r.failed === true,
        text: str(v?.text),
        trace: traceOf(v?.trace),
      };
    }),
  ];
  const lostAt = new Map<string, number>();
  for (const r of detail.rounds.filter(isObj)) for (const id of arr(r.lost)) if (typeof id === 'string' && !lostAt.has(id)) lostAt.set(id, num(r.round) ?? 0);
  const status = facts.map((f) =>
    columns.map((c): LaneStatus => {
      const t = c.trace?.[f.id];
      if (t && (t.status === 'kept' || t.status === 'changed' || t.status === 'lost')) return t.status;
      if (c.failed) return 'lost';
      if (c.round === 0) return 'kept';
      const at = lostAt.get(f.id);
      return at !== undefined && c.round >= at ? 'lost' : 'kept';
    }),
  );
  return {
    facts,
    columns,
    status,
    total: num(detail.total) ?? facts.length,
    survived: num(detail.survived) ?? facts.filter((f) => f.survived).length,
    hasTrace: columns.some((c) => c.trace !== null),
  };
}

/** What changed at a column: the facts lost, drifted or recovered since the previous one. */
export function whisperDelta(m: WhispersModel, col: number): { lost: WhisperFact[]; changed: WhisperFact[]; kept: number } {
  const lost: WhisperFact[] = [];
  const changed: WhisperFact[] = [];
  let kept = 0;
  m.facts.forEach((f, i) => {
    const now = m.status[i]![col]!;
    const before = col > 0 ? m.status[i]![col - 1]! : 'kept';
    if (now === 'kept') kept++;
    if (now === 'lost' && before !== 'lost') lost.push(f);
    if (now === 'changed' && before === 'kept') changed.push(f);
  });
  return { lost, changed, kept };
}

export function whisperHeadline(m: WhispersModel, col: number): { title: string; tone: Tone } {
  const c = m.columns[col]!;
  if (c.round === 0) return { title: `The original story: ${m.facts.length} facts to keep alive`, tone: 'neutral' };
  if (c.failed) return { title: `The chain broke: empty reply, every fact lost`, tone: 'bad' };
  const d = whisperDelta(m, col);
  const names = (fs: WhisperFact[]) => fs.slice(0, 2).map((f) => f.label).join(' and ') + (fs.length > 2 ? ` +${fs.length - 2} more` : '');
  if (d.lost.length) return { title: `${c.kind === 'summary' ? 'Squeezed' : 'Retold'}: lost the ${names(d.lost)}`, tone: 'bad' };
  if (d.changed.length) return { title: `The ${names(d.changed)} started to drift`, tone: 'warn' };
  const n = m.facts.length;
  if (col === m.columns.length - 1) {
    const share = d.kept / Math.max(1, n);
    return { title: `${d.kept} of ${n} facts survived the whole chain`, tone: share >= 0.75 ? 'good' : share >= 0.4 ? 'warn' : 'bad' };
  }
  return d.kept === n ? { title: `Nothing lost: all ${n} facts still intact`, tone: 'good' } : { title: `No new losses: ${d.kept} of ${n} facts still intact`, tone: 'neutral' };
}

export interface TextSegment {
  text: string;
  factIds?: string[];
  status?: 'kept' | 'changed';
}

/** Splits a round's text into plain runs and highlighted fact sentences. */
export function highlightFacts(text: string, trace: WhisperColumn['trace']): TextSegment[] {
  if (!trace) return [{ text }];
  const spans: Array<{ start: number; end: number; ids: string[]; status: 'kept' | 'changed' }> = [];
  for (const [id, t] of Object.entries(trace)) {
    if (!t.sentence || (t.status !== 'kept' && t.status !== 'changed')) continue;
    const start = text.indexOf(t.sentence);
    if (start < 0) continue;
    const end = start + t.sentence.length;
    const same = spans.find((s) => s.start === start && s.end === end);
    if (same) {
      same.ids.push(id);
      if (t.status === 'kept') same.status = 'kept';
    } else spans.push({ start, end, ids: [id], status: t.status });
  }
  spans.sort((a, b) => a.start - b.start);
  const out: TextSegment[] = [];
  let at = 0;
  for (const s of spans) {
    if (s.start < at) continue; // overlapping sentence: keep the first
    if (s.start > at) out.push({ text: text.slice(at, s.start) });
    out.push({ text: text.slice(s.start, s.end), factIds: s.ids, status: s.status });
    at = s.end;
  }
  if (at < text.length) out.push({ text: text.slice(at) });
  return out;
}

// ───────────────────────────── Draw It Blind ─────────────────────────────

export interface DrawShape {
  i: number;
  type: string;
  color: string;
  cx: number;
  cy: number;
  w: number;
  h: number;
  angle: number | null;
}

export interface DrawPair {
  target: DrawShape;
  drawn: { kind: string; color: string; cx: number; cy: number; w: number; h: number } | null;
  score: number;
  type: number;
  color: number;
  position: number;
  size: number;
  distance: number | null;
}

export interface DrawModel {
  canvas: number;
  shapes: DrawShape[];
  pairs: DrawPair[];
  extras: Array<{ kind: string; color: string; cx: number; cy: number; w: number; h: number }>;
  description: string;
  delivered: string;
  words: number;
  limit: number | null;
  truncated: boolean;
  violations: string[];
  match: number;
  rebuilt: number;
  penalty: number;
  targetSvg: string | null;
  drawnSvg: string | null;
}

export function drawModel(detail: unknown, replay: unknown): DrawModel | null {
  if (!isObj(detail) || !Array.isArray(detail.scene) || !Array.isArray(detail.matches)) return null;
  const vis = visualOf(replay, 'draw-it-blind');
  const shapes: DrawShape[] = detail.scene.filter(isObj).map((s, i) => ({
    i,
    type: str(s.type) ?? 'shape',
    color: str(s.color) ?? 'gray',
    cx: num(s.cx) ?? 0,
    cy: num(s.cy) ?? 0,
    w: num(s.w) ?? 0,
    h: num(s.h) ?? 0,
    angle: num(arr(vis?.angles)[i]),
  }));
  const pairs: DrawPair[] = detail.matches.filter(isObj).map((p, i) => {
    const d = isObj(p.drawn) ? p.drawn : null;
    return {
      target: shapes[i]!,
      drawn: d ? { kind: str(d.kind) ?? '?', color: str(d.color) ?? '?', cx: num(d.cx) ?? 0, cy: num(d.cy) ?? 0, w: num(d.w) ?? 0, h: num(d.h) ?? 0 } : null,
      score: num(p.score) ?? 0,
      type: num(p.type) ?? 0,
      color: num(p.color) ?? 0,
      position: num(p.position) ?? 0,
      size: num(p.size) ?? 0,
      distance: num(p.distance),
    };
  }).filter((p) => !!p.target);
  const svgCompare = isObj(replay) && isObj(replay.svgCompare) ? replay.svgCompare : null;
  const pen = isObj(detail.penalties) ? detail.penalties : {};
  return {
    canvas: num(vis?.canvas) ?? 400,
    shapes,
    pairs,
    extras: arr(vis?.extras).filter(isObj).map((e) => ({ kind: str(e.kind) ?? '?', color: str(e.color) ?? '?', cx: num(e.cx) ?? 0, cy: num(e.cy) ?? 0, w: num(e.w) ?? 0, h: num(e.h) ?? 0 })),
    description: str(detail.description) ?? '',
    delivered: str(detail.deliveredDescription) ?? '',
    words: num(detail.descriptionWords) ?? 0,
    limit: num(vis?.limit),
    truncated: detail.descriptionTruncated === true,
    violations: arr(detail.numberViolations).map(String),
    match: num(detail.match) ?? 0,
    rebuilt: num(detail.rebuilt) ?? 0,
    penalty: (num(pen.extras) ?? 0) + (num(pen.description) ?? 0),
    targetSvg: isObj(svgCompare?.left) ? str(svgCompare!.left.svg) : null,
    drawnSvg: isObj(svgCompare?.right) ? str(svgCompare!.right.svg) : null,
  };
}

export const pairTone = (score: number): Tone => (score >= 0.75 ? 'good' : score >= 0.5 ? 'warn' : 'bad');

const TYPE_WORDS: Record<string, string[]> = {
  circle: ['circle', 'circles', 'disc', 'disk', 'dot', 'ball', 'round'],
  square: ['square', 'squares'],
  rectangle: ['rectangle', 'rectangles', 'bar', 'box', 'block', 'oblong'],
  triangle: ['triangle', 'triangles'],
  star: ['star', 'stars'],
};

export interface PhraseLink {
  start: number;
  end: number;
  /** Index into DrawModel.shapes. */
  shape: number;
}

/**
 * Links phrases of the description to the shapes they describe: a colour word
 * followed (within four words) by that shape's type word, e.g. "large red circle".
 * Shapes sharing colour and type are linked in order of mention. Unlinked
 * shapes simply get no phrase: this is a reading aid, not part of the score.
 */
export function phraseLinks(text: string, shapes: DrawShape[]): PhraseLink[] {
  const words: Array<{ w: string; start: number; end: number }> = [];
  for (const m of text.matchAll(/[A-Za-z]+/g)) words.push({ w: m[0].toLowerCase(), start: m.index!, end: m.index! + m[0].length });
  const used = new Set<number>();
  const links: PhraseLink[] = [];
  for (const s of shapes) {
    const types = TYPE_WORDS[s.type] ?? [s.type];
    for (let i = 0; i < words.length; i++) {
      if (used.has(i) || words[i]!.w !== s.color) continue;
      let j = -1;
      for (let k = i + 1; k <= Math.min(words.length - 1, i + 4); k++) {
        if (types.includes(words[k]!.w)) {
          j = k;
          break;
        }
      }
      if (j < 0) continue;
      used.add(i);
      links.push({ start: words[i]!.start, end: words[j]!.end, shape: s.i });
      break;
    }
  }
  return links.sort((a, b) => a.start - b.start);
}

// ───────────────────────────── Vision ─────────────────────────────

export interface VisionGrid {
  id: string;
  x0: number;
  y0: number;
  size: number;
  n: number;
}

export interface VisionLayout {
  width: number;
  height: number;
  grids?: VisionGrid[];
  targets?: Array<{ x: number; y: number; r: number }>;
  highlights?: Array<{ shape: 'rect' | 'dot'; x: number; y: number; w?: number; h?: number; label?: string }>;
  lines?: Array<{ key: string; y: number; h: number }>;
}

function parseJsonish(v: unknown): unknown {
  if (typeof v !== 'string') return v;
  const t = v.trim();
  if (!/^[[{]/.test(t)) return v;
  try {
    return JSON.parse(t);
  } catch {
    return v;
  }
}

export interface CellAnswer {
  cells: string[];
  /** Typed answers ("differences"): cell → kind of change. */
  changes?: Record<string, string>;
}

const cellName = (c: unknown) => (typeof c === 'string' ? c.trim().toUpperCase() : '');

/** Reads {"cells": [...]} or {"differences": [{cell, change}]} from an answer (JSON string or object). */
export function parseCells(v: unknown): CellAnswer | null {
  const o = parseJsonish(v);
  if (!isObj(o)) return null;
  if (Array.isArray(o.cells)) return { cells: o.cells.map(cellName).filter(Boolean) };
  if (Array.isArray(o.differences)) {
    const changes: Record<string, string> = {};
    const cells: string[] = [];
    for (const d of o.differences) {
      if (!isObj(d)) continue;
      const c = cellName(d.cell);
      if (!c) continue;
      cells.push(c);
      if (typeof d.change === 'string') changes[c] = d.change.toLowerCase() === 'color' ? 'colour' : d.change.toLowerCase();
    }
    return { cells, changes };
  }
  return null;
}

export interface CellMark {
  cell: string;
  status: 'correct' | 'wrong' | 'missed';
  /** Typed answers: the true change and the one the model named. */
  change?: string;
  said?: string;
}

/** Model cells vs true cells: correct (both), wrong (only the model), missed (only the key). */
export function cellMarks(model: CellAnswer | null, truth: CellAnswer): CellMark[] {
  const said = new Set(model?.cells ?? []);
  const real = new Set(truth.cells);
  const out: CellMark[] = [];
  for (const c of truth.cells) {
    const mark: CellMark = { cell: c, status: said.has(c) ? 'correct' : 'missed', change: truth.changes?.[c], said: model?.changes?.[c] };
    if (mark.status === 'correct' && mark.change && mark.said && mark.change !== mark.said) mark.status = 'wrong';
    out.push(mark);
  }
  for (const c of said) if (!real.has(c)) out.push({ cell: c, status: 'wrong', said: model?.changes?.[c] });
  return out;
}

/** Pixel box of a cell ("C4") in a lettered grid, or null when it is outside the grid. */
export function cellRect(g: VisionGrid, cell: string): { x: number; y: number; w: number; h: number } | null {
  const m = /^([A-Z])(\d{1,2})$/.exec(cell);
  if (!m) return null;
  const c = m[1]!.charCodeAt(0) - 65;
  const r = Number(m[2]) - 1;
  if (c < 0 || r < 0 || c >= g.n || r >= g.n) return null;
  const s = g.size / g.n;
  return { x: g.x0 + c * s, y: g.y0 + r * s, w: s, h: s };
}

/** A number from an answer ("7", "7 red triangles", 7). */
export function parseNumber(v: unknown): number | null {
  if (typeof v === 'number') return v;
  if (typeof v !== 'string') return null;
  const m = v.replace(/,/g, '').match(/-?\d+(?:\.\d+)?/);
  return m ? Number(m[0]) : null;
}

/** The "line" field of a {"line": 3, …} answer. */
export function parseLine(v: unknown): number | null {
  const o = parseJsonish(v);
  return isObj(o) ? num(o.line) : null;
}

/** An answer as a viewer should read it (JSON flattened, first accepted alias). */
export function showAnswer(v: unknown): string {
  const o = parseJsonish(v);
  if (o === undefined || o === null || o === '') return '—';
  if (typeof o === 'string' || typeof o === 'number') return String(o);
  if (Array.isArray(o)) return o.length ? String(o[0]) : '—';
  if (isObj(o)) {
    if (Array.isArray(o.cells)) return o.cells.join(', ') || '(none)';
    if (Array.isArray(o.differences)) return o.differences.map((d) => (isObj(d) ? `${d.cell} ${d.change ?? ''}`.trim() : '')).join(', ');
    return Object.entries(o)
      .map(([k, x]) => `${k} ${typeof x === 'object' ? JSON.stringify(x) : x}`)
      .join(' · ');
  }
  return String(o);
}
