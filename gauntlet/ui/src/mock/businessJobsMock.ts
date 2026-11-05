/**
 * Demo mode (?mock=1) for "Which model for this job?" (GET /api/business-jobs): the real jobs config and the real
 * rules (src/core/business-jobs.ts) over the two Business demo runs: the Business Ladder demo run (every work.*
 * test, areaDemo.ts) and the Business Ladders demo run (the eight ten-rung ladders, laddersMock.ts).
 *
 * Scores, costs and times are the demo runs' own (invented, never real results). "What goes wrong" is read from
 * the same demo answers the Result Inspector and the close-up show for each case (detailFor, and for the ladders
 * the close-up's scripted replies marked by the real JSON scorer), so the mistakes listed always match the case
 * a viewer opens. The view carries `demo: true`, so the page says "Demo data — not real results".
 */
import configJson from '../../../config/business-jobs.json';
import { buildBusinessJobsView, type BusinessJobsConfig, type BusinessJobsView, type JobAnswerIn, type JobTestInfo } from '../../../src/core/business-jobs.ts';
import type { CaseResult, CaseResultLite, TestDefinition } from '../types.ts';
import { WORK_TESTS } from './areaTests.ts';
import { LADDER_DEMO_TESTS } from './laddersMock.ts';
import { CONTESTANTS } from './fixtures.ts';

const config = configJson as unknown as BusinessJobsConfig;

let cached: { sig: string; view: Promise<BusinessJobsView> } | null = null;

export function handleBusinessJobs(results: readonly CaseResultLite[], full: (lite: CaseResultLite) => CaseResult | Promise<CaseResult>): Promise<BusinessJobsView> {
  const sig = `${results.length}|${results[0]?.key ?? ''}`;
  if (cached?.sig === sig) return cached.view;
  const view = build(results, full);
  cached = { sig, view };
  view.catch(() => (cached = null));
  return view;
}

async function build(results: readonly CaseResultLite[], full: (lite: CaseResultLite) => CaseResult | Promise<CaseResult>): Promise<BusinessJobsView> {
  const defs = new Map<string, TestDefinition>([...(LADDER_DEMO_TESTS as unknown as TestDefinition[]), ...WORK_TESTS].map((t) => [t.id, t]));
  const tests: Record<string, JobTestInfo> = {};
  for (const id of new Set(config.jobs.flatMap((j) => j.tests))) {
    const d = defs.get(id);
    tests[id] = d ? { name: d.name, exists: true, kind: d.kind, cases: d.kind === 'prompt' ? d.cases.length : d.seeds.length } : { name: id, exists: false };
  }
  const wanted = results.filter((r) => tests[r.testId] && typeof r.score === 'number');
  const answers: JobAnswerIn[] = await Promise.all(
    wanted.map(async (lite) => {
      const res = await Promise.resolve(full(lite)).catch(() => null);
      // The demo run's score (the one every Business page shows); the marks behind it from the full answer.
      return { ...lite, scoreDetail: res?.scoreDetail ?? lite.scoreDetail };
    }),
  );
  const baseline = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);
  const models = CONTESTANTS.filter((c) => !baseline(c.id)).map((c) => ({ id: c.id, label: c.label, color: c.color, vendor: c.vendor }));
  return buildBusinessJobsView({ config, tests, models, results: answers, demo: true });
}
