/**
 * Shortest Plans (and Extreme): recognise the puzzle in the prompt, re-solve
 * it with the same exhaustive search the verification kit uses
 * (verification/planning, verification/frontier-reasoning) and return one
 * optimal plan as animation frames.
 *
 * The plan is only shown when the re-derived optimum equals the answer key,
 * so a parser slip can never put a wrong "optimal" plan on screen. Puzzle
 * types without a solver here (scheduling, the jeep, …) fall back to the
 * plan written in the case's auditor notes.
 */
import { numberWord, plainNumber, statedAnswer, type CaseVisualInput } from './common.ts';

export interface Person {
  name: string;
  time?: number;
  weight?: number;
}

export type PlanPuzzle =
  | { kind: 'jugs'; caps: number[]; start: number[]; tap: boolean; amount: number; count: number }
  | { kind: 'coins'; start: boolean[]; window: number }
  | { kind: 'crossing'; vehicle: 'bridge' | 'gondola'; people: Person[]; cap: number; maxWeight: number | null; neverAlone: string[]; cost: 'time' | 'trips'; places: [string, string] }
  | { kind: 'hanoi'; pegs: string[]; disks: number; start: number[][]; target: number; moves: Array<[number, number]> | null }
  | { kind: 'sliding'; rows: number; cols: number; start: string[]; goal: string[] }
  | { kind: 'lights'; rows: number; cols: number; start: boolean[] }
  | { kind: 'pancakes'; start: number[]; burntUp: boolean[] | null }
  | { kind: 'maze'; grid: string[] }
  | { kind: 'traffic'; grid: string[]; target: string; goalCol: number };

export type PlanState =
  | { kind: 'jugs'; v: number[] }
  | { kind: 'coins'; v: boolean[] }
  | { kind: 'crossing'; start: boolean[]; lanternStart: boolean }
  | { kind: 'hanoi'; v: number[][] }
  | { kind: 'sliding'; v: string[] }
  | { kind: 'lights'; v: boolean[] }
  | { kind: 'pancakes'; v: number[]; up: boolean[] | null }
  | { kind: 'maze'; r: number; c: number; keys: string }
  | { kind: 'traffic'; v: string[] };

export interface PlanFrame {
  state: PlanState;
  /** Plain-English description of the move that led here (null for the start). */
  action: string | null;
  /** Cells / coins / people touched by the move (renderer-specific indexes). */
  mark: number[];
  /** Running cost after this frame (moves, or minutes for a bridge). */
  total: number;
}

export interface PlanVisual {
  puzzle: PlanPuzzle | null;
  /** Readable name of the puzzle family. */
  title: string;
  /** Frames of one optimal plan, when re-derived and consistent with the key. */
  frames: PlanFrame[] | null;
  /** The optimum from the answer key. */
  optimum: number;
  unit: string;
  /** The model's claimed minimum, or null when it gave no number. */
  claimed: number | null;
  claimedText: string;
  /** One optimal plan as written in the auditor notes (fallback when there is no solver). */
  notesPlan: string[] | null;
  /** The notation key written before the notes' plan, e.g. "loadN = load N cells at the base". */
  notesLegend: string | null;
}

// ─────────────────────────────── search ───────────────────────────────

interface Edge<S> {
  to: S;
  action: string;
  mark: number[];
  cost?: number;
}

/** Breadth-first search (unit costs). Returns the path of frames or null. */
function bfs<S>(start: S, key: (s: S) => string, next: (s: S) => Edge<S>[], goal: (s: S) => boolean, limit = 400_000): Array<{ s: S; e: Edge<S> | null }> | null {
  const startKey = key(start);
  const parent = new Map<string, { prev: string | null; s: S; e: Edge<S> | null }>([[startKey, { prev: null, s: start, e: null }]]);
  let frontier: S[] = [start];
  let found: string | null = goal(start) ? startKey : null;
  while (!found && frontier.length) {
    const nf: S[] = [];
    for (const s of frontier) {
      const k = key(s);
      for (const e of next(s)) {
        const nk = key(e.to);
        if (parent.has(nk)) continue;
        parent.set(nk, { prev: k, s: e.to, e });
        if (goal(e.to)) {
          found = nk;
          break;
        }
        nf.push(e.to);
      }
      if (found) break;
      if (parent.size > limit) return null;
    }
    frontier = nf;
  }
  if (!found) return null;
  const path: Array<{ s: S; e: Edge<S> | null }> = [];
  let k: string | null = found;
  while (k !== null) {
    const p: { prev: string | null; s: S; e: Edge<S> | null } = parent.get(k)!;
    path.push({ s: p.s, e: p.e });
    k = p.prev;
  }
  return path.reverse();
}

/** Dijkstra for small weighted graphs (bridge crossings). */
function dijkstra<S>(start: S, key: (s: S) => string, next: (s: S) => Edge<S>[], goal: (s: S) => boolean, limit = 200_000): Array<{ s: S; e: Edge<S> | null }> | null {
  const dist = new Map<string, number>([[key(start), 0]]);
  const parent = new Map<string, { prev: string | null; s: S; e: Edge<S> | null }>([[key(start), { prev: null, s: start, e: null }]]);
  const done = new Set<string>();
  const open: Array<{ d: number; s: S }> = [{ d: 0, s: start }];
  while (open.length) {
    let bi = 0;
    for (let i = 1; i < open.length; i++) if (open[i]!.d < open[bi]!.d) bi = i;
    const { d, s } = open.splice(bi, 1)[0]!;
    const k = key(s);
    if (done.has(k)) continue;
    done.add(k);
    if (goal(s)) {
      const path: Array<{ s: S; e: Edge<S> | null }> = [];
      let c: string | null = k;
      while (c !== null) {
        const p: { prev: string | null; s: S; e: Edge<S> | null } = parent.get(c)!;
        path.push({ s: p.s, e: p.e });
        c = p.prev;
      }
      return path.reverse();
    }
    for (const e of next(s)) {
      const nk = key(e.to);
      const nd = d + (e.cost ?? 1);
      if (nd < (dist.get(nk) ?? Infinity)) {
        dist.set(nk, nd);
        parent.set(nk, { prev: k, s: e.to, e });
        open.push({ d: nd, s: e.to });
      }
    }
    if (dist.size > limit) return null;
  }
  return null;
}

function framesOf<S>(path: Array<{ s: S; e: Edge<S> | null }>, wrap: (s: S) => PlanState): PlanFrame[] {
  let total = 0;
  return path.map(({ s, e }) => {
    if (e) total += e.cost ?? 1;
    return { state: wrap(s), action: e ? e.action : null, mark: e ? e.mark : [], total };
  });
}

// ─────────────────────────────── parsers ───────────────────────────────

function gridBlock(prompt: string, heading: RegExp): string[][] | null {
  const m = prompt.match(heading);
  if (!m || m.index === undefined) return null;
  const rest = prompt.slice(m.index + m[0].length).replace(/^\s*\n/, '');
  const lines: string[][] = [];
  for (const line of rest.split('\n')) {
    if (!line.trim()) break;
    lines.push(line.trim().split(/\s+/));
  }
  if (!lines.length || lines.some((l) => l.length !== lines[0]!.length)) return null;
  return lines;
}

function parseJugs(p: string): PlanPuzzle | null {
  const two = p.match(/two unmarked jugs: one holds exactly (\d+) litres and the other exactly (\d+) litres\. Both start empty/);
  if (two && /unlimited water tap/.test(p)) {
    const goal = p.match(/one of the jugs contains exactly (\d+) litres/);
    if (!goal) return null;
    return { kind: 'jugs', caps: [Number(two[1]), Number(two[2])], start: [0, 0], tap: true, amount: Number(goal[1]), count: 1 };
  }
  const three = p.match(/three unmarked jugs with capacities (\d+) litres, (\d+) litres and (\d+) litres\. The (\d+)-litre jug is completely full of water and the other two are empty/);
  if (three && /no tap and no drain/.test(p)) {
    const caps = [Number(three[1]), Number(three[2]), Number(three[3])];
    const full = caps.indexOf(Number(three[4]));
    if (full < 0) return null;
    const goal = p.match(/(\w+) of the jugs each contain exactly (\d+) litres/);
    if (!goal) return null;
    const count = numberWord(goal[1]!);
    if (!count) return null;
    return { kind: 'jugs', caps, start: caps.map((c, i) => (i === full ? c : 0)), tap: false, amount: Number(goal[2]), count };
  }
  return null;
}

function parseCoins(p: string): PlanPuzzle | null {
  const m = p.match(/coins lie in a row\. From left to right they show:\s*\n([HT ]+)\n/);
  const w = p.match(/choose any (\w+) coins that are next to each other/);
  if (!m || !w || !/make all \w+ coins show heads/.test(p)) return null;
  const window = numberWord(w[1]!);
  const start = m[1]!.trim().split(/\s+/).map((c) => c === 'H');
  if (!window || start.length < window) return null;
  return { kind: 'coins', start, window };
}

function parseCrossing(p: string): PlanPuzzle | null {
  const head = p.split('\n\n')[0] ?? '';
  const count = numberWord((head.match(/^(\w+) (?:hikers|explorers|people)/i) ?? head.match(/\b(\w+) people are at/) ?? [])[1] ?? '');
  if (/rope bridge|fragile bridge|bridge at night|bridge in the dark/.test(p)) {
    let people: Person[] = [...p.matchAll(/^- ([A-Z][a-z]+): (\d+) minutes, (\d+) kg$/gm)].map((m) => ({ name: m[1]!, time: Number(m[2]), weight: Number(m[3]) }));
    if (!people.length) {
      const seg = head.slice(head.indexOf(':') + 1);
      people = [...seg.matchAll(/([A-Z][a-z]+)(?: takes)? (\d+)(?: minutes?)?/g)].map((m) => ({ name: m[1]!, time: Number(m[2]) }));
    }
    const cap = numberWord((p.match(/At most (\w+) people can be on the bridge/) ?? [])[1] ?? '');
    const w = p.match(/total weight may not exceed (\d+) kg/);
    if (!cap || people.length < 2 || (count && count !== people.length)) return null;
    return { kind: 'crossing', vehicle: 'bridge', people, cap, maxWeight: w ? Number(w[1]) : null, neverAlone: [], cost: 'time', places: ['Start', 'Far side'] };
  }
  if (/gondola/.test(p)) {
    const people = [...head.matchAll(/([A-Z][a-z]+) \((\d+) kg/g)].map((m) => ({ name: m[1]!, weight: Number(m[2]) }));
    const lim = p.match(/A ride can carry at most (\d+) people and at most (\d+) kg in total/);
    if (!lim || people.length < 2 || (count && count !== people.length)) return null;
    const neverAlone = [...p.matchAll(/([A-Z][a-z]+) may never ride alone/g)].map((m) => m[1]!);
    return { kind: 'crossing', vehicle: 'gondola', people, cap: Number(lim[1]), maxWeight: Number(lim[2]), neverAlone, cost: 'trips', places: ['Valley', 'Summit'] };
  }
  return null;
}

function parseHanoi(p: string): PlanPuzzle | null {
  if (!/\bpegs?\b/.test(p) || !/disks?/.test(p)) return null;
  const pegLines = [...p.matchAll(/^- Peg ([A-Z]): (.*)$/gm)];
  if (pegLines.length < 3) return null;
  const pegs = pegLines.map((m) => m[1]!);
  const start = pegLines.map((m) => (/^empty\.?$/i.test(m[2]!.trim()) ? [] : [...m[2]!.matchAll(/disk (\d+)/g)].map((d) => Number(d[1]))));
  const disks = start.reduce((s, x) => s + x.length, 0);
  const all = start.flat().sort((a, b) => a - b);
  if (all.some((d, i) => d !== i + 1)) return null;
  for (const peg of start) for (let i = 1; i < peg.length; i++) if (peg[i]! > peg[i - 1]!) return null;
  const target = pegs.indexOf((p.match(/all \w+ disks onto peg ([A-Z])/) ?? [])[1] ?? '');
  if (target < 0) return null;
  const only = p.match(/Only these moves exist: ([^.]+)\./);
  let moves: Array<[number, number]> | null = null;
  if (only) {
    moves = [...only[1]!.matchAll(/([A-Z]) to ([A-Z])/g)].map((m) => [pegs.indexOf(m[1]!), pegs.indexOf(m[2]!)] as [number, number]);
    if (!moves.length || moves.some(([a, b]) => a < 0 || b < 0)) return null;
  }
  return { kind: 'hanoi', pegs, disks, start, target, moves };
}

function parseSliding(p: string): PlanPuzzle | null {
  if (!/sliding puzzle/i.test(p) || !/Goal position/.test(p)) return null;
  const start = gridBlock(p, /Current position[^:\n]*:/);
  const goal = gridBlock(p, /Goal position[^:\n]*:/);
  if (!start || !goal || start.length !== goal.length || start[0]!.length !== goal[0]!.length) return null;
  const s = start.flat();
  const g = goal.flat();
  if (s.filter((x) => x === '_').length !== 1 || [...s].sort().join() !== [...g].sort().join()) return null;
  return { kind: 'sliding', rows: start.length, cols: start[0]!.length, start: s, goal: g };
}

function parseLights(p: string): PlanPuzzle | null {
  if (!/Pressing a lamp toggles that lamp and every lamp directly above, below, left or right/.test(p)) return null;
  const g = gridBlock(p, /Current state[^:\n]*:/);
  if (!g || g.flat().some((x) => x !== '0' && x !== '1') || !/turn every lamp off/.test(p)) return null;
  return { kind: 'lights', rows: g.length, cols: g[0]!.length, start: g.flat().map((x) => x === '1') };
}

function parsePancakes(p: string): PlanPuzzle | null {
  if (!/pancakes?/.test(p) || !/flip/i.test(p)) return null;
  const plain = p.match(/the sizes are currently:\s*\n([\d, ]+)\n/);
  if (plain && /arrange the stack as [\d, ]+ from top to bottom/.test(p)) {
    const start = plain[1]!.split(/,\s*/).map(Number);
    if ([...start].sort((a, b) => a - b).some((d, i) => d !== i + 1)) return null;
    return { kind: 'pancakes', start, burntUp: null };
  }
  const burnt = p.match(/it is currently:\s*\n\s*\n?(.+)\n/);
  if (burnt && /every burnt side facing down/.test(p)) {
    const items = [...burnt[1]!.matchAll(/(\d+) \(burnt side (up|down)\)/g)];
    if (!items.length) return null;
    const start = items.map((m) => Number(m[1]));
    if ([...start].sort((a, b) => a - b).some((d, i) => d !== i + 1)) return null;
    return { kind: 'pancakes', start, burntUp: items.map((m) => m[2] === 'up') };
  }
  return null;
}

function parseMaze(p: string): PlanPuzzle | null {
  if (!/A robot moves on this grid/.test(p)) return null;
  const m = p.match(/grid[^\n]*:\s*\n\s*\n((?:[#.A-Za-z]+\n)+)/);
  if (!m) return null;
  const grid = m[1]!.trim().split('\n');
  if (grid.some((r) => r.length !== grid[0]!.length)) return null;
  if (grid.join('').split('S').length !== 2 || grid.join('').split('E').length !== 2) return null;
  return { kind: 'maze', grid };
}

function parseTraffic(p: string): PlanPuzzle | null {
  if (!/sliding-block traffic puzzle/.test(p)) return null;
  const m = p.match(/\n\s+1 2 3 4 5 6\n((?:\d [A-Z.](?: [A-Z.])+\n)+)/);
  if (!m) return null;
  const grid = m[1]!.trim().split('\n').map((l) => l.slice(2).split(' ').join(''));
  const goal = p.match(/get vehicle ([A-Z]) to the right edge, so that \1 occupies squares \(row (\d), column (\d)\) and \(row \2, column (\d)\)/);
  if (!goal || grid.length !== 6 || grid.some((r) => r.length !== 6)) return null;
  return { kind: 'traffic', grid, target: goal[1]!, goalCol: Number(goal[4]) - 1 };
}

export function parsePlanPuzzle(prompt: string): PlanPuzzle | null {
  for (const f of [parseJugs, parseCoins, parseCrossing, parseHanoi, parseSliding, parseLights, parsePancakes, parseMaze, parseTraffic]) {
    try {
      const r = f(prompt);
      if (r) return r;
    } catch {
      /* not this kind */
    }
  }
  return null;
}

// ─────────────────────────────── solvers ───────────────────────────────

const JUG = (c: number) => `${c} L jug`;

function solveJugs(z: Extract<PlanPuzzle, { kind: 'jugs' }>): PlanFrame[] | null {
  const n = z.caps.length;
  const path = bfs<number[]>(
    z.start,
    (s) => s.join(','),
    (s) => {
      const out: Edge<number[]>[] = [];
      for (let i = 0; i < n; i++) {
        if (z.tap && s[i]! < z.caps[i]!) out.push({ to: s.map((v, k) => (k === i ? z.caps[i]! : v)), action: `Fill the ${JUG(z.caps[i]!)} from the tap`, mark: [i] });
        if (z.tap && s[i]! > 0) out.push({ to: s.map((v, k) => (k === i ? 0 : v)), action: `Empty the ${JUG(z.caps[i]!)} down the drain`, mark: [i] });
        for (let j = 0; j < n; j++) {
          if (i === j || s[i] === 0 || s[j] === z.caps[j]) continue;
          const amt = Math.min(s[i]!, z.caps[j]! - s[j]!);
          out.push({ to: s.map((v, k) => (k === i ? v - amt : k === j ? v + amt : v)), action: `Pour ${amt} L from the ${JUG(z.caps[i]!)} into the ${JUG(z.caps[j]!)}`, mark: [i, j] });
        }
      }
      return out;
    },
    (s) => s.filter((v) => v === z.amount).length >= z.count,
  );
  return path && framesOf(path, (v) => ({ kind: 'jugs', v }));
}

function solveCoins(z: Extract<PlanPuzzle, { kind: 'coins' }>): PlanFrame[] | null {
  const n = z.start.length;
  const path = bfs<boolean[]>(
    z.start,
    (s) => s.map((b) => (b ? 'H' : 'T')).join(''),
    (s) => {
      const out: Edge<boolean[]>[] = [];
      for (let i = 0; i + z.window <= n; i++) {
        const mark = Array.from({ length: z.window }, (_, k) => i + k);
        out.push({ to: s.map((b, k) => (k >= i && k < i + z.window ? !b : b)), action: `Flip coins ${i + 1}–${i + z.window}`, mark });
      }
      return out;
    },
    (s) => s.every(Boolean),
  );
  return path && framesOf(path, (v) => ({ kind: 'coins', v }));
}

function subsets(idx: number[], max: number): number[][] {
  const out: number[][] = [];
  const rec = (from: number, cur: number[]) => {
    if (cur.length) out.push(cur.slice());
    if (cur.length === max) return;
    for (let i = from; i < idx.length; i++) {
      cur.push(idx[i]!);
      rec(i + 1, cur);
      cur.pop();
    }
  };
  rec(0, []);
  return out;
}

function solveCrossing(z: Extract<PlanPuzzle, { kind: 'crossing' }>): PlanFrame[] | null {
  type S = { start: boolean[]; lanternStart: boolean };
  const n = z.people.length;
  const names = (g: number[]) => g.map((i) => z.people[i]!.name).join(' + ');
  const path = dijkstra<S>(
    { start: z.people.map(() => true), lanternStart: true },
    (s) => `${s.start.map((b) => (b ? 1 : 0)).join('')}|${s.lanternStart ? 1 : 0}`,
    (s) => {
      const here = [...Array(n).keys()].filter((i) => s.start[i] === s.lanternStart);
      const out: Edge<S>[] = [];
      for (const g of subsets(here, z.cap)) {
        if (z.maxWeight !== null && g.reduce((w, i) => w + (z.people[i]!.weight ?? 0), 0) > z.maxWeight) continue;
        if (g.length === 1 && z.neverAlone.includes(z.people[g[0]!]!.name)) continue;
        const cost = z.cost === 'time' ? Math.max(...g.map((i) => z.people[i]!.time ?? 0)) : 1;
        const dir = s.lanternStart ? (z.vehicle === 'gondola' ? 'ride up' : 'cross over') : z.vehicle === 'gondola' ? 'ride down' : 'come back';
        const verb = g.length === 1 ? dir.replace(/^(\w+)/, (w) => (w === 'come' ? 'comes' : `${w}s`)) : dir;
        out.push({
          to: { start: s.start.map((b, i) => (g.includes(i) ? !b : b)), lanternStart: !s.lanternStart },
          action: `${names(g)} ${verb}${z.cost === 'time' ? ` (${cost} min)` : ''}`,
          mark: g,
          cost,
        });
      }
      return out;
    },
    (s) => s.start.every((b) => !b),
  );
  return path && framesOf(path, (s) => ({ kind: 'crossing', start: s.start, lanternStart: s.lanternStart }));
}

function solveHanoi(z: Extract<PlanPuzzle, { kind: 'hanoi' }>): PlanFrame[] | null {
  const moves = z.moves ?? z.pegs.flatMap((_, a) => z.pegs.map((__, b) => [a, b] as [number, number])).filter(([a, b]) => a !== b);
  const path = bfs<number[][]>(
    z.start,
    (s) => s.map((p) => p.join('.')).join('|'),
    (s) => {
      const out: Edge<number[][]>[] = [];
      for (const [a, b] of moves) {
        const top = s[a]![s[a]!.length - 1];
        if (top === undefined) continue;
        const under = s[b]![s[b]!.length - 1];
        if (under !== undefined && under < top) continue;
        const to = s.map((p) => p.slice());
        to[a]!.pop();
        to[b]!.push(top);
        out.push({ to, action: `Move disk ${top} from peg ${z.pegs[a]} to peg ${z.pegs[b]}`, mark: [top] });
      }
      return out;
    },
    (s) => s[z.target]!.length === z.disks,
  );
  return path && framesOf(path, (v) => ({ kind: 'hanoi', v }));
}

function solveSliding(z: Extract<PlanPuzzle, { kind: 'sliding' }>): PlanFrame[] | null {
  const goal = z.goal.join(',');
  const path = bfs<string[]>(
    z.start,
    (s) => s.join(','),
    (s) => {
      const e = s.indexOf('_');
      const r = Math.floor(e / z.cols);
      const c = e % z.cols;
      const out: Edge<string[]>[] = [];
      const dirs: Array<[number, number, string]> = [
        [-1, 0, 'down'],
        [1, 0, 'up'],
        [0, -1, 'right'],
        [0, 1, 'left'],
      ];
      for (const [dr, dc, word] of dirs) {
        const rr = r + dr;
        const cc = c + dc;
        if (rr < 0 || cc < 0 || rr >= z.rows || cc >= z.cols) continue;
        const t = rr * z.cols + cc;
        const to = s.slice();
        to[e] = s[t]!;
        to[t] = '_';
        out.push({ to, action: `Slide ${s[t]} ${word}`, mark: [e] });
      }
      return out;
    },
    (s) => s.join(',') === goal,
  );
  return path && framesOf(path, (v) => ({ kind: 'sliding', v }));
}

function solveLights(z: Extract<PlanPuzzle, { kind: 'lights' }>): PlanFrame[] | null {
  const path = bfs<boolean[]>(
    z.start,
    (s) => s.map((b) => (b ? 1 : 0)).join(''),
    (s) => {
      const out: Edge<boolean[]>[] = [];
      for (let i = 0; i < s.length; i++) {
        const r = Math.floor(i / z.cols);
        const c = i % z.cols;
        const hit = [i];
        if (r > 0) hit.push(i - z.cols);
        if (r < z.rows - 1) hit.push(i + z.cols);
        if (c > 0) hit.push(i - 1);
        if (c < z.cols - 1) hit.push(i + 1);
        out.push({ to: s.map((b, k) => (hit.includes(k) ? !b : b)), action: `Press the lamp in row ${r + 1}, column ${c + 1}`, mark: [i] });
      }
      return out;
    },
    (s) => s.every((b) => !b),
  );
  return path && framesOf(path, (v) => ({ kind: 'lights', v }));
}

function solvePancakes(z: Extract<PlanPuzzle, { kind: 'pancakes' }>): PlanFrame[] | null {
  type S = { v: number[]; up: boolean[] | null };
  const n = z.start.length;
  const path = bfs<S>(
    { v: z.start, up: z.burntUp },
    (s) => `${s.v.join(',')}|${s.up ? s.up.map((b) => (b ? 1 : 0)).join('') : ''}`,
    (s) => {
      const out: Edge<S>[] = [];
      for (let k = z.burntUp ? 1 : 2; k <= n; k++) {
        const v = [...s.v.slice(0, k).reverse(), ...s.v.slice(k)];
        const up = s.up ? [...s.up.slice(0, k).reverse().map((b) => !b), ...s.up.slice(k)] : null;
        out.push({ to: { v, up }, action: `Flip the top ${k} pancake${k === 1 ? '' : 's'}`, mark: Array.from({ length: k }, (_, i) => i) });
      }
      return out;
    },
    (s) => s.v.every((x, i) => x === i + 1) && (!s.up || s.up.every((b) => !b)),
  );
  return path && framesOf(path, (s) => ({ kind: 'pancakes', v: s.v, up: s.up }));
}

function solveMaze(z: Extract<PlanPuzzle, { kind: 'maze' }>): PlanFrame[] | null {
  type S = { r: number; c: number; keys: string };
  const g = z.grid;
  const find = (ch: string) => {
    const r = g.findIndex((row) => row.includes(ch));
    return { r, c: g[r]!.indexOf(ch) };
  };
  const s0 = find('S');
  const e = find('E');
  const path = bfs<S>(
    { ...s0, keys: '' },
    (s) => `${s.r},${s.c},${s.keys}`,
    (s) => {
      const out: Edge<S>[] = [];
      const dirs: Array<[number, number, string]> = [
        [-1, 0, 'up'],
        [1, 0, 'down'],
        [0, -1, 'left'],
        [0, 1, 'right'],
      ];
      for (const [dr, dc, word] of dirs) {
        const r = s.r + dr;
        const c = s.c + dc;
        const ch = g[r]?.[c];
        if (ch === undefined || ch === '#') continue;
        if (/[A-Z]/.test(ch) && ch !== 'S' && ch !== 'E' && !s.keys.includes(ch.toLowerCase())) continue;
        let keys = s.keys;
        let action = `Step ${word}`;
        if (/[a-z]/.test(ch) && !keys.includes(ch)) {
          keys = [...keys, ch].sort().join('');
          action = `Step ${word} and pick up key ${ch}`;
        } else if (/[A-Z]/.test(ch) && ch !== 'S' && ch !== 'E') action = `Step ${word} through door ${ch}`;
        out.push({ to: { r, c, keys }, action, mark: [r * g[0]!.length + c] });
      }
      return out;
    },
    (s) => s.r === e.r && s.c === e.c,
  );
  return path && framesOf(path, (s) => ({ kind: 'maze', r: s.r, c: s.c, keys: s.keys }));
}

function solveTraffic(z: Extract<PlanPuzzle, { kind: 'traffic' }>): PlanFrame[] | null {
  const N = 6;
  const cells = (s: string[], id: string) => {
    const out: Array<[number, number]> = [];
    s.forEach((row, r) => [...row].forEach((ch, c) => ch === id && out.push([r, c])));
    return out;
  };
  const ids = [...new Set(z.grid.join('').replace(/\./g, ''))];
  const horiz = new Map(ids.map((id) => [id, (() => {
    const cs = cells(z.grid, id);
    return cs.every(([r]) => r === cs[0]![0]);
  })()]));
  const path = bfs<string[]>(
    z.grid,
    (s) => s.join(''),
    (s) => {
      const out: Edge<string[]>[] = [];
      for (const id of ids) {
        const cs = cells(s, id);
        const h = horiz.get(id)!;
        for (const dir of [-1, 1]) {
          for (let k = 1; k < N; k++) {
            const moved = cs.map(([r, c]) => (h ? [r, c + dir * k] : [r + dir * k, c]) as [number, number]);
            if (moved.some(([r, c]) => r < 0 || c < 0 || r >= N || c >= N)) break;
            if (moved.some(([r, c]) => s[r]![c] !== '.' && s[r]![c] !== id)) break;
            const grid = s.map((row) => [...row]);
            for (const [r, c] of cs) grid[r]![c] = '.';
            for (const [r, c] of moved) grid[r]![c] = id;
            const word = h ? (dir > 0 ? 'right' : 'left') : dir > 0 ? 'down' : 'up';
            out.push({ to: grid.map((row) => row.join('')), action: `Slide ${id} ${word} ${k}`, mark: moved.map(([r, c]) => r * N + c) });
          }
        }
      }
      return out;
    },
    (s) => cells(s, z.target).some(([, c]) => c === z.goalCol),
    300_000,
  );
  return path && framesOf(path, (v) => ({ kind: 'traffic', v }));
}

export function solvePlan(z: PlanPuzzle): PlanFrame[] | null {
  switch (z.kind) {
    case 'jugs':
      return solveJugs(z);
    case 'coins':
      return solveCoins(z);
    case 'crossing':
      return solveCrossing(z);
    case 'hanoi':
      return solveHanoi(z);
    case 'sliding':
      return solveSliding(z);
    case 'lights':
      return solveLights(z);
    case 'pancakes':
      return solvePancakes(z);
    case 'maze':
      return solveMaze(z);
    case 'traffic':
      return solveTraffic(z);
  }
}

export const PLAN_TITLES: Record<PlanPuzzle['kind'], string> = {
  jugs: 'Water jugs',
  coins: 'Coin flips',
  crossing: 'Crossing',
  hanoi: 'Towers of Hanoi',
  sliding: 'Sliding tiles',
  lights: 'Lights out',
  pancakes: 'Pancake flips',
  maze: 'Keys and doors maze',
  traffic: 'Traffic jam',
};

/** The "(AU1 = vehicle A up 1 square, …)" key the notes give before a plan, if any. */
export function notesLegend(notes: string | undefined): string | null {
  const m = notes?.match(/One optimal plan:\s*\(([^)]*(?:\([^)]*\)[^)]*)*)\)/);
  return m ? m[1]!.trim() : null;
}

/** "One optimal plan: …" from the auditor notes, split into steps. */
export function notesPlan(notes: string | undefined): string[] | null {
  if (!notes) return null;
  const m = notes.match(/One optimal plan:\s*(?:\([^)]*\)\s*)?([\s\S]+?)(?:\s*=\s*\d+ \w+)?\.?\s*$/);
  if (!m) return null;
  const body = m[1]!.trim().replace(/\.$/, '');
  const parts = body.includes(';') ? body.split(/;\s*/) : body.split(/\s+/);
  const steps = parts.map((s) => s.trim()).filter(Boolean);
  return steps.length >= 2 && steps.length <= 120 ? steps : null;
}

/** The unit the question asks for ("minutes", "rides", "moves", …). */
export function unitOf(prompt: string): string {
  const q = prompt.split('\n').find((l) => /\bminimum\b/i.test(l) && /\?/.test(l)) ?? '';
  const num = q.match(/minimum (?:total )?number of ([a-z]+(?: cells)?)/i);
  if (num) return num[1]!.toLowerCase();
  const inUnit = q.match(/minimum (?:possible |total )*(?:time|cost|amount)[^,]*,\s*in ([a-z]+)/i);
  if (inUnit) return inUnit[1]!.toLowerCase();
  return 'moves';
}

/** "presses" → "press", "moves" → "move". */
export function singularUnit(u: string): string {
  if (/(ss|sh|ch|x)es$/.test(u)) return u.slice(0, -2);
  return u.endsWith('s') ? u.slice(0, -1) : u;
}

/**
 * Parse, re-solve and compare. Solving is exhaustive search and can take a
 * moment on the largest puzzles, so callers should run it off the first paint.
 */
export function planVisual(input: CaseVisualInput): PlanVisual | null {
  const prompt = input.turns[0] ?? '';
  const optimum = typeof input.expected === 'number' ? input.expected : plainNumber(String(input.expected ?? ''));
  if (optimum === null) return null;
  const stated = statedAnswer(input);
  const parsed = typeof input.detail.parsed === 'number' ? input.detail.parsed : stated ? plainNumber(stated.answer) : null;
  const puzzle = parsePlanPuzzle(prompt);
  let frames: PlanFrame[] | null = null;
  if (puzzle) {
    try {
      frames = solvePlan(puzzle);
    } catch {
      frames = null;
    }
    // Only show a plan whose length matches the answer key.
    if (frames && frames[frames.length - 1]!.total !== optimum) frames = null;
  }
  return {
    puzzle: frames ? puzzle : null,
    title: puzzle ? PLAN_TITLES[puzzle.kind] : 'Planning puzzle',
    frames,
    optimum,
    unit: unitOf(prompt),
    claimed: parsed,
    claimedText: stated?.answer ?? '',
    notesPlan: notesPlan(input.notes),
    notesLegend: notesLegend(input.notes),
  };
}

export function planHeadline(v: PlanVisual): string {
  if (v.claimed === null) return 'No number given';
  const d = v.claimed - v.optimum;
  if (d === 0) return `Found the true minimum: ${v.optimum} ${v.unit}`;
  if (d > 0) return `${d === 1 ? 'One' : d} ${d === 1 ? singularUnit(v.unit) : v.unit} too many: ${v.claimed} vs ${v.optimum}`;
  return `Claimed ${v.claimed}, but ${v.optimum} is the minimum — impossible plan`;
}
