/**
 * Plan the Van Routes (work.delivery-routes): replays each van's route minute by minute with the travel-time matrix
 * (after any road closure in the brief, applied by the generator to the stored matrix): vans leave when the depot
 * opens, wait if early for a drop's window, must arrive before the window closes, spend the stated unloading time,
 * and must be back before the depot closes. A driver may not drive more than the stated limit without a break; a
 * break is written "BREAK" in the route after a drop and takes the stated minutes. Every drop must be delivered
 * exactly once and each van's load must fit. The plan's value is the total van time: for every van used, from the
 * depot opening to its return.
 */
import type { PlanCheck, PlanVerifier, VerifierItem } from './types.ts';
import { field, normText, readId, readList, readNumber } from './common.ts';

export interface RouteDrop {
  id: string;
  name: string;
  /** Window, minutes after midnight: arrival must be no later than `close`; unloading starts no earlier than `open`. */
  open: number;
  close: number;
  /** Unloading minutes. */
  service: number;
  kg: number;
}

export interface RouteVan {
  id: string;
  name: string;
  capacity: number;
}

export interface RoutesWorld {
  depot: string;
  depotOpen: number;
  depotClose: number;
  vans: RouteVan[];
  drops: RouteDrop[];
  /** Minutes between places: index 0 is the depot, index i is drops[i - 1]. */
  matrix: number[][];
  /** Most driving minutes without a break, and the break's length. */
  driveLimit: number;
  breakMinutes: number;
}

export interface VanReplay {
  ok: boolean;
  problems: string[];
  /** Return time (minutes after midnight), when the route could be driven. */
  back: number;
  kg: number;
  driving: number;
}

export function hm(min: number): string {
  return `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(Math.round(min % 60)).padStart(2, '0')}`;
}

/** Replays one van's stops (drop ids and "BREAK"). */
export function replayVan(w: RoutesWorld, van: RouteVan, stops: string[]): VanReplay {
  const problems: string[] = [];
  let t = w.depotOpen;
  let since = 0;
  let at = 0;
  let kg = 0;
  let driving = 0;
  const idx = new Map(w.drops.map((d, i) => [d.id, i + 1]));
  const drive = (to: number, label: string) => {
    const d = w.matrix[at]![to]!;
    since += d;
    driving += d;
    if (since > w.driveLimit) problems.push(`${van.name}: ${since} min driving without a break by ${label}`);
    t += d;
    at = to;
  };
  let lastWasBreak = true;
  for (const s of stops) {
    if (s === 'BREAK') {
      if (lastWasBreak) problems.push(`${van.name}: a break must come after a drop`);
      t += w.breakMinutes;
      since = 0;
      lastWasBreak = true;
      continue;
    }
    const i = idx.get(s)!;
    const drop = w.drops[i - 1]!;
    drive(i, drop.id);
    if (t > drop.close) problems.push(`${van.name}: reaches ${drop.id} at ${hm(t)}, after its window closes (${hm(drop.close)})`);
    if (t < drop.open) t = drop.open;
    t += drop.service;
    kg += drop.kg;
    lastWasBreak = false;
  }
  if (stops.length) drive(0, 'the depot');
  if (stops.length && t > w.depotClose) problems.push(`${van.name}: back at ${hm(t)}, after the depot closes (${hm(w.depotClose)})`);
  if (kg > van.capacity) problems.push(`${van.name}: ${kg} kg on board, holds ${van.capacity} kg`);
  return { ok: problems.length === 0, problems, back: stops.length ? t : w.depotOpen, kg, driving };
}

const BREAK_WORDS = new Set(['break', 'rest', 'driverbreak', '45minbreak', '45minutebreak', 'lunch', 'lunchbreak']);

/** Reads {"routes": {"Van 1": [...]}} or [{"van": "V1", "stops": [...]}]; ignores "Depot" at either end. */
export function readRoutes(w: RoutesWorld, raw: unknown): { routes: Map<string, string[]>; bad?: string } {
  const routes = new Map<string, string[]>();
  const vanIds = w.vans.map((v) => v.id);
  const vanNames = new Map(w.vans.map((v) => [v.id, v.name]));
  const dropIds = w.drops.map((d) => d.id);
  const dropNames = new Map(w.drops.map((d) => [d.id, d.name]));
  const entries: Array<[unknown, unknown]> = Array.isArray(raw)
    ? raw.map((r) => [field(r, 'van', 'vehicle', 'id', 'name'), field(r, 'stops', 'route', 'drops', 'sequence')])
    : raw && typeof raw === 'object'
      ? Object.entries(raw as Record<string, unknown>)
      : [];
  if (!entries.length) return { routes, bad: 'no routes given' };
  for (const [k, v] of entries) {
    const van = readId(k, vanIds, vanNames) ?? (typeof k === 'string' ? w.vans.find((x) => normText(x.name).startsWith(normText(k)) && normText(k).length >= 4)?.id ?? null : null);
    if (!van) return { routes, bad: `"${String(k).slice(0, 30)}" is not one of the vans` };
    if (routes.has(van)) return { routes, bad: `${van} is given two routes` };
    const list = v === null || v === undefined ? [] : readList(v);
    if (!list) return { routes, bad: `${van}: the route must be a list of stops` };
    const stops: string[] = [];
    for (const item of list) {
      const text = typeof item === 'string' ? item : typeof item === 'object' && item ? String(field(item, 'drop', 'stop', 'id') ?? '') : String(item);
      const n = normText(text).replace(/\s+/g, '');
      if (!n) continue;
      if (BREAK_WORDS.has(n) || /^break\b/.test(normText(text)) || /\bbreak$/.test(normText(text))) {
        stops.push('BREAK');
        continue;
      }
      if (/^depot\b/.test(normText(text)) || normText(text) === normText(w.depot)) continue;
      const id = readId(text, dropIds, dropNames);
      if (!id) return { routes, bad: `${van}: "${text.slice(0, 30)}" is not one of the drops` };
      stops.push(id);
    }
    routes.set(van, stops);
  }
  return { routes };
}

export function checkRoutes(w: RoutesWorld, answer: Record<string, unknown>): PlanCheck {
  const items: VerifierItem[] = [];
  const statedRaw = field(answer, 'totalminutes', 'total', 'totaltime', 'totalvanminutes', 'minutes');
  const stated = readMinutes(statedRaw);
  const raw = field(answer, 'routes', 'vans', 'plan');
  const { routes, bad } = readRoutes(w, raw);
  items.push({ label: 'every route is a listed van with listed drops', passed: !bad, hard: true, detail: bad });
  if (bad) return { feasible: false, items, stated };
  const seen = new Map<string, number>();
  for (const stops of routes.values()) for (const s of stops) if (s !== 'BREAK') seen.set(s, (seen.get(s) ?? 0) + 1);
  const missing = w.drops.filter((d) => !seen.has(d.id)).map((d) => d.id);
  const twice = [...seen.entries()].filter(([, n]) => n > 1).map(([id]) => id);
  items.push({ label: 'every drop delivered exactly once', passed: !missing.length && !twice.length, hard: true, detail: [missing.length ? `missing ${missing.join(', ')}` : '', twice.length ? `twice ${twice.join(', ')}` : ''].filter(Boolean).join('; ') || undefined });
  const replays = w.vans.map((v) => ({ v, r: replayVan(w, v, routes.get(v.id) ?? []) }));
  const all = replays.flatMap((x) => x.r.problems);
  const fail = (re: RegExp) => {
    const xs = all.filter((p) => re.test(p));
    return xs.length ? xs.slice(0, 3).join('; ') + (xs.length > 3 ? ` (+${xs.length - 3} more)` : '') : undefined;
  };
  items.push({ label: 'every drop reached before its window closes', passed: !fail(/window closes/), hard: true, detail: fail(/window closes/) });
  items.push({ label: `no more than ${w.driveLimit / 60} h of driving without a ${w.breakMinutes}-minute break`, passed: !fail(/without a break|break must come/), hard: true, detail: fail(/without a break|break must come/) });
  items.push({ label: 'every van back before the depot closes', passed: !fail(/depot closes/), hard: true, detail: fail(/depot closes/) });
  items.push({ label: 'no van overloaded', passed: !fail(/kg on board/), hard: true, detail: fail(/kg on board/) });
  const total = replays.reduce((a, x) => a + (x.r.back - w.depotOpen), 0);
  const feasible = items.every((i) => i.passed || !i.hard);
  return { feasible, value: total, stated, items };
}

/** 845, "845", "845 min", "14 h 5 min", "14:05" (hours:minutes) → minutes; null otherwise. */
export function readMinutes(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? Math.round(v) : null;
  if (typeof v !== 'string') return null;
  const t = v.trim().toLowerCase();
  let m = /^(\d+)\s*h(?:ours?|rs?)?\s*(?:(\d+)\s*m(?:in(?:ute)?s?)?)?$/.exec(t);
  if (m) return Number(m[1]) * 60 + Number(m[2] ?? 0);
  m = /^(\d+):(\d{2})$/.exec(t);
  if (m) return Number(m[1]) * 60 + Number(m[2]);
  const n = readNumber(t.replace(/\s*(?:min(?:ute)?s?|mins?)\.?$/, ''));
  return n === null ? null : Math.round(n);
}

export function fmtMinutes(min: number): string {
  return `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')} min (${min} min)`;
}

export const routes: PlanVerifier<RoutesWorld> = {
  kind: 'plan',
  name: 'routes',
  noun: 'route plan',
  objective: 'min',
  answerKeys: ['routes'],
  quotesTotal: true,
  marking: [
    'Each van’s route is replayed minute by minute with the travel times as they stand after any road closure in the brief: leave when the depot opens, wait if early for a window, unload for the stated time. Every drop must be delivered exactly once, reached before its window closes, no van may be overloaded or back after the depot closes, and nobody may drive longer than the stated limit without the stated break. Any broken rule scores 0.',
    'A workable plan is valued at its total van time (for every van used, from depot opening to its return). The least total time (proven by an exact search over every split of the drops between the vans and every order within a route) scores 100; a workable but slower plan scores 50 × (best ÷ its time)², always under 50. The total must be quoted: a missing or wrong total halves the score.',
  ],
  formatValue: fmtMinutes,
  check: checkRoutes,
};
