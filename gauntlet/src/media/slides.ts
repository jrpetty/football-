/**
 * The Presenter's slide order, reproduced without React so the script can say
 * "[Slide 7]" and land on exactly the slide the Presenter shows as 7.
 * Keep in step with buildDeck / buildSlides in ui/src/pages/PresentPage.tsx.
 */
import { isBaselineId } from './common.ts';
import type { SlideRef, StudioInput } from './types.ts';

/** Tests in the Presenter's running order: by category (canonical order), then manifest order. */
export function runningOrder(input: StudioInput): string[] {
  const catOrder = new Map<string, number>();
  [...input.categories, ...(input.leaderboard?.categories ?? [])].forEach((c, i) => {
    if (!catOrder.has(c.id)) catOrder.set(c.id, i);
  });
  return input.manifest.tests
    .map((snap, i) => ({ snap, i }))
    .sort((a, b) => (catOrder.get(a.snap.category) ?? 999) - (catOrder.get(b.snap.category) ?? 999) || a.i - b.i)
    .map(({ snap }) => snap.id);
}

export function presenterSlides(input: StudioInput): SlideRef[] {
  const out: Array<Omit<SlideRef, 'n'>> = [{ kind: 'title' }, { kind: 'how' }];
  for (const testId of runningOrder(input)) out.push({ kind: 'explainer', testId }, { kind: 'result', testId });
  const rows = input.leaderboard?.rows ?? [];
  const comps = rows.filter((r) => !isBaselineId({ id: r.contestantId, vendor: r.vendor, label: r.label }) && typeof r.index === 'number');
  if (comps.length) out.push({ kind: 'final' });
  if (comps.some((r) => (r.totals?.costUsd ?? 0) > 0)) out.push({ kind: 'scatter' });
  if (comps.length && (input.leaderboard?.medals ?? []).some((e) => e.gold || e.silver || e.bronze)) out.push({ kind: 'medals' });
  out.push({ kind: 'outro' });
  return out.map((s, i) => ({ ...s, n: i + 1 }));
}

export function slideOf(slides: SlideRef[], kind: SlideRef['kind'], testId?: string): number | undefined {
  return slides.find((s) => s.kind === kind && (testId === undefined || s.testId === testId))?.n;
}
