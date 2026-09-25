/**
 * Presenter "best moment" slide for the simulation tests (Survival Island,
 * The Escape Room, The Startup, The Liar's Table): the best-scoring model's
 * run frozen at its most telling step (a rescue, an escape, the moment a lie
 * breaks), drawn with the replay's purpose-built stage, next to that run's
 * finale card. Everything comes from the recorded replay.
 */
import { api } from '../../api.ts';
import { useAsync } from '../../hooks.ts';
import type { CaseResultLite } from '../../types.ts';
import { SimFinaleCard } from '../viz/SimFinaleCard.tsx';
import { SimStage, hasSimStage } from '../viz/SimStage.tsx';
import { bestMoment, simFinale } from '../viz/simStory.ts';

/** Programs that have a best-moment slide. */
export const SIM_PROGRAMS = new Set(['survival-island', 'escape-room', 'startup-sim', 'startup', 'liars-table']);

/** The case to feature: the best non-baseline score (ties: first listed contender), else any replay. */
export function pickSimCase(results: CaseResultLite[], testId: string, contenderOrder: string[], baselineIds: Set<string>): CaseResultLite | null {
  const mine = results.filter((r) => r.testId === testId && r.hasReplay && r.status === 'ok');
  if (!mine.length) return null;
  const rank = (r: CaseResultLite) => [baselineIds.has(r.contestantId) ? 1 : 0, -(r.score ?? -1), contenderOrder.indexOf(r.contestantId)] as const;
  return mine.slice().sort((a, b) => {
    const x = rank(a);
    const y = rank(b);
    return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
  })[0]!;
}

export function SimMomentSlide({ runId, pick, modelLabel, modelColor }: { runId: string; pick: CaseResultLite; modelLabel: string; modelColor?: string }) {
  const st = useAsync(() => api.result(runId, pick.key), [runId, pick.key]);
  const res = st.data;
  const replay = res?.replay;
  if (st.loading && !res) return <div className="sim-moment-empty">Loading the replay…</div>;
  if (!replay || !hasSimStage(replay)) return <div className="sim-moment-empty">This run has no recorded scene to show (it was recorded before the illustrated replays existed).</div>;
  const idx = bestMoment(replay);
  const finale = simFinale(replay);
  return (
    <div className="sim-moment">
      <div className="smm-head">
        <span className="smm-k">Best moment</span>
        <span className="smm-model">
          {modelColor && <i style={{ background: modelColor }} />}
          {modelLabel}
        </span>
        <span className="smm-seed mono">{res?.seed !== undefined ? `world #${res.seed}` : pick.caseId}</span>
        <span className="smm-step">
          step {idx + 1} of {replay.frames.length}
        </span>
      </div>
      <div className="smm-body">
        <div className="smm-stage">
          <SimStage replay={replay} idx={idx} video moveMs={0} model={modelLabel} score={null} hideFinale />
        </div>
        {finale && <SimFinaleCard finale={finale} score={res?.score ?? pick.score} className="smm-finale" />}
      </div>
    </div>
  );
}
