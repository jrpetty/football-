/**
 * The Gallery: turns stored results of The Gallery Masterpiece tests into what the museum views draw
 * (paintings, placards, checklists). Everything comes from recorded data: scoreDetail.gallery (written by
 * src/programs/lib/gallery-core.ts), the painting artifact and the case metrics. Older or partial results
 * fall back to clear "not recorded" states.
 */
import { artifactUrl } from '../../api.ts';
import { BRIEFS, type Brief } from '../../../../src/programs/lib/gallery-briefs.ts';
import type { GalleryDetail, GalleryItem, GalleryCriterion } from '../../../../src/programs/lib/gallery-judge.ts';
import type { ArtifactRef, CaseResultLite, ContestantSnapshot, ResultStatus } from '../../types.ts';

export type { GalleryDetail, GalleryItem, GalleryCriterion, Brief };

export const GALLERY_TEST_IDS = ['art.gallery-masterpiece', 'art.gallery-painted-in-code'] as const;

export function isGalleryTest(testId: string | undefined | null): boolean {
  return !!testId && (GALLERY_TEST_IDS as readonly string[]).includes(testId);
}

export function galleryOf(r: { scoreDetail?: unknown } | null | undefined): GalleryDetail | null {
  const g = (r?.scoreDetail as { gallery?: GalleryDetail } | undefined)?.gallery;
  return g && typeof g === 'object' && g.version === 1 && Array.isArray(g.items) ? g : null;
}

/** "seed-3" → the third commission (always available, even for results without detail). */
export function briefForCase(caseId: string): Brief | null {
  const n = Number(/^seed-(\d+)$/.exec(caseId)?.[1]);
  return BRIEFS.find((b) => b.n === n) ?? null;
}

/** The picture artifact (the PNG/JPEG the judges saw; for Painted in Code the rendered PNG, not the SVG source). */
export function paintingArtifact(r: { artifacts?: ArtifactRef[] }): ArtifactRef | null {
  const arts = r.artifacts ?? [];
  return arts.find((a) => /^painting\.(png|jpg)$/.test(a.name)) ?? arts.find((a) => a.kind === 'png' || a.kind === 'jpg') ?? null;
}

export function svgSourceArtifact(r: { artifacts?: ArtifactRef[] }): ArtifactRef | null {
  return (r.artifacts ?? []).find((a) => a.name === 'painting.svg') ?? null;
}

export type FrameStyle = 'gilt' | 'ebony' | 'walnut' | 'oak';

/** Frames suited to each commission: ebony for the Dutch interior, a matted oak frame for the woodblock print, … */
export function frameFor(briefN: number | undefined): FrameStyle {
  switch (briefN) {
    case 1:
      return 'ebony';
    case 4:
      return 'oak';
    case 6:
      return 'walnut';
    default:
      return 'gilt';
  }
}

export type WallState = 'painting' | 'refused' | 'no-picture' | 'no-output' | 'awaiting' | 'error' | 'missing';

export interface WallEntry {
  key: string;
  runId: string;
  contestantId: string;
  label: string;
  vendor: string;
  color: string;
  baseline: boolean;
  manual: boolean;
  result: CaseResultLite | null;
  detail: GalleryDetail | null;
  url: string | null;
  width: number;
  height: number;
  state: WallState;
  score: number | null;
  costUsd: number | null;
}

const isBaselineC = (c: Pick<ContestantSnapshot, 'id' | 'label' | 'provider'>) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(c.id) || /random baseline/i.test(c.label);

export function stateOf(r: Pick<CaseResultLite, 'status' | 'artifacts'> | null, g: GalleryDetail | null): WallState {
  if (!r) return 'missing';
  if (r.status === 'skipped') return 'no-output';
  if (r.status === 'error' || r.status === 'cancelled' || r.status === 'timeout') return 'error';
  if (g?.status === 'refused' || r.status === 'refusal') return 'refused';
  if (g?.status === 'no-picture') return 'no-picture';
  if (!g?.painting) return paintingArtifact(r) ? 'painting' : 'no-picture';
  if (r.status === 'pending-human') return 'awaiting';
  return 'painting';
}

/**
 * Every contestant's entry for one commission (one case of one Gallery test), in the run's contestant order
 * with the Random Baseline last. Uses the first repeat when a run has several.
 */
export function wallFor(opts: { runId: string; testId: string; caseId: string; results: CaseResultLite[]; contestants: ContestantSnapshot[]; manualProviders?: Set<string>; includeSkipped?: boolean }): WallEntry[] {
  const out: WallEntry[] = [];
  for (const c of opts.contestants) {
    const mine = opts.results.filter((r) => r.testId === opts.testId && r.caseId === opts.caseId && r.contestantId === c.id).sort((a, b) => a.repeat - b.repeat);
    const r = mine[0] ?? null;
    if (!r) continue;
    const g = galleryOf(r);
    const state = stateOf(r, g);
    if (state === 'no-output' && !opts.includeSkipped) continue;
    const art = paintingArtifact(r);
    out.push({
      key: r.key,
      runId: opts.runId,
      contestantId: c.id,
      label: c.label,
      vendor: c.vendor,
      color: c.color,
      baseline: isBaselineC(c),
      manual: !!opts.manualProviders?.has(c.provider) || c.provider === 'manual',
      result: r,
      detail: g,
      url: art ? artifactUrl(opts.runId, art.file) || null : null,
      width: g?.painting?.width ?? 1536,
      height: g?.painting?.height ?? 1024,
      state,
      score: typeof r.score === 'number' ? r.score : null,
      costUsd: r.metrics ? r.metrics.costUsd : null,
    });
  }
  return out.sort((a, b) => Number(a.baseline) - Number(b.baseline));
}

/** The commissions a Gallery test has results for, in brief order. */
export function briefsIn(results: CaseResultLite[], testId: string, caseIds?: string[]): Array<{ caseId: string; brief: Brief }> {
  const ids = new Set(caseIds ?? results.filter((r) => r.testId === testId).map((r) => r.caseId));
  return [...ids]
    .map((caseId) => ({ caseId, brief: briefForCase(caseId) }))
    .filter((x): x is { caseId: string; brief: Brief } => !!x.brief)
    .sort((a, b) => a.brief.n - b.brief.n);
}

/** Highest score among real contestants with a painting (ties: higher artistry). Null when nobody painted. */
export function bestOf(entries: WallEntry[]): WallEntry | null {
  const judged = entries.filter((e) => !e.baseline && e.state === 'painting' && e.score !== null);
  judged.sort((a, b) => b.score! - a.score! || (b.detail?.artistry ?? 0) - (a.detail?.artistry ?? 0));
  return judged[0] ?? null;
}

export const fmtArtistry = (x: number | null | undefined) => (typeof x === 'number' ? x.toFixed(1) : '—');

/** "$0.25", "$0.039", "under $0.001", "not recorded". */
export function fmtPaintCost(usd: number | null | undefined, manual = false): string {
  if (typeof usd !== 'number' || !Number.isFinite(usd)) return 'not recorded';
  if (usd === 0) return manual ? 'not recorded' : '$0';
  if (usd < 0.001) return 'under $0.001';
  if (usd < 0.1) return `$${usd.toFixed(3).replace(/0$/, '')}`;
  return `$${usd.toFixed(2)}`;
}

/** Judges' artistry range, e.g. "6.1–8.4", when they differ by 2 points or more. */
export function spreadNote(g: GalleryDetail | null): string | null {
  if (!g || g.spread === null || g.spread < 2) return null;
  const vals = g.judges.map((j) => j.artistry).filter((x): x is number => typeof x === 'number');
  if (vals.length < 2) return null;
  return `${Math.min(...vals).toFixed(1)}–${Math.max(...vals).toFixed(1)}`;
}

export function stateText(state: WallState, mode: 'image' | 'code'): { title: string; sub: string } {
  switch (state) {
    case 'refused':
      return { title: 'Declined the commission', sub: mode === 'code' ? 'The model refused to write the painting.' : 'The image generator refused to paint this brief. It scores 0.' };
    case 'no-picture':
      return { title: 'No painting delivered', sub: mode === 'code' ? 'No SVG came back, or it would not render. It scores 0.' : 'The model answered without a picture. It scores 0.' };
    case 'no-output':
      return { title: 'Cannot paint', sub: 'This model has no image output, so it was not asked. Not scored.' };
    case 'awaiting':
      return { title: 'Awaiting judges', sub: 'Too few judges answered: rate it in Blind Review.' };
    case 'error':
      return { title: 'Not recorded', sub: 'The call failed before a painting was made. Not scored.' };
    case 'missing':
      return { title: 'Not recorded', sub: 'No result for this commission.' };
    default:
      return { title: '', sub: '' };
  }
}

export function statusIsScored(s: ResultStatus): boolean {
  return s === 'ok' || s === 'refusal';
}

/** Short style name for the placard, e.g. "Dutch Golden Age interior". */
export function styleShort(style: string): string {
  return style.split(/, in the manner of /)[0] ?? style;
}

export function mastersOf(style: string): string | null {
  return style.split(/, in the manner of /)[1] ?? null;
}
