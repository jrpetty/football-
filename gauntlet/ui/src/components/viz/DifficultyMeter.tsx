/**
 * "Hard / Medium / Easy for today's AI": the average recorded score of the real
 * models on a test, shown as a three-step meter with its basis spelled out.
 * Never drawn without recorded scores (callers pass null and it renders nothing).
 */
import { cx } from '../ui.tsx';
import '../../styles/explain.css';

export interface Difficulty {
  level: 'hard' | 'medium' | 'easy';
  label: string;
  /** Mean score 0..1. */
  mean: number;
  /** Number of models averaged. */
  n: number;
}

/** Below 40 = hard, 40–69 = medium, 70+ = easy (average of the models' scores out of 100). */
export function difficultyFromScores(scores: Array<number | null | undefined>): Difficulty | null {
  const xs = scores.filter((s): s is number => typeof s === 'number' && Number.isFinite(s));
  if (!xs.length) return null;
  const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
  const level = mean < 0.4 ? 'hard' : mean < 0.7 ? 'medium' : 'easy';
  return { level, label: level === 'hard' ? 'Hard' : level === 'medium' ? 'Medium' : 'Easy', mean, n: xs.length };
}

const STEPS: Array<Difficulty['level']> = ['easy', 'medium', 'hard'];

export function DifficultyMeter({ d, basis, size = 'md' }: { d: Difficulty | null; /** e.g. "on this run" */ basis: string; size?: 'md' | 'slide' }) {
  if (!d) return null;
  const at = STEPS.indexOf(d.level);
  return (
    <div className={cx('dmeter', `dmeter-${size}`, `lv-${d.level}`)} role="img" aria-label={`${d.label} for today’s AI: average ${Math.round(d.mean * 100)} out of 100 across ${d.n} models ${basis}`}>
      <div className="dm-head">
        <span className="dm-k">Difficulty</span>
        <span className="dm-v">
          <b>{d.label}</b> for today’s AI
        </span>
      </div>
      <div className="dm-bar" aria-hidden="true">
        {STEPS.map((s, i) => (
          <span key={s} className={cx('dm-seg', `s-${s}`, i <= at && 'on', i === at && 'cur')}>
            {s === 'easy' ? 'Easy' : s === 'medium' ? 'Medium' : 'Hard'}
          </span>
        ))}
      </div>
      <div className="dm-basis">
        Average score <b className="tnum">{Math.round(d.mean * 100)}</b>/100 across {d.n} {d.n === 1 ? 'model' : 'models'} {basis}
      </div>
    </div>
  );
}
