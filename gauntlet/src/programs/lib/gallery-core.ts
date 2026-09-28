/**
 * The Gallery Masterpiece: the part both programs share. Given the finished painting (or the reason there is
 * none), it saves the picture, asks the judge panel, and builds the result.
 */
import type { GeneratedImage, ProgramContext, ProgramResult, ScoreBreakdownItem } from '../../core/types.ts';
import type { Brief } from './gallery-briefs.ts';
import { MIN_JUDGES, type GalleryDetail, aggregate, galleryScore, judgePainting, summaryLine } from './gallery-judge.ts';
import { judgeImage } from './gallery-render.ts';

export type PaintingOutcome =
  | { kind: 'painting'; image: GeneratedImage; costUsd: number }
  | { kind: 'refused'; text: string; costUsd: number }
  | { kind: 'no-picture'; text: string; costUsd: number };

const briefRef = (b: Brief): GalleryDetail['brief'] => ({ n: b.n, id: b.id, title: b.title, medium: b.medium, style: b.style });

export async function finishPainting(ctx: ProgramContext, b: Brief, outcome: PaintingOutcome, mode: GalleryDetail['mode'], extra: Record<string, unknown> = {}): Promise<ProgramResult> {
  const base: GalleryDetail = {
    version: 1,
    mode,
    brief: briefRef(b),
    painting: null,
    ...aggregate(b, [], []),
    paintingCostUsd: Math.round(outcome.costUsd * 1e6) / 1e6,
  };
  if (outcome.kind !== 'painting') {
    const d: GalleryDetail = { ...base, status: outcome.kind };
    const why = outcome.kind === 'refused' ? 'Refused to paint' : mode === 'code' ? 'No SVG painting returned' : 'No picture returned';
    return {
      score: 0,
      passed: false,
      summary: `${why} · 0`,
      status: outcome.kind === 'refused' ? 'refusal' : undefined,
      detail: { gallery: d, notes: outcome.text ? `The model said: ${outcome.text.slice(0, 600)}` : undefined, ...extra },
    };
  }

  const img = outcome.image;
  const ext = img.mediaType === 'image/png' ? 'png' : 'jpg';
  const buf = Buffer.from(img.data, 'base64');
  if (ctx.artifactBytes) ctx.artifactBytes(`painting.${ext}`, ext, buf);
  const { verdicts, failures, agg } = await judgePainting(ctx.judges, b, await judgeImage(img));
  const d: GalleryDetail = {
    ...base,
    ...agg,
    painting: { name: `painting.${ext}`, mediaType: img.mediaType, width: img.width, height: img.height, bytes: img.bytes },
    status: verdicts.length >= MIN_JUDGES ? 'judged' : 'awaiting-judges',
  };
  const items: ScoreBreakdownItem[] = d.items.map((i) => ({
    label: `${i.kind === 'avoid' ? 'Avoided: ' : ''}${i.text}`,
    passed: i.followed,
    score: i.consensus ?? undefined,
    detail: i.verdicts.map((v) => `${v.verdict}: ${v.reason}`).join(' · ') || undefined,
  }));
  const judge = d.judges.filter((j) => j.artistry !== null).map((j) => ({ contestantId: j.judgeId, score: galleryScore(j.adherence, j.artistry) ?? 0, rationale: `Brief ${j.adherence === null ? '—' : Math.round(j.adherence * 100)}% · artistry ${j.artistry!.toFixed(1)}/10. ${j.summary}` }));
  const issues = failures.length ? `Judge issues: ${failures.map((f) => `${f.judgeId}: ${f.error}`).join('; ')}` : undefined;
  if (verdicts.length < MIN_JUDGES) {
    // Too few judges: keep the painting and whatever the panel said, and let the owner rate it in Blind Review.
    const have = verdicts.length ? `only ${verdicts.length} of ${MIN_JUDGES} judges answered` : 'no eligible judge answered';
    return {
      score: 0,
      status: 'pending-human',
      summary: `Awaiting your rating: ${have}`,
      detail: { gallery: d, items, judge, notes: [`The Gallery needs at least ${MIN_JUDGES} vision judges from other companies than the artist's. Rate this painting in Blind Review; your rating becomes its score.`, issues].filter(Boolean).join(' '), ...extra },
    };
  }
  const score = galleryScore(d.adherence, d.artistry) ?? 0;
  return {
    score,
    passed: score >= 0.7,
    summary: summaryLine(d),
    detail: {
      gallery: d,
      items,
      judge,
      judgeSpread: d.spread === null ? undefined : Math.round((d.spread / 10) * 1000) / 1000,
      judgeDisagreement: d.disagreement || undefined,
      notes: issues,
      ...extra,
    },
  };
}
