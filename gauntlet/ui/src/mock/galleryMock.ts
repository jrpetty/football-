/**
 * Mock-mode fixtures for The Gallery: a demo run of both Gallery tests with procedurally painted placeholder
 * pictures (galleryPaintings.ts, made locally), judge verdicts that match what each picture actually shows, one
 * refusal, a picture-only skip, an owner artistry rating, blind votes and a pending Manual Inbox picture request.
 * Scores are computed with the real aggregation code (src/programs/lib/gallery-judge.ts).
 */
import masterpieceJson from '../../../tests/art/gallery-masterpiece.json';
import codeJson from '../../../tests/art/gallery-painted-in-code.json';
import { BRIEFS, briefItems, briefForSeed, codePrompt, imagePrompt } from '../../../src/programs/lib/gallery-briefs.ts';
import { ARTISTRY_CRITERIA, aggregate, galleryScore, summaryLine, type GalleryDetail, type JudgeVerdict, type Verdict } from '../../../src/programs/lib/gallery-judge.ts';
import { SKIP_NO_IMAGE_OUTPUT, SKIP_PICTURE_ONLY } from '../../../src/core/image-output.ts';
import type { ArtifactRef, CaseResult, CaseResultLite, ContestantView, ManualRequest, ProgramInfo, ProgramTest, TestDefinition, TranscriptEntry } from '../types.ts';
import { mockArtifactUrls } from './registry.ts';
import { circleMock, noiseMock, paintMock, type MockQuality } from './galleryPaintings.ts';
import type { RunSpec } from './fixtures.ts';

export const GALLERY_TESTS: TestDefinition[] = [masterpieceJson as unknown as ProgramTest, codeJson as unknown as ProgramTest];
const IMAGE_TEST = 'art.gallery-masterpiece';
const CODE_TEST = 'art.gallery-painted-in-code';

export const GALLERY_PROGRAMS: ProgramInfo[] = [
  { id: 'gallery-masterpiece', name: 'The Gallery Masterpiece', description: 'A museum commissions a painting with a precise brief; the model paints it with its own image generator.', scoring: 'Half brief adherence (yes / partly / no per line, median across judges), half artistry (six criteria 1–10, median across judges).' },
  { id: 'gallery-code', name: 'The Gallery Masterpiece: Painted in Code', description: 'The same briefs painted as a single SVG, rendered in headless Chromium and judged like a painting.', scoring: 'Half brief adherence, half artistry, judged on the rendered picture.' },
];

const view = (c: Omit<ContestantView, 'hasKey' | 'providerLabel' | 'providerType' | 'configHash'> & { providerLabel: string; providerType: ContestantView['providerType'] }): ContestantView => ({ ...c, configHash: `cfg-${c.id}`, hasKey: true });

export const GALLERY_CONTESTANTS: ContestantView[] = [
  view({ id: 'meridian-canvas-2', label: 'Canvas 2', vendor: 'Meridian AI', provider: 'meridian', providerLabel: 'Meridian AI', providerType: 'openai-compatible', model: 'canvas-2', color: '#5aa9e6', enabled: true, imageOutput: true, imageOnly: true, imageOptions: { size: '1536x1024', quality: 'high' }, pricing: { inputPerM: 5, outputPerM: 40, source: 'meridian.example/pricing', verifiedAt: '2026-09-02' }, imagePricing: { perImage: { '1536x1024': { medium: 0.06, high: 0.19 } }, source: 'meridian.example/pricing', verifiedAt: '2026-09-02' } }),
  view({ id: 'helios-lumen-image', label: 'Lumen Image', vendor: 'Helios', provider: 'helios', providerLabel: 'Helios', providerType: 'gemini', model: 'lumen-image', color: '#e8a33d', enabled: true, imageOutput: true, imageOnly: true, pricing: { inputPerM: 0.3, outputPerM: 30, source: 'helios.example/pricing', verifiedAt: '2026-08-28' }, imagePricing: { perImage: { '*': { '*': 0.039 } }, source: 'helios.example/pricing', verifiedAt: '2026-08-28' } }),
  view({ id: 'obsidian-prism-image', label: 'Prism XL', vendor: 'Obsidian', provider: 'obsidian', providerLabel: 'Obsidian Cloud', providerType: 'openai-compatible', model: 'prism-xl', color: '#b07cd8', enabled: true, imageOutput: true, imageOnly: true, imageOptions: { size: null, quality: null, responseFormat: 'b64_json' }, pricing: { inputPerM: 0, outputPerM: 0, source: 'obsidian.example/models', verifiedAt: '2026-07-15' }, imagePricing: { perImage: { '*': { '*': 0.07 } }, source: 'obsidian.example/models', verifiedAt: '2026-07-15' } }),
];

export const GALLERY_RUN_SPEC: RunSpec = {
  id: 'run-2026-09-28-gallery',
  name: 'The Gallery · September 2026',
  status: 'completed',
  contestantIds: ['meridian-canvas-2', 'helios-lumen-image', 'obsidian-prism-image', 'manual-orbit-chat', 'meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-nova-3-pro', 'random-baseline'],
  testIds: [IMAGE_TEST, CODE_TEST],
  repeats: 1,
  suiteId: 'art',
  createdAt: '2026-09-28T10:00:00Z',
  notes: 'Demo run for The Gallery: open The Gallery page, the Presenter, or any cell of the Art tests.',
};

const IMAGE_ONLY = new Set(GALLERY_CONTESTANTS.map((c) => c.id));
const PAINTERS = new Set([...IMAGE_ONLY, 'manual-orbit-chat', 'random-baseline']);
const JUDGES: Array<{ id: string; vendor: string }> = [
  { id: 'meridian-atlas-4-ultra', vendor: 'Meridian AI' },
  { id: 'helios-nova-3-pro', vendor: 'Helios' },
  { id: 'kestrel-kite-reasoner', vendor: 'Kestrel' },
];
const VENDOR: Record<string, string> = { 'meridian-canvas-2': 'Meridian AI', 'helios-lumen-image': 'Helios', 'obsidian-prism-image': 'Obsidian', 'manual-orbit-chat': 'Orbit Labs', 'meridian-atlas-4-ultra': 'Meridian AI', 'kestrel-kite-reasoner': 'Kestrel', 'helios-nova-3-pro': 'Helios', 'random-baseline': 'Baseline' };

function rngOf(seed: string) {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  let s = h >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Plan {
  kind: 'paint' | 'noise' | 'circle' | 'refused' | 'skip-output' | 'skip-picture-only';
  quality: MockQuality;
  missing: string[];
  breaks: string[];
  artistry: number;
}

/** What each demo artist does with each commission (deterministic). */
function planFor(testId: string, contestantId: string, n: number): Plan {
  const r = rngOf(`${testId}|${contestantId}|${n}`);
  const pick = <T,>(xs: T[]) => xs[Math.floor(r() * xs.length)]!;
  const el = () => `E${1 + Math.floor(r() * 6)}`;
  const base = { missing: [] as string[], breaks: [] as string[] };
  if (testId === IMAGE_TEST) {
    if (!PAINTERS.has(contestantId)) return { kind: 'skip-output', quality: 'good', ...base, artistry: 0 };
    if (contestantId === 'random-baseline') return { kind: 'noise', quality: 'crude', ...base, artistry: 1.3 };
    if (contestantId === 'obsidian-prism-image' && n === 7) return { kind: 'refused', quality: 'good', ...base, artistry: 0 };
    if (contestantId === 'meridian-canvas-2') return { kind: 'paint', quality: 'master', missing: r() < 0.3 ? [el()] : [], breaks: n === 4 ? ['N1'] : [], artistry: 7.9 + r() * 1.1 };
    if (contestantId === 'helios-lumen-image') return { kind: 'paint', quality: pick(['master', 'good'] as MockQuality[]), missing: r() < 0.35 ? [el()] : [], breaks: n === 5 ? ['N2'] : [], artistry: 7.2 + r() * 1.3 };
    if (contestantId === 'manual-orbit-chat') return { kind: 'paint', quality: 'master', missing: r() < 0.25 ? [el()] : [], breaks: [], artistry: 7.6 + r() * 1.1 };
    return { kind: 'paint', quality: 'good', missing: [el(), ...(r() < 0.5 ? [el()] : [])], breaks: r() < 0.5 ? ['N1'] : [], artistry: 5.8 + r() * 1.3 };
  }
  if (IMAGE_ONLY.has(contestantId)) return { kind: 'skip-picture-only', quality: 'good', ...base, artistry: 0 };
  if (contestantId === 'random-baseline') return { kind: 'circle', quality: 'crude', ...base, artistry: 1 };
  if (contestantId === 'meridian-atlas-4-ultra') return { kind: 'paint', quality: 'good', missing: r() < 0.4 ? [el()] : [], breaks: [], artistry: 5.6 + r() * 1.2 };
  if (contestantId === 'kestrel-kite-reasoner') return { kind: 'paint', quality: pick(['good', 'crude'] as MockQuality[]), missing: r() < 0.5 ? [el()] : [], breaks: [], artistry: 5 + r() * 1.3 };
  if (contestantId === 'helios-nova-3-pro') return { kind: 'paint', quality: 'crude', missing: [el(), ...(r() < 0.4 ? [el()] : [])], breaks: [], artistry: 4.2 + r() * 1.2 };
  return { kind: 'paint', quality: 'crude', missing: [el()], breaks: [], artistry: 4.5 };
}

const REASON_YES = ['clearly painted and easy to find', 'present exactly as described', 'there, and well placed', 'unmistakable at a glance'];
const REASON_PARTLY = ['present, but small and easy to miss', 'there, though not quite as specified', 'suggested rather than clearly shown'];
const RULE_BROKEN: Record<string, string> = { N1: 'a signature-like scribble sits in the lower right corner', N2: 'a painted frame border runs around the picture', N3: 'a modern object appears' };

function verdictsFor(n: number, drawn: Set<string>, broken: Set<string>, artistry: number, seed: string, judges: string[], noise: boolean): JudgeVerdict[] {
  const b = briefForSeed(n);
  return judges.map((j, k) => {
    const r = rngOf(`${seed}|${j}`);
    const checklist: JudgeVerdict['checklist'] = {};
    for (const i of briefItems(b)) {
      let verdict: Verdict;
      let reason: string;
      if (i.kind === 'element') {
        if (noise || !drawn.has(i.id)) {
          verdict = 'no';
          reason = noise ? 'the picture is abstract noise; nothing recognisable' : 'not in the painting';
        } else if (r() < 0.14) {
          verdict = 'partly';
          reason = REASON_PARTLY[Math.floor(r() * REASON_PARTLY.length)]!;
        } else {
          verdict = 'yes';
          reason = REASON_YES[Math.floor(r() * REASON_YES.length)]!;
        }
      } else if (broken.has(i.id)) {
        verdict = r() < 0.25 ? 'partly' : 'no';
        reason = RULE_BROKEN[i.id] ?? 'rule broken';
      } else {
        verdict = 'yes';
        reason = noise && i.id === 'N3' ? 'nothing modern, but nothing at all is depicted' : 'respected';
      }
      checklist[i.id] = { verdict, reason };
    }
    // One commission shows a wide judge disagreement on purpose (the spread is reported, not hidden).
    const tilt = n === 3 && !noise ? (k === 0 ? 1.4 : -1.3) : (r() - 0.5) * 1.2;
    const art = Object.fromEntries(
      ARTISTRY_CRITERIA.map((c, ci) => {
        const s = Math.max(1, Math.min(10, Math.round((artistry + tilt + (r() - 0.5) * 1.4 + (ci === 3 ? -0.4 : 0)) * 2) / 2));
        return [c.id, { score: s, reason: s >= 8 ? 'confident and cohesive' : s >= 6 ? 'competent, some flat passages' : s >= 4 ? 'naive and flat' : 'no craft to speak of' }];
      }),
    ) as JudgeVerdict['artistry'];
    const summary = noise ? 'Random coloured noise: no painting.' : artistry >= 7.5 ? `A luminous, well-composed take on “${b.title}”.` : artistry >= 5.5 ? `A pleasant, simplified “${b.title}” with a few gaps.` : `A naive sketch of “${b.title}” that misses much of the brief.`;
    return { judgeId: `${j}@judge`, checklist, artistry: art, summary };
  });
}

function dataUrl(svg: string): string {
  return `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`;
}

const cache = new Map<string, { svg: string; detail: GalleryDetail | null; plan: Plan }>();

function build(lite: CaseResultLite): { svg: string; detail: GalleryDetail | null; plan: Plan } {
  const k = `${lite.testId}|${lite.contestantId}|${lite.caseId}`;
  const hit = cache.get(k);
  if (hit) return hit;
  const n = Number(/^seed-(\d+)$/.exec(lite.caseId)?.[1] ?? 1);
  const plan = planFor(lite.testId, lite.contestantId, n);
  const b = briefForSeed(n);
  const mode = lite.testId === CODE_TEST ? 'code' : 'image';
  let svg = '';
  let drawn = new Set<string>();
  let broken = new Set<string>();
  if (plan.kind === 'paint') {
    const p = paintMock(n, `${lite.contestantId}|${lite.testId}`, plan.quality, plan.missing, plan.breaks);
    svg = p.svg;
    drawn = p.drawn;
    broken = p.broken;
  } else if (plan.kind === 'noise') svg = noiseMock(`${lite.contestantId}|${n}`);
  else if (plan.kind === 'circle') svg = circleMock();
  let detail: GalleryDetail | null = null;
  const brief = { n: b.n, id: b.id, title: b.title, medium: b.medium, style: b.style };
  if (plan.kind === 'refused') {
    detail = { version: 1, mode, brief, painting: null, ...aggregate(b, [], []), paintingCostUsd: 0, status: 'refused' };
  } else if (svg) {
    const judges = JUDGES.filter((j) => j.vendor !== VENDOR[lite.contestantId]).map((j) => j.id);
    const vs = verdictsFor(n, drawn, broken, plan.artistry, k, judges, plan.kind !== 'paint');
    const agg = aggregate(b, vs, []);
    const noise = plan.kind === 'noise';
    detail = { version: 1, mode, brief, painting: { name: 'painting.png', mediaType: 'image/png', width: noise ? 384 : 1536, height: noise ? 256 : 1024, bytes: noise ? 180_000 : 2_300_000 }, ...agg, paintingCostUsd: 0, status: 'judged' };
  }
  const out = { svg, detail, plan };
  cache.set(k, out);
  return out;
}

const PRICE: Record<string, number> = { 'meridian-canvas-2': 0.19, 'helios-lumen-image': 0.039, 'obsidian-prism-image': 0.07, 'manual-orbit-chat': 0.04, 'random-baseline': 0 };

/** Replace the generic mock outcome of a Gallery test with a painting, its judged verdicts and its artifacts. */
export function decorateGallery(t: TestDefinition, lite: CaseResultLite): CaseResultLite {
  if (t.id !== IMAGE_TEST && t.id !== CODE_TEST) return lite;
  const { svg, detail, plan } = build(lite);
  const zero = { ...lite.metrics, apiCalls: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0, judgeCostUsd: 0, wallMs: 0, ttftMs: null, outputTokensPerSec: null };
  if (plan.kind === 'skip-output' || plan.kind === 'skip-picture-only') {
    return { ...lite, status: 'skipped', score: null, passed: null, summary: plan.kind === 'skip-output' ? SKIP_NO_IMAGE_OUTPUT : SKIP_PICTURE_ONLY, scoreDetail: {}, metrics: zero, artifacts: [], error: undefined, hasReplay: false, humanScores: undefined };
  }
  const image = t.id === IMAGE_TEST;
  const metrics = image ? { ...zero, apiCalls: 1, inputTokens: 480, costUsd: PRICE[lite.contestantId] ?? 0, judgeCostUsd: 0.011, wallMs: 18_000 + Math.round(lite.metrics.wallMs % 20_000) } : { ...lite.metrics, judgeCostUsd: 0.011, costUsd: lite.contestantId === 'random-baseline' ? 0 : lite.metrics.costUsd };
  if (plan.kind === 'refused') {
    return { ...lite, status: 'refusal', score: 0, passed: false, summary: 'Refused to paint · 0', scoreDetail: { gallery: detail, notes: 'The model said: This request may violate our content policy.' }, metrics: { ...metrics, costUsd: 0, judgeCostUsd: 0 }, artifacts: [], error: undefined, hasReplay: false, humanScores: undefined };
  }
  const artifacts: ArtifactRef[] = [];
  const add = (name: string, kind: ArtifactRef['kind'], content: string) => {
    const file = `${lite.contestantId}/${lite.testId}/${lite.caseId}-r${lite.repeat}/${name}`;
    mockArtifactUrls.set(`${lite.runId}/${file}`, dataUrl(content));
    artifacts.push({ name, kind, file, bytes: detail?.painting?.bytes ?? content.length });
  };
  if (!image) add('painting.svg', 'svg', svg);
  add('painting.png', 'png', svg);
  let g = detail!;
  let score = galleryScore(g.adherence, g.artistry) ?? 0;
  let summary = summaryLine(g);
  let humanScores: CaseResultLite['humanScores'];
  const n = g.brief.n;
  // An owner's artistry rating (Blind Review) on one painting, and blind votes on commission No. 1.
  if (image && n === 2 && lite.contestantId === 'helios-lumen-image') {
    humanScores = [{ rater: 'owner', score: 0.9, at: '2026-09-28T12:00:00Z' }];
    g = { ...g, owner: { artistry: 9, raters: ['owner'], judgeArtistry: g.artistry }, artistry: 9 };
    score = galleryScore(g.adherence, 9)!;
    summary = `Artistry 9.0/10 (owner) · Brief followed ${g.followed}/${g.total}`;
  }
  if (image && n === 1 && PAINTERS.has(lite.contestantId)) {
    const votes: Record<string, number> = { 'meridian-canvas-2': 9, 'manual-orbit-chat': 11, 'helios-lumen-image': 6, 'obsidian-prism-image': 3, 'random-baseline': 1 };
    const v = votes[lite.contestantId] ?? 0;
    humanScores = [{ rater: 'Blind vote', score: v / 11, at: '2026-09-28T12:30:00Z', note: `${v} of 30 votes` }];
    g = { ...g, votes: { votes: v, of: 30, share: Math.round((v / 30) * 1000) / 1000 } };
  }
  return {
    ...lite,
    status: 'ok',
    score,
    passed: score >= 0.7,
    summary,
    scoreDetail: { gallery: g, judgeDisagreement: g.disagreement || undefined, ...(g.owner ? { humanScored: true } : {}) },
    metrics,
    artifacts,
    humanScores,
    error: undefined,
    hasReplay: false,
  };
}

/** Full result for the inspector: the painting call and each judge's reply in the transcript. */
export function galleryDetail(full: CaseResult): CaseResult {
  const g = full.scoreDetail?.gallery as GalleryDetail | undefined;
  const b = BRIEFS.find((x) => x.n === Number(/^seed-(\d+)$/.exec(full.caseId)?.[1]));
  if (!b || full.status === 'skipped') return { ...full, transcript: [] };
  const usage = { inputTokens: 480, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
  const transcript: TranscriptEntry[] = [
    { label: 'painting', messages: [{ role: 'user', content: full.testId === CODE_TEST ? codePrompt(b) : imagePrompt(b) }], response: g?.painting ? `[picture · ${g.painting.width}×${g.painting.height} PNG]` : '[no picture returned]\nThis request may violate our content policy.', usage, ttftMs: null, totalMs: full.metrics.wallMs, stopReason: g?.painting ? 'end' : 'refusal', rawStopReason: g?.painting ? 'image' : 'refused:moderation_blocked', costUsd: full.metrics.costUsd, retries: 0 },
  ];
  for (const j of g?.judges ?? []) {
    const art = g!.criteria.map((c) => `"${c.id}": ${c.scores.find((s) => s.judgeId === j.judgeId)?.score ?? '—'}`).join(', ');
    transcript.push({ label: `gallery judge · ${j.judgeId.replace(/@judge$/, '')}`, judge: true, messages: [{ role: 'user', content: 'The attached image is a painting submitted for this commission: …', images: [{ name: 'painting.png', mediaType: 'image/png', width: g!.painting?.width, height: g!.painting?.height }] }], response: `{"artistry": {${art}}, "summary": ${JSON.stringify(j.summary)}}`, usage: { inputTokens: 3200, outputTokens: 900, reasoningTokens: 400, cachedInputTokens: 0, cacheWriteTokens: 0 }, ttftMs: 1200, totalMs: 9400, stopReason: 'end', rawStopReason: 'stop', costUsd: 0.0055, retries: 0 });
  }
  return { ...full, transcript, replay: undefined };
}

// ───────────────────────────── Manual Inbox ─────────────────────────────

const answered = new Set<string>();

/** A picture request waiting in the Manual Inbox (a chat app painting commission No. 2). */
export function galleryManualRequests(): ManualRequest[] {
  const id = 'gallery-demo-request';
  if (answered.has(id)) return [];
  const b = briefForSeed(2);
  const prompt = imagePrompt(b);
  return [
    {
      id,
      runId: 'run-2026-09-28-gallery-live',
      key: `manual-orbit-chat::${IMAGE_TEST}::seed-2::r0`,
      contestantId: 'manual-orbit-chat',
      contestantLabel: 'Orbit Chat (web)',
      testId: IMAGE_TEST,
      testName: 'The Gallery Masterpiece',
      caseId: 'seed-2',
      label: 'painting',
      messages: [{ role: 'user', content: prompt }],
      combinedPrompt: prompt,
      latestUserMessage: prompt,
      isContinuation: false,
      createdAt: new Date(Date.now() - 95_000).toISOString(),
      expects: 'image',
      aspectRatio: '3:2',
    },
  ];
}

export function answerGalleryRequest(id: string): { ok: true } {
  answered.add(id);
  return { ok: true };
}
