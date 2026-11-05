/**
 * "See a level" on a ladder test's page: one button per level opens the Level close-up (question first, the
 * answer behind "Reveal", every model's result underneath). Deep-linkable: ?level=6&c=<model>&run=<run id>.
 */
import { navigate, useRoute } from '../../router.tsx';
import { ladderRuleOf, ladderSpecOf } from '../../ladders.ts';
import { LevelCloseUp } from './LevelCloseUp.tsx';
import './closeup/closeup.css';

export function LevelCloseUpLauncher({ testId }: { testId: string }) {
  const route = useRoute();
  const spec = ladderSpecOf(testId);
  if (!spec || spec.planned) return null;
  const levels = ladderRuleOf(testId).levels ?? 10;
  const lv = Number(route.query.get('level'));
  const open = Number.isInteger(lv) && lv >= 1 && lv <= levels ? lv : null;
  const set = (level: number | null) => {
    const q: Record<string, string | null> = {};
    for (const [k, v] of route.query) q[k] = v;
    q.level = level === null ? null : String(level);
    if (level === null) {
      q.c = null;
      q.run = null;
    }
    navigate(route.path, q, true);
  };
  return (
    <section className="card lc-launch" aria-label="See one level up close">
      <div className="lc-launch-t">
        <b>See a level up close</b>
        <span className="muted">The question as the model got it, the answer, and where each model went wrong.</span>
      </div>
      <div className="lc-launch-levels">
        {Array.from({ length: levels }, (_, i) => (
          <button key={i} type="button" className="btn sm" onClick={() => set(i + 1)} title={spec.rungs?.[i]}>
            L{i + 1}
          </button>
        ))}
      </div>
      {open !== null && <LevelCloseUp testId={testId} level={open} contestantId={route.query.get('c') ?? undefined} runId={route.query.get('run')} onClose={() => set(null)} mode={route.query.get('slide') === '1' ? 'slide' : 'panel'} />}
    </section>
  );
}
