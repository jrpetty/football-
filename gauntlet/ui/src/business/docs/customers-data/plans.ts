/**
 * Plan the Van Routes (work.delivery-routes) and Build the Rota (work.shift-rota): the plan read with the real
 * verifiers' own readers and replays (src/scoring/verifiers/routes.ts, rota.ts), plus what the pictures need — a
 * map laid out from the travel-time matrix, and which rota cell breaks which rule. Pure; tested in
 * test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { answerCandidates, scoreVerifier, type VerifierOutcome } from '../../../../../src/scoring/verifier.ts';
import { readRoutes, replayVan, type RoutesWorld, type VanReplay } from '../../../../../src/scoring/verifiers/routes.ts';
import { assignmentCost, readRota, touchesNight, type RotaWorld } from '../../../../../src/scoring/verifiers/rota.ts';
import { finalReply } from './kit.ts';

// ───────────────────────────── van routes ─────────────────────────────

export interface RoutesDoc {
  world: RoutesWorld;
  /** Map positions (0..1) of the depot (index 0) and each drop, laid out from the travel times. */
  pos: Array<[number, number]>;
  optimum: number;
  best: Record<string, string[]>;
  firm: string;
  date: string;
}

/** Classical multidimensional scaling of a travel-time matrix to two dimensions, scaled into the unit square. */
export function layoutFromMatrix(m: number[][]): Array<[number, number]> {
  const n = m.length;
  const d2 = m.map((row, i) => row.map((v, j) => {
    const s = (v + (m[j]?.[i] ?? v)) / 2;
    return s * s;
  }));
  const rowMean = d2.map((r) => r.reduce((a, b) => a + b, 0) / n);
  const all = rowMean.reduce((a, b) => a + b, 0) / n;
  const B = d2.map((r, i) => r.map((v, j) => -0.5 * (v - rowMean[i]! - rowMean[j]! + all)));
  const eig = (exclude: number[] | null): number[] => {
    let v = Array.from({ length: n }, (_, i) => Math.sin(i + 1) + (exclude ? Math.cos(i * 3) : 0.5));
    for (let it = 0; it < 200; it++) {
      let w = B.map((r) => r.reduce((a, b, j) => a + b * v[j]!, 0));
      if (exclude) {
        const dot = w.reduce((a, b, i) => a + b * exclude[i]!, 0);
        w = w.map((x, i) => x - dot * exclude[i]!);
      }
      const norm = Math.hypot(...w) || 1;
      v = w.map((x) => x / norm);
    }
    return v;
  };
  const v1 = eig(null);
  const v2 = eig(v1);
  const val = (v: number[]) => Math.max(0, v.reduce((a, x, i) => a + x * B[i]!.reduce((s, b, j) => s + b * v[j]!, 0), 0));
  const l1 = Math.sqrt(val(v1));
  const l2 = Math.sqrt(val(v2));
  const pts = v1.map((x, i) => [x * l1, v2[i]! * l2] as [number, number]);
  const xs = pts.map((p) => p[0]);
  const ys = pts.map((p) => p[1]);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const s = Math.max(x1 - x0, y1 - y0) || 1;
  return pts.map(([x, y]) => [(x - x0) / s, (y - y0) / s]);
}

export function parseRoutes(input: CaseVisualInput): RoutesDoc | null {
  const e = input.expected as { world?: RoutesWorld; optimum?: number; reference?: { routes: Record<string, string[]> } } | undefined;
  if (!e?.world?.matrix || !e.world.drops?.length) return null;
  const p = input.turns[0] ?? '';
  return {
    world: e.world,
    pos: layoutFromMatrix(e.world.matrix),
    optimum: Number(e.optimum),
    best: e.reference?.routes ?? {},
    firm: (p.match(/routes for ([^?]+?)\?/) ?? [])[1] ?? '',
    date: (p.match(/Subject: Van routes for (.+)/) ?? [])[1]?.trim() ?? '',
  };
}

export interface RoutesAnswer {
  outcome: VerifierOutcome;
  routes: Map<string, string[]> | null;
  bad: string | null;
  replays: Array<{ van: string; stops: string[]; r: VanReplay }>;
  total: number | null;
  stated: number | null;
  /** Drops reached after their window closed. */
  late: Set<string>;
}

export function routesAnswer(input: CaseVisualInput, doc: RoutesDoc): RoutesAnswer | null {
  const reply = finalReply(input);
  if (!reply.trim()) return null;
  const outcome = scoreVerifier('routes', input.expected, reply, input.turns.join('\n\n'));
  const cands = answerCandidates(reply, ['routes'], input.turns.join('\n\n'));
  const ans = cands.length === 1 ? cands[0]! : null;
  if (!ans) return { outcome, routes: null, bad: outcome.summary, replays: [], total: null, stated: null, late: new Set() };
  const raw = Object.entries(ans).find(([k]) => /^(routes|vans|plan)$/i.test(k))?.[1];
  const { routes, bad } = readRoutes(doc.world, raw);
  const replays = bad ? [] : doc.world.vans.map((v) => ({ van: v.id, stops: routes.get(v.id) ?? [], r: replayVan(doc.world, v, routes.get(v.id) ?? []) }));
  const late = new Set<string>();
  for (const x of replays) for (const pr of x.r.problems) {
    const m = pr.match(/reaches (\S+) at/);
    if (m) late.add(m[1]!.replace(/,$/, ''));
  }
  return { outcome, routes: bad ? null : routes, bad: bad ?? null, replays, total: typeof outcome.detail.value === 'number' ? outcome.detail.value : null, stated: typeof outcome.detail.stated === 'number' ? outcome.detail.stated : null, late };
}

/** The best plan replayed (for the "best plan" map and its van times). */
export function bestReplays(doc: RoutesDoc): Array<{ van: string; stops: string[]; r: VanReplay }> {
  return doc.world.vans.map((v) => ({ van: v.id, stops: doc.best[v.id] ?? [], r: replayVan(doc.world, v, doc.best[v.id] ?? []) }));
}

// ───────────────────────────── rota ─────────────────────────────

export interface RotaDoc {
  world: RotaWorld;
  optimum: number;
  best: Map<string, string[]>;
  place: string;
  week: string;
  days: string[];
}

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function parseRota(input: CaseVisualInput): RotaDoc | null {
  const e = input.expected as { world?: RotaWorld; optimum?: number; reference?: { rota: Record<string, string[]> } } | undefined;
  if (!e?.world?.shifts?.length) return null;
  const p = input.turns[0] ?? '';
  const best = readRota(e.world, e.reference?.rota ?? {}).byShift;
  return { world: e.world, optimum: Number(e.optimum), best, place: (p.match(/rota for ([^?]+?)\?/) ?? [])[1] ?? '', week: (p.match(/Subject: Rota for the week of (.+)/) ?? [])[1]?.trim() ?? '', days: DAYS };
}

export interface CellFlag {
  pid: string;
  sid: string;
  why: string;
}

export interface RotaAnswer {
  outcome: VerifierOutcome;
  byShift: Map<string, string[]> | null;
  bad: string | null;
  cost: number | null;
  stated: number | null;
  /** Person+shift cells that break a rule. */
  cells: CellFlag[];
  /** Shifts short of people or skills. */
  shifts: Array<{ sid: string; why: string }>;
  /** People outside their contracted hours or over the cap: id → text. */
  hours: Record<string, string>;
  paid: Record<string, number>;
}

export function rotaFlags(w: RotaWorld, byShift: Map<string, string[]>): Pick<RotaAnswer, 'cells' | 'shifts' | 'hours' | 'paid'> {
  const cells: CellFlag[] = [];
  const shifts: RotaAnswer['shifts'] = [];
  const hours: Record<string, string> = {};
  const paid: Record<string, number> = {};
  const staff = new Map(w.staff.map((p) => [p.id, p]));
  for (const s of w.shifts) {
    const ppl = [...new Set(byShift.get(s.id) ?? [])];
    if (ppl.length !== s.need) shifts.push({ sid: s.id, why: `${ppl.length} on, needs ${s.need}` });
    for (const [sk, n] of Object.entries(s.skills)) {
      const have = ppl.filter((pid) => staff.get(pid)?.skills.includes(sk)).length;
      if (have < n) shifts.push({ sid: s.id, why: `needs ${n} ${w.skillNames[sk] ?? sk}, has ${have}` });
    }
    for (const pid of ppl) {
      const p = staff.get(pid);
      if (!p) continue;
      if (p.off.includes(s.id)) cells.push({ pid, sid: s.id, why: `${p.name.split(' ')[0]} can’t work this shift` });
      if (p.under18 && touchesNight(s)) cells.push({ pid, sid: s.id, why: 'under-18 working after 22:00' });
      else if (p.under18 && s.paid > w.youngDaily) cells.push({ pid, sid: s.id, why: `under-18 working over ${w.youngDaily} hours in a day` });
    }
  }
  for (const p of w.staff) {
    const mine = w.shifts.filter((s) => (byShift.get(s.id) ?? []).includes(p.id)).sort((a, b) => a.start - b.start);
    const need = (p.under18 ? w.youngRest : w.rest) * 60;
    let prevEnd = p.lastEnd ?? -Infinity;
    for (const s of mine) {
      if (s.start - prevEnd < need) cells.push({ pid: p.id, sid: s.id, why: `only ${Math.max(0, Math.round(((s.start - prevEnd) / 60) * 10) / 10)} h rest before it (needs ${need / 60})` });
      prevEnd = s.end;
    }
    const h = mine.reduce((a, s) => a + s.paid, 0);
    paid[p.id] = h;
    const cap = p.under18 ? w.youngCap : w.cap;
    if (h > cap) hours[p.id] = `${h} h, over the ${cap} h cap`;
    else if (h < p.min || h > p.max) hours[p.id] = `${h} h, contract ${p.min}–${p.max}`;
  }
  return { cells, shifts, hours, paid };
}

export function rotaAnswer(input: CaseVisualInput, doc: RotaDoc): RotaAnswer | null {
  const reply = finalReply(input);
  if (!reply.trim()) return null;
  const outcome = scoreVerifier('rota', input.expected, reply, input.turns.join('\n\n'));
  const cands = answerCandidates(reply, ['rota', 'roster'], input.turns.join('\n\n'));
  const ans = cands.length === 1 ? cands[0]! : null;
  const raw = ans ? Object.entries(ans).find(([k]) => /^(rota|schedule|shifts|roster)$/i.test(k))?.[1] : undefined;
  const read = ans ? readRota(doc.world, raw) : null;
  const byShift = read && !read.bad ? read.byShift : null;
  const flags = byShift ? rotaFlags(doc.world, byShift) : { cells: [], shifts: [], hours: {}, paid: {} };
  return { outcome, byShift, bad: read?.bad ?? (ans ? null : outcome.summary), cost: typeof outcome.detail.value === 'number' ? outcome.detail.value : null, stated: typeof outcome.detail.stated === 'number' ? outcome.detail.stated : null, ...flags };
}

/** Cost of one person's week on a rota, in pence. */
export function personCost(w: RotaWorld, byShift: Map<string, string[]>, pid: string): number {
  const p = w.staff.find((x) => x.id === pid)!;
  return w.shifts.filter((s) => (byShift.get(s.id) ?? []).includes(pid)).reduce((a, s) => a + assignmentCost(p, s), 0);
}
