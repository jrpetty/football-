/**
 * DEMO data (?mock=1) for the two difficulty-ladder suites, Frontier Ladders and Business Ladders.
 *
 * The thirteen ladder tests are still being built (config/ladders.json lists them as planned), so the tests here
 * are PLACEHOLDERS: ten levels named after the real ones, with no real questions. The heights are invented for
 * the invented demo models, with a believable spread (small models L1–L3, mid-sized L3–L5, top L5–L7, nobody
 * above L8), to show the layout. They say nothing about any real model. Every run note, banner and test
 * description says so. When a ladder lands, its real test replaces its placeholder (see fixtures.ts).
 */
import frontierSuiteJson from '../../../suites/frontier-ladders.json';
import businessSuiteJson from '../../../suites/business-ladders.json';
import { LADDERS } from '../ladders.ts';
import type { CaseResultLite, PromptTest, SuiteView, TestDefinition } from '../types.ts';
import type { RunSpec } from './fixtures.ts';
import { ladderLevel, type LadderRule } from '../../../src/core/ladders.ts';

export const LADDER_DEMO_FRONTIER_RUN = 'run-2026-10-20-frontier-ladders';
export const LADDER_DEMO_BUSINESS_RUN = 'run-2026-10-21-business-ladders';

const PLANNED = LADDERS.tests.filter((t) => t.planned);
/** The thirteen new ladders in config order (fixed, so a ladder landing never shifts the demo heights). */
const ORDER = LADDERS.tests.filter((t) => t.category !== 'horizon').map((t) => t.id);
const DEMO_NOTE = 'Demo placeholder: this difficulty ladder is still being built. The ten levels shown here are not the real questions.';

/** Placeholder tests for the planned ladders (the real tests replace them when they land). */
export const LADDER_DEMO_TESTS: PromptTest[] = PLANNED.map((s) => ({
  kind: 'prompt',
  id: s.id,
  version: '0.0.0-demo',
  name: s.name,
  category: s.category,
  difficulty: 'extreme',
  description: `Coming soon. ${s.grows} ${DEMO_NOTE}`,
  hook: s.grows,
  tags: ['difficulty-ladder', 'demo'],
  scorer: s.area === 'business' ? { type: 'json' } : { type: 'exact' },
  estimate: s.area === 'business' ? { inputTokens: 14000, outputTokens: 2500 } : { inputTokens: 1500, outputTokens: 30000 },
  cases: Array.from({ length: LADDERS.levels }, (_, i) => ({
    id: `L${String(i + 1).padStart(2, '0')}`,
    prompt: `Level ${i + 1} of ${LADDERS.levels} of ${s.name}: ${DEMO_NOTE}`,
    expected: 'demo',
    notes: DEMO_NOTE,
  })),
})) as unknown as PromptTest[];

const IDS = new Set(LADDER_DEMO_TESTS.map((t) => t.id));
const BENCH = LADDER_DEMO_TESTS.filter((t) => t.category !== 'work').map((t) => t.id);
const WORK = LADDER_DEMO_TESTS.filter((t) => t.category === 'work').map((t) => t.id);

const MODELS = ['kestrel-kite-reasoner', 'meridian-atlas-4-ultra', 'helios-nova-3-pro', 'obsidian-sable-large', 'meridian-atlas-4-mini', 'helios-quill-flash', 'random-baseline'];

export const LADDER_DEMO_RUN_SPECS: RunSpec[] = [
  {
    id: LADDER_DEMO_FRONTIER_RUN,
    name: 'Frontier Ladders · demo climb',
    status: 'completed',
    contestantIds: MODELS,
    testIds: BENCH,
    repeats: 3,
    suiteId: 'frontier-ladders',
    createdAt: '2026-10-20T09:00:00Z',
    notes: 'Demo data: invented heights for the invented demo models on placeholder levels. The five Frontier difficulty ladders are still being built. Open a cell for the ladder; the Presenter has a “How high can it climb?” slide per test.',
  },
  {
    id: LADDER_DEMO_BUSINESS_RUN,
    name: 'Business Ladders · demo climb',
    status: 'completed',
    contestantIds: MODELS,
    testIds: WORK,
    repeats: 3,
    suiteId: 'business-ladders',
    createdAt: '2026-10-21T09:00:00Z',
    notes: 'Demo data: invented heights for the invented demo models on placeholder levels. The eight Business difficulty ladders are still being built. A rung is passed at 90% of its marks.',
  },
];

const suiteView = (json: unknown, fp: string): SuiteView => {
  const s = json as Omit<SuiteView, 'fingerprint' | 'testCount'>;
  return { ...s, fingerprint: fp, testCount: s.tests.filter((t) => !t.planned).length, plannedCount: s.tests.filter((t) => t.planned).length };
};
export const LADDER_DEMO_SUITES: SuiteView[] = [suiteView(frontierSuiteJson, 'fl4dd3r5demo'), suiteView(businessSuiteJson, 'bl4dd3r5demo')];

/** Every ladder's rule for the demo, planned ones included (the server only measures built ladders). */
export const LADDER_DEMO_RULES = new Map<string, LadderRule>(LADDERS.tests.map((t) => [t.id, { passMark: t.passMark, levels: t.rungs?.length ?? LADDERS.levels }]));

/** Demo heights per model, in config order (benchmark: grid, truth, plans, maths, engineering; business: the eight work ladders). */
const HEIGHTS: Record<string, number[]> = {
  'kestrel-kite-reasoner': [7, 6, 5, 6, 5, 6, 7, 6, 5, 7, 6, 5, 6],
  'meridian-atlas-4-ultra': [6, 7, 5, 5, 6, 7, 6, 6, 6, 6, 7, 6, 5],
  'helios-nova-3-pro': [4, 5, 3, 4, 3, 5, 4, 4, 3, 5, 4, 4, 5],
  'obsidian-sable-large': [3, 4, 4, 3, 4, 4, 4, 3, 4, 4, 3, 5, 4],
  'meridian-atlas-4-mini': [2, 3, 1, 2, 2, 3, 2, 2, 2, 3, 2, 2, 3],
  'helios-quill-flash': [1, 2, 1, 1, 0, 2, 1, 2, 1, 2, 1, 1, 2],
};

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

export function isLadderDemoTest(t: TestDefinition | undefined): boolean {
  return !!t && IDS.has(t.id);
}

/** The demo score of one attempt: passes up to the height (a shaky top rung now and then), a near miss above, a rare lucky pass. */
function demoScore(contestantId: string, testId: string, level: number, repeat: number): number {
  const business = testId.startsWith('work.');
  const u = hash(`${contestantId}|${testId}|L${level}|${repeat}`);
  if (contestantId === 'random-baseline') return business ? Math.round(u * 0.12 * 100) / 100 : 0;
  const idx = ORDER.indexOf(testId);
  const h = HEIGHTS[contestantId]?.[idx] ?? 0;
  const per = hash(`${contestantId}|${testId}`);
  const shakyTop = per < 0.22 && h > 1;
  const lucky = per > 0.86 && h + 2 <= 8 ? h + 2 : 0;
  let pass: boolean;
  if (level < h) pass = u > 0.04 || repeat !== 1; // at most one slip below the top, never two
  else if (level === h) pass = shakyTop ? repeat !== 2 : true;
  else if (level === h + 1) pass = repeat === 0 && per > 0.55 && per < 0.75;
  else pass = level === lucky && repeat === 1;
  if (!business) return pass ? 1 : 0;
  // Partial credit: a pass is 95–100% of the marks (clear of the 90 line); a miss scores less the further above the height.
  if (pass) return Math.round((0.955 + u * 0.045) * 100) / 100;
  const gap = Math.max(1, level - h);
  return Math.max(0, Math.round((0.86 - gap * 0.12 - u * 0.1) * 100) / 100);
}

/** Replace the generic mock outcome of a placeholder ladder case with its demo score. */
export function decorateLadderDemo(lite: CaseResultLite, t: TestDefinition | undefined): CaseResultLite {
  if (!isLadderDemoTest(t)) return lite;
  const level = ladderLevel(lite.caseId);
  if (level === null) return lite;
  const score = demoScore(lite.contestantId, t!.id, level, lite.repeat);
  const passMark = LADDER_DEMO_RULES.get(t!.id)?.passMark ?? 1;
  const passed = passMark >= 1 ? score >= 0.999 : score >= passMark;
  return {
    ...lite,
    status: 'ok',
    score,
    passed,
    error: undefined,
    summary: passed ? `Demo: passed level ${level}` : score > 0 ? `Demo: ${Math.round(score * 100)} of 100 on level ${level}, below the pass mark` : `Demo: failed level ${level}`,
  };
}
