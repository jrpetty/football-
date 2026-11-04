/**
 * Build the Rota (work.shift-rota): checks a week's rota against every rule in the brief: exact cover per shift,
 * the skills each shift needs, who is available, rest between shifts (11 hours, or 12 for under-18s, including the
 * last shift each person worked the week before), the weekly hours cap, under-18 limits (no work from 22:00 to
 * 06:00, at most 8 hours a day and 40 a week), each contract's minimum and maximum hours and the weekend fairness
 * rule. The rota is costed at each person's hourly rate plus any shift premium, on paid hours.
 */
import type { PlanCheck, PlanVerifier, VerifierItem } from './types.ts';
import { field, gbp, normText, readId, readPence } from './common.ts';

export interface RotaShift {
  /** "Mon-E" style code. */
  id: string;
  /** 0 = Monday … 6 = Sunday (the day the shift starts). */
  day: number;
  name: string;
  /** Minutes from 00:00 on the Monday of the rota week. */
  start: number;
  end: number;
  /** Paid hours (the shift length less any unpaid break). */
  paid: number;
  /** Exactly this many people. */
  need: number;
  /** Skill → at least this many people with it. */
  skills: Record<string, number>;
  /** Extra pence per paid hour on this shift (e.g. a night premium). */
  premium: number;
}

export interface RotaStaff {
  id: string;
  name: string;
  /** Pence per paid hour. */
  rate: number;
  /** Contracted hours for the week. */
  min: number;
  max: number;
  skills: string[];
  under18: boolean;
  /** Shift ids this person cannot work (leave, availability, the late message). */
  off: string[];
  /** Counts in the weekend fairness rule. */
  fair: boolean;
  /** When this person's last shift of the previous week ended (minutes before Monday 00:00 are negative). */
  lastEnd?: number;
}

export interface RotaWorld {
  shifts: RotaShift[];
  staff: RotaStaff[];
  /** Rest between shifts in hours (adults; under-18s get `youngRest`). */
  rest: number;
  youngRest: number;
  /** Weekly cap for adults (hours) and for under-18s. */
  cap: number;
  youngCap: number;
  /** Most paid hours in one day for under-18s. */
  youngDaily: number;
  /** Skill codes → display names. */
  skillNames: Record<string, string>;
}

const DAY_NAMES = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

/** Whether a shift touches 22:00–06:00 on any night. */
export function touchesNight(s: RotaShift): boolean {
  for (let d = -1; d <= 7; d++) {
    const from = d * 1440 + 22 * 60;
    const to = d * 1440 + 30 * 60;
    if (s.start < to && s.end > from) return true;
  }
  return false;
}

/** The cost of one person on one shift, in pence. */
export function assignmentCost(p: RotaStaff, s: RotaShift): number {
  return Math.round(s.paid * (p.rate + s.premium));
}

/** Reads "Mon-E", "Monday Early", "mon early shift", "TUES L" as a shift id. */
export function readShift(v: unknown, shifts: readonly RotaShift[]): string | null {
  if (typeof v !== 'string' || /\bor\b|\//i.test(v.replace(/-/g, ' '))) return null;
  const exact = readId(v, shifts.map((s) => s.id));
  if (exact) return exact;
  const tokens = normText(v).split(' ').filter((x) => x && x !== 'shift');
  const dayOf = (tok: string) => DAY_NAMES.findIndex((d) => tok === d || tok === d.slice(0, 3) || (tok.length >= 3 && d.startsWith(tok)) || (tok === 'weds' && d === 'wednesday'));
  const di = tokens.findIndex((tok) => dayOf(tok) >= 0);
  if (di < 0) return null;
  const dayNo = dayOf(tokens[di]!);
  const rest = tokens.filter((_, i) => i !== di);
  const hits = shifts.filter((s) => s.day === dayNo && rest.some((tok) => tok === normText(s.name) || tok === (s.id.split('-')[1] ?? '').toLowerCase()));
  return hits.length === 1 ? hits[0]!.id : null;
}

/** Reads the rota: {"Mon-E": ["Amara", …]} (by shift) or {"Amara": ["Mon-E", …]} (by person). */
export function readRota(w: RotaWorld, raw: unknown): { byShift: Map<string, string[]>; bad?: string } {
  const byShift = new Map<string, string[]>(w.shifts.map((s) => [s.id, []]));
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { byShift, bad: 'the rota must be an object of shifts (or of people)' };
  const ids = w.staff.map((p) => p.id);
  const names = new Map(w.staff.map((p) => [p.id, p.name]));
  const person = (v: unknown) => readId(v, ids, names) ?? (typeof v === 'string' ? w.staff.find((p) => normText(p.name.split(' ')[0]!) === normText(v))?.id ?? null : null);
  const entries = Object.entries(raw as Record<string, unknown>);
  const asList = (v: unknown): unknown[] => (Array.isArray(v) ? v : typeof v === 'string' ? (v.trim() ? v.split(/\s*(?:,|;|&|\band\b)\s*/i) : []) : v === null || v === undefined ? [] : [v]);
  const shiftKeys = entries.filter(([k]) => readShift(k, w.shifts)).length;
  const personKeys = entries.filter(([k]) => person(k)).length;
  if (shiftKeys >= personKeys) {
    for (const [k, v] of entries) {
      const sid = readShift(k, w.shifts);
      // A shift with no cover (e.g. "Sat-N": []) may be listed empty; anyone put on a shift that does not exist is wrong.
      if (!sid && asList(v).length === 0) continue;
      if (!sid) return { byShift, bad: `"${k.slice(0, 30)}" is not one of the shifts` };
      for (const x of asList(v)) {
        const pid = person(x);
        if (!pid) return { byShift, bad: `${sid}: "${String(x).slice(0, 30)}" is not on the staff list` };
        byShift.get(sid)!.push(pid);
      }
    }
  } else {
    for (const [k, v] of entries) {
      const pid = person(k);
      if (!pid) return { byShift, bad: `"${k.slice(0, 30)}" is not on the staff list` };
      for (const x of asList(v)) {
        const sid = readShift(x, w.shifts);
        if (!sid) return { byShift, bad: `${k}: "${String(x).slice(0, 30)}" is not one of the shifts` };
        byShift.get(sid)!.push(pid);
      }
    }
  }
  return { byShift };
}

export function rotaCost(w: RotaWorld, byShift: Map<string, string[]>): number {
  let total = 0;
  for (const s of w.shifts) for (const pid of byShift.get(s.id) ?? []) total += assignmentCost(w.staff.find((p) => p.id === pid)!, s);
  return total;
}

export function checkRota(w: RotaWorld, answer: Record<string, unknown>): PlanCheck {
  const items: VerifierItem[] = [];
  const stated = readPence(field(answer, 'total', 'totalcost', 'wagecost', 'cost', 'totalwagecost', 'wages'));
  const raw = field(answer, 'rota', 'schedule', 'shifts', 'roster');
  const { byShift, bad } = readRota(w, raw);
  items.push({ label: 'every entry is a listed shift and a listed person', passed: !bad, hard: true, detail: bad });
  if (bad) return { feasible: false, items, stated };
  const fail = (xs: string[]) => (xs.length ? xs.slice(0, 3).join('; ') + (xs.length > 3 ? ` (+${xs.length - 3} more)` : '') : undefined);
  const staff = new Map(w.staff.map((p) => [p.id, p]));
  const first = (pid: string) => staff.get(pid)!.name.split(' ')[0]!;

  const cover: string[] = [];
  const skill: string[] = [];
  const avail: string[] = [];
  const young: string[] = [];
  for (const s of w.shifts) {
    const ppl = byShift.get(s.id)!;
    const uniq = new Set(ppl);
    if (uniq.size !== ppl.length) cover.push(`${s.id}: someone is listed twice`);
    if (uniq.size !== s.need) cover.push(`${s.id}: ${uniq.size} people, needs exactly ${s.need}`);
    for (const [sk, n] of Object.entries(s.skills)) {
      const have = [...uniq].filter((pid) => staff.get(pid)!.skills.includes(sk)).length;
      if (have < n) skill.push(`${s.id}: ${have} ${w.skillNames[sk] ?? sk}, needs ${n}`);
    }
    for (const pid of uniq) {
      const p = staff.get(pid)!;
      if (p.off.includes(s.id)) avail.push(`${first(pid)} cannot work ${s.id}`);
      if (p.under18 && (touchesNight(s) || s.paid > w.youngDaily)) young.push(`${first(pid)} (under 18) on ${s.id}`);
    }
  }
  items.push({ label: 'every shift has exactly the staff it needs', passed: !cover.length, hard: true, detail: fail(cover) });
  items.push({ label: 'every shift has the skills it needs', passed: !skill.length, hard: true, detail: fail(skill) });
  items.push({ label: 'nobody works a shift they are not available for', passed: !avail.length, hard: true, detail: fail(avail) });

  const rest: string[] = [];
  const hours: string[] = [];
  const contract: string[] = [];
  const weekend = new Map<string, number>();
  for (const p of w.staff) {
    const mine = w.shifts.filter((s) => (byShift.get(s.id) ?? []).includes(p.id)).sort((a, b) => a.start - b.start);
    const need = (p.under18 ? w.youngRest : w.rest) * 60;
    let prevEnd = p.lastEnd ?? -Infinity;
    let prevId = 'last week';
    for (const s of mine) {
      if (s.start - prevEnd < need) rest.push(`${p.name.split(' ')[0]}: ${prevId} then ${s.id} (${Math.max(0, (s.start - prevEnd) / 60)} h rest)`);
      prevEnd = s.end;
      prevId = s.id;
    }
    const h = mine.reduce((a, s) => a + s.paid, 0);
    const cap = p.under18 ? w.youngCap : w.cap;
    if (h > cap) (p.under18 ? young : hours).push(`${p.name.split(' ')[0]}: ${h} h (cap ${cap})`);
    if (h < p.min || h > p.max) contract.push(`${p.name.split(' ')[0]}: ${h} h (contract ${p.min}–${p.max})`);
    if (p.fair) weekend.set(p.id, mine.filter((s) => s.day >= 5).length);
  }
  items.push({ label: `at least ${w.rest} hours’ rest between shifts (${w.youngRest} for under-18s), counting last week`, passed: !rest.length, hard: true, detail: fail(rest) });
  items.push({ label: `nobody over ${w.cap} hours`, passed: !hours.length, hard: true, detail: fail(hours) });
  items.push({ label: 'under-18 rules kept (no work 22:00–06:00, daily and weekly limits)', passed: !young.length, hard: true, detail: fail(young) });
  items.push({ label: 'everyone within their contracted hours', passed: !contract.length, hard: true, detail: fail(contract) });
  const counts = [...weekend.values()];
  const spread = counts.length ? Math.max(...counts) - Math.min(...counts) : 0;
  items.push({ label: 'weekend shifts shared fairly (within one of each other)', passed: spread <= 1, hard: true, detail: spread <= 1 ? undefined : [...weekend.entries()].map(([pid, n]) => `${first(pid)} ${n}`).join(', ') });
  const feasible = items.every((i) => i.passed || !i.hard);
  return { feasible, value: rotaCost(w, byShift), stated, items };
}

export const rota: PlanVerifier<RotaWorld> = {
  kind: 'plan',
  name: 'rota',
  noun: 'rota',
  objective: 'min',
  answerKeys: ['rota', 'roster'],
  quotesTotal: true,
  marking: [
    'The rota is checked rule by rule: exactly the staff each shift needs and the skills it needs, nobody on a shift they cannot work, the rest between shifts (counting last week’s last shift), the weekly hours cap, the under-18 rules, everyone’s contracted hours and the weekend fairness rule. Any broken rule scores 0, because the rota could not be used.',
    'A legal rota is costed at each person’s hourly rate plus any shift premium. The cheapest legal rota (proven by an exact branch-and-bound search over every assignment) scores 100; a legal but dearer rota scores 50 × (cheapest ÷ its cost)², always under 50. The wage total must be quoted: a missing or wrong total halves the score.',
  ],
  formatValue: gbp,
  check: checkRota,
};
