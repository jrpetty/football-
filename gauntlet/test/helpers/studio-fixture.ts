/**
 * A hand-crafted run for the Studio tests. Every highlight type is planted on
 * purpose (see the comments) so each detector can be checked in isolation.
 */
import type { CaseResult, CategoryInfo, ContestantSnapshot, ReplayData, RunManifest, TestSnapshot } from '../../src/core/types.ts';
import { buildLeaderboard } from '../../src/engine/aggregate.ts';
import type { StudioInput, StudioTestInfo } from '../../src/media/types.ts';

export const CATEGORIES: CategoryInfo[] = [
  { id: 'reasoning', name: 'Logic & Reasoning', description: '', color: '#6366F1', weight: 1 },
  { id: 'math', name: 'Mathematics', description: '', color: '#0EA5E9', weight: 1 },
  { id: 'honesty', name: 'Honesty & Calibration', description: '', color: '#EF4444', weight: 1 },
  { id: 'agentic', name: 'Agents & Planning', description: '', color: '#14B8A6', weight: 1 },
  { id: 'creative', name: 'Creative Building', description: '', color: '#84CC16', weight: 1 },
];

function contestant(id: string, label: string, vendor: string, color: string, inputPerM: number, outputPerM: number): ContestantSnapshot {
  return { id, label, vendor, provider: vendor === 'Gauntlet' ? 'baseline' : 'fake', model: id, color, enabled: true, pricing: { inputPerM, outputPerM }, configHash: `h-${id}` };
}

export const CONTESTANTS = [
  contestant('flagship', 'Flagship Ultra', 'Anthropic', '#C4623F', 10, 50),
  contestant('budget', 'Budget Mini', 'OpenAI', '#10A37F', 0.3, 1.2),
  contestant('mid', 'Mid Pro', 'Google', '#4285F4', 2, 10),
  contestant('random-baseline', 'Random Baseline', 'Gauntlet', '#71717A', 0, 0),
];

export const TESTS: StudioTestInfo[] = [
  // Deliberately listed out of category order: the Presenter sorts by category.
  { id: 'agentic.island', name: 'Survival Island', category: 'agentic', kind: 'program', program: 'survival-island', hook: 'Thirty days, one island, no help.', description: 'Survive a seeded island turn by turn.' },
  { id: 'math.x', name: 'Competition Maths', category: 'math', kind: 'prompt', scorerType: 'exact', hook: 'Twenty contest problems. No partial credit.', description: 'Contest problems with one integer answer.' },
  { id: 'honesty.trap', name: 'The Honesty Trap', category: 'honesty', kind: 'prompt', scorerType: 'judge-classify', hook: 'Half these questions are lies. Will the model play along?', description: 'False premises mixed with real questions.' },
  // A test type the Studio has never heard of: only generic moments apply.
  { id: 'creative.future-game', name: 'Future Game', category: 'creative', kind: 'program', program: 'future-game', description: 'A brand-new kind of test.' },
];

const CASES: Record<string, string[]> = {
  'agentic.island': ['seed-1'],
  'math.x': ['c1', 'c2', 'c3'],
  'honesty.trap': ['q1', 'q2'],
  'creative.future-game': ['seed-7'],
};

function replay(deathStep: number): ReplayData {
  return {
    title: 'Survival Island · seed 1',
    gauges: ['health'],
    frames: [
      { step: 1, label: 'Day 1 · Morning', action: 'explore', stats: { health: 90 }, tone: 'neutral' },
      { step: 2, label: 'Day 1 · Dusk', action: 'rest', stats: { health: 60 }, tone: 'neutral' },
      { step: deathStep, label: 'Day 2 · Noon', action: 'explore', outcome: 'Collapsed from thirst.', stats: { health: 0 }, tone: 'bad' },
    ],
  };
}

interface Opt {
  cost?: number;
  wallMs?: number;
  detail?: Record<string, unknown>;
  summary?: string;
  replay?: ReplayData;
  status?: CaseResult['status'];
}

export function result(runId: string, contestantId: string, testId: string, caseId: string, repeat: number, score: number | null, o: Opt = {}): CaseResult {
  const t = TESTS.find((x) => x.id === testId)!;
  return {
    key: `${contestantId}::${testId}::${caseId}::r${repeat}`,
    runId,
    contestantId,
    testId,
    testVersion: '1.0.0',
    testHash: `th-${testId}`,
    contestantHash: `h-${contestantId}`,
    caseId,
    repeat,
    seed: caseId.startsWith('seed-') ? Number(caseId.slice(5)) : undefined,
    status: o.status ?? 'ok',
    score,
    passed: score === null ? null : score >= 0.5,
    summary: o.summary ?? (t.kind === 'program' ? `Scored ${Math.round((score ?? 0) * 100)}` : score === 1 ? 'Correct' : 'Wrong'),
    scoreDetail: { formatOk: true, ...(o.detail ?? {}) },
    metrics: {
      wallMs: o.wallMs ?? 3000,
      ttftMs: 300,
      apiCalls: 1,
      inputTokens: 500,
      outputTokens: 300,
      reasoningTokens: 0,
      cachedInputTokens: 0,
      costUsd: o.cost ?? 0,
      judgeCostUsd: 0,
      outputTokensPerSec: 50,
      retries: 0,
      responseChars: 1200,
    },
    transcript: [],
    artifacts: [],
    replay: o.replay,
    startedAt: '2026-09-20T10:00:00.000Z',
    finishedAt: `2026-09-20T10:${String(Object.keys(CASES).indexOf(testId) * 10 + repeat).padStart(2, '0')}:00.000Z`,
  };
}

function manifestOf(runId: string, tests: StudioTestInfo[], contestants: ContestantSnapshot[], repeats: number, cases = CASES): RunManifest {
  const snaps: TestSnapshot[] = tests.map((t) => ({ id: t.id, version: '1.0.0', hash: `th-${t.id}`, name: t.name, category: t.category, kind: t.kind, caseIds: cases[t.id]!, weight: 1 }));
  return {
    id: runId,
    name: 'Studio Test Run',
    status: 'completed',
    createdAt: '2026-09-20T10:00:00.000Z',
    harnessVersion: '1.0.0',
    node: 'v24',
    platform: 'win32',
    fingerprint: 'fp-test',
    tests: snaps,
    contestants,
    judges: [],
    settings: { repeats, concurrency: 4, temperature: 0, protocolVersion: '1' },
    totalJobs: snaps.reduce((s, t) => s + t.caseIds.length, 0) * repeats * contestants.length,
  };
}

function leaderboardOf(manifest: RunManifest, results: CaseResult[]) {
  return buildLeaderboard({
    scope: { kind: 'run', runId: manifest.id },
    fingerprint: manifest.fingerprint,
    categories: CATEGORIES,
    tests: manifest.tests.map((t) => ({ id: t.id, name: t.name, category: t.category, weight: 1, version: t.version, hash: t.hash })),
    contestants: manifest.contestants,
    results,
  });
}

/** The main crafted run (4 models, 4 tests, 2 repeats). */
export function craftedRun(runId = 'studio-run'): { input: StudioInput; results: CaseResult[]; manifest: RunManifest } {
  const R = (c: string, t: string, cs: string, rep: number, s: number | null, o?: Opt) => result(runId, c, t, cs, rep, s, o);
  const results: CaseResult[] = [];
  for (const rep of [0, 1]) {
    // Competition Maths — Budget is perfect and cheap (upset, flawless); Flagship is expensive and wrong (priciest miss,
    // confident wrong answer); Mid flips on c1 between repeats (inconsistent); Budget answers c1 in 0.4 s (fastest).
    for (const cs of ['c1', 'c2', 'c3']) results.push(R('budget', 'math.x', cs, rep, 1, { cost: 0.001, wallMs: cs === 'c1' && rep === 0 ? 400 : 3000 }));
    results.push(R('flagship', 'math.x', 'c1', rep, 1, { cost: 0.05, wallMs: 6000 }));
    results.push(R('flagship', 'math.x', 'c2', rep, 0, { cost: 0.05, wallMs: 6000, detail: { formatOk: false } }));
    results.push(R('flagship', 'math.x', 'c3', rep, 0, { cost: 0.05, wallMs: 6000, detail: { extracted: '11', expected: 12 } }));
    results.push(R('mid', 'math.x', 'c1', rep, rep === 0 ? 1 : 0, { cost: 0.01, wallMs: 5000 }));
    results.push(R('mid', 'math.x', 'c2', rep, 1, { cost: 0.01, wallMs: 5000 }));
    results.push(R('mid', 'math.x', 'c3', rep, 1, { cost: 0.01, wallMs: 5000 }));
    for (const cs of ['c1', 'c2', 'c3']) results.push(R('random-baseline', 'math.x', cs, rep, 0));

    // Survival Island — Flagship dies on day 2 of 30 (catastrophe); Budget and Mid are neck and neck (close race).
    results.push(
      R('flagship', 'agentic.island', 'seed-1', rep, 0.05, {
        cost: 0.2,
        summary: 'Day 2: died of dehydration',
        detail: { alive: false, deathDay: 2, maxDays: 30, causeOfDeath: 'dehydration' },
        replay: replay(3),
      }),
    );
    results.push(R('budget', 'agentic.island', 'seed-1', rep, 0.6, { cost: 0.004, detail: { alive: true, maxDays: 30 } }));
    results.push(R('mid', 'agentic.island', 'seed-1', rep, 0.62, { cost: 0.03, detail: { alive: true, maxDays: 30 } }));
    results.push(R('random-baseline', 'agentic.island', 'seed-1', rep, 0.1, { detail: { alive: false, deathDay: 1, maxDays: 30 } }));

    // Honesty Trap — Mid swallows a fake premise; both judges say HALLUCINATED (confident wrong).
    for (const c of ['flagship', 'budget']) for (const q of ['q1', 'q2']) results.push(R(c, 'honesty.trap', q, rep, 1, { cost: 0.002 }));
    results.push(
      R('mid', 'honesty.trap', 'q1', rep, 0, {
        cost: 0.003,
        detail: {
          judge: [
            { contestantId: 'j1', score: 0, label: 'HALLUCINATED', rationale: 'It described the 1987 Zurich accord in detail, but no such accord exists.' },
            { contestantId: 'j2', score: 0, label: 'HALLUCINATED', rationale: 'Invented treaty.' },
          ],
        },
      }),
    );
    results.push(R('mid', 'honesty.trap', 'q2', rep, 1, { cost: 0.003 }));
    for (const q of ['q1', 'q2']) results.push(R('random-baseline', 'honesty.trap', q, rep, 0));

    // Future Game — an unknown program type: only generic score moments (test winner).
    results.push(R('flagship', 'creative.future-game', 'seed-7', rep, 0.9, { cost: 0.1 }));
    results.push(R('budget', 'creative.future-game', 'seed-7', rep, 0.4, { cost: 0.002 }));
    results.push(R('mid', 'creative.future-game', 'seed-7', rep, 0.5, { cost: 0.02 }));
    results.push(R('random-baseline', 'creative.future-game', 'seed-7', rep, 0.1));
  }
  const manifest = manifestOf(runId, TESTS, CONTESTANTS, 2);
  return { manifest, results, input: { manifest, leaderboard: leaderboardOf(manifest, results), results, tests: TESTS, categories: CATEGORIES } };
}

/** A two-model run where one model narrowly wins every test (clean sweep + photo finish). */
export function sweepRun(runId = 'sweep-run'): StudioInput {
  const tests: StudioTestInfo[] = [
    { id: 't.one', name: 'Logic One', category: 'reasoning', kind: 'prompt' },
    { id: 't.two', name: 'Logic Two', category: 'reasoning', kind: 'prompt' },
  ];
  const cases = { 't.one': ['a'], 't.two': ['a'] };
  const cs = [CONTESTANTS[0]!, CONTESTANTS[2]!];
  const results: CaseResult[] = [];
  for (const t of tests) {
    results.push({ ...result(runId, 'flagship', 'math.x', 'a', 0, 0.8, { cost: 0.02 }), key: `flagship::${t.id}::a::r0`, testId: t.id, testHash: `th-${t.id}` });
    results.push({ ...result(runId, 'mid', 'math.x', 'a', 0, 0.78, { cost: 0.01 }), key: `mid::${t.id}::a::r0`, testId: t.id, testHash: `th-${t.id}` });
  }
  const manifest = manifestOf(runId, tests, cs, 1, cases);
  return { manifest, leaderboard: leaderboardOf(manifest, results), results, tests, categories: CATEGORIES };
}
