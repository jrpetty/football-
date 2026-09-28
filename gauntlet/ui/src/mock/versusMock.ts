/**
 * Mock Head to Head API (?mock=1): the real builder (src/versus/build.ts) run
 * over the demo runs' fixture results, so every screen works with no server.
 * "Every run" uses the September core run, like the mock leaderboard.
 */
import { ApiError } from '../api.ts';
import type { CaseResult, ContestantView } from '../types.ts';
import { buildVersus, buildVersusOptions, fighterOf, type VersusResult, type VersusTestInfo } from '../../../src/versus/build.ts';
import type { VersusData, VersusFighter, VersusOptions, VersusScope } from '../../../src/versus/types.ts';
import { CATEGORIES, CONTESTANTS, RUN_SPECS, TESTS, buildRun, detailFor } from './fixtures.ts';
import type { MockRun } from './fixtures.ts';

const COMBINED_RUN = 'run-2026-09-21-core';
const runs = new Map<string, MockRun>();
const details = new Map<string, CaseResult>();

function runOf(id: string): MockRun {
  let r = runs.get(id);
  if (!r) {
    const spec = RUN_SPECS.find((s) => s.id === id);
    if (!spec) throw new ApiError('Run not found', 404);
    r = buildRun(spec);
    runs.set(id, r);
  }
  return r;
}

function fighter(c: Pick<ContestantView, 'id' | 'label' | 'vendor' | 'color' | 'pricing' | 'contextWindow'> & { providerType?: string }): VersusFighter {
  return fighterOf(c, { baseline: c.providerType === 'mock', manual: c.providerType === 'manual' });
}

interface MockScope {
  scope: VersusScope;
  tests: VersusTestInfo[];
  fighters: VersusFighter[];
  run: MockRun;
}

function scopeOf(runId: string | null): MockScope {
  const run = runOf(runId || COMBINED_RUN);
  const hook = new Map(TESTS.map((t) => [t.id, t.hook]));
  return {
    scope: runId ? { kind: 'run', runId, runName: run.manifest.name || runId } : { kind: 'combined' },
    tests: run.manifest.tests.map((t) => ({ id: t.id, name: t.name, category: t.category, hook: hook.get(t.id) })),
    fighters: run.manifest.contestants.map((c) => fighter({ ...c, providerType: CONTESTANTS.find((x) => x.id === c.id)?.providerType })),
    run,
  };
}

/** Full results (with transcripts) for two models. */
function resultsFor(run: MockRun, ids: string[]): VersusResult[] {
  return run.results
    .filter((r) => ids.includes(r.contestantId))
    .map((lite) => {
      const k = `${lite.runId}|${lite.key}`;
      let full = details.get(k);
      if (!full) {
        full = detailFor(lite);
        details.set(k, full);
      }
      return full;
    });
}

export function handleVersus(method: string, parts: string[], q: URLSearchParams, body: unknown): VersusData | VersusOptions {
  const [sub] = parts;
  if (method === 'GET' && sub === 'options') {
    const s = scopeOf(q.get('run'));
    const lite = s.run.results as unknown as VersusResult[];
    const list = RUN_SPECS.filter((r) => r.status === 'completed' || r.status === 'running' || r.status === 'interrupted').map((r) => ({ id: r.id, name: r.name, createdAt: r.createdAt, contestantIds: r.contestantIds }));
    return buildVersusOptions(s.scope, s.fighters, s.tests, lite, list);
  }
  if (method === 'POST' && sub === 'render') {
    void body;
    throw new ApiError('Demo mode: PNGs are rendered in your browser.', 409, { fallback: 'browser' });
  }
  if (method === 'GET' && !sub) {
    const a = q.get('a') ?? '';
    const b = q.get('b') ?? '';
    if (!a || !b) throw new ApiError('Pick two models: ?a=<model id>&b=<model id>', 400);
    if (a === b) throw new ApiError('Pick two different models.', 400);
    const s = scopeOf(q.get('run'));
    const fa = s.fighters.find((f) => f.id === a);
    const fb = s.fighters.find((f) => f.id === b);
    const where = q.get('run') ? 'in this run' : 'in your model list';
    if (!fa) throw new ApiError(`Model "${a}" is not ${where}`, 404);
    if (!fb) throw new ApiError(`Model "${b}" is not ${where}`, 404);
    return buildVersus({ scope: s.scope, a: fa, b: fb, tests: s.tests, categories: CATEGORIES, results: resultsFor(s.run, [a, b]) });
  }
  throw new ApiError(`Mock: no route for ${method} /api/versus/${parts.join('/')}`, 404);
}
