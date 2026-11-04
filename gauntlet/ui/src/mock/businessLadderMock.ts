/**
 * Demo mode (?mock=1) for the Business area: GET /api/business-ladder with clearly labelled DEMO scores.
 *
 * Every built Business test is used (tests/work/, loaded in areaTests.ts). The ladder is computed, with the
 * real rules (src/core/business-ladder.ts), from the demo Business run's per-case results (areaDemo.ts):
 * the mock's invented contestants with invented scores. They are never real results, and the view carries
 * `demo: true` so every page says so.
 */
import definitionJson from '../../../config/business-ladder.json';
import { buildBusinessLadderView, ladderTestInfo, type BusinessLadderView, type LadderDefinition, type ModelClusters } from '../../../src/core/business-ladder.ts';
import { explainerFor } from '../../../src/core/explainers.ts';
import type { CaseResultLite, TestDefinition } from '../types.ts';
import { WORK_TESTS } from './areaTests.ts';
import { CONTESTANTS } from './fixtures.ts';

const definition = definitionJson as unknown as LadderDefinition;

export function handleBusinessLadder(q: URLSearchParams, results: readonly CaseResultLite[]): BusinessLadderView {
  const byId = new Map<string, TestDefinition>(WORK_TESTS.map((t) => [t.id, t]));
  const tests = ladderTestInfo(
    definition,
    (id) => {
      const d = byId.get(id);
      return d ? { name: d.name, difficulty: d.difficulty, cases: d.kind === 'prompt' ? d.cases.length : d.seeds.length } : undefined;
    },
    explainerFor,
  );
  const clusters = new Map<string, ModelClusters>();
  for (const r of results) {
    if (!byId.has(r.testId) || typeof r.score !== 'number' || r.status === 'error' || r.status === 'cancelled') continue;
    const m = clusters.get(r.contestantId) ?? {};
    const t = (m[r.testId] ??= {});
    t[r.caseId] = [...(t[r.caseId] ?? []), r.score];
    clusters.set(r.contestantId, m);
  }
  const models = CONTESTANTS.filter((c) => clusters.has(c.id)).map((c) => ({ id: c.id, label: c.label, color: c.color, vendor: c.vendor, clusters: clusters.get(c.id)! }));
  const compare = (q.get('models') ?? '').split(',').filter(Boolean);
  return buildBusinessLadderView({ definition, suiteId: 'business', tests, models, compare, demo: true });
}
