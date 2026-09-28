/**
 * Plain-English test explainers in the app:
 *  - ExplainerCard: the "What this test is" card at the top of a test's page.
 *  - AboutTestPanel: the collapsible "About this test" panel in the result inspector.
 * The text comes from src/core/explainers.ts via GET /api/tests/:id (`explainer`).
 */
import { useEffect, useState } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import type { Leaderboard, SuiteView, TestExplainer } from '../types.ts';
import { cx } from './ui.tsx';
import { Icon } from './icons.tsx';
import { isBaseline } from './leaderboard/util.ts';
import { ExplainIcon } from './viz/ExplainIcon.tsx';
import { HowScoredStrip } from './viz/HowScoredStrip.tsx';
import { ScoreScale, type ScoreMarker } from './viz/ScoreScale.tsx';
import { DifficultyMeter, difficultyFromScores, type Difficulty } from './viz/DifficultyMeter.tsx';
import { SampleQuestion, useTestSample } from './viz/SampleQuestion.tsx';
import '../styles/explain.css';

/** Recorded reference points for a test from the first leaderboard (suite) that includes it. */
interface Recorded {
  suiteName: string;
  markers: ScoreMarker[];
  difficulty: Difficulty | null;
}

function suiteHas(s: SuiteView, testId: string): boolean {
  return s.tests.some((t) => t.id === testId);
}

async function recordedFor(testId: string): Promise<Recorded | null> {
  const suites = await api.suites();
  // Suites that list the test by name first; the catch-all "every test" suite last.
  const ordered = [...suites.filter((s) => suiteHas(s, testId)), ...suites.filter((s) => !suiteHas(s, testId) && s.tests.some((t) => t.id === '*'))];
  for (const s of ordered) {
    let lb: Leaderboard;
    try {
      lb = await api.leaderboard(s.id);
    } catch {
      continue;
    }
    const rows = lb.rows.filter((r) => typeof r.tests?.[testId]?.score === 'number');
    if (!rows.length) continue;
    const models = rows.filter((r) => !isBaseline(r));
    const base = rows.find((r) => isBaseline(r));
    const scores = models.map((r) => r.tests[testId]!.score as number);
    const markers: ScoreMarker[] = [];
    if (scores.length) {
      const best = models.reduce((a, b) => ((b.tests[testId]!.score as number) > (a.tests[testId]!.score as number) ? b : a));
      markers.push({ kind: 'best', value: best.tests[testId]!.score as number, label: `best: ${best.label}` });
      if (scores.length > 1) markers.push({ kind: 'average', value: scores.reduce((a, b) => a + b, 0) / scores.length, label: 'average' });
    }
    if (base) markers.push({ kind: 'random', value: base.tests[testId]!.score as number, label: 'random guessing' });
    return { suiteName: s.name, markers, difficulty: difficultyFromScores(scores) };
  }
  return null;
}

export function ExplainerCard({ testId, explainer }: { testId: string; explainer: TestExplainer | undefined }) {
  const sample = useTestSample(explainer ? testId : null);
  const recorded = useAsync(() => (explainer ? recordedFor(testId).catch(() => null) : Promise.resolve(null)), [testId, !!explainer]);
  if (!explainer) return null;
  const rec = recorded.data ?? null;
  return (
    <section className={cx('card xcard', explainer.generated && 'is-generated')} aria-label="What this test is">
      <div className="xc-head">
        <span className="xc-icon" aria-hidden="true">
          <ExplainIcon name={explainer.icon} />
        </span>
        <div className="xc-head-t">
          <div className="xc-eyebrow">What this test is</div>
          <h2 className={cx('xc-hook', explainer.hook.length > 70 && 'long')}>{explainer.hook}</h2>
        </div>
      </div>
      <div className="xc-grid">
        <div className="xc-left">
          <div className="xc-facts">
            <div>
              <h3>What it tests</h3>
              <p>{explainer.whatItTests}</p>
            </div>
            {explainer.whyHard && (
              <div>
                <h3>Why it’s hard for AI</h3>
                <p>{explainer.whyHard}</p>
              </div>
            )}
          </div>
          <HowScoredStrip steps={explainer.howScored} />
          <div className="xc-scale">
            <ScoreScale markers={rec?.markers ?? []} goodScore={explainer.goodScore} />
            {rec && rec.markers.length > 0 && <div className="xc-basis">Recorded on the {rec.suiteName} leaderboard.</div>}
          </div>
          {rec?.difficulty && <DifficultyMeter d={rec.difficulty} basis={`on the ${rec.suiteName} leaderboard`} />}
          {explainer.generated && <p className="xc-gen muted no-broadcast">Built from the test’s own description. Write a proper explainer in src/core/explainers.ts (see docs/ADDING_TESTS.md).</p>}
        </div>
        <div className="xc-right">
          <SampleQuestion sample={sample.data} loading={sample.loading} />
          {sample.error && <p className="muted">Sample not available: {sample.error.message}</p>}
        </div>
      </div>
    </section>
  );
}

const OPEN_KEY = 'gauntlet.aboutTest.open';

function readOpen(): boolean {
  try {
    return window.localStorage.getItem(OPEN_KEY) === '1';
  } catch {
    return false;
  }
}

let explainersOnce: Promise<Record<string, TestExplainer>> | null = null;

/** The explainer of a test or Arena game ("arena.<game>"): the shared list first, then the test itself (custom tests). */
async function loadExplainer(id: string): Promise<TestExplainer | null> {
  explainersOnce ??= api
    .explainers()
    .then((r) => r.explainers)
    .catch(() => {
      explainersOnce = null;
      return {};
    });
  const all = await explainersOnce;
  if (all[id]) return all[id]!;
  if (id.startsWith('arena.')) return null;
  return (await api.test(id).catch(() => null))?.explainer ?? null;
}

/**
 * Collapsible "About this test" panel (result inspector, Arena tournament page), with the model's mean
 * score on the 0–100 scale when one is given.
 */
export function AboutTestPanel({
  testId,
  model,
  randomScore,
  label = 'About this test',
}: {
  testId: string;
  model?: { label: string; color?: string; score: number | null };
  randomScore?: number | null;
  label?: string;
}) {
  const detail = useAsync(async () => ({ explainer: await loadExplainer(testId) }), [testId]);
  const [open, setOpen] = useState(readOpen);
  useEffect(() => {
    try {
      window.localStorage.setItem(OPEN_KEY, open ? '1' : '0');
    } catch {
      /* storage unavailable: the panel still works */
    }
  }, [open]);
  const x = detail.data?.explainer;
  if (!x) return null;
  return (
    <div className={cx('about-test', open && 'open')}>
      <button type="button" className="at-sum" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className="at-icon" aria-hidden="true">
          <ExplainIcon name={x.icon} />
        </span>
        <span className="at-k">{label}</span>
        <span className="at-hook">{x.hook}</span>
        <Icon.ChevronDown className="at-chev" />
      </button>
      {open && (
        <div className="at-body">
          <p className="at-what">{x.whatItTests}</p>
          <HowScoredStrip steps={x.howScored} size="sm" />
          {testId.startsWith('arena.') ? (
            // Arena games are won on points or chips, not scored 0–100.
            <p className="at-good">
              <b>What counts as good:</b> {x.goodScore}
            </p>
          ) : (
            <ScoreScale
              markers={[
                ...(model && typeof model.score === 'number' ? [{ kind: 'model' as const, value: model.score, label: model.label, color: model.color }] : []),
                ...(typeof randomScore === 'number' ? [{ kind: 'random' as const, value: randomScore, label: 'random guessing' }] : []),
              ]}
              goodScore={x.goodScore}
            />
          )}
        </div>
      )}
    </div>
  );
}
