/**
 * Presenter "What this test is" intro slide, shown before each test's results:
 * the explainer's hook as the headline, a real question from the test (answer
 * behind a click), the "how it's scored" strip, what a good score means, and —
 * only when this run recorded scores — how hard the test was for the models.
 */
import type { CategoryInfo, TestExplainer } from '../../types.ts';
import { cx } from '../ui.tsx';
import { ExplainIcon } from '../viz/ExplainIcon.tsx';
import { HowScoredStrip } from '../viz/HowScoredStrip.tsx';
import { ScoreScale, type ScoreMarker } from '../viz/ScoreScale.tsx';
import { DifficultyMeter, difficultyFromScores } from '../viz/DifficultyMeter.tsx';
import { SampleQuestion, useTestSample } from '../viz/SampleQuestion.tsx';
import '../../styles/explain.css';

export interface IntroSlideProps {
  testId: string;
  name: string;
  cat: CategoryInfo;
  /** 1-based position in the episode, and the episode's test count. */
  n: number;
  total: number;
  explainer: TestExplainer;
  /** Recorded scores (0..1) of the real models on this test in this run. */
  modelScores: Array<number | null | undefined>;
  /** Random Baseline score on this test in this run, when it took part. */
  randomScore: number | null;
}

/** Caption for the Presenter's "What you're seeing" strip. */
export function introCaption(p: Pick<IntroSlideProps, 'name' | 'n' | 'total' | 'modelScores'>): { text: string; fine: string } {
  const d = difficultyFromScores(p.modelScores);
  return {
    text: `Test ${p.n} of ${p.total}: what “${p.name}” asks the models, a real example, and how every answer is scored.`,
    fine: d
      ? `Difficulty = average score of the ${d.n} ${d.n === 1 ? 'model' : 'models'} on this run (below 40 hard, 70+ easy)`
      : 'Explainer written in plain English from the test’s real scoring rules',
  };
}

export function IntroSlide(p: IntroSlideProps) {
  const x = p.explainer;
  const sample = useTestSample(p.testId);
  const diff = difficultyFromScores(p.modelScores);
  const markers: ScoreMarker[] = typeof p.randomScore === 'number' ? [{ kind: 'random', value: p.randomScore, label: 'random guessing' }] : [];
  const hookLen = x.hook.length;
  // Long explainers get a denser type scale so the hook, the skill and the "why" all fit.
  const dense = hookLen + x.whatItTests.length + x.whyHard.length > 300;
  return (
    <div className="s-intro" style={{ ['--cc' as string]: p.cat.color }}>
      <div className="in-top">
        <span className="in-eyebrow">
          <span className="in-icon" aria-hidden="true">
            <ExplainIcon name={x.icon} />
          </span>
          What this test is
        </span>
        <span className="pcat" style={{ ['--cc' as string]: p.cat.color }}>
          <i />
          {p.cat.name}
        </span>
        <span className="in-count">
          Test {p.n} of {p.total}
        </span>
        <span className="in-name">{p.name}</span>
      </div>
      <div className="in-grid">
        <div className={cx('in-left', dense && 'dense')}>
          <h1 className={cx('in-hook', hookLen > 64 && 'long', hookLen > 84 && 'xlong')}>{x.hook}</h1>
          <p className="in-what">{x.whatItTests}</p>
          {x.whyHard && (
            <p className="in-why">
              <b>Why it’s hard for AI:</b> {x.whyHard}
            </p>
          )}
        </div>
        <div className="in-right">
          <SampleQuestion sample={sample.data} loading={sample.loading} size="slide" />
        </div>
      </div>
      <HowScoredStrip steps={x.howScored} size="slide" />
      <div className={cx('in-bottom', !diff && 'no-diff')}>
        <ScoreScale markers={markers} goodScore={x.goodScore} size="slide" title={null} />
        {diff && <DifficultyMeter d={diff} basis="on this run" size="slide" />}
      </div>
    </div>
  );
}
