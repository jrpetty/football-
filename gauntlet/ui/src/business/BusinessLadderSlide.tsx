/**
 * Presenter slide "How far up the ladder?" for a run that includes Business Ladder tests: the six business
 * scenarios as ladders for the run's top two models, computed from the run's own recorded results
 * with the same rules as the Business Ladder page (src/core/business-ladder.ts).
 */
import definitionJson from '../../../config/business-ladder.json';
import { buildBusinessLadderView, ladderTestIds, type BusinessLadderView, type LadderDefinition, type ModelClusters } from '../../../src/core/business-ladder.ts';
import type { CaseResultLite } from '../types.ts';
import { ScenarioLadder } from './LadderParts.tsx';
import './business.css';

const definition = definitionJson as unknown as LadderDefinition;
const BUILT = new Set(definition.scenarios.flatMap((s) => s.tests.filter((t) => !t.planned).map((t) => t.id)));

export interface SlideContender {
  id: string;
  label: string;
  color: string;
  baseline: boolean;
}

/** True when the run recorded a score on any built Business Ladder test. */
export function runHasBusinessLadder(results: readonly CaseResultLite[]): boolean {
  return results.some((r) => BUILT.has(r.testId) && typeof r.score === 'number');
}

export function businessLadderFromRun(results: readonly CaseResultLite[], contenders: readonly SlideContender[], names: Record<string, string>, max = 2): BusinessLadderView {
  const clusters = new Map<string, ModelClusters>();
  for (const r of results) {
    if (!BUILT.has(r.testId) || typeof r.score !== 'number' || r.status === 'error' || r.status === 'cancelled' || r.reassignedTo) continue;
    const m = clusters.get(r.contestantId) ?? {};
    const t = (m[r.testId] ??= {});
    t[r.caseId] = [...(t[r.caseId] ?? []), r.score];
    clusters.set(r.contestantId, m);
  }
  const tests = Object.fromEntries(ladderTestIds(definition).map((id) => [id, { name: names[id] ?? id, exists: BUILT.has(id) }]));
  const all = contenders.filter((c) => !c.baseline && clusters.has(c.id)).map((c) => ({ id: c.id, label: c.label, color: c.color, clusters: clusters.get(c.id)! }));
  const view = buildBusinessLadderView({ definition, suiteId: 'run', tests, models: all });
  const rank = (id: string) => {
    const m = view.models.find((x) => x.id === id)!;
    return m.scenarios.reduce((s, x) => s + x.height * 10 + (x.levels[0]?.score ?? 0), 0);
  };
  const top = all.slice().sort((a, b) => rank(b.id) - rank(a.id)).slice(0, max);
  return buildBusinessLadderView({ definition, suiteId: 'run', tests, models: top, compare: top.length > 1 ? top.map((m) => m.id) : [] });
}

export function businessLadderHeadline(view: BusinessLadderView): string {
  if (!view.models.length) return 'Nobody has climbed yet';
  const total = (i: number) => view.models[i]!.scenarios.reduce((s, x) => s + x.height, 0);
  const best = view.models[0]!;
  const areas = best.scenarios.filter((s) => s.height > 0).length;
  if (total(0) === 0) return 'No model reached Level 1 yet';
  return `${best.label} reached Level 1 or higher in ${areas} of ${view.definition.scenarios.length} business areas`;
}

export function BusinessLadderSlide({ results, contenders, names }: { results: readonly CaseResultLite[]; contenders: readonly SlideContender[]; names: Record<string, string> }) {
  const view = businessLadderFromRun(results, contenders, names);
  return (
    <div className="s-bizladder biz-page" data-area="business">
      <div className="tr-top">
        <span className="pcat" style={{ ['--cc' as string]: 'var(--area-business)' }}>
          <i />
          Business
        </span>
        <span className="tr-test">How far up the ladder?</span>
        <span className="tr-k">{businessLadderHeadline(view)}</span>
      </div>
      <div className="biz-slide-grid">
        {view.definition.scenarios.map((s) => (
          <ScenarioLadder key={s.id} view={view} scenario={s} models={view.models} slide />
        ))}
      </div>
    </div>
  );
}
