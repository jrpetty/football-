/**
 * Plan the Van Routes (work.delivery-routes): delivery-day generator + exact solver.
 *
 * Drops are real towns around an invented depot, with invented customers, weights, unloading times and delivery
 * windows; the travel-time matrix (minutes) comes from the towns' rough positions and is printed in full, and a road
 * closure in a late message changes some entries (the stored matrix is the one after the closure).
 *
 * The solver is exact:
 *  1. For every set of drops, the quickest single route that serves them (a dynamic programme over (set, last drop)
 *     keeping every non-dominated (time, driving since the last break) label, with a break allowed after any drop,
 *     so waiting, windows and the break rule are all respected).
 *  2. The best split of the drops between the vans (each van's capacity applied, a van may stay at the depot): a
 *     memoised search over vans and remaining sets.
 * `bruteForceRoutes` enumerates every assignment and every order with every break position (tiny worlds only) and is
 * used by the tests to confirm the DP.
 */
import { Rng, ONE_ANSWER, type Careless, type Difficulty } from './common.mts';
import { checkRoutes, hm, replayVan, type RouteDrop, type RouteVan, type RoutesWorld } from '../../src/scoring/verifiers/routes.ts';

// ─────────────────────────────── solver ───────────────────────────────

interface Label {
  t: number;
  since: number;
  /** For rebuilding the route: previous state key, previous label index, whether a break was taken before this leg. */
  prev: number;
  prevLabel: number;
  brk: boolean;
}

export interface RouteTable {
  /** Best return time per drop set (Infinity when no route works), and how to rebuild it. */
  best: Float64Array;
  rebuild: (mask: number) => string[];
}

/** Quickest route for every set of drops (ignoring capacity). */
export function routeTable(w: RoutesWorld): RouteTable {
  const n = w.drops.length;
  const N = 1 << n;
  const M = w.matrix;
  const states = new Map<number, Label[]>(); // key = mask * n + last
  const push = (key: number, lab: Label) => {
    const list = states.get(key);
    if (!list) {
      states.set(key, [lab]);
      return;
    }
    for (const o of list) if (o.t <= lab.t && o.since <= lab.since) return;
    const kept = list.filter((o) => !(lab.t <= o.t && lab.since <= o.since));
    kept.push(lab);
    states.set(key, kept);
  };
  const legs = (t: number, since: number, from: number, to: number): Array<{ t: number; since: number; brk: boolean }> => {
    const out: Array<{ t: number; since: number; brk: boolean }> = [];
    const d = M[from]![to]!;
    if (since + d <= w.driveLimit) out.push({ t: t + d, since: since + d, brk: false });
    if (from !== 0 && d <= w.driveLimit) out.push({ t: t + w.breakMinutes + d, since: d, brk: true });
    return out;
  };
  for (let j = 0; j < n; j++) {
    for (const leg of legs(w.depotOpen, 0, 0, j + 1)) {
      const dr = w.drops[j]!;
      if (leg.t > dr.close) continue;
      push((1 << j) * n + j, { t: Math.max(leg.t, dr.open) + dr.service, since: leg.since, prev: -1, prevLabel: -1, brk: leg.brk });
    }
  }
  // Masks in increasing order: every predecessor mask is smaller.
  const best = new Float64Array(N).fill(Infinity);
  const bestFrom = new Map<number, [number, number, boolean]>(); // mask → [state key, label index, break before return]
  best[0] = w.depotOpen;
  for (let mask = 1; mask < N; mask++) {
    for (let last = 0; last < n; last++) {
      if (!(mask & (1 << last))) continue;
      const key = mask * n + last;
      const labs = states.get(key);
      if (!labs) continue;
      labs.forEach((lab, li) => {
        // Return to the depot.
        for (const leg of legs(lab.t, lab.since, last + 1, 0)) {
          if (leg.t <= w.depotClose && leg.t < best[mask]!) {
            best[mask] = leg.t;
            bestFrom.set(mask, [key, li, leg.brk]);
          }
        }
        for (let j = 0; j < n; j++) {
          if (mask & (1 << j)) continue;
          const dr = w.drops[j]!;
          for (const leg of legs(lab.t, lab.since, last + 1, j + 1)) {
            if (leg.t > dr.close) continue;
            push((mask | (1 << j)) * n + j, { t: Math.max(leg.t, dr.open) + dr.service, since: leg.since, prev: key, prevLabel: li, brk: leg.brk });
          }
        }
      });
    }
  }
  const rebuild = (mask: number): string[] => {
    if (mask === 0) return [];
    const from = bestFrom.get(mask);
    if (!from) throw new Error('no route');
    const out: string[] = [];
    if (from[2]) out.push('BREAK');
    let [key, li] = from;
    while (key >= 0) {
      const last = key % n;
      const lab = states.get(key)![li]!;
      out.push(w.drops[last]!.id);
      if (lab.brk) out.push('BREAK');
      key = lab.prev;
      li = lab.prevLabel;
    }
    return out.reverse();
  };
  return { best, rebuild };
}

export interface RoutesSolution {
  routes: Record<string, string[]>;
  optimum: number;
}

/** Exact best split of the drops between the vans. */
export function solveRoutes(w: RoutesWorld): RoutesSolution {
  const n = w.drops.length;
  const full = (1 << n) - 1;
  const table = routeTable(w);
  const kgOf = new Float64Array(1 << n);
  for (let m = 1; m <= full; m++) {
    const low = m & -m;
    kgOf[m] = kgOf[m ^ low]! + w.drops[31 - Math.clz32(low)]!.kg;
  }
  const cost = (m: number) => (m === 0 ? 0 : table.best[m]! - w.depotOpen);
  const V = w.vans.length;
  const N = 1 << n;
  const memoV: Float64Array[] = w.vans.map(() => new Float64Array(N).fill(NaN));
  const memoT: Int32Array[] = w.vans.map(() => new Int32Array(N));
  // f(k, S): least total time to serve S with vans k..V-1; memoT holds the set van k takes.
  const f = (k: number, S: number): number => {
    if (S === 0) return 0;
    if (k === V) return Infinity;
    const hit = memoV[k]![S]!;
    if (!Number.isNaN(hit)) return hit;
    let bestV = f(k + 1, S);
    let bestT = 0;
    const cap = w.vans[k]!.capacity;
    // Van k takes a non-empty subset T of S (when the vans left are identical to it, T must hold S's lowest drop).
    const sameRest = w.vans.slice(k + 1).every((v) => v.capacity === cap);
    const low = S & -S;
    for (let T = S; T > 0; T = (T - 1) & S) {
      if (sameRest && !(T & low)) continue;
      if (kgOf[T]! > cap) continue;
      const c = cost(T);
      if (!(c < bestV)) continue;
      const rest = f(k + 1, S & ~T);
      if (c + rest < bestV) {
        bestV = c + rest;
        bestT = T;
      }
    }
    memoV[k]![S] = bestV;
    memoT[k]![S] = bestT;
    return bestV;
  };
  const optimum = f(0, full);
  if (!Number.isFinite(optimum)) throw new Error('no workable plan');
  const routes: Record<string, string[]> = {};
  let S = full;
  for (let k = 0; k < V; k++) {
    const T = S === 0 || Number.isNaN(memoV[k]![S]!) ? 0 : memoT[k]![S]!;
    routes[w.vans[k]!.id] = table.rebuild(T);
    S &= ~T;
  }
  // The rebuilt plan must replay to the optimum with the app's checker.
  const r = checkRoutes(w, { routes, total_minutes: optimum });
  if (!r.feasible || r.value !== optimum) throw new Error(`rebuilt plan does not replay: ${JSON.stringify(r.items.filter((i) => !i.passed))}`);
  return { routes, optimum };
}

/** Every assignment of drops to vans, every order, every break position (tiny worlds only). */
export function bruteForceRoutes(w: RoutesWorld): number | null {
  const n = w.drops.length;
  let best = Infinity;
  const perms = (xs: string[]): string[][] => (xs.length <= 1 ? [xs] : xs.flatMap((x, i) => perms([...xs.slice(0, i), ...xs.slice(i + 1)]).map((p) => [x, ...p])));
  const withBreaks = (route: string[]): string[][] => {
    // A break (or none) after every drop: every subset of positions.
    const out: string[][] = [];
    for (let bits = 0; bits < 1 << route.length; bits++) {
      const r: string[] = [];
      route.forEach((x, i) => {
        r.push(x);
        if (bits & (1 << i)) r.push('BREAK');
      });
      out.push(r);
    }
    return out;
  };
  const bestFor = new Map<string, number>();
  const vanBest = (van: RouteVan, ids: string[]): number => {
    const key = `${van.id}|${ids.join(',')}`;
    const hit = bestFor.get(key);
    if (hit !== undefined) return hit;
    let b = Infinity;
    if (!ids.length) b = 0;
    else
      for (const p of perms(ids))
        for (const r of withBreaks(p)) {
          const rep = replayVan(w, van, r);
          if (rep.ok) b = Math.min(b, rep.back - w.depotOpen);
        }
    bestFor.set(key, b);
    return b;
  };
  const assign = (i: number, groups: string[][]): void => {
    if (i === n) {
      const total = groups.reduce((a, g, k) => a + vanBest(w.vans[k]!, g), 0);
      if (total < best) best = total;
      return;
    }
    for (let k = 0; k < w.vans.length; k++) {
      groups[k]!.push(w.drops[i]!.id);
      assign(i + 1, groups);
      groups[k]!.pop();
    }
  };
  assign(0, w.vans.map(() => []));
  return Number.isFinite(best) ? best : null;
}

// ─────────────────────────────── places ───────────────────────────────

interface Place {
  town: string;
  x: number;
  y: number;
}

/** Rough positions (km east/north of the depot town) for real Peak District / Derbyshire towns. */
const PEAK: Place[] = [
  { town: 'Bakewell', x: -8, y: 6 },
  { town: 'Buxton', x: -22, y: 12 },
  { town: 'Ashbourne', x: -10, y: -16 },
  { town: 'Wirksworth', x: -2, y: -8 },
  { town: 'Belper', x: 6, y: -12 },
  { town: 'Chesterfield', x: 12, y: 10 },
  { town: 'Hathersage', x: -6, y: 22 },
  { town: 'Castleton', x: -16, y: 22 },
  { town: 'Tideswell', x: -16, y: 12 },
  { town: 'Leek', x: -32, y: -2 },
  { town: 'Cromford', x: 1, y: -4 },
  { town: 'Hartington', x: -20, y: -4 },
  { town: 'Youlgreave', x: -10, y: 2 },
  { town: 'Eyam', x: -10, y: 16 },
  { town: 'Baslow', x: -4, y: 12 },
  { town: 'Alfreton', x: 12, y: -6 },
  { town: 'Ripley', x: 10, y: -12 },
  { town: 'Clay Cross', x: 10, y: 2 },
  { town: 'Dronfield', x: 12, y: 20 },
  { town: 'Glossop', x: -20, y: 36 },
];

/** Rough positions around Worcester. */
const WORCS: Place[] = [
  { town: 'Droitwich Spa', x: 1, y: 9 },
  { town: 'Malvern', x: -11, y: -10 },
  { town: 'Pershore', x: 12, y: -11 },
  { town: 'Evesham', x: 22, y: -18 },
  { town: 'Upton-upon-Severn', x: -3, y: -17 },
  { town: 'Bromsgrove', x: 4, y: 22 },
  { town: 'Redditch', x: 15, y: 19 },
  { town: 'Kidderminster', x: -12, y: 20 },
  { town: 'Stourport-on-Severn', x: -12, y: 15 },
  { town: 'Tenbury Wells', x: -28, y: 14 },
  { town: 'Ledbury', x: -17, y: -21 },
  { town: 'Bewdley', x: -16, y: 19 },
  { town: 'Inkberrow', x: 15, y: 3 },
  { town: 'Broadway', x: 28, y: -24 },
  { town: 'Tewkesbury', x: 6, y: -24 },
  { town: 'Alcester', x: 25, y: 6 },
];

const CUSTOMERS = [
  'The Old Bakehouse café', 'Dale View farm shop', 'Rowan Tree deli', 'The Larder', 'Millstone tea rooms', 'Hilltop Stores', 'Wheatsheaf Inn', 'Green Lane greengrocer',
  'Corner Pantry', 'Riverside Kitchen', 'The Tuck Box', 'Market Place café', 'Bluebell Bistro', 'Nook & Cranny', 'Station Road Spar', 'Copper Pot café', 'The Granary',
  'Saddler’s Arms', 'Fern & Feather café', 'Village Post Office stores',
];

interface Spec {
  id: string;
  difficulty: Difficulty;
  seed: number;
  region: 'peak' | 'worcs';
  firm: string;
  manager: string;
  date: string;
  drops: number;
  vans: Array<{ name: string; capacity: number }>;
  /** Share of drops with a narrow window. */
  windowed: number;
  /** A road closure: indexes into the drop list (0 = depot) and the extra minutes. */
  closure?: { reason: string; pairs: Array<[number, number]>; extra: number };
  /** A cancelled drop in the late message (it is not in the world). */
  cancel?: boolean;
  heavy?: boolean;
  /** Spread the towns wider (more driving: breaks bite). */
  scale?: number;
}

export interface RoutesCase {
  id: string;
  difficulty: Difficulty;
  prompt: string;
  notes: string;
  world: RoutesWorld;
  optimum: number;
  reference: Record<string, unknown>;
  careless: Careless[];
}

function travel(a: Place, b: Place, scale: number): number {
  const d = Math.hypot(a.x - b.x, a.y - b.y) * scale;
  return Math.round(5 + d * 1.9);
}

/** The first attempt (seed, seed + 7919, …) that is workable and has the traps the difficulty asks for. */
function buildCase(spec: Spec): RoutesCase {
  for (let attempt = 0; attempt < 60; attempt++) {
    const c = buildAttempt(spec, attempt);
    if (c) return c;
  }
  throw new Error(`${spec.id}: no suitable delivery day found`);
}

function buildAttempt(spec: Spec, attempt: number): RoutesCase | null {
  const rng = new Rng(spec.seed + 7919 * attempt);
  const pool = spec.region === 'peak' ? PEAK : WORCS;
  const depotTown = spec.region === 'peak' ? 'Matlock' : 'Worcester';
  const towns = rng.sample(pool, spec.drops + (spec.cancel ? 1 : 0));
  const custs = rng.sample(CUSTOMERS, towns.length);
  const scale = spec.scale ?? 1;
  const depot: Place = { town: depotTown, x: 0, y: 0 };
  const places = [depot, ...towns];
  const base = places.map((a) => places.map((b) => (a === b ? 0 : travel(a, b, scale))));
  const allDrops: RouteDrop[] = towns.map((t, i) => {
    const kg = spec.heavy ? rng.int(80, 420) : rng.int(30, 260);
    const service = 10 + 5 * Math.floor(kg / 120);
    const r = rng.next();
    let open = 7 * 60 + 30;
    let close = 16 * 60 + 30;
    if (r < spec.windowed) {
      const kind = rng.int(0, 3);
      if (kind === 0) close = rng.pick([9 * 60 + 30, 10 * 60, 10 * 60 + 30]); // before opening
      else if (kind === 1) open = rng.pick([12 * 60, 13 * 60, 13 * 60 + 30]); // after lunch
      else if (kind === 2) {
        open = rng.pick([10 * 60, 10 * 60 + 30, 11 * 60]);
        close = open + rng.pick([60, 90]);
      } else {
        open = rng.pick([8 * 60 + 30, 9 * 60]);
        close = open + 120;
      }
    }
    return { id: `D${String(i + 1).padStart(2, '0')}`, name: `${custs[i]}, ${t.town}`, open, close, service, kg };
  });
  const vans: RouteVan[] = spec.vans.map((v, i) => ({ id: `V${i + 1}`, name: `Van ${i + 1}`, capacity: v.capacity }));
  // The world after the late message: closure applied, cancelled drop removed.
  const matrix = base.map((row) => row.slice());
  if (spec.closure) for (const [a, b] of spec.closure.pairs) {
    matrix[a]![b]! += spec.closure.extra;
    matrix[b]![a]! += spec.closure.extra;
  }
  const cancelled = spec.cancel ? allDrops[allDrops.length - 1]! : null;
  const keep = allDrops.map((_, i) => i).filter((i) => allDrops[i] !== cancelled);
  const sub = (m: number[][]) => [0, ...keep.map((i) => i + 1)].map((a) => [0, ...keep.map((i) => i + 1)].map((b) => m[a]![b]!));
  const world: RoutesWorld = { depot: `${spec.firm} depot, ${depotTown}`, depotOpen: 7 * 60 + 30, depotClose: 17 * 60 + 30, vans, drops: keep.map((i) => allDrops[i]!), matrix: sub(matrix), driveLimit: 270, breakMinutes: 45 };
  let sol: RoutesSolution;
  try {
    sol = solveRoutes(world);
  } catch {
    return null;
  }
  const reference = { routes: sol.routes, total_minutes: sol.optimum };

  const careless: Careless[] = [];
  const variant = (label: string, cw: RoutesWorld) => {
    try {
      const s = solveRoutes(cw);
      // Keep only drops that exist in the true world (a cancelled drop delivered anyway is kept: it is a slip).
      const r = checkRoutes(world, { routes: s.routes });
      careless.push({ label, answer: { routes: s.routes, total_minutes: r.value ?? s.optimum } });
    } catch {
      /* no plan under the misreading */
    }
  };
  if (spec.closure) variant('road closure missed (old travel times)', { ...world, matrix: sub(base) });
  variant('break rule ignored', { ...world, driveLimit: 100000 });
  variant('van capacities ignored', { ...world, vans: world.vans.map((v) => ({ ...v, capacity: 100000 })) });
  variant('delivery windows ignored', { ...world, drops: world.drops.map((d) => ({ ...d, open: 0, close: 24 * 60 })) });
  // Nearest-neighbour planner: each van in turn takes the nearest drop that still works, until none does.
  careless.push({ label: 'nearest-drop-first, van by van', answer: nearestNeighbour(world) });
  // The right plan, but the total quoted as driving time only.
  const driving = world.vans.reduce((a, v) => a + replayVan(world, v, sol.routes[v.id] ?? []).driving, 0);
  careless.push({ label: 'right plan, total quoted as driving time only', answer: { routes: sol.routes, total_minutes: driving } });

  // Traps must bite: the closure must change the best plan, the hard cases need a break, and medium and hard
  // cases need more than one van.
  const scored = (label: string) => {
    const k = careless.find((x) => x.label === label);
    if (!k) return null;
    const r = checkRoutes(world, k.answer);
    return r.feasible ? r.value! : Infinity;
  };
  const used0 = Object.values(sol.routes).filter((r) => r.length).length;
  const breaks0 = Object.values(sol.routes).flat().filter((x) => x === 'BREAK').length;
  if (spec.closure && !((scored('road closure missed (old travel times)') ?? sol.optimum) > sol.optimum)) return null;
  if (spec.difficulty !== 'easy' && used0 < 2) return null;
  if (spec.difficulty === 'hard' && breaks0 < 1) return null;
  if (spec.difficulty === 'hard' && !((scored('nearest-drop-first, van by van') ?? 0) > sol.optimum)) return null;
  const prompt = writePrompt(spec, world, allDrops, base, cancelled);
  const used = Object.values(sol.routes).filter((r) => r.length).length;
  const breaks = Object.values(sol.routes).flat().filter((x) => x === 'BREAK').length;
  const notes = `[${spec.difficulty}] ${world.drops.length} drops (generator attempt ${attempt}), ${vans.length} vans (${used} used in the best plan, ${breaks} break${breaks === 1 ? '' : 's'}). Least total van time ${sol.optimum} min (exact DP over every drop set, then over every split between vans).`;
  return { id: spec.id, difficulty: spec.difficulty, prompt, notes, world, optimum: sol.optimum, reference, careless };
}

function nearestNeighbour(w: RoutesWorld): Record<string, unknown> {
  const left = new Set(w.drops.map((d) => d.id));
  const routes: Record<string, string[]> = {};
  for (const van of w.vans) {
    const route: string[] = [];
    for (;;) {
      const at = route.length ? w.drops.findIndex((d) => d.id === route.filter((x) => x !== 'BREAK').at(-1)) + 1 : 0;
      const cands = [...left].map((id) => ({ id, i: w.drops.findIndex((d) => d.id === id) + 1 })).sort((a, b) => w.matrix[at]![a.i]! - w.matrix[at]![b.i]! || a.i - b.i);
      let added = false;
      for (const c of cands) {
        for (const trial of [[...route, c.id], [...route, 'BREAK', c.id]]) {
          if (route.length === 0 && trial[0] === 'BREAK') continue;
          if (replayVan(w, van, trial).ok) {
            route.splice(0, route.length, ...trial);
            left.delete(c.id);
            added = true;
            break;
          }
        }
        if (added) break;
      }
      if (!added) break;
    }
    routes[van.id] = route;
  }
  const r = checkRoutes(w, { routes });
  return { routes, total_minutes: r.value ?? 0 };
}

function writePrompt(spec: Spec, w: RoutesWorld, allDrops: RouteDrop[], base: number[][], cancelled: RouteDrop | null): string {
  const L: string[] = [];
  const depotTown = spec.region === 'peak' ? 'Matlock' : 'Worcester';
  L.push(`From: ${spec.manager}`);
  L.push(`Subject: Van routes for ${spec.date}`);
  L.push('');
  L.push(`Hi, can you plan tomorrow’s delivery routes for ${spec.firm}? Our drivers are paid from when the depot opens until they get back, so I want the plan with the least total van time that keeps every rule.`);
  L.push('');
  L.push(`DEPOT: ${depotTown}. Opens 07:30 (all vans leave at 07:30; they are loaded the night before). Every van must be back by 17:30. A van we don’t need can stay at the depot and costs nothing.`);
  L.push('');
  L.push('VANS');
  for (const v of w.vans) L.push(`- ${v.id} (${v.name}): carries up to ${v.capacity.toLocaleString('en-GB')} kg.`);
  L.push('');
  L.push('DROPS (unloading time is spent at the drop; the van must ARRIVE no later than the window’s end; if it arrives before the window opens it waits, and unloading starts when the window opens)');
  L.push('Drop | Customer | Weight | Unloading | Window');
  for (const d of allDrops) {
    const win = d.open <= 7 * 60 + 30 && d.close >= 16 * 60 + 30 ? 'any time' : d.open <= 7 * 60 + 30 ? `by ${hm(d.close)}` : d.close >= 16 * 60 + 30 ? `from ${hm(d.open)}` : `${hm(d.open)}–${hm(d.close)}`;
    L.push(`${d.id} | ${d.name} | ${d.kg} kg | ${d.service} min | ${win}`);
  }
  L.push('');
  L.push('TRAVEL TIMES (minutes, either direction)');
  const ids = ['Depot', ...allDrops.map((d) => d.id)];
  L.push(`From \\ To | ${ids.join(' | ')}`);
  base.forEach((row, i) => L.push(`${ids[i]} | ${row.map((x, j) => (i === j ? '-' : String(x))).join(' | ')}`));
  L.push('');
  L.push('RULES');
  L.push('1. Each drop is delivered by exactly one van, in one visit. A van’s load (the total weight of its drops) must not be more than it carries.');
  L.push('2. Drivers’ hours (company rule for this exercise): no more than 4 h 30 min (270 minutes) of driving in total before a 45-minute break, and again after each break. A break is taken at a drop after unloading, and is shown in the route as "BREAK". Only time actually driving counts towards the 270 minutes; waiting and unloading do not, and waiting does not count as a break.');
  L.push('3. Van time = from 07:30 until that van is back at the depot (driving, waiting, unloading and breaks all count). Total van time = the sum over the vans that go out.');
  if (spec.closure || cancelled) {
    L.push('');
    const parts: string[] = [];
    if (spec.closure) {
      const nm = (i: number) => (i === 0 ? `the depot` : `${allDrops[i - 1]!.id} (${allDrops[i - 1]!.name.split(', ').at(-1)})`);
      const pairs = spec.closure.pairs.map(([a, b]) => `${nm(a)} and ${nm(b)}`);
      parts.push(`a road is closed after ${spec.closure.reason}, so every trip between ${pairs.slice(0, -1).join(', between ')}${pairs.length > 1 ? ', or between ' : ''}${pairs.at(-1)} now takes ${spec.closure.extra} minutes longer than in the table (either direction). Everything else in the table is unchanged.`);
    }
    if (cancelled) parts.push(`${cancelled.id} (${cancelled.name}) has just cancelled: they are closed tomorrow for a family funeral. Take them off the plan.`);
    L.push(`Update from ${spec.manager.split(' ')[0]} at 16:45: ${parts.join(' Also, ')}`);
  }
  L.push('');
  L.push('Answer with each van’s drops in order (put BREAK where the driver takes a break; leave a van’s list empty if it stays at the depot) and the total van time in minutes, in this JSON format:');
  L.push('```json');
  L.push(`{"routes": {"V1": ["<drop>", "BREAK", "<drop>"], "V2": []}, "total_minutes": 0}`);
  L.push('```');
  L.push(ONE_ANSWER);
  void w;
  return L.join('\n');
}

// ─────────────────────────────── cases ───────────────────────────────

const SPECS: Spec[] = [
  { id: 'v01', difficulty: 'easy', seed: 9101, region: 'peak', firm: 'Peak Pantry Wholesale', manager: 'Gethin Price', date: 'Tue 10 Nov 2026', drops: 8, vans: [{ name: 'Van 1', capacity: 1000 }, { name: 'Van 2', capacity: 1000 }], windowed: 0.25 },
  { id: 'v02', difficulty: 'easy', seed: 9102, region: 'worcs', firm: 'Severn Vale Florist Supplies', manager: 'Amara Osei', date: 'Wed 11 Nov 2026', drops: 8, vans: [{ name: 'Van 1', capacity: 900 }, { name: 'Van 2', capacity: 900 }], windowed: 0.35 },
  { id: 'v03', difficulty: 'easy', seed: 9103, region: 'peak', firm: 'Peak Pantry Wholesale', manager: 'Gethin Price', date: 'Thu 12 Nov 2026', drops: 9, vans: [{ name: 'Van 1', capacity: 1200 }, { name: 'Van 2', capacity: 800 }], windowed: 0.35 },
  { id: 'v04', difficulty: 'medium', seed: 9204, region: 'worcs', firm: 'Severn Vale Florist Supplies', manager: 'Amara Osei', date: 'Fri 13 Nov 2026', drops: 10, vans: [{ name: 'Van 1', capacity: 1000 }, { name: 'Van 2', capacity: 1000 }, { name: 'Van 3', capacity: 600 }], windowed: 0.5, heavy: true, scale: 1.3 },
  { id: 'v05', difficulty: 'medium', seed: 9205, region: 'peak', firm: 'Peak Pantry Wholesale', manager: 'Gethin Price', date: 'Mon 16 Nov 2026', drops: 11, vans: [{ name: 'Van 1', capacity: 1200 }, { name: 'Van 2', capacity: 1000 }], windowed: 0.45, scale: 1.4, closure: { reason: 'a burst water main', pairs: [[0, 2], [2, 5]], extra: 25 } },
  { id: 'v06', difficulty: 'medium', seed: 64639, region: 'worcs', firm: 'Severn Vale Florist Supplies', manager: 'Amara Osei', date: 'Tue 17 Nov 2026', drops: 12, vans: [{ name: 'Van 1', capacity: 1000 }, { name: 'Van 2', capacity: 1000 }, { name: 'Van 3', capacity: 700 }], windowed: 0.5, heavy: true, scale: 1.5, cancel: true },
  { id: 'v07', difficulty: 'hard', seed: 72659, region: 'peak', firm: 'Peak Pantry Wholesale', manager: 'Gethin Price', date: 'Wed 18 Nov 2026', drops: 13, vans: [{ name: 'Van 1', capacity: 1200 }, { name: 'Van 2', capacity: 1000 }, { name: 'Van 3', capacity: 800 }], windowed: 0.6, heavy: true, scale: 1.6, closure: { reason: 'snow', pairs: [[3, 6], [3, 9], [0, 3]], extra: 30 } },
  { id: 'v08', difficulty: 'hard', seed: 25146, region: 'worcs', firm: 'Severn Vale Florist Supplies', manager: 'Amara Osei', date: 'Thu 19 Nov 2026', drops: 14, vans: [{ name: 'Van 1', capacity: 1100 }, { name: 'Van 2', capacity: 1100 }, { name: 'Van 3', capacity: 800 }, { name: 'Van 4', capacity: 600 }], windowed: 0.6, heavy: true, scale: 1.7, cancel: true, closure: { reason: 'flooding', pairs: [[4, 7], [0, 4]], extra: 35 } },
  { id: 'v09', difficulty: 'hard', seed: 191446, region: 'peak', firm: 'Peak Pantry Wholesale', manager: 'Gethin Price', date: 'Fri 20 Nov 2026', drops: 15, vans: [{ name: 'Van 1', capacity: 1200 }, { name: 'Van 2', capacity: 1200 }, { name: 'Van 3', capacity: 900 }], windowed: 0.55, heavy: true, scale: 1.45, closure: { reason: 'a crash', pairs: [[1, 8], [8, 11]], extra: 40 } },
];

export function buildRoutesCases(): RoutesCase[] {
  return SPECS.map(buildCase);
}

export const ROUTE_SPECS = SPECS;
export { buildCase as buildRoutesCase };

/** Cross-check: random tiny delivery days solved by the DP and by brute force. Returns the mismatches. */
export function crosscheckRoutes(trials = 30, seed = 5): { checked: number; feasible: number; mismatches: string[] } {
  const rng = new Rng(seed);
  const mismatches: string[] = [];
  let feasible = 0;
  for (let t = 0; t < trials; t++) {
    const n = rng.int(3, 6);
    const towns = rng.sample(PEAK, n);
    const scale = 1 + rng.next() * 2.5;
    const places: Place[] = [{ town: 'Depot', x: 0, y: 0 }, ...towns];
    const matrix = places.map((a) => places.map((b) => (a === b ? 0 : travel(a, b, scale))));
    const drops: RouteDrop[] = towns.map((tw, i) => {
      const open = rng.next() < 0.4 ? rng.int(8, 13) * 60 : 7 * 60 + 30;
      const close = rng.next() < 0.4 ? open + rng.int(1, 4) * 60 : 16 * 60 + 30;
      return { id: `D${i + 1}`, name: tw.town, open, close: Math.max(close, open + 30), service: rng.pick([10, 15, 20]), kg: rng.int(50, 400) };
    });
    const vans: RouteVan[] = Array.from({ length: rng.int(1, 2) }, (_, i) => ({ id: `V${i + 1}`, name: `Van ${i + 1}`, capacity: rng.pick([600, 900, 1200]) }));
    const w: RoutesWorld = { depot: 'Depot', depotOpen: 450, depotClose: 1050, vans, drops, matrix, driveLimit: rng.pick([120, 180, 270]), breakMinutes: 45 };
    let dp: number | null = null;
    try {
      dp = solveRoutes(w).optimum;
    } catch {
      dp = null;
    }
    const bf = bruteForceRoutes(w);
    if (bf !== null) feasible++;
    if (dp !== bf) mismatches.push(`trial ${t}: DP ${dp}, brute force ${bf}`);
  }
  return { checked: trials, feasible, mismatches };
}
