/**
 * Rung thumbnails: a tiny drawing of WHAT one level of a difficulty ladder looks like, so a viewer sees the task
 * getting bigger as the ladder goes up (a 4×3 logic grid at the bottom, a 12×8 one with 172 clues at the top).
 *
 * Every picture is drawn from the level's real parameters (data.ts, generated from the test files by
 * scripts/rung-thumbs-data.ts). Small counts are drawn one mark per item; where a count is too big to draw
 * (949 records, 879 receipt lines) the marks get denser and a badge gives the real number. Colours come from the
 * theme (currentColor plus the accent, good and bad tokens), so a thumbnail works in dark, light and broadcast.
 *
 * Used by the ladder views: `hasRungThumb(testId)` says whether a ladder has thumbnails, `<RungThumb testId level />`
 * draws level 1..10 (null for anything else).
 */
import type { ReactNode } from 'react';
import { RUNG_THUMB_DATA } from './data.ts';
import type {
  ContractRung,
  CrmRung,
  EngineeringRung,
  ExactRung,
  ExpensesRung,
  GridRung,
  InvoicesRung,
  MindRunnerRung,
  ModpowRung,
  NonogramRung,
  PayrollRung,
  PlansRung,
  ReconciliationRung,
  RungThumbDataMap,
  RungThumbTestId,
  SlidingRung,
  SqlRung,
  SupportRung,
  TilingRung,
  TruthRung,
} from './types.ts';
import './rung-thumbs.css';

export type RungThumbTone = 'default' | 'broadcast';

export interface RungThumbProps {
  testId: string;
  /** 1..10 (L01..L10). */
  level: number;
  /** Rendered box in px (default 48; designed for 32 to 96). */
  size?: number;
  tone?: RungThumbTone;
}

/* ───────── geometry helpers (all in a 48×48 viewBox) ───────── */

const BOX = 48;
const PAD = 3;
const INNER = BOX - 2 * PAD;

interface Ctx {
  /** Base stroke width in viewBox units. */
  sw: number;
  broadcast: boolean;
  level: number;
}

interface Drawn {
  body: ReactNode;
  label: string;
  /** Short count shown in the corner when the marks are too many to count. */
  badge?: string;
  /** Badge drawn as a speech bubble (statements, replies). */
  bubble?: boolean;
}

const r2 = (v: number) => Math.round(v * 100) / 100;

/** The grid (cols × rows) that fits n square cells in w × h with the biggest pitch, capped at `max`. */
function fit(n: number, w: number, h: number, max = Infinity): { cols: number; rows: number; pitch: number } {
  let best = { cols: 1, rows: Math.max(1, n), pitch: 0 };
  for (let cols = 1; cols <= Math.max(1, n); cols++) {
    const rows = Math.ceil(n / cols);
    const pitch = Math.min(w / cols, h / rows, max);
    // On a tie (the cap reached), prefer the squarer block over one long column.
    const squarer = Math.abs(pitch - best.pitch) < 1e-9 && Math.abs(cols - rows) < Math.abs(best.cols - best.rows);
    if (pitch > best.pitch + 1e-9 || squarer) best = { cols, rows, pitch };
  }
  return best;
}

/** Deterministic pseudo-random numbers (mulberry32): the same picture on every render and every machine. */
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** k of n indexes, chosen pseudo-randomly but reproducibly. */
function pick(n: number, k: number, seed: number): Set<number> {
  const idx = Array.from({ length: n }, (_, i) => i);
  const rand = rng(seed);
  for (let i = 0; i < Math.min(k, n); i++) {
    const j = i + Math.floor(rand() * (n - i));
    [idx[i], idx[j]] = [idx[j]!, idx[i]!];
  }
  return new Set(idx.slice(0, Math.min(k, n)));
}

/** k of n indexes spread evenly (index i is chosen when the running share crosses a whole number). */
function spread(n: number, k: number): Set<number> {
  const out = new Set<number>();
  if (k <= 0) return out;
  for (let i = 0; i < n; i++) if (Math.floor(((i + 1) * k) / n) > Math.floor((i * k) / n)) out.add(i);
  return out;
}

function rectD(x: number, y: number, w: number, h: number): string {
  return `M${r2(x)} ${r2(y)}h${r2(w)}v${r2(h)}h${r2(-w)}z`;
}

function dotD(x: number, y: number, r: number): string {
  return `M${r2(x - r)} ${r2(y)}a${r2(r)} ${r2(r)} 0 1 0 ${r2(2 * r)} 0a${r2(r)} ${r2(r)} 0 1 0 ${r2(-2 * r)} 0z`;
}

const plain = (n: number) => n.toLocaleString('en-GB');
const plural = (n: number, one: string, many = `${one}s`) => `${plain(n)} ${n === 1 ? one : many}`;
const sum = (a: number[]) => a.reduce((x, y) => x + y, 0);

/** 155 → "155", 2,187 → "2.2k", 73,119 → "73k". */
function compact(n: number): string {
  if (n < 1000) return String(n);
  if (n < 10000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return `${Math.round(n / 1000)}k`;
}

function Badge({ text, bubble, broadcast, size }: { text: string; bubble?: boolean; broadcast: boolean; size: number }) {
  // About 9px text at 48px; a little smaller in the box at bigger sizes so the badge does not take over the picture.
  const fs = (broadcast ? 10 : 9) * Math.min(1, Math.max(0.6, 60 / size));
  const w = text.length * fs * 0.6 + 5;
  const h = fs + 3;
  const x = BOX - w - 0.5;
  const y = BOX - h - (bubble ? 2.7 : 0.5);
  return (
    <g className="rt-badge" aria-hidden="true">
      {bubble ? (
        <path d={`M${r2(x + 3)} ${r2(y)}h${r2(w - 6)}a3 3 0 0 1 3 3v${r2(h - 6)}a3 3 0 0 1 -3 3h${r2(-(w - 9))}l-3 2.4v-2.4a3 3 0 0 1 -3 -3v${r2(-(h - 6))}a3 3 0 0 1 3 -3z`} strokeWidth={0.8} style={{ fill: 'var(--surface-1)', stroke: 'var(--border-strong)' }} />
      ) : (
        <rect x={r2(x)} y={r2(y)} width={r2(w)} height={h} rx={3} strokeWidth={0.8} />
      )}
      <text x={r2(x + w / 2)} y={r2(y + h / 2 + fs * 0.36)} fontSize={fs} textAnchor="middle">
        {text}
      </text>
    </g>
  );
}

/* ───────── Horizon ───────── */

/** Run It In Your Head: a program page whose execution trace gets longer, with a loop arrow. */
function mindRunner(d: MindRunnerRung, c: Ctx): Drawn {
  // The page and its trace grow with the log of statements executed: 155 → a short page of 5 rows, 73,119 → a full page of 17.
  const t = Math.min(1, Math.max(0, (Math.log10(d.steps) - 2) / 3));
  const rows = Math.round(4 + 13 * t);
  const pageTop = 45 - (18 + 24 * t);
  const top = pageTop + 4.5;
  const bottom = 41.5;
  const pitch = (bottom - top) / rows;
  const rand = rng(d.steps);
  const indents = [0, 1, 1, 2, 2, 1, 2, 3, 2, 1];
  let lines = '';
  for (let i = 0; i < rows; i++) {
    const ind = indents[(i * 3 + Math.floor(rand() * 3)) % indents.length]! * 2.6;
    const len = 5 + rand() * (15 - ind);
    lines += `M${r2(8 + ind)} ${r2(top + (i + 0.5) * pitch)}h${r2(len)}`;
  }
  return {
    label: `Level ${c.level}: ${d.lines}-line program, ${plain(d.steps)} steps to run in your head`,
    badge: compact(d.steps),
    body: (
      <>
        <rect className="rt-paper" x={4} y={r2(pageTop)} width={27} height={r2(45 - pageTop)} rx={2.5} strokeWidth={c.sw} />
        <path className="rt-line" d={lines} strokeWidth={r2(Math.min(2.2, pitch * 0.55))} strokeOpacity={0.8} />
        <path className="rt-acc-line" d={`M33.5 ${r2(bottom - 4)} C40.5 ${r2(bottom - 4)} 40.5 ${r2(top + 1)} 33.5 ${r2(top + 1)}`} strokeWidth={c.sw * 1.3} />
        <path className="rt-acc-line" d={`M36 ${r2(top - 1.5)} L33.3 ${r2(top + 1)} L36 ${r2(top + 3.5)}`} strokeWidth={c.sw * 1.3} />
      </>
    ),
  };
}

/** No Calculator: a^e mod m drawn as digit tiles, the modulus big, the exponent as a superscript block. */
function modpow(d: ModpowRung, c: Ctx): Drawn {
  // Base digits 8 to a row (5.2 apart), the exponent's 8 to a row at half size, sitting on top like a power.
  const per = 8;
  const pitch = 5.2;
  const cell = 4.3;
  const rows = Math.ceil(d.mDigits / per);
  const baseTop = 45 - rows * pitch + (pitch - cell);
  let base = '';
  for (let i = 0; i < d.mDigits; i++) base += rectD(PAD + (i % per) * pitch, baseTop + Math.floor(i / per) * pitch, cell, cell);
  const ePer = 8;
  const ePitch = 2.45;
  const eCell = 1.95;
  const eRows = Math.ceil(d.eDigits / ePer);
  const eTop = baseTop - 1.4 - eRows * ePitch;
  const eLeft = 45 - ePer * ePitch + (ePitch - eCell);
  let exp = '';
  for (let i = 0; i < d.eDigits; i++) exp += rectD(eLeft + (i % ePer) * ePitch, eTop + Math.floor(i / ePer) * ePitch, eCell, eCell);
  return {
    label: `Level ${c.level}: ${d.mDigits}-digit numbers, a ${d.eDigits}-digit power, about ${d.multiplications} multiplications by hand`,
    body: (
      <>
        <path className="rt-mid" d={base} />
        <path className="rt-acc" d={exp} />
      </>
    ),
  };
}

/** The Sliding Ladder: the start board (tiles and the gap) and a trail with one dot per move of the shortest plan. */
function sliding(d: SlidingRung, c: Ctx): Drawn {
  const p = 8.5;
  const x0 = BOX / 2 - (d.cols * p) / 2;
  const y0 = PAD + (34 - d.rows * p) / 2;
  let tiles = '';
  for (let r = 0; r < d.rows; r++) for (let k = 0; k < d.cols; k++) if (r !== d.blank[0] || k !== d.blank[1]) tiles += rectD(x0 + k * p + 0.6, y0 + r * p + 0.6, p - 1.2, p - 1.2);
  const per = Math.ceil(d.moves / 2);
  const dp = 1.5;
  let trail = '';
  const dr = c.broadcast ? 0.68 : 0.55;
  for (let i = 0; i < d.moves; i++) trail += dotD(PAD + 0.7 + (i % per) * dp, 40.6 + Math.floor(i / per) * 2.8, dr);
  return {
    label: `Level ${c.level}: ${d.rows} by ${d.cols} sliding board, shortest plan ${d.moves} moves`,
    body: (
      <>
        <rect className="rt-faint" x={r2(x0)} y={r2(y0)} width={r2(d.cols * p)} height={r2(d.rows * p)} rx={1.5} strokeWidth={c.sw * 0.8} />
        <path className="rt-mid" d={tiles} />
        <rect className="rt-acc-line" x={r2(x0 + d.blank[1] * p + 0.9)} y={r2(y0 + d.blank[0] * p + 0.9)} width={p - 1.8} height={p - 1.8} rx={1} strokeWidth={c.sw * 0.9} strokeDasharray="1.6 1.2" />
        <path className="rt-acc" d={trail} />
      </>
    ),
  };
}

/** The Picture Logic Ladder: an n×n grid with its clue bands; exactly `filled` cells are painted (the picture is random). */
function nonogram(d: NonogramRung, c: Ctx): Drawn {
  const band = 7;
  // The board grows with the log of its side (8 → 17 units, 50 → 35) and its cells get finer.
  const side = Math.min(INNER - band, 17 + (18 * Math.log(d.size / 8)) / Math.log(50 / 8));
  const cell = side / d.size;
  const x0 = (BOX - band - side) / 2 + band;
  const y0 = x0;
  const chosen = pick(d.size * d.size, d.filled, d.size * 7919 + d.filled);
  let fill = '';
  for (const i of chosen) fill += rectD(x0 + (i % d.size) * cell, y0 + Math.floor(i / d.size) * cell, cell, cell);
  let fine = '';
  let fives = '';
  for (let i = 1; i < d.size; i++) {
    const seg = `M${r2(x0 + i * cell)} ${r2(y0)}v${r2(side)}M${r2(x0)} ${r2(y0 + i * cell)}h${r2(side)}`;
    if (d.size >= 15 && i % 5 === 0) fives += seg;
    else if (cell >= 1.5) fine += seg;
  }
  let clues = '';
  const unit = 1.45;
  d.rowRuns.forEach((n, i) => {
    const len = Math.min(band - 1.4, n * unit);
    clues += rectD(x0 - 0.9 - len, y0 + i * cell + cell * 0.2, len, cell * 0.6);
  });
  d.colRuns.forEach((n, i) => {
    const len = Math.min(band - 1.4, n * unit);
    clues += rectD(x0 + i * cell + cell * 0.2, y0 - 0.9 - len, cell * 0.6, len);
  });
  return {
    label: `Level ${c.level}: ${d.size} by ${d.size} picture grid (${plain(d.size * d.size)} cells), ${plain(d.filled)} to paint`,
    body: (
      <>
        <rect className="rt-paper" x={x0} y={y0} width={r2(side)} height={r2(side)} strokeWidth={c.sw * 0.7} />
        <path className="rt-ink" d={fill} fillOpacity={0.82} />
        {fine && <path className="rt-faint" d={fine} strokeWidth={0.22} />}
        {fives && <path d={fives} strokeWidth={r2(Math.max(0.45, cell * 0.55))} style={{ stroke: 'var(--rt-paper)' }} />}
        <path className="rt-acc" d={clues} />
      </>
    ),
  };
}

const tilingCache = new Map<string, Array<[number, number]>>();

/** One real domino covering of the board (bipartite matching), so the thumbnail shows an actual answer shape. */
function dominoes(d: TilingRung): Array<[number, number]> {
  const key = `${d.rows}x${d.cols}:${d.holes.join(',')}`;
  const hit = tilingCache.get(key);
  if (hit) return hit;
  const holes = new Set(d.holes);
  const n = d.rows * d.cols;
  const mate = new Array<number>(n).fill(-1);
  const nbrs = (i: number) => {
    const r = Math.floor(i / d.cols);
    const k = i % d.cols;
    const out: number[] = [];
    // Alternate the preferred direction by row so the covering looks like a brick pattern, not stripes.
    const order = r % 2 === 0 ? [[0, 1], [1, 0], [0, -1], [-1, 0]] : [[1, 0], [0, 1], [-1, 0], [0, -1]];
    for (const [dr, dk] of order) {
      const rr = r + dr!;
      const kk = k + dk!;
      if (rr >= 0 && rr < d.rows && kk >= 0 && kk < d.cols && !holes.has(rr * d.cols + kk)) out.push(rr * d.cols + kk);
    }
    return out;
  };
  const tryAug = (u: number, seen: Set<number>): boolean => {
    for (const v of nbrs(u)) {
      if (seen.has(v)) continue;
      seen.add(v);
      if (mate[v] === -1 || tryAug(mate[v]!, seen)) {
        mate[v] = u;
        mate[u] = v;
        return true;
      }
    }
    return false;
  };
  for (let i = 0; i < n; i++) {
    const r = Math.floor(i / d.cols);
    if (holes.has(i) || (r + (i % d.cols)) % 2 !== 0 || mate[i] !== -1) continue;
    tryAug(i, new Set());
  }
  const out: Array<[number, number]> = [];
  for (let i = 0; i < n; i++) if (mate[i]! > i) out.push([i, mate[i]!]);
  tilingCache.set(key, out);
  return out;
}

/** Domino tilings: the board with its holes, covered by one real tiling. */
function tiling(d: TilingRung, c: Ctx): Drawn {
  // 4.2 units a square until the board fills the box, so a 6×6 board is visibly smaller than a 12×12 one.
  const cell = Math.min(4.2, INNER / Math.max(d.rows, d.cols));
  const x0 = BOX / 2 - (d.cols * cell) / 2;
  const y0 = BOX / 2 - (d.rows * cell) / 2;
  const g = cell * 0.13;
  let tiles = '';
  for (const [a, b] of dominoes(d)) {
    const ra = Math.floor(a / d.cols);
    const ka = a % d.cols;
    const rb = Math.floor(b / d.cols);
    const kb = b % d.cols;
    const x = x0 + Math.min(ka, kb) * cell + g;
    const y = y0 + Math.min(ra, rb) * cell + g;
    tiles += rectD(x, y, (Math.abs(kb - ka) + 1) * cell - 2 * g, (Math.abs(rb - ra) + 1) * cell - 2 * g);
  }
  let holes = '';
  for (const h of d.holes) holes += rectD(x0 + (h % d.cols) * cell + g, y0 + Math.floor(h / d.cols) * cell + g, cell - 2 * g, cell - 2 * g);
  return {
    label: `Level ${c.level}: ${d.rows} by ${d.cols} board, ${d.squares} squares to cover with dominoes`,
    body: (
      <>
        <rect className="rt-faint" x={r2(x0)} y={r2(y0)} width={r2(d.cols * cell)} height={r2(d.rows * cell)} strokeWidth={c.sw * 0.7} />
        <path className="rt-paper" d={tiles} strokeWidth={r2(Math.min(c.sw * 0.7, cell * 0.16))} />
        <path className="rt-bad" d={holes} />
      </>
    ),
  };
}

/* ───────── Frontier ───────── */

/** Deduction Grid: positions × categories, and one dot per clue (compound clues in the accent). */
function grid(d: GridRung, c: Ctx): Drawn {
  const cell = Math.min(5.2, INNER / d.positions, 24 / d.categories);
  const gw = cell * d.positions;
  const gh = cell * d.categories;
  const x0 = BOX / 2 - gw / 2;
  const y0 = PAD;
  let lines = '';
  for (let i = 1; i < d.positions; i++) lines += `M${r2(x0 + i * cell)} ${y0}v${r2(gh)}`;
  for (let i = 1; i < d.categories; i++) lines += `M${r2(x0)} ${r2(y0 + i * cell)}h${r2(gw)}`;
  const top = y0 + gh + 2.6;
  const f = fit(d.clues, INNER, 45 - top, 4.2);
  const dx = BOX / 2 - (f.cols * f.pitch) / 2;
  const dr = Math.max(c.broadcast ? 0.62 : 0.48, f.pitch * 0.32);
  const comp = spread(d.clues, d.compound);
  let plainDots = '';
  let compDots = '';
  for (let i = 0; i < d.clues; i++) {
    const x = dx + ((i % f.cols) + 0.5) * f.pitch;
    const y = top + (Math.floor(i / f.cols) + 0.5) * f.pitch;
    if (comp.has(i)) compDots += dotD(x, y, dr);
    else plainDots += dotD(x, y, dr);
  }
  return {
    label: `Level ${c.level}: ${d.positions} by ${d.categories} grid, ${d.clues} clues (${d.compound} compound)`,
    badge: `×${d.clues}`,
    body: (
      <>
        <rect className="rt-soft" x={r2(x0)} y={y0} width={r2(gw)} height={r2(gh)} />
        <rect className="rt-acc" x={r2(x0)} y={y0} width={r2(gw)} height={r2(cell)} fillOpacity={0.35} />
        <path className="rt-line" d={lines} strokeWidth={c.sw * 0.45} strokeOpacity={0.6} />
        <rect className="rt-line" x={r2(x0)} y={y0} width={r2(gw)} height={r2(gh)} strokeWidth={c.sw * 0.8} />
        <path className="rt-mid" d={plainDots} />
        <path className="rt-acc" d={compDots} />
      </>
    ),
  };
}

function personD(cx: number, top: number, p: number, withHead = true): string {
  const hr = p * 0.17;
  const head = withHead ? dotD(cx, top + p * 0.3, hr) : '';
  const w = p * 0.3;
  const sy = top + p * 0.53;
  const by = top + p * 0.93;
  return `${head}M${r2(cx - w)} ${r2(by)}V${r2(sy + w * 0.7)}Q${r2(cx - w)} ${r2(sy)} ${r2(cx)} ${r2(sy)}Q${r2(cx + w)} ${r2(sy)} ${r2(cx + w)} ${r2(sy + w * 0.7)}V${r2(by)}z`;
}

/** Knights & Knaves: one figure per islander; spies wear a hat, alternators are marked with a "?". */
function truth(d: TruthRung, c: Ctx): Drawn {
  const f = fit(d.islanders, INNER, 32, 10.5);
  const x0 = BOX / 2 - (f.cols * f.pitch) / 2;
  const y0 = PAD + (32 - f.rows * f.pitch) / 2;
  const special = d.spies + d.alternators;
  const kind = new Map<number, 'spy' | 'alt'>();
  let s = d.spies;
  let a = d.alternators;
  for (let k = 0; k < special; k++) {
    const at = Math.floor(((k + 0.5) * d.islanders) / special);
    const useSpy = s > 0 && (a === 0 || k % 2 === 0);
    kind.set(at, useSpy ? 'spy' : 'alt');
    if (useSpy) s--;
    else a--;
  }
  let plainP = '';
  let spies = '';
  let hats = '';
  let alts = '';
  const marks: ReactNode[] = [];
  for (let i = 0; i < d.islanders; i++) {
    const cx = x0 + ((i % f.cols) + 0.5) * f.pitch;
    const top = y0 + Math.floor(i / f.cols) * f.pitch;
    const k = kind.get(i);
    if (k === 'spy') {
      spies += personD(cx, top, f.pitch);
      const hr = f.pitch * 0.17;
      const hy = top + f.pitch * 0.3;
      hats += rectD(cx - hr * 1.7, hy - hr * 0.95, hr * 3.4, hr * 0.5) + rectD(cx - hr * 0.95, hy - hr * 2.1, hr * 1.9, hr * 1.2);
    } else if (k === 'alt') {
      alts += personD(cx, top, f.pitch, false);
      marks.push(
        <text key={i} className="rt-warn" x={r2(cx)} y={r2(top + f.pitch * 0.47)} fontSize={r2(f.pitch * 0.52)} fontWeight={800} textAnchor="middle" style={{ fontFamily: 'var(--font-sans, sans-serif)' }}>
          ?
        </text>,
      );
    } else plainP += personD(cx, top, f.pitch);
  }
  const extra = [d.spies ? plural(d.spies, 'spy', 'spies') : '', d.alternators ? plural(d.alternators, 'alternator') : ''].filter(Boolean).join(' and ');
  return {
    label: `Level ${c.level}: ${d.islanders} islanders${extra ? ` (${extra})` : ''}, ${d.statements} statements`,
    badge: String(d.statements),
    bubble: true,
    body: (
      <>
        <path className="rt-mid" d={plainP} />
        <path className="rt-acc" d={spies} />
        <path className="rt-ink" d={hats} />
        <path className="rt-warn" d={alts} />
        {marks}
      </>
    ),
  };
}

/** Shortest Plans: the real start position, n disks on four pegs; peg D (the goal) in the accent. */
function plans(d: PlansRung, c: Ctx): Drawn {
  const base = 43.5;
  const colW = INNER / 4;
  const dh = 5;
  const poleH = 30;
  let disks = '';
  let big = '';
  d.pegs.forEach((peg, k) => {
    const cx = PAD + colW * (k + 0.5);
    peg.forEach((size, j) => {
      const w = 3.4 + (6.4 * (size - 1)) / Math.max(1, d.disks - 1);
      const dd = `M${r2(cx - w / 2 + 1)} ${r2(base - (j + 1) * dh + 0.4)}h${r2(w - 2)}a1 1 0 0 1 1 1v${r2(dh - 2.8)}a1 1 0 0 1 -1 1h${r2(-(w - 2))}a1 1 0 0 1 -1 -1v${r2(-(dh - 2.8))}a1 1 0 0 1 1 -1z`;
      // The biggest disk (the one the plan must move last) stands out.
      if (size === d.disks) big += dd;
      else disks += dd;
    });
  });
  const poles = [0, 1, 2].map((k) => `M${r2(PAD + colW * (k + 0.5))} ${base}v${-poleH}`).join('');
  const goal = PAD + colW * 3.5;
  return {
    label: `Level ${c.level}: ${d.disks} disks on four pegs, shortest plan ${d.moves} moves`,
    body: (
      <>
        <path className="rt-line" d={poles} strokeWidth={c.sw * 0.9} strokeOpacity={0.45} />
        <path className="rt-acc-line" d={`M${r2(goal)} ${base}v${-poleH}`} strokeWidth={c.sw * 1.1} />
        <path className="rt-line" d={`M${PAD} ${base}h${INNER}`} strokeWidth={c.sw * 1.2} />
        <path className="rt-ink" d={disks} fillOpacity={0.78} />
        <path className="rt-acc" d={big} />
      </>
    ),
  };
}

/** Exact Maths: the real snakes-and-ladders track (squares 1..n, boustrophedon from the bottom left). */
function exact(d: ExactRung, c: Ctx): Drawn {
  const n = d.squares;
  const cols = Math.ceil(Math.sqrt(n));
  const rows = Math.ceil(n / cols);
  const p = Math.min(8, INNER / cols, 40 / rows);
  const x0 = BOX / 2 - (cols * p) / 2 + (cols * p < INNER - 2 ? 1 : 0);
  const bottom = BOX / 2 + (rows * p) / 2;
  const at = (sq: number): [number, number] => {
    if (sq <= 0) return [Math.max(1.4, x0 - p * 0.45), bottom - p * 0.5];
    const i = sq - 1;
    const r = Math.floor(i / cols);
    const k = r % 2 === 0 ? i % cols : cols - 1 - (i % cols);
    return [x0 + (k + 0.5) * p, bottom - (r + 0.5) * p];
  };
  const traps = new Set(d.traps);
  let cells = '';
  let trapCells = '';
  let finish = '';
  const g = p * 0.08;
  for (let sq = 1; sq <= n; sq++) {
    const [x, y] = at(sq);
    const dd = rectD(x - p / 2 + g, y - p / 2 + g, p - 2 * g, p - 2 * g);
    if (sq === n) finish = dd;
    else if (traps.has(sq)) trapCells += dd;
    else cells += dd;
  }
  let ladders = '';
  for (const [a, b] of d.ladders) {
    const [x1, y1] = at(a);
    const [x2, y2] = at(b);
    const len = Math.hypot(x2 - x1, y2 - y1) || 1;
    const nx = (-(y2 - y1) / len) * p * 0.18;
    const ny = ((x2 - x1) / len) * p * 0.18;
    ladders += `M${r2(x1 + nx)} ${r2(y1 + ny)}L${r2(x2 + nx)} ${r2(y2 + ny)}M${r2(x1 - nx)} ${r2(y1 - ny)}L${r2(x2 - nx)} ${r2(y2 - ny)}`;
    const steps = Math.max(1, Math.floor(len / (p * 0.55)));
    for (let s = 1; s < steps; s++) {
      const t = s / steps;
      const mx = x1 + (x2 - x1) * t;
      const my = y1 + (y2 - y1) * t;
      ladders += `M${r2(mx + nx)} ${r2(my + ny)}L${r2(mx - nx)} ${r2(my - ny)}`;
    }
  }
  let snakes = '';
  let heads = '';
  for (const [a, b] of d.snakes) {
    const [x1, y1] = at(a);
    const [x2, y2] = at(b);
    const len = Math.hypot(x2 - x1, y2 - y1) || 1;
    const ux = -(y2 - y1) / len;
    const uy = (x2 - x1) / len;
    const waves = Math.max(1, Math.round(len / (p * 1.4)));
    const pts: string[] = [];
    for (let s = 0; s <= 24; s++) {
      const t = s / 24;
      const off = Math.sin(t * Math.PI * 2 * waves) * p * 0.26 * (1 - t * 0.5);
      pts.push(`${r2(x1 + (x2 - x1) * t + ux * off)} ${r2(y1 + (y2 - y1) * t + uy * off)}`);
    }
    snakes += `M${pts.join('L')}`;
    heads += dotD(x1, y1, p * 0.2);
  }
  const sw = Math.min(c.sw * 1.1, p * 0.2);
  return {
    label: `Level ${c.level}: ${n}-square board, ${plural(d.ladders.length, 'ladder')}, ${plural(d.snakes.length, 'snake')}, ${plural(d.traps.length, 'trap')}`,
    body: (
      <>
        <path className="rt-soft" d={cells} />
        <path className="rt-bad" d={trapCells} fillOpacity={0.45} />
        <path className="rt-acc" d={finish} />
        <path className="rt-good-line" d={ladders} strokeWidth={r2(sw * 0.8)} />
        <path className="rt-bad-line" d={snakes} strokeWidth={r2(sw * 1.15)} />
        <path className="rt-bad" d={heads} />
      </>
    ),
  };
}

/** Engineering: one block per range operation so far (this level's new ones in the accent) over the array. */
function engineering(d: EngineeringRung, c: Ctx): Drawn {
  const bw = INNER * Math.min(1, Math.log10(d.n) / 6);
  let ticks = '';
  for (let x = PAD + 1.3; x < PAD + bw - 0.5; x += 1.3) ticks += `M${r2(x)} 41.6v3`;
  const added = new Set(d.added);
  let old = '';
  let fresh = '';
  d.ops.forEach((op, i) => {
    const x = PAD + (i % 2) * 21.3;
    const y = 38.4 - (Math.floor(i / 2) + 1) * 5.3;
    const r = rectD(x, y, 20.7, 4.5);
    if (added.has(op) && d.ops.indexOf(op) === i) fresh += r;
    else old += r;
  });
  return {
    label: `Level ${c.level}: ${d.ops.length} range operations (new: ${d.added.join(', ')}) on up to ${plain(d.n)} numbers`,
    body: (
      <>
        <path className="rt-mid" d={old} fillOpacity={0.42} />
        <path className="rt-acc" d={fresh} />
        <rect className="rt-soft" x={PAD} y={41} width={r2(bw)} height={4.2} rx={0.8} />
        <path className="rt-faint" d={ticks} strokeWidth={0.35} />
        <rect className="rt-line" x={PAD} y={41} width={r2(bw)} height={4.2} rx={0.8} strokeWidth={c.sw * 0.8} />
      </>
    ),
  };
}

/* ───────── Business ───────── */

function sheetD(x: number, y: number, w: number, h: number): string {
  const f = Math.min(w, h) * 0.28;
  return `M${r2(x)} ${r2(y)}h${r2(w - f)}l${r2(f)} ${r2(f)}v${r2(h - f)}h${r2(-w)}z`;
}

/** Invoices: one pile per dependency pool (the pool's documents stacked), loose documents as small sheets. */
function invoices(d: InvoicesRung, c: Ctx): Drawn {
  let piles = '';
  let fronts = '';
  let text = '';
  const pooled = d.pools * d.poolSize;
  if (d.pools > 0) {
    const perRow = 5;
    const rows = Math.ceil(d.pools / perRow);
    const sw = 7;
    const sh = 8.4;
    const off = 1.05;
    const pileH = sh + (d.poolSize - 1) * off;
    const rowP = pileH + 1.6;
    const y0 = PAD + Math.max(0, (33 - rows * rowP) / 2);
    const pitch = INNER / perRow;
    for (let i = 0; i < d.pools; i++) {
      const inRow = i < Math.floor(d.pools / perRow) * perRow ? perRow : d.pools % perRow || perRow;
      const r = Math.floor(i / perRow);
      const k = i % perRow;
      const rowX = BOX / 2 - (inRow * pitch) / 2;
      const x = rowX + k * pitch + (pitch - sw) / 2;
      const yb = y0 + r * rowP + pileH;
      for (let j = d.poolSize - 1; j >= 0; j--) {
        const sd = sheetD(x + j * 0.25, yb - sh - j * off, sw, sh);
        if (j === 0) fronts += sd;
        else piles += sd;
      }
      text += `M${r2(x + 1.2)} ${r2(yb - sh + 3)}h3M${r2(x + 1.2)} ${r2(yb - sh + 4.8)}h${r2(sw - 2.4)}`;
    }
  }
  const loose = d.docs - pooled;
  let looseD = '';
  if (d.pools === 0) {
    const f = fit(loose, INNER, 30, 9);
    const x0 = BOX / 2 - (f.cols * f.pitch) / 2;
    for (let i = 0; i < loose; i++) {
      const x = x0 + (i % f.cols) * f.pitch + f.pitch * 0.12;
      const y = PAD + 4 + Math.floor(i / f.cols) * f.pitch * 1.2;
      fronts += sheetD(x, y, f.pitch * 0.76, f.pitch * 1.0);
      text += `M${r2(x + f.pitch * 0.15)} ${r2(y + f.pitch * 0.4)}h${r2(f.pitch * 0.4)}M${r2(x + f.pitch * 0.15)} ${r2(y + f.pitch * 0.62)}h${r2(f.pitch * 0.46)}`;
    }
  } else if (loose > 0) {
    const f = fit(loose, 26, 7.5, 3.4);
    for (let i = 0; i < loose; i++) looseD += rectD(PAD + (i % f.cols) * f.pitch, 38.5 + Math.floor(i / f.cols) * f.pitch, f.pitch * 0.66, f.pitch * 0.82);
  }
  return {
    label:
      d.pools > 0
        ? `Level ${c.level}: ${d.docs} documents, ${plural(d.pools, 'pool')} of ${d.poolSize} that depend on each other, ${d.families} rule families`
        : `Level ${c.level}: ${d.docs} documents, ${d.families} rule families`,
    badge: `×${d.docs}`,
    body: (
      <>
        <path className="rt-paper" d={piles} strokeWidth={c.sw * 0.55} strokeOpacity={0.7} />
        <path className="rt-paper" d={fronts} strokeWidth={c.sw * 0.75} style={{ stroke: d.pools ? 'var(--rt-acc)' : undefined }} />
        <path className="rt-line" d={text} strokeWidth={c.sw * 0.6} strokeOpacity={0.6} />
        <path className="rt-mid" d={looseD} fillOpacity={0.45} />
      </>
    ),
  };
}

/** Payroll: a calendar, one column per month, one payslip per member of staff; planted wrong payslips in red. */
function payroll(d: PayrollRung, c: Ctx): Drawn {
  const px = Math.min(7.5, INNER / d.months);
  const py = Math.min(4.2, 36 / d.staff);
  const x0 = BOX / 2 - (d.months * px) / 2;
  let heads = '';
  let slips = '';
  let wrong = '';
  for (let m = 0; m < d.months; m++) {
    const x = x0 + m * px + 0.4;
    heads += rectD(x, PAD, px - 0.8, 3.8);
    const bad = pick(d.staff, d.wrongPerMonth[m] ?? 0, d.staff * 31 + m * 7 + d.months);
    for (let s = 0; s < d.staff; s++) {
      const r = rectD(x, 8.6 + s * py, px - 0.8, Math.max(0.5, py - 0.55));
      if (bad.has(s)) wrong += r;
      else slips += r;
    }
  }
  const total = d.months * d.staff;
  return {
    label: `Level ${c.level}: ${plural(d.months, 'month')} × ${d.staff} staff = ${total} payslips, ${sum(d.wrongPerMonth)} planted errors`,
    body: (
      <>
        <path className="rt-acc" d={heads} />
        <path className="rt-mid" d={slips} fillOpacity={0.38} />
        <path className="rt-bad" d={wrong} />
      </>
    ),
  };
}

function receiptD(x: number, top: number, w: number, bottom: number): string {
  const teeth = Math.max(1, Math.round(w / 1.6));
  const tw = w / teeth;
  let dd = `M${r2(x)} ${r2(bottom)}V${r2(top + 0.8)}`;
  for (let i = 0; i < teeth; i++) dd += `l${r2(tw / 2)} -0.8l${r2(tw / 2)} 0.8`;
  return `${dd}V${r2(bottom)}z`;
}

/** Expenses: one receipt per monthly batch, as tall as that month's claim lines (same scale on every level). */
function expenses(d: ExpensesRung, c: Ctx, all: ExpensesRung[]): Drawn {
  const maxLines = Math.max(...all.flatMap((l) => l.linesPerMonth));
  const m = d.linesPerMonth.length;
  const pitch = Math.min(11, INNER / m);
  const w = pitch - 1.4;
  const x0 = BOX / 2 - (m * pitch) / 2 + 0.7;
  // Above the count badge; heights on one square-root scale for the whole ladder (8 lines → 7, 162 → 32).
  const bottom = 35.5;
  let paper = '';
  let stripes = '';
  d.linesPerMonth.forEach((lines, i) => {
    const h = 32 * Math.sqrt(lines / maxLines);
    const x = x0 + i * pitch;
    paper += receiptD(x, bottom - h, w, bottom);
    for (let y = bottom - h + 2.4; y < bottom - 0.9; y += 1.7) stripes += `M${r2(x + 1)} ${r2(y)}h${r2(w - 2)}`;
  });
  const total = sum(d.linesPerMonth);
  return {
    label: `Level ${c.level}: ${plural(m, 'month')} of expense claims, ${plain(total)} lines from ${d.people} people`,
    badge: `×${total}`,
    body: (
      <>
        <path className="rt-paper" d={paper} strokeWidth={c.sw * 0.7} />
        <path className="rt-faint" d={stripes} strokeWidth={0.45} />
      </>
    ),
  };
}

/**
 * Reconciliation: one band per month, top to bottom. In each, the bank statement (left card) is linked line by line
 * to the cash book (right card); a band is taller when that month has more lines. Entries that clear a month late
 * are the amber links that cross into the next month.
 */
function reconciliation(d: ReconciliationRung, c: Ctx, all: ReconciliationRung[]): Drawn {
  const busiest = Math.max(...all.flatMap((l) => [...l.bankPerMonth, ...l.booksPerMonth]));
  const m = d.bankPerMonth.length;
  const gap = 0.7;
  const cardW = 15;
  const lx = PAD;
  const rx = BOX - PAD - cardW;
  const bands: Array<{ top: number; h: number; rows: number[] }> = [];
  let y = PAD;
  for (let k = 0; k < m; k++) {
    const lines = Math.max(d.bankPerMonth[k]!, d.booksPerMonth[k]!);
    const h = 2.4 + 4.2 * Math.sqrt(lines / busiest);
    const rows: number[] = [];
    for (let ry = y + 1.05; ry < y + h - 0.6; ry += 1.15) rows.push(ry);
    bands.push({ top: y, h, rows });
    y += h + gap;
  }
  let cards = '';
  let ticks = '';
  let links = '';
  const rand = rng(sum(d.bankPerMonth) * 7 + m);
  for (const b of bands) {
    cards += rectD(lx, b.top, cardW, b.h) + rectD(rx, b.top, cardW, b.h);
    for (const ry of b.rows) {
      ticks += `M${r2(lx + 1.2)} ${r2(ry)}h${r2((cardW - 2.4) * (0.5 + rand() * 0.5))}M${r2(rx + cardW - 1.2)} ${r2(ry)}h${r2(-(cardW - 2.4) * (0.5 + rand() * 0.5))}`;
      const j = b.rows[Math.min(b.rows.length - 1, Math.max(0, b.rows.indexOf(ry) + (rand() < 0.25 ? (rand() < 0.5 ? -1 : 1) : 0)))]!;
      links += `M${r2(lx + cardW + 0.7)} ${r2(ry)}L${r2(rx - 0.7)} ${r2(j)}`;
    }
  }
  // Late-clearing entries: spread over the month boundaries, each a link from a cash-book entry down to next month's bank line.
  let late = '';
  if (m > 1) {
    for (let i = 0; i < d.clearedLater; i++) {
      const k = Math.min(m - 2, Math.floor((i * (m - 1)) / d.clearedLater));
      const from = bands[k]!;
      const to = bands[k + 1]!;
      const fy = from.top + from.h * (0.25 + rand() * 0.6);
      const ty = to.top + to.h * (0.2 + rand() * 0.6);
      late += `M${r2(rx - 0.7)} ${r2(fy)}L${r2(lx + cardW + 0.7)} ${r2(ty)}`;
    }
  }
  const totalBank = sum(d.bankPerMonth);
  const totalBooks = sum(d.booksPerMonth);
  return {
    label: `Level ${c.level}: ${plural(m, 'month')}, ${totalBank} bank lines against ${totalBooks} cash-book entries, ${d.clearedLater} clear a month late`,
    badge: `×${totalBank}`,
    body: (
      <>
        <path className="rt-paper" d={cards} strokeWidth={c.sw * 0.6} />
        <path className="rt-faint" d={ticks} strokeWidth={0.55} strokeOpacity={0.6} />
        <path className="rt-acc-line" d={links} strokeWidth={r2(c.sw * 0.4)} strokeOpacity={0.8} />
        <path d={late} strokeWidth={r2(c.sw * 0.55)} style={{ stroke: 'var(--rt-warn)', fill: 'none' }} />
      </>
    ),
  };
}


/** Customer records: one dot per record; the records of each duplicate group sit together, joined, in the accent. */
function crm(d: CrmRung, c: Ctx): Drawn {
  const groups: number[] = [];
  for (const [size, count] of Object.entries(d.groupSizes)) for (let i = 0; i < count; i++) groups.push(Number(size));
  const inGroups = sum(groups);
  const units: number[] = [...groups, ...new Array<number>(Math.max(0, d.records - inGroups)).fill(1)];
  const rand = rng(d.records);
  for (let i = units.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [units[i], units[j]] = [units[j]!, units[i]!];
  }
  const f = fit(d.records, INNER, INNER, 7);
  const x0 = BOX / 2 - (f.cols * f.pitch) / 2;
  const y0 = BOX / 2 - (f.rows * f.pitch) / 2;
  const pos = (i: number): [number, number] => {
    const r = Math.floor(i / f.cols);
    const k = r % 2 === 0 ? i % f.cols : f.cols - 1 - (i % f.cols);
    return [x0 + (k + 0.5) * f.pitch + (rand() - 0.5) * f.pitch * 0.22, y0 + (r + 0.5) * f.pitch + (rand() - 0.5) * f.pitch * 0.22];
  };
  const dr = Math.max(c.broadcast ? 0.55 : 0.42, f.pitch * 0.3);
  let singles = '';
  let members = '';
  let links = '';
  let at = 0;
  for (const u of units) {
    const pts: Array<[number, number]> = [];
    for (let k = 0; k < u; k++) pts.push(pos(at++));
    if (u === 1) singles += dotD(pts[0]![0], pts[0]![1], dr);
    else {
      for (const [x, y] of pts) members += dotD(x, y, dr);
      links += `M${pts.map(([x, y]) => `${r2(x)} ${r2(y)}`).join('L')}`;
    }
  }
  return {
    label: `Level ${c.level}: ${plain(d.records)} customer records, ${groups.length} duplicate groups to merge, ${d.refusedMerges} merges refused`,
    badge: `×${d.records}`,
    body: (
      <>
        <path className="rt-mid" d={singles} fillOpacity={0.42} />
        <path className="rt-acc-line" d={links} strokeWidth={r2(dr * 1.1)} />
        <path className="rt-acc" d={members} />
      </>
    ),
  };
}

/** Contract: the draft as pages of clauses (one line each); changed clauses are red-lined. */
function contract(d: ContractRung, c: Ctx): Drawn {
  const perPage = 24;
  const pages = Math.ceil(d.clauses / perPage);
  const aspect = 0.76;
  let best = { cols: 1, rows: 1, pw: 0, gap: 0 };
  for (let cols = 1; cols <= pages; cols++) {
    const rows = Math.ceil(pages / cols);
    const gap = pages > 12 ? 0.9 : 1.4;
    const pw = Math.min((INNER - (cols - 1) * gap) / cols, ((INNER - (rows - 1) * gap) / rows) * aspect, 30);
    if (pw > best.pw) best = { cols, rows, pw, gap };
  }
  const { cols, rows, pw, gap } = best;
  const ph = pw / aspect;
  const x0 = BOX / 2 - (cols * pw + (cols - 1) * gap) / 2;
  const y0 = BOX / 2 - (rows * ph + (rows - 1) * gap) / 2;
  const changed = spread(d.clauses, d.changes);
  const rand = rng(d.clauses * 13 + d.changes);
  let paper = '';
  let lines = '';
  let red = '';
  const pad = pw * 0.14;
  const lp = (ph - 2 * pad) / perPage;
  for (let pg = 0; pg < pages; pg++) {
    const x = x0 + (pg % cols) * (pw + gap);
    const y = y0 + Math.floor(pg / cols) * (ph + gap);
    paper += sheetD(x, y, pw, ph);
    for (let k = 0; k < perPage; k++) {
      const j = pg * perPage + k;
      if (j >= d.clauses) break;
      const seg = `M${r2(x + pad)} ${r2(y + pad + (k + 0.5) * lp)}h${r2((pw - 2 * pad) * (0.5 + rand() * 0.5))}`;
      if (changed.has(j)) red += seg;
      else lines += seg;
    }
  }
  return {
    label: `Level ${c.level}: ${d.clauses} clauses, ${d.changes} changes to settle, ${d.replies} replies`,
    badge: `×${d.changes}`,
    body: (
      <>
        <path className="rt-paper" d={paper} strokeWidth={r2(Math.min(c.sw * 0.7, pw * 0.08))} />
        <path className="rt-faint" d={lines} strokeWidth={r2(Math.min(0.9, lp * 0.5))} strokeOpacity={0.45} />
        <path className="rt-bad-line" d={red} strokeWidth={r2(Math.max(0.35, Math.min(1.6, lp * 0.8)))} />
      </>
    ),
  };
}

/** SQL: the tables the query reads (joined), and one block per rule the one query must compose. */
function sql(d: SqlRung, c: Ctx): Drawn {
  const tw = 5.8;
  const th = 9;
  const tg = 1.4;
  const tx0 = BOX / 2 - (d.tables * tw + (d.tables - 1) * tg) / 2;
  let tables = '';
  let heads = '';
  let rowsD = '';
  let joins = '';
  for (let i = 0; i < d.tables; i++) {
    const x = tx0 + i * (tw + tg);
    tables += rectD(x, PAD, tw, th);
    heads += rectD(x, PAD, tw, 2.4);
    rowsD += `M${r2(x)} ${PAD + 4.6}h${tw}M${r2(x)} ${PAD + 6.8}h${tw}`;
    if (i > 0) joins += `M${r2(x - tg)} ${PAD + 5.7}h${tg}`;
  }
  const per = 4;
  const cw = 9.6;
  const ch = 5;
  const cp = 10.6;
  const rp = 6.3;
  const nrows = Math.ceil(d.rules / per);
  const y0 = 17;
  let chips = '';
  for (let i = 0; i < d.rules; i++) {
    const inRow = Math.floor(i / per) < nrows - 1 ? per : d.rules - (nrows - 1) * per;
    const x = BOX / 2 - (inRow * cp) / 2 + (cp - cw) / 2 + (i % per) * cp;
    chips += rectD(x, y0 + Math.floor(i / per) * rp, cw, ch);
  }
  return {
    label: `Level ${c.level}: one query composing ${plural(d.rules, 'rule')} over ${plural(d.tables, 'table')}`,
    body: (
      <>
        <path className="rt-paper" d={tables} strokeWidth={c.sw * 0.7} />
        <path className="rt-acc" d={heads} />
        <path className="rt-faint" d={rowsD} strokeWidth={0.5} />
        <path className="rt-line" d={joins} strokeWidth={c.sw * 0.9} />
        <path className="rt-line" d={`M24 ${PAD + th + 0.6}V${y0 - 1.2}`} strokeWidth={c.sw * 0.8} strokeOpacity={0.5} />
        <path className="rt-mid" d={chips} />
      </>
    ),
  };
}

/** Support: the ticket queue (one card per ticket) and, from level 3, the customer accounts whose limits carry over. */
function support(d: SupportRung, c: Ctx): Drawn {
  let people = '';
  let top = PAD;
  if (d.accounts > 0) {
    const f = fit(d.accounts, INNER, 9, 6);
    const x0 = BOX / 2 - (f.cols * f.pitch) / 2;
    for (let i = 0; i < d.accounts; i++) people += personD(x0 + ((i % f.cols) + 0.5) * f.pitch, PAD + Math.floor(i / f.cols) * f.pitch, f.pitch);
    top = PAD + f.rows * f.pitch + 2;
  }
  const f = fit(d.tickets, INNER, 45 - top, 9);
  const x0 = BOX / 2 - (f.cols * f.pitch) / 2;
  const changed = spread(d.tickets, d.changedByAccounts);
  let tickets = '';
  let hit = '';
  for (let i = 0; i < d.tickets; i++) {
    const x = x0 + (i % f.cols) * f.pitch + f.pitch * 0.08;
    const y = top + Math.floor(i / f.cols) * f.pitch + f.pitch * 0.18;
    const r = rectD(x, y, f.pitch * 0.84, f.pitch * 0.62);
    if (changed.has(i)) hit += r;
    else tickets += r;
  }
  return {
    label: `Level ${c.level}: ${d.tickets} tickets${d.accounts ? ` from ${d.accounts} customer accounts whose limits carry over` : ''}, ${d.families} rule families`,
    badge: `×${d.tickets}`,
    body: (
      <>
        <path className="rt-mid" d={people} />
        <path className="rt-mid" d={tickets} fillOpacity={0.4} />
        <path className="rt-acc" d={hit} />
      </>
    ),
  };
}

/* ───────── registry ───────── */

type Drawer<K extends RungThumbTestId> = (d: RungThumbDataMap[K][number], c: Ctx, all: RungThumbDataMap[K]) => Drawn;

const DRAW: { [K in RungThumbTestId]: Drawer<K> } = {
  'horizon.mind-runner': mindRunner,
  'horizon.modpow-ladder': modpow,
  'horizon.sliding-ladder': sliding,
  'horizon.nonogram-ladder': nonogram,
  'horizon.tiling-count': tiling,
  'reasoning.grid-ladder': grid,
  'reasoning.truth-ladder': truth,
  'reasoning.plans-ladder': plans,
  'math.exact-ladder': exact,
  'coding.engineering-ladder': engineering,
  'work.invoices-ladder': invoices,
  'work.payroll-ladder': payroll,
  'work.expenses-ladder': expenses,
  'work.reconciliation-ladder': reconciliation,
  'work.crm-ladder': crm,
  'work.contract-ladder': contract,
  'work.sql-ladder': sql,
  'work.support-ladder': support,
};

function isThumbId(testId: string): testId is RungThumbTestId {
  return Object.prototype.hasOwnProperty.call(DRAW, testId);
}

function draw(testId: string, level: number, ctx: Omit<Ctx, 'level'>): Drawn | null {
  if (!isThumbId(testId) || !Number.isInteger(level)) return null;
  const all = RUNG_THUMB_DATA[testId] as unknown[];
  const d = all[level - 1];
  if (!d) return null;
  return (DRAW[testId] as (d: unknown, c: Ctx, all: unknown[]) => Drawn)(d, { ...ctx, level }, all);
}

/** True when this ladder has rung thumbnails (all 18 built ladders do). */
export function hasRungThumb(testId: string): boolean {
  return isThumbId(testId) && (RUNG_THUMB_DATA[testId] as unknown[]).length > 0;
}

/** The thumbnail's accessible description, e.g. "Level 5: 8 by 5 grid, 29 clues (10 compound)". */
export function rungThumbLabel(testId: string, level: number): string | null {
  return draw(testId, level, { sw: 1, broadcast: false })?.label ?? null;
}

export function RungThumb({ testId, level, size = 48, tone = 'default' }: RungThumbProps): JSX.Element | null {
  const broadcast = tone === 'broadcast';
  // Strokes stay about a pixel or more at small sizes and do not get heavy at large ones.
  const sw = (broadcast ? 1.55 : 1) * Math.min(1.4, Math.max(0.8, 48 / size));
  const r = draw(testId, level, { sw, broadcast });
  if (!r) return null;
  return (
    <svg className="rung-thumb" data-tone={tone} viewBox={`0 0 ${BOX} ${BOX}`} width={size} height={size} role="img" aria-label={r.label} focusable="false">
      <title>{r.label}</title>
      {r.body}
      {r.badge && size >= 36 && <Badge text={r.badge} bubble={r.bubble} broadcast={broadcast} size={size} />}
    </svg>
  );
}
