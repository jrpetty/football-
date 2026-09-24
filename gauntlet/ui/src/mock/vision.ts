/**
 * Mock-mode fixtures for vision tests: a slice of the real vision library (only cases whose images are bundled,
 * see ../vision.ts), a completed "Vision" run where one model has no image input (its picture cases are skipped),
 * and a running manual run so the Manual Inbox shows an image with Download / Copy buttons.
 */
import chart from '../../../tests/vision/read-the-chart.json';
import spot from '../../../tests/vision/spot-the-difference.json';
import hand from '../../../tests/vision/handwritten-maths.json';
import count from '../../../tests/vision/count-and-locate.json';
import type { CaseResultLite, ChatImage, ContestantView, Leaderboard, PromptTest, PromptTestCase, SuiteView, TestDefinition } from '../types.ts';
import { MOCK_TEST_IMAGES } from '../vision.ts';

/** Contestant in the demo roster that has no image input. */
export const MOCK_TEXT_ONLY = 'obsidian-sable-large';

function slice(def: unknown): PromptTest {
  const t = def as PromptTest;
  return { ...t, cases: t.cases.filter((c) => (c.images ?? []).every((ref) => `vision/${typeof ref === 'string' ? ref : ref.file}` in MOCK_TEST_IMAGES)) };
}

export const VISION_TESTS: PromptTest[] = [slice(chart), slice(spot), slice(hand), slice(count)];

export const VISION_SUITE: SuiteView = {
  id: 'vision',
  version: '1.0.0',
  name: 'Vision',
  description: 'Show the model a picture: charts, spot the difference, handwritten maths, count & locate. Models without image input are skipped, not scored as 0.',
  tests: VISION_TESTS.map((t) => ({ id: t.id })),
  repeats: 3,
  fingerprint: 'c0ffee5ee1a9',
  testCount: VISION_TESTS.length,
};

/** Image references of a case → ChatImage[] (paths relative to the tests folder). */
function caseImages(c: PromptTestCase | undefined, turn = 0): ChatImage[] {
  return (c?.images ?? [])
    .map((ref) => (typeof ref === 'string' ? { file: ref, turn: 0 } : { file: ref.file, turn: ref.turn ?? 0 }))
    .filter((r) => r.turn === turn)
    .map((r) => ({ name: r.file.split('/').pop() ?? r.file, mediaType: 'image/png' as const, path: `vision/${r.file}` }));
}

/** `images` for renderedOf(): { turn, file, path }. */
export function mockRenderedImages(t: TestDefinition, caseId: string): Array<{ turn: number; file: string; path: string }> | undefined {
  if (t.kind !== 'prompt') return undefined;
  const c = t.cases.find((x) => x.id === caseId);
  const refs = (c?.images ?? []).map((ref) => (typeof ref === 'string' ? { file: ref, turn: 0 } : { file: ref.file, turn: ref.turn ?? 0 }));
  return refs.length ? refs.map((r) => ({ ...r, path: `vision/${r.file}` })) : undefined;
}

/** `{ images }` spread into a user message of the given turn (transcripts, manual requests). */
export function mockTurnImages(t: TestDefinition | undefined, caseId: string, turn: number): { images?: ChatImage[] } {
  if (!t || t.kind !== 'prompt') return {};
  const imgs = caseImages(t.cases.find((x) => x.id === caseId), turn);
  return imgs.length ? { images: imgs } : {};
}

function isVisionCase(t: TestDefinition, caseId: string): boolean {
  return t.kind === 'prompt' && (t.cases.find((c) => c.id === caseId)?.images?.length ?? 0) > 0;
}

/** Turns a generated result into "skipped" when the contestant has no image input (same rule as the real runner). */
export function mockVisionSkip(lite: CaseResultLite, c: ContestantView, t: TestDefinition): CaseResultLite {
  const sees = c.vision ?? (c.providerType === 'mock' || c.providerType === 'manual' || c.id === 'random-baseline');
  if (sees || !isVisionCase(t, lite.caseId)) return lite;
  return {
    ...lite,
    status: 'skipped',
    score: null,
    passed: null,
    summary: 'Skipped — model has no image input',
    scoreDetail: { notes: 'This case shows the model an image. The model is not marked as accepting images, so the case was not sent and is left out of every mean.' },
    metrics: { ...lite.metrics, wallMs: 0, ttftMs: null, apiCalls: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0, judgeCostUsd: 0, outputTokensPerSec: null, responseChars: 0 },
    error: undefined,
  };
}

/** Adds the skipped counts the real aggregator reports. */
export function mockLeaderboardSkips(lb: Leaderboard, results: CaseResultLite[]): Leaderboard {
  for (const row of lb.rows) {
    const mine = results.filter((r) => r.contestantId === row.contestantId && r.status === 'skipped');
    if (!mine.length) continue;
    row.totals.skipped = mine.length;
    for (const [testId, agg] of Object.entries(row.tests)) {
      const n = mine.filter((r) => r.testId === testId).length;
      if (n) {
        agg.skipped = n;
        if (agg.n === 0) agg.summary = 'Skipped — model has no image input';
      }
    }
  }
  return lb;
}

/** Vision flags for the demo roster: every API model sees images except one text-only model. */
export function applyMockVisionFlags(contestants: ContestantView[]): void {
  for (const c of contestants) {
    if (c.providerType === 'manual' || c.id === 'random-baseline') continue;
    c.vision = c.id !== MOCK_TEXT_ONLY;
  }
}

export const VISION_RUN_SPECS = [
  {
    id: 'run-2026-09-22-vision',
    name: 'Vision · picture questions',
    status: 'completed' as const,
    contestantIds: ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-nova-3-pro', MOCK_TEXT_ONLY, 'helios-quill-flash', 'random-baseline'],
    testIds: VISION_TESTS.map((t) => t.id),
    repeats: 3,
    suiteId: 'vision',
    createdAt: '2026-09-22T14:05:00Z',
    notes: 'Sable Large has no image input: its picture cases are skipped, not scored as 0.',
  },
  {
    id: 'run-2026-09-24-vision-manual',
    name: 'Orbit Chat by hand · vision',
    status: 'running' as const,
    contestantIds: ['manual-orbit-chat'],
    testIds: VISION_TESTS.map((t) => t.id),
    repeats: 1,
    createdAt: '2026-09-24T08:10:00Z',
    completedFrac: 0,
    concurrency: 1,
    notes: 'Manual contestant: each picture is pasted into the chat app together with the prompt.',
  },
];
