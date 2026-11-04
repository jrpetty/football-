/**
 * Build the Rota (work.shift-rota): rota generator + exact solver.
 *
 * Settings: a pub (Open / Close), a care home (Day / Night, 11.5 paid hours) and a warehouse (Early / Late / Night,
 * night premium). Every staff member has a rate, contracted hours, skills, availability and (for some) the last
 * shift they worked last week; the brief states the working-time rules for the exercise.
 *
 * The solver is an exact depth-first branch-and-bound over every assignment: shifts are filled one at a time
 * (weekend shifts first, so the fairness rule prunes early), each with every combination of eligible people of
 * exactly the size the shift needs, and a branch is cut only when a valid lower bound on what the rest of the week
 * must cost (the larger of: the cheapest eligible people for every remaining shift; and every remaining slot filled
 * at each person's cheapest rate within their remaining hours, contract minimums first) cannot beat the best rota
 * found so far. It returns the proven optimum. `exhaustiveRota` enumerates every rota without any bound (used by the
 * tests on small worlds to confirm the branch-and-bound).
 */
import { Rng, money, pounds, ONE_ANSWER, type Careless, type Difficulty } from './common.mts';
import { assignmentCost, checkRota, touchesNight, type RotaShift, type RotaStaff, type RotaWorld } from '../../src/scoring/verifiers/rota.ts';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

// ─────────────────────────────── solver ───────────────────────────────

interface Prep {
  w: RotaWorld;
  n: number;
  order: number[];
  /** eligible[p][s] (availability, under-18 rules, last week's rest). */
  eligible: boolean[][];
  cost: number[][];
  /** conflict[p][s][t]: person p cannot do both s and t (rest rule). */
  conflict: boolean[][][];
  maxH: number[];
}

function prepare(w: RotaWorld): Prep {
  const n = w.staff.length;
  const S = w.shifts.length;
  const eligible = w.staff.map((p) =>
    w.shifts.map((s) => {
      if (p.off.includes(s.id)) return false;
      if (p.under18 && (touchesNight(s) || s.paid > w.youngDaily)) return false;
      const need = (p.under18 ? w.youngRest : w.rest) * 60;
      if (p.lastEnd !== undefined && s.start - p.lastEnd < need) return false;
      return true;
    }),
  );
  const cost = w.staff.map((p) => w.shifts.map((s) => assignmentCost(p, s)));
  const conflict = w.staff.map((p) => {
    const need = (p.under18 ? w.youngRest : w.rest) * 60;
    return w.shifts.map((a) => w.shifts.map((b) => a !== b && (a.start < b.start ? b.start - a.end < need : a.start - b.end < need)));
  });
  const maxH = w.staff.map((p) => Math.min(p.max, p.under18 ? w.youngCap : w.cap));
  const order = w.shifts.map((_, i) => i).sort((a, b) => {
    const wa = w.shifts[a]!.day >= 5 ? 0 : 1;
    const wb = w.shifts[b]!.day >= 5 ? 0 : 1;
    return wa - wb || w.shifts[a]!.start - w.shifts[b]!.start;
  });
  void S;
  return { w, n, order, eligible, cost, conflict, maxH };
}

const BUDGET = 'rota search budget exhausted';

export interface RotaSolution {
  byShift: Map<string, string[]>;
  optimum: number;
  nodes: number;
}

/** Exact branch-and-bound. Throws if the node budget runs out (the case is then regenerated), so a returned optimum is proven. */
export function solveRota(w: RotaWorld, nodeLimit = 3_000_000, incumbentOk = false): RotaSolution {
  const P = prepare(w);
  const { n, order, eligible, cost, conflict, maxH } = P;
  const shifts = w.shifts;
  const hours = new Array<number>(n).fill(0);
  const weekend = new Array<number>(n).fill(0);
  const assigned: number[][] = w.staff.map(() => []);
  const pick: number[][] = shifts.map(() => []);
  const lastWeekendPos = order.reduce((m, si, pos) => (shifts[si]!.day >= 5 ? pos : m), -1);
  let best = Infinity;
  let bestPick: number[][] | null = null;
  let nodes = 0;

  const canTake = (p: number, si: number): boolean => {
    if (!eligible[p]![si]) return false;
    if (hours[p]! + shifts[si]!.paid > maxH[p]!) return false;
    for (const t of assigned[p]!) if (conflict[p]![si]![t]) return false;
    return true;
  };

  // Every shift in a world has the same paid hours, so a rota's cost is h0 × (the sum of the rates of everyone on
  // every slot) + the premiums (a constant: every slot is filled).
  const h0 = shifts[0]!.paid;
  if (shifts.some((s) => s.paid !== h0)) throw new Error('the solver needs equal paid hours on every shift');

  /**
   * Lower bound on the cost of filling shifts order[pos..]; Infinity when they cannot be filled. Relaxation: drop
   * the rest rule between two remaining shifts, the skills and fairness, keep availability, the rest rule against
   * shifts already given, hours caps and contract minimums. What is left is a polymatroid (people → slots, at most
   * one slot per person per shift), so the greedy rule is exact for it: give slots to people in order of pay,
   * contract-minimum slots first, each by an augmenting path.
   */
  const bound = (pos: number): number => {
    const R = order.slice(pos);
    const m = R.length;
    const ok: boolean[][] = [];
    let slots = 0;
    let premium = 0;
    for (let p = 0; p < n; p++) ok.push(R.map((si) => canTake(p, si)));
    for (let k = 0; k < m; k++) {
      const s = shifts[R[k]!]!;
      slots += s.need;
      premium += s.need * s.premium * h0;
      let have = 0;
      for (let p = 0; p < n; p++) if (ok[p]![k]) have++;
      if (have < s.need) return Infinity;
      for (const [sk, need] of Object.entries(s.skills)) {
        let c = 0;
        for (let p = 0; p < n; p++) if (ok[p]![k] && w.staff[p]!.skills.includes(sk)) c++;
        if (c < need) return Infinity;
      }
    }
    const units: Array<[number, number, number]> = []; // [forced 0/1, rate, person]
    for (let p = 0; p < n; p++) {
      // The most remaining shifts p could work with the rest rule between them: interval scheduling on
      // [start, end + rest), earliest end first, is exact.
      const restMin = (w.staff[p]!.under18 ? w.youngRest : w.rest) * 60;
      const mine = R.filter((_, k) => ok[p]![k]).map((si) => shifts[si]!).sort((a, b) => a.end - b.end);
      let avail = 0;
      let freeFrom = -Infinity;
      for (const x of mine) {
        if (x.start >= freeFrom) {
          avail++;
          freeFrom = x.end + restMin;
        }
      }
      const cap = Math.min(avail, Math.floor((maxH[p]! - hours[p]! + 1e-9) / h0));
      const f = Math.max(0, Math.ceil((w.staff[p]!.min - hours[p]! - 1e-9) / h0));
      if (f > cap) return Infinity;
      for (let i = 0; i < cap; i++) units.push([i < f ? 0 : 1, w.staff[p]!.rate, p]);
    }
    units.sort((a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2]);
    const skillsHere = [...new Set(R.flatMap((si) => Object.keys(shifts[si]!.skills)))];
    let lb = 0;
    for (const sk of [null, ...skillsHere]) {
      const v = greedy(R, ok, units, slots, sk);
      if (!Number.isFinite(v)) return Infinity;
      if (v > lb) lb = v;
    }
    return Math.round(lb * h0) + Math.round(premium);
  };

  /**
   * The greedy rule on one relaxation: every remaining slot filled, at most one slot per person per shift, and (when
   * `sk` is given) each shift's slots for that skill only filled by people with it. Returns the least sum of rates,
   * or Infinity. Each shift has two pools (skill slots, other slots); an augmenting path may move someone between
   * shifts or between the two pools of the same shift.
   */
  const greedy = (R: number[], ok: boolean[][], units: Array<[number, number, number]>, slots: number, sk: string | null): number => {
    const m = R.length;
    const cap: number[][] = R.map((si) => {
      const s = shifts[si]!;
      const c1 = sk ? Math.min(s.need, s.skills[sk] ?? 0) : 0;
      return [s.need - c1, c1];
    });
    const holds = w.staff.map((p) => (sk ? p.skills.includes(sk) : false));
    const pos: number[][] = Array.from({ length: n }, () => new Array<number>(m).fill(-1)); // pool or -1
    const load: number[][] = R.map(() => [0, 0]);
    // Flow network: person node pn(x) → person-shift node ps(x, j) (cap 1) → pool node (j, q) → sink (cap).
    const PS = n;
    const POOL = n + n * m;
    const total = POOL + 2 * m;
    const augment = (src: number): boolean => {
      const parent = new Int32Array(total).fill(-2);
      parent[src] = -1;
      const queue: number[] = [src];
      let head = 0;
      let end = -1;
      while (head < queue.length && end < 0) {
        const u = queue[head++]!;
        if (u < PS) {
          const x = u;
          for (let j = 0; j < m; j++) {
            if (!ok[x]![j] || pos[x]![j] !== -1) continue;
            const v = PS + x * m + j;
            if (parent[v] !== -2) continue;
            parent[v] = u;
            queue.push(v);
          }
        } else if (u < POOL) {
          const x = Math.floor((u - PS) / m);
          const j = (u - PS) % m;
          for (let q = 0; q < 2; q++) {
            if (cap[j]![q] === 0 || (q === 1 && !holds[x]) || pos[x]![j] === q) continue;
            const v = POOL + j * 2 + q;
            if (parent[v] !== -2) continue;
            parent[v] = u;
            if (load[j]![q]! < cap[j]![q]!) {
              end = v;
              break;
            }
            queue.push(v);
          }
          // Back along x's own edge to the person node (x is on shift j and could move to another shift).
          if (end < 0 && pos[x]![j] !== -1 && parent[x] === -2) {
            parent[x] = u;
            queue.push(x);
          }
        } else {
          const j = Math.floor((u - POOL) / 2);
          const q = (u - POOL) % 2;
          for (let y = 0; y < n; y++) {
            if (pos[y]![j] !== q) continue;
            const v = PS + y * m + j;
            if (parent[v] !== -2) continue;
            parent[v] = u;
            queue.push(v);
          }
        }
      }
      if (end < 0) return false;
      // Flip every edge on the path, in order: a pool → person edge takes that person out of the pool, a person →
      // pool edge puts them in (so every pool keeps its count except the free one at the end, which gains one).
      const path: number[] = [];
      for (let v = end; v !== -1; v = parent[v]!) path.push(v);
      path.reverse();
      for (let i = 0; i + 1 < path.length; i++) {
        const u = path[i]!;
        const v = path[i + 1]!;
        if (u >= PS && u < POOL && v >= POOL) {
          const x = Math.floor((u - PS) / m);
          const j = (u - PS) % m;
          const q = (v - POOL) % 2;
          pos[x]![j] = q;
          load[j]![q]!++;
        } else if (u >= POOL && v >= PS && v < POOL) {
          const y = Math.floor((v - PS) / m);
          const j = (v - PS) % m;
          load[j]![pos[y]![j]!]!--;
          pos[y]![j] = -1;
        }
      }
      return true;
    };
    let matched = 0;
    let rates = 0;
    for (const [forced, rate, p] of units) {
      if (matched === slots) break;
      if (augment(p)) {
        matched++;
        rates += rate;
      } else if (forced === 0) return Infinity;
    }
    return matched < slots ? Infinity : rates;
  };

  /**
   * Weekend fairness, as far as it can be judged from order[pos..] on: someone's weekend count may not end up more
   * than one above anyone else's. Each fair person's final count is at most their current count plus the remaining
   * weekend shifts they are eligible for, so a gap that can no longer close prunes the branch. At the end (or once
   * the weekend is filled) this is the exact rule.
   */
  const fairOk = (pos: number): boolean => {
    let hiNow = -Infinity;
    let loMax = Infinity;
    for (let p = 0; p < n; p++) {
      if (!w.staff[p]!.fair) continue;
      let more = 0;
      for (let k = pos; k < order.length; k++) if (shifts[order[k]!]!.day >= 5 && eligible[p]![order[k]!]) more++;
      hiNow = Math.max(hiNow, weekend[p]!);
      loMax = Math.min(loMax, weekend[p]! + more);
    }
    return !(hiNow > loMax + 1);
  };

  const rec = (pos: number, spent: number): void => {
    if (++nodes > nodeLimit) throw new Error(BUDGET);
    if (pos === order.length) {
      for (let p = 0; p < n; p++) if (hours[p]! < w.staff[p]!.min) return;
      if (!fairOk(pos)) return;
      if (spent < best) {
        best = spent;
        bestPick = pick.map((x) => x.slice());
      }
      return;
    }
    if (pos <= lastWeekendPos + 1 && !fairOk(pos)) return;
    const lb = bound(pos);
    if (spent + lb >= best) return;
    const si = order[pos]!;
    const s = shifts[si]!;
    const cands: number[] = [];
    for (let p = 0; p < n; p++) if (canTake(p, si)) cands.push(p);
    cands.sort((a, b) => cost[a]![si]! - cost[b]![si]! || a - b);
    const chosen: number[] = [];
    const choose = (start: number, acc: number): void => {
      if (chosen.length === s.need) {
        for (const [sk, need] of Object.entries(s.skills)) if (chosen.filter((p) => w.staff[p]!.skills.includes(sk)).length < need) return;
        if (spent + acc >= best) return;
        for (const p of chosen) {
          hours[p]! += s.paid;
          assigned[p]!.push(si);
          if (s.day >= 5) weekend[p]!++;
        }
        pick[si] = chosen.slice();
        rec(pos + 1, spent + acc);
        for (const p of chosen) {
          hours[p]! -= s.paid;
          assigned[p]!.pop();
          if (s.day >= 5) weekend[p]!--;
        }
        pick[si] = [];
        return;
      }
      for (let i = start; i <= cands.length - (s.need - chosen.length); i++) {
        chosen.push(cands[i]!);
        choose(i + 1, acc + cost[cands[i]!]![si]!);
        chosen.pop();
      }
    };
    choose(0, 0);
  };
  try {
    rec(0, 0);
  } catch (e) {
    // Careless answers only (incumbentOk): the best rota found within the budget. A key is always proven.
    if (!incumbentOk || (e as Error).message !== BUDGET) throw e;
  }
  if (!bestPick) throw new Error('no legal rota');
  const byShift = new Map<string, string[]>();
  shifts.forEach((s, i) => byShift.set(s.id, (bestPick as number[][])[i]!.map((p) => w.staff[p]!.id)));
  return { byShift, optimum: best, nodes };
}

/** Every legal rota, no bounds (small worlds only): the cheapest cost, checked with the app's own checker. */
export function exhaustiveRota(w: RotaWorld, limit = 2_000_000): number | null {
  const shifts = w.shifts;
  const n = w.staff.length;
  const pick: number[][] = shifts.map(() => []);
  let best = Infinity;
  let leaves = 0;
  const combos = (k: number, from: number[]): number[][] => {
    if (k === 0) return [[]];
    const out: number[][] = [];
    from.forEach((x, i) => {
      for (const rest of combos(k - 1, from.slice(i + 1))) out.push([x, ...rest]);
    });
    return out;
  };
  const all = Array.from({ length: n }, (_, i) => i);
  // Per shift, only the groups that are available and have the skills (rules local to one shift); every other rule
  // is left to the app's checker at the end.
  const options = shifts.map((s) =>
    combos(s.need, all).filter((c) => c.every((p) => !w.staff[p]!.off.includes(s.id)) && Object.entries(s.skills).every(([sk, k]) => c.filter((p) => w.staff[p]!.skills.includes(sk)).length >= k)),
  );
  const rec = (si: number): boolean => {
    if (si === shifts.length) {
      if (++leaves > limit) return false;
      const rota: Record<string, string[]> = {};
      shifts.forEach((s, i) => (rota[s.id] = pick[i]!.map((p) => w.staff[p]!.id)));
      const r = checkRota(w, { rota });
      if (r.feasible && r.value! < best) best = r.value!;
      return true;
    }
    for (const c of options[si]!) {
      pick[si] = c;
      if (!rec(si + 1)) return false;
    }
    return true;
  };
  if (!rec(0)) return null;
  return Number.isFinite(best) ? best : null;
}

// ─────────────────────────────── settings ───────────────────────────────

type Setting = 'pub' | 'care' | 'warehouse';

interface ShiftType {
  code: string;
  name: string;
  start: string;
  end: string;
  paid: number;
  premium: number;
}

const SHIFT_TYPES: Record<Setting, ShiftType[]> = {
  pub: [
    { code: 'O', name: 'Open', start: '10:00', end: '17:30', paid: 7, premium: 0 },
    { code: 'C', name: 'Close', start: '17:30', end: '01:00', paid: 7, premium: 0 },
  ],
  care: [
    { code: 'D', name: 'Day', start: '08:00', end: '20:00', paid: 12, premium: 0 },
    { code: 'N', name: 'Night', start: '20:00', end: '08:00', paid: 12, premium: 120 },
  ],
  warehouse: [
    { code: 'E', name: 'Early', start: '06:00', end: '14:00', paid: 8, premium: 0 },
    { code: 'L', name: 'Late', start: '14:00', end: '22:00', paid: 8, premium: 0 },
    { code: 'N', name: 'Night', start: '22:00', end: '06:00', paid: 8, premium: 150 },
  ],
};

const SKILL_NAMES: Record<Setting, Record<string, string>> = {
  pub: { KH: 'keyholder', PLH: 'personal licence holder' },
  care: { MED: 'medication-trained', FA: 'first aider' },
  warehouse: { FLT: 'forklift (FLT) licence', FA: 'first aider' },
};

const toMin = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));

interface StaffSpec {
  name: string;
  role: string;
  rate: number;
  min: number;
  max: number;
  skills: string[];
  under18?: boolean;
  /** Free-text availability, and the shift codes it rules out (applied by the generator). */
  off?: Array<{ text: string; shifts: string[] }>;
  /** Not in the weekend fairness rule (bank staff). */
  bank?: boolean;
  /** Last week's last shift: [text, end minute relative to Monday 00:00]. */
  last?: [string, number];
}

interface Spec {
  id: string;
  difficulty: Difficulty;
  setting: Setting;
  place: string;
  manager: string;
  weekOf: string;
  /** need[code][day] and skills per code (same every day unless overridden). */
  need: Record<string, number[]>;
  skills: Record<string, Record<string, number>>;
  staff: StaffSpec[];
  /** A late message: who/what changes, applied to the world (the careless answer misses it). */
  late?: { text: string; apply: (w: RotaWorld) => void };
  /** Previous-week finishing times are given (hard). */
  showLast?: boolean;
}

export interface RotaCase {
  id: string;
  difficulty: Difficulty;
  prompt: string;
  notes: string;
  world: RotaWorld;
  optimum: number;
  reference: Record<string, unknown>;
  careless: Careless[];
  nodes: number;
}

export function buildWorld(spec: Spec, withLate = true): RotaWorld {
  const types = SHIFT_TYPES[spec.setting];
  const shifts: RotaShift[] = [];
  for (let d = 0; d < 7; d++) {
    for (const t of types) {
      const need = spec.need[t.code]![d]!;
      if (!need) continue;
      const start = d * 1440 + toMin(t.start);
      let end = d * 1440 + toMin(t.end);
      if (end <= start) end += 1440;
      shifts.push({ id: `${DAYS[d]}-${t.code}`, day: d, name: t.name, start, end, paid: t.paid, need, skills: { ...(spec.skills[t.code] ?? {}) }, premium: t.premium });
    }
  }
  const staff: RotaStaff[] = spec.staff.map((p, i) => ({
    id: `S${String(i + 1).padStart(2, '0')}`,
    name: p.name,
    rate: p.rate,
    min: p.min,
    max: p.max,
    skills: p.skills.slice(),
    under18: !!p.under18,
    off: [...new Set((p.off ?? []).flatMap((o) => o.shifts))].filter((id) => shifts.some((s) => s.id === id)),
    fair: !p.bank,
    ...(p.last ? { lastEnd: p.last[1] } : {}),
  }));
  const w: RotaWorld = { shifts, staff, rest: 11, youngRest: 12, cap: 48, youngCap: 40, youngDaily: 8, skillNames: SKILL_NAMES[spec.setting] };
  // Fairness counts only people available for at least one weekend shift.
  for (const p of w.staff) if (p.fair && !w.shifts.some((s) => s.day >= 5 && !p.off.includes(s.id))) p.fair = false;
  if (withLate && spec.late) spec.late.apply(w);
  return w;
}

/** Shift ids for a day list and codes: days('Sat','Sun')('O','C'). */
function ids(days: string[], codes: string[]): string[] {
  return days.flatMap((d) => codes.map((c) => `${d}-${c}`));
}

const ALL_DAYS = DAYS.slice();

function rotaAnswer(w: RotaWorld, byShift: Map<string, string[]>, total?: number): Record<string, unknown> {
  const rota: Record<string, string[]> = {};
  for (const s of w.shifts) rota[s.id] = (byShift.get(s.id) ?? []).map((pid) => w.staff.find((p) => p.id === pid)!.name.split(' ')[0]!);
  let cost = 0;
  for (const s of w.shifts) for (const pid of byShift.get(s.id) ?? []) cost += assignmentCost(w.staff.find((p) => p.id === pid)!, s);
  return { rota, total: pounds(total ?? cost) };
}

function hoursText(h: number): string {
  return Number.isInteger(h) ? `${h}` : h.toFixed(1);
}

function writePrompt(spec: Spec, w: RotaWorld): string {
  const types = SHIFT_TYPES[spec.setting];
  const L: string[] = [];
  L.push(`From: ${spec.manager}`);
  L.push(`Subject: Rota for the week of ${spec.weekOf}`);
  L.push('');
  L.push(`Hi, can you build next week’s rota for ${spec.place}? It has to keep every rule below, and I want it as cheap as possible on wages.`);
  L.push('');
  L.push('SHIFTS');
  for (const t of types) L.push(`- ${t.name} (${t.code}): ${t.start}–${t.end}${toMin(t.end) <= toMin(t.start) ? ' (finishes the next morning)' : ''}, ${hoursText(t.paid)} paid hours${t.paid * 60 < toMin(t.end) - toMin(t.start) || (toMin(t.end) <= toMin(t.start) && t.paid * 60 < toMin(t.end) + 1440 - toMin(t.start)) ? ' (the rest is an unpaid break)' : ''}${t.premium ? `, plus a ${money(t.premium)} an hour premium for whoever works it` : ''}.`);
  L.push('');
  L.push('COVER (exactly this many people on each shift: no more, no fewer)');
  L.push(`Shift | ${DAYS.join(' | ')}`);
  for (const t of types) L.push(`${t.name} | ${DAYS.map((_, d) => String(spec.need[t.code]![d]!)).join(' | ')}`);
  for (const t of types) {
    const sk = spec.skills[t.code];
    if (sk && Object.keys(sk).length) L.push(`Every ${t.name} shift needs at least ${Object.entries(sk).map(([k, n]) => `${n} ${SKILL_NAMES[spec.setting][k]}${n > 1 ? 's' : ''}`).join(' and ')} on it.`);
  }
  L.push('');
  L.push('STAFF (pay is per paid hour; contracted hours are the least and most each person must be given this week)');
  L.push('Name | Role | Pay | Contracted hours | Skills | Availability');
  for (const p of spec.staff) {
    const sk = p.skills.map((k) => SKILL_NAMES[spec.setting][k]).join(', ') || '-';
    const av = (p.off ?? []).map((o) => o.text).join('; ') || 'any shift';
    L.push(`${p.name} | ${p.role}${p.under18 ? ' (aged 17)' : ''}${p.bank ? ' (bank staff)' : ''} | ${money(p.rate)} | ${p.min}–${p.max} | ${sk} | ${av}`);
  }
  if (spec.showLast) {
    const last = spec.staff.filter((p) => p.last);
    if (last.length) {
      L.push('');
      L.push(`Last week (for the rest rule): ${last.map((p) => `${p.name.split(' ')[0]} ${p.last![0]}`).join('; ')}. Everyone else finished early enough to start any shift on Monday.`);
    }
  }
  L.push('');
  L.push('RULES FOR THIS ROTA');
  L.push(`1. At least ${w.rest} hours’ rest between the end of one shift and the start of the next (${w.youngRest} hours for anyone under 18). This includes the last shift someone worked last week.`);
  L.push(`2. Nobody works more than ${w.cap} paid hours in the week (use this as a strict weekly cap for this exercise; no opt-outs).`);
  L.push(`3. Under-18s: no work at all between 22:00 and 06:00, no more than ${w.youngDaily} paid hours in a day and ${w.youngCap} in the week.`);
  L.push('4. Everyone gets between the least and the most of their contracted hours.');
  L.push('5. Weekend fairness: a weekend shift is any shift that starts on a Saturday or Sunday. Among the staff who are available for at least one weekend shift (bank staff excepted), the number of weekend shifts each person works must be within one of each other (the most minus the least is 0 or 1).');
  L.push('6. Nobody works a shift they are not available for, and nobody works two shifts at once.');
  L.push('7. Wage cost = paid hours × (hourly pay + any shift premium), added up over every shift worked. Make it as low as possible.');
  if (spec.late) {
    L.push('');
    L.push(spec.late.text);
  }
  L.push('');
  L.push('Answer with the rota as the people on every shift (shift codes are the day and the shift letter, e.g. "Mon-' + types[0]!.code + '"; first names are fine) and the total wage cost in pounds, in this JSON format:');
  L.push('```json');
  L.push(`{"rota": {"Mon-${types[0]!.code}": ["<name>"]}, "total": 0.00}`);
  L.push('```');
  L.push(ONE_ANSWER);
  return L.join('\n');
}

function buildCase(spec: Spec): RotaCase {
  const world = buildWorld(spec);
  const sol = solveRota(world);
  const reference = rotaAnswer(world, sol.byShift);
  const careless: Careless[] = [];
  const tryVariant = (label: string, cw: RotaWorld) => {
    try {
      // A careless planner's rota: the best the search finds within a small budget under the misread rules.
      const s = solveRota(cw, 20_000, true);
      careless.push({ label, answer: rotaAnswer(world, s.byShift) });
    } catch {
      /* the misreading has no legal rota of its own: not a scripted answer */
    }
  };
  if (spec.late) tryVariant('late message missed', buildWorld(spec, false));
  {
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    cw.rest = 0;
    cw.youngRest = 0;
    tryVariant('rest rule ignored (back-to-back close then open)', cw);
  }
  {
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    for (const p of cw.staff) delete p.lastEnd;
    if (world.staff.some((p) => p.lastEnd !== undefined)) tryVariant('last week’s finishing times ignored', cw);
  }
  {
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    for (const p of cw.staff) p.fair = false;
    tryVariant('weekend fairness ignored', cw);
  }
  if (world.staff.some((p) => p.under18)) {
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    for (const p of cw.staff) p.under18 = false;
    tryVariant('under-18 rules ignored', cw);
  }
  {
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    for (const s of cw.shifts) s.skills = {};
    tryVariant('skill cover ignored', cw);
  }
  {
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    for (const p of cw.staff) p.min = 0;
    tryVariant('contract minimum hours ignored', cw);
  }
  {
    // A legal rota built without looking at pay: everyone costed the same, so the search just finds any legal rota.
    const cw = JSON.parse(JSON.stringify(world)) as RotaWorld;
    for (const p of cw.staff) p.rate = 1000;
    for (const s of cw.shifts) s.premium = 0;
    tryVariant('legal, but built without looking at pay', cw);
  }
  if (world.shifts.some((s) => s.premium)) {
    // The right rota, but the total quoted without the night premium.
    const noPremium = world.shifts.reduce((a, s) => a + s.premium * s.paid * s.need, 0);
    careless.push({ label: 'night premium left out of the quoted total', answer: rotaAnswer(world, sol.byShift, sol.optimum - noPremium) });
  }
  const prompt = writePrompt(spec, world);
  const notes = `[${spec.difficulty}] ${spec.setting}, ${world.staff.length} staff, ${world.shifts.length} shifts, ${world.shifts.reduce((a, s) => a + s.need, 0)} slots. Cheapest legal rota ${money(sol.optimum)} (exact branch-and-bound, ${sol.nodes} nodes).`;
  return { id: spec.id, difficulty: spec.difficulty, prompt, notes, world, optimum: sol.optimum, reference, careless, nodes: sol.nodes };
}

// ─────────────────────────────── the cases ───────────────────────────────

const NLW = 1271; // National Living Wage from April 2026 (21+)
const R1820 = 1085;
const R1617 = 800;

const off = (text: string, shifts: string[]) => ({ text, shifts });

export const ROTA_SPECS: Spec[] = [
  {
    id: 'r01', difficulty: 'easy', setting: 'pub', place: 'The Fox & Fiddle in Shrewsbury', manager: 'Callum Reid', weekOf: 'Mon 2 Nov 2026',
    need: { O: [1, 1, 1, 1, 1, 2, 2], C: [2, 2, 2, 2, 2, 3, 2] },
    skills: { O: { KH: 1 }, C: { KH: 1, PLH: 1 } },
    staff: [
      { name: 'Callum Reid', role: 'Manager', rate: 1520, min: 26, max: 48, skills: ['KH', 'PLH'] },
      { name: 'Nadia Hussain', role: 'Deputy manager', rate: 1390, min: 20, max: 40, skills: ['KH', 'PLH'] },
      { name: 'Tomasz Wójcik', role: 'Bar staff', rate: NLW, min: 14, max: 40, skills: ['KH'], off: [off('not Tuesdays', ids(['Tue'], ['O', 'C']))] },
      { name: 'Ines Carvalho', role: 'Bar staff', rate: NLW, min: 0, max: 28, skills: [], off: [off('evenings only', ids(ALL_DAYS, ['O']))] },
      { name: 'Rhys Morgan', role: 'Bar staff', rate: R1820, min: 0, max: 28, skills: [], off: [off('on holiday Mon–Wed', ids(['Mon', 'Tue', 'Wed'], ['O', 'C']))] },
      { name: 'Hamza Ali', role: 'Bar staff', rate: NLW, min: 0, max: 21, skills: [] },
    ],
  },
  {
    id: 'r02', difficulty: 'easy', setting: 'care', place: 'Willow Bank care home in Ipswich', manager: 'Joanna Mensah', weekOf: 'Mon 9 Nov 2026',
    need: { D: [2, 2, 2, 2, 2, 2, 2], N: [1, 1, 1, 1, 1, 1, 1] },
    skills: { D: { MED: 1 }, N: { MED: 1 } },
    staff: [
      { name: 'Joanna Mensah', role: 'Senior carer', rate: 1480, min: 24, max: 48, skills: ['MED', 'FA'] },
      { name: 'Bilal Chaudhry', role: 'Senior carer', rate: 1450, min: 24, max: 48, skills: ['MED'], off: [off('no nights', ids(ALL_DAYS, ['N']))] },
      { name: 'Grace Achterberg', role: 'Carer', rate: NLW, min: 36, max: 48, skills: ['FA'] },
      { name: 'Kofi Asante', role: 'Carer', rate: NLW, min: 12, max: 48, skills: [], off: [off('annual leave Thu–Sun', ids(['Thu', 'Fri', 'Sat', 'Sun'], ['D', 'N']))] },
      { name: 'Lucia Romero', role: 'Carer', rate: 1300, min: 0, max: 48, skills: ['MED'], off: [off('nights only', ids(ALL_DAYS, ['D']))] },
      { name: 'Sunil Varma', role: 'Bank carer', rate: 1650, min: 0, max: 48, skills: ['MED'], bank: true },
    ],
  },
  {
    id: 'r03', difficulty: 'easy', setting: 'pub', place: 'The Crown at Hebden Bridge', manager: 'Aoife Byrne', weekOf: 'Mon 16 Nov 2026',
    need: { O: [1, 1, 1, 1, 1, 2, 2], C: [2, 2, 2, 2, 3, 3, 2] },
    skills: { O: { KH: 1 }, C: { KH: 1, PLH: 1 } },
    staff: [
      { name: 'Aoife Byrne', role: 'Manager', rate: 1550, min: 30, max: 48, skills: ['KH', 'PLH'] },
      { name: 'Kai Thompson', role: 'Supervisor', rate: 1340, min: 20, max: 40, skills: ['KH', 'PLH'] },
      { name: 'Zara Malik', role: 'Bar staff', rate: NLW, min: 13, max: 39, skills: ['KH'] },
      { name: 'Oskar Lindqvist', role: 'Bar staff', rate: NLW, min: 0, max: 26, skills: [], off: [off('can’t do Sundays', ids(['Sun'], ['O', 'C']))] },
      { name: 'Femi Bello', role: 'Bar staff', rate: R1820, min: 0, max: 28, skills: [] },
      { name: 'Yasmin Haddad', role: 'Glass collector / waiter', rate: R1617, min: 0, max: 28, skills: [], under18: true, off: [off('school days: not before 17:00 Mon–Fri', ids(['Mon', 'Tue', 'Wed', 'Thu', 'Fri'], ['O']))] },
    ],
  },
  {
    id: 'r04', difficulty: 'medium', setting: 'care', place: 'Willow Bank care home in Ipswich', manager: 'Joanna Mensah', weekOf: 'Mon 23 Nov 2026',
    need: { D: [3, 3, 3, 3, 3, 2, 2], N: [2, 2, 2, 2, 2, 2, 2] },
    skills: { D: { MED: 1, FA: 1 }, N: { MED: 1 } },
    staff: [
      { name: 'Joanna Mensah', role: 'Senior carer', rate: 1480, min: 24, max: 48, skills: ['MED', 'FA'] },
      { name: 'Bilal Chaudhry', role: 'Senior carer', rate: 1450, min: 24, max: 48, skills: ['MED'], off: [off('no nights', ids(ALL_DAYS, ['N']))] },
      { name: 'Grace Achterberg', role: 'Carer', rate: NLW, min: 36, max: 48, skills: ['FA'] },
      { name: 'Kofi Asante', role: 'Carer', rate: NLW, min: 24, max: 48, skills: [], off: [off('not Mon or Tue', ids(['Mon', 'Tue'], ['D', 'N']))] },
      { name: 'Lucia Romero', role: 'Carer', rate: 1300, min: 24, max: 48, skills: ['MED'], off: [off('nights only', ids(ALL_DAYS, ['D']))] },
      { name: 'Hana Kobayashi', role: 'Carer', rate: NLW, min: 0, max: 36, skills: ['FA'], off: [off('days only', ids(ALL_DAYS, ['N']))] },
      { name: 'Emeka Obi', role: 'Carer', rate: 1300, min: 12, max: 48, skills: ['MED'] },
      { name: 'Uche Eze', role: 'Carer', rate: NLW, min: 0, max: 36, skills: [] },
      { name: 'Sunil Varma', role: 'Bank carer', rate: 1650, min: 0, max: 48, skills: ['MED', 'FA'], bank: true },
      { name: 'Dalia Petrova', role: 'Agency nurse', rate: 2400, min: 0, max: 48, skills: ['MED', 'FA'], bank: true },
    ],
    late: {
      text: 'Update (Friday 15:10): Kofi has just told me he is at a family wedding on Saturday 28th, so he cannot work Saturday (day or night).',
      apply: (w) => {
        const k = w.staff.find((p) => p.name.startsWith('Kofi'))!;
        k.off.push('Sat-D', 'Sat-N');
      },
    },
  },
  {
    id: 'r05', difficulty: 'medium', setting: 'pub', place: 'The Fox & Fiddle in Shrewsbury', manager: 'Callum Reid', weekOf: 'Mon 30 Nov 2026',
    need: { O: [1, 1, 1, 2, 2, 3, 3], C: [2, 2, 2, 3, 4, 4, 2] },
    skills: { O: { KH: 1 }, C: { KH: 1, PLH: 1 } },
    staff: [
      { name: 'Callum Reid', role: 'Manager', rate: 1520, min: 26, max: 48, skills: ['KH', 'PLH'], off: [off('off Mon and Tue', ids(['Mon', 'Tue'], ['O', 'C']))] },
      { name: 'Nadia Hussain', role: 'Deputy manager', rate: 1390, min: 26, max: 45, skills: ['KH', 'PLH'] },
      { name: 'Tomasz Wójcik', role: 'Supervisor', rate: 1310, min: 20, max: 40, skills: ['KH', 'PLH'], off: [off('not Tuesdays', ids(['Tue'], ['O', 'C']))] },
      { name: 'Ines Carvalho', role: 'Bar staff', rate: NLW, min: 14, max: 30, skills: [], off: [off('evenings only', ids(ALL_DAYS, ['O']))] },
      { name: 'Rhys Morgan', role: 'Bar staff', rate: R1820, min: 0, max: 30, skills: ['KH'] },
      { name: 'Wen Li', role: 'Bar staff', rate: NLW, min: 12, max: 26, skills: [], off: [off('days only (lectures in the evening)', ids(ALL_DAYS, ['C']))] },
      { name: 'Malik Johnson', role: 'Bar staff', rate: NLW, min: 0, max: 21, skills: [] },
      { name: 'Talia Rosen', role: 'Waiter', rate: R1617, min: 0, max: 28, skills: [], under18: true, off: [off('school days: not before 17:00 Mon–Fri', ids(['Mon', 'Tue', 'Wed', 'Thu', 'Fri'], ['O']))] },
    ],
  },
  {
    id: 'r06', difficulty: 'medium', setting: 'warehouse', place: 'the goods-in team at Brightwell Logistics, Daventry', manager: 'Sana Iqbal', weekOf: 'Mon 7 Dec 2026',
    need: { E: [2, 2, 2, 2, 2, 1, 1], L: [2, 2, 2, 2, 2, 1, 0], N: [1, 1, 1, 1, 0, 0, 0] },
    skills: { E: { FLT: 1, FA: 1 }, L: { FLT: 1 }, N: { FLT: 1 } },
    staff: [
      { name: 'Sana Iqbal', role: 'Shift lead', rate: 1580, min: 32, max: 48, skills: ['FLT', 'FA'] },
      { name: 'Gethin Evans', role: 'FLT driver', rate: 1420, min: 32, max: 48, skills: ['FLT', 'FA'] },
      { name: 'Amara Nwosu', role: 'FLT driver', rate: 1390, min: 16, max: 48, skills: ['FLT'], off: [off('no nights', ids(ALL_DAYS, ['N']))] },
      { name: 'Mateusz Kowal', role: 'Warehouse operative', rate: NLW, min: 32, max: 48, skills: ['FA'] },
      { name: 'Priya Desai', role: 'Warehouse operative', rate: NLW, min: 24, max: 40, skills: [], off: [off('earlies only', ids(ALL_DAYS, ['L', 'N']))] },
      { name: 'Ibrahim Saleh', role: 'Warehouse operative', rate: R1820, min: 0, max: 40, skills: ['FLT'] },
      { name: 'Chloe Barker', role: 'Apprentice', rate: R1617, min: 16, max: 40, skills: [], under18: true },
    ],
  },
  {
    id: 'r07', difficulty: 'hard', setting: 'warehouse', place: 'the goods-in team at Brightwell Logistics, Daventry', manager: 'Sana Iqbal', weekOf: 'Mon 14 Dec 2026', showLast: true,
    need: { E: [3, 3, 3, 3, 3, 2, 1], L: [2, 2, 2, 2, 3, 1, 1], N: [1, 1, 2, 2, 1, 0, 0] },
    skills: { E: { FLT: 1, FA: 1 }, L: { FLT: 1, FA: 1 }, N: { FLT: 1 } },
    staff: [
      { name: 'Sana Iqbal', role: 'Shift lead', rate: 1580, min: 32, max: 48, skills: ['FLT', 'FA'], last: ['finished Sun Late at 22:00', -120] },
      { name: 'Gethin Evans', role: 'FLT driver', rate: 1420, min: 32, max: 48, skills: ['FLT', 'FA'], last: ['finished Sun Night at 06:00 this Monday', 360] },
      { name: 'Amara Nwosu', role: 'FLT driver', rate: 1390, min: 24, max: 48, skills: ['FLT'], off: [off('no nights', ids(ALL_DAYS, ['N']))] },
      { name: 'Bongani Dlamini', role: 'FLT driver', rate: 1390, min: 0, max: 40, skills: ['FLT'], off: [off('on leave Thu–Sun', ids(['Thu', 'Fri', 'Sat', 'Sun'], ['E', 'L', 'N']))] },
      { name: 'Mateusz Kowal', role: 'Warehouse operative', rate: NLW, min: 32, max: 48, skills: ['FA'], last: ['finished Sun Late at 22:00', -120] },
      { name: 'Priya Desai', role: 'Warehouse operative', rate: NLW, min: 24, max: 40, skills: [], off: [off('earlies only', ids(ALL_DAYS, ['L', 'N']))] },
      { name: 'Ibrahim Saleh', role: 'Warehouse operative', rate: R1820, min: 0, max: 40, skills: ['FA'] },
      { name: 'Eilidh Fraser', role: 'Warehouse operative', rate: NLW, min: 16, max: 32, skills: [], off: [off('not weekends', ids(['Sat', 'Sun'], ['E', 'L', 'N']))] },
      { name: 'Chloe Barker', role: 'Apprentice', rate: R1617, min: 8, max: 40, skills: [], under18: true, off: [off('at college Mon–Thu until 15:00 (no Early or Late shifts those days)', ids(['Mon', 'Tue', 'Wed', 'Thu'], ['E', 'L']))], last: ['finished Sun Late at 22:00', -120] },
      { name: 'Omar Farouk', role: 'Agency FLT driver', rate: 1900, min: 0, max: 48, skills: ['FLT', 'FA'], bank: true },
    ],
    late: {
      text: 'Update (Thursday 17:20): Gethin’s first-aid certificate expired last week and he is not booked on the refresher until January, so he does not count as a first aider this week. Everything else stands.',
      apply: (w) => {
        const g = w.staff.find((p) => p.name.startsWith('Gethin'))!;
        g.skills = g.skills.filter((s) => s !== 'FA');
      },
    },
  },
  {
    id: 'r08', difficulty: 'hard', setting: 'pub', place: 'The Crown at Hebden Bridge', manager: 'Aoife Byrne', weekOf: 'Mon 21 Dec 2026', showLast: true,
    need: { O: [1, 2, 2, 2, 2, 3, 2], C: [2, 3, 3, 3, 4, 4, 3] },
    skills: { O: { KH: 1 }, C: { KH: 1, PLH: 1 } },
    staff: [
      { name: 'Aoife Byrne', role: 'Manager', rate: 1550, min: 30, max: 48, skills: ['KH', 'PLH'], last: ['closed on Sunday (finished at 01:00 this Monday)', 60] },
      { name: 'Kai Thompson', role: 'Supervisor', rate: 1340, min: 26, max: 45, skills: ['KH', 'PLH'], off: [off('Christmas Day off: not Fri', ids(['Fri'], ['O', 'C']))] },
      { name: 'Zara Malik', role: 'Bar staff', rate: NLW, min: 20, max: 40, skills: ['KH'], last: ['closed on Sunday (finished at 01:00 this Monday)', 60] },
      { name: 'Oskar Lindqvist', role: 'Bar staff', rate: NLW, min: 13, max: 33, skills: ['PLH'], off: [off('can’t do Sundays', ids(['Sun'], ['O', 'C']))] },
      { name: 'Femi Bello', role: 'Bar staff', rate: R1820, min: 0, max: 33, skills: ['KH'] },
      { name: 'Rosa Quintero', role: 'Bar staff', rate: NLW, min: 12, max: 26, skills: [], off: [off('evenings only', ids(ALL_DAYS, ['O']))] },
      { name: 'Jun Park', role: 'Bar staff', rate: NLW, min: 0, max: 21, skills: [], off: [off('home for Christmas from Thu', ids(['Thu', 'Fri', 'Sat', 'Sun'], ['O', 'C']))] },
      { name: 'Yasmin Haddad', role: 'Glass collector / waiter', rate: R1617, min: 6, max: 20, skills: [], under18: true },
      { name: 'Xavier Dupont', role: 'Relief manager', rate: 1700, min: 0, max: 40, skills: ['KH', 'PLH'], bank: true },
    ],
    late: {
      text: 'Update (Saturday 11:05): the brewery has asked us to host their Christmas Eve quiz, so Thursday’s Close now needs 4 people instead of 3. Everything else stands.',
      apply: (w) => {
        w.shifts.find((s) => s.id === 'Thu-C')!.need = 4;
      },
    },
  },
  {
    id: 'r09', difficulty: 'hard', setting: 'care', place: 'Willow Bank care home in Ipswich', manager: 'Joanna Mensah', weekOf: 'Mon 28 Dec 2026', showLast: true,
    need: { D: [3, 3, 3, 3, 3, 3, 3], N: [2, 2, 2, 2, 2, 2, 2] },
    skills: { D: { MED: 1, FA: 1 }, N: { MED: 1, FA: 1 } },
    staff: [
      { name: 'Joanna Mensah', role: 'Senior carer', rate: 1480, min: 24, max: 48, skills: ['MED', 'FA'], last: ['finished Sun Night at 08:00 this Monday', 480] },
      { name: 'Bilal Chaudhry', role: 'Senior carer', rate: 1450, min: 24, max: 48, skills: ['MED'], off: [off('no nights', ids(ALL_DAYS, ['N']))] },
      { name: 'Grace Achterberg', role: 'Carer', rate: NLW, min: 36, max: 48, skills: ['FA'], last: ['finished Sun Night at 08:00 this Monday', 480] },
      { name: 'Kofi Asante', role: 'Carer', rate: NLW, min: 24, max: 48, skills: [], off: [off('New Year’s Day off: not Fri', ids(['Fri'], ['D', 'N']))] },
      { name: 'Lucia Romero', role: 'Carer', rate: 1300, min: 24, max: 48, skills: ['MED'], off: [off('nights only', ids(ALL_DAYS, ['D']))] },
      { name: 'Hana Kobayashi', role: 'Carer', rate: NLW, min: 0, max: 36, skills: ['FA'], off: [off('days only', ids(ALL_DAYS, ['N']))] },
      { name: 'Emeka Obi', role: 'Carer', rate: 1300, min: 12, max: 48, skills: ['MED', 'FA'] },
      { name: 'Farah Siddiqui', role: 'Carer', rate: NLW, min: 24, max: 48, skills: [], off: [off('not weekends', ids(['Sat', 'Sun'], ['D', 'N']))] },
      { name: 'Uche Eze', role: 'Carer', rate: NLW, min: 0, max: 36, skills: ['FA'] },
      { name: 'Malik Osei', role: 'Senior carer', rate: 1480, min: 12, max: 48, skills: ['MED', 'FA'], off: [off('nights only', ids(ALL_DAYS, ['D']))] },
      { name: 'Sunil Varma', role: 'Bank carer', rate: 1650, min: 0, max: 48, skills: ['MED', 'FA'], bank: true },
      { name: 'Dalia Petrova', role: 'Agency nurse', rate: 2400, min: 0, max: 48, skills: ['MED', 'FA'], bank: true },
    ],
    late: {
      text: 'Update (Wednesday 09:30): Lucia has broken her wrist and is signed off all week. Please leave her off the rota (her contracted hours do not apply this week).',
      apply: (w) => {
        const l = w.staff.find((p) => p.name.startsWith('Lucia'))!;
        l.off = w.shifts.map((s) => s.id);
        l.min = 0;
        l.fair = false;
      },
    },
  },
];

export function buildRotaCases(): RotaCase[] {
  return ROTA_SPECS.map(buildCase);
}

/**
 * Cross-check: random small worlds (two or three days, four or five people, every rule switched on) solved by the
 * branch-and-bound and by plain enumeration of every rota through the app's checker. Returns the mismatches.
 */
export function crosscheckRota(trials = 40, seed = 99): { checked: number; feasible: number; mismatches: string[]; worlds: RotaWorld[] } {
  const rng = new Rng(seed);
  const mismatches: string[] = [];
  const worlds: RotaWorld[] = [];
  let feasible = 0;
  for (let t = 0; t < trials; t++) {
    const setting = rng.pick<Setting>(['pub', 'care', 'warehouse']);
    const types = SHIFT_TYPES[setting];
    const days = types.length === 3 ? 2 : rng.int(2, 3);
    const first = 7 - days;
    const shifts: RotaShift[] = [];
    for (let d = first; d < 7; d++) {
      for (const ty of types) {
        const start = d * 1440 + toMin(ty.start);
        let end = d * 1440 + toMin(ty.end);
        if (end <= start) end += 1440;
        const sk = Object.keys(SKILL_NAMES[setting]);
        shifts.push({ id: `${DAYS[d]}-${ty.code}`, day: d, name: ty.name, start, end, paid: ty.paid, need: rng.next() < 0.4 ? 2 : 1, skills: rng.next() < 0.6 ? { [rng.pick(sk)]: 1 } : {}, premium: ty.premium });
      }
    }
    const nStaff = 4;
    const staff: RotaStaff[] = Array.from({ length: nStaff }, (_, i) => ({
      id: `S${i + 1}`,
      name: `Person${i + 1} X`,
      rate: rng.pick([800, 1085, 1271, 1300, 1450, 1650]),
      min: rng.next() < 0.4 ? types[0]!.paid : 0,
      max: rng.pick([types[0]!.paid * 2, types[0]!.paid * 3, 48]),
      skills: Object.keys(SKILL_NAMES[setting]).filter(() => rng.next() < 0.5),
      under18: rng.next() < 0.15,
      off: shifts.filter(() => rng.next() < 0.1).map((s) => s.id),
      fair: rng.next() < 0.8,
      ...(rng.next() < 0.3 ? { lastEnd: shifts[0]!.start - rng.int(4, 14) * 60 } : {}),
    }));
    const w: RotaWorld = { shifts, staff, rest: 11, youngRest: 12, cap: 48, youngCap: 40, youngDaily: 8, skillNames: SKILL_NAMES[setting] };
    const brute = exhaustiveRota(w);
    let bb: number | null = null;
    try {
      bb = solveRota(w).optimum;
    } catch {
      bb = null;
    }
    if (brute !== null) feasible++;
    if (brute !== bb) {
      mismatches.push(`trial ${t} (${setting}): exhaustive ${brute}, branch-and-bound ${bb}`);
      worlds.push(w);
    }
  }
  return { checked: trials, feasible, mismatches, worlds };
}
