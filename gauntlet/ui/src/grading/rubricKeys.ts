/**
 * Keyboard grading, as plain data (unit-tested in test/grading-ui.test.ts).
 *
 * A rubric becomes a list of "stops" — one per criterion, one per requirement
 * of a requirement criterion, or one list of labels. Keys on the focused stop:
 *  - criterion 0–N points: the digit is the points (0–9); on 0–10 scales 1–9 = 1–9, 0 = 10, X = 0;
 *  - requirement: 1 = met, 2 = half met (only where the rubric gives half credit), 0 or X = missed;
 *  - labels: 1–9 picks the label with that number.
 * A key that sets a value moves to the next stop.
 */
import { requirementPoints, scoreFromGrade, type GradingSpec, type RequirementState } from '../../../src/grading/spec.ts';

export interface RubricDraft {
  criteria: Record<string, number>;
  requirements: Record<string, RequirementState>;
  label?: string;
}

export type Stop = { type: 'criterion'; id: string } | { type: 'req'; criterion: string; req: string } | { type: 'labels' };

export function emptyDraft(): RubricDraft {
  return { criteria: {}, requirements: {} };
}

export function stopsFor(spec: Pick<GradingSpec, 'criteria' | 'labels'>): Stop[] {
  if (spec.labels?.length) return [{ type: 'labels' }];
  const out: Stop[] = [];
  for (const c of spec.criteria) {
    if (c.requirements?.items.length) for (const it of c.requirements.items) out.push({ type: 'req', criterion: c.id, req: it.id });
    else out.push({ type: 'criterion', id: c.id });
  }
  return out;
}

/** Criterion points with requirement criteria computed from their verdicts. */
export function effectivePoints(spec: Pick<GradingSpec, 'criteria'>, d: RubricDraft): Record<string, number | undefined> {
  const out: Record<string, number | undefined> = {};
  for (const c of spec.criteria) {
    if (c.requirements?.items.length) {
      const states = Object.fromEntries(c.requirements.items.map((it) => [it.id, d.requirements[`${c.id}:${it.id}`]]));
      out[c.id] = c.requirements.items.every((it) => states[it.id]) ? requirementPoints(c, states) : undefined;
    } else out[c.id] = d.criteria[c.id];
  }
  return out;
}

/** 0..1 score of a draft, or null while something is ungraded. */
export function draftScore(spec: Pick<GradingSpec, 'criteria' | 'labels'>, d: RubricDraft): number | null {
  if (spec.labels?.length) return scoreFromGrade(spec, { label: d.label });
  const pts = effectivePoints(spec, d);
  if (Object.values(pts).some((v) => v === undefined)) return null;
  return scoreFromGrade(spec, { criteria: pts as Record<string, number> });
}

export function isStopDone(_spec: Pick<GradingSpec, 'criteria' | 'labels'>, d: RubricDraft, s: Stop): boolean {
  if (s.type === 'labels') return !!d.label;
  if (s.type === 'req') return !!d.requirements[`${s.criterion}:${s.req}`];
  return typeof d.criteria[s.id] === 'number';
}

/** Apply a key to the focused stop. Returns the new draft and whether the key set a value (then focus moves on), or null when the key does nothing here. */
export function applyKey(spec: Pick<GradingSpec, 'criteria' | 'labels'>, d: RubricDraft, stop: Stop | undefined, key: string): RubricDraft | null {
  if (!stop) return null;
  const k = key.toLowerCase();
  if (stop.type === 'labels') {
    const n = Number(k);
    const l = Number.isInteger(n) && n >= 1 ? spec.labels?.find((x) => x.key === n) : undefined;
    return l ? { ...d, label: l.id } : null;
  }
  if (stop.type === 'req') {
    const c = spec.criteria.find((x) => x.id === stop.criterion);
    const state: RequirementState | null = k === '1' ? 'met' : k === '2' && c?.requirements?.mode === 'share' ? 'partial' : k === '0' || k === 'x' ? 'missed' : null;
    return state ? { ...d, requirements: { ...d.requirements, [`${stop.criterion}:${stop.req}`]: state } } : null;
  }
  const c = spec.criteria.find((x) => x.id === stop.id);
  if (!c) return null;
  let v: number | null = null;
  if (k === 'x') v = c.min;
  else if (/^\d$/.test(k)) {
    const n = Number(k);
    v = c.max === 10 && c.min === 0 && n === 0 ? 10 : n;
    if (c.max === 10 && c.min === 1 && n === 0) v = 10;
  }
  if (v === null || v < c.min || v > c.max) return null;
  return { ...d, criteria: { ...d.criteria, [c.id]: v } };
}

/** ←/→ nudges the focused criterion by one step (requirements cycle through their states). */
export function nudge(spec: Pick<GradingSpec, 'criteria' | 'labels'>, d: RubricDraft, stop: Stop | undefined, dir: 1 | -1): RubricDraft {
  if (!stop) return d;
  if (stop.type === 'labels') {
    const ls = spec.labels ?? [];
    const i = ls.findIndex((l) => l.id === d.label);
    const next = ls[Math.max(0, Math.min(ls.length - 1, i < 0 ? 0 : i + dir))];
    return next ? { ...d, label: next.id } : d;
  }
  if (stop.type === 'req') {
    const c = spec.criteria.find((x) => x.id === stop.criterion);
    const order: RequirementState[] = c?.requirements?.mode === 'share' ? ['missed', 'partial', 'met'] : ['missed', 'met'];
    const key = `${stop.criterion}:${stop.req}`;
    const i = order.indexOf(d.requirements[key] ?? 'missed');
    return { ...d, requirements: { ...d.requirements, [key]: order[Math.max(0, Math.min(order.length - 1, i + dir))]! } };
  }
  const c = spec.criteria.find((x) => x.id === stop.id);
  if (!c) return d;
  const cur = d.criteria[c.id] ?? (dir > 0 ? c.min - c.step : c.max + c.step);
  return { ...d, criteria: { ...d.criteria, [c.id]: Math.max(c.min, Math.min(c.max, Math.round((cur + dir * c.step) * 2) / 2)) } };
}
