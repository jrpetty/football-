/**
 * Shortest Plans: the model's claimed minimum against the true optimum (big),
 * and the puzzle itself with one optimal plan animated step by step. The plan
 * is re-derived in the browser by exhaustive search and only shown when its
 * length equals the answer key.
 */
import { useEffect, useRef, useState } from 'react';
import type { CaseVisualInput } from '../../../../src/presenter/visuals/common.ts';
import { viewerText } from '../../../../src/presenter/visuals/common.ts';
import { planHeadline, planVisual, singularUnit, type PlanVisual } from '../../../../src/presenter/visuals/planning.ts';
import { cx } from '../ui.tsx';
import { PlanPuzzleScene } from './PlanPuzzleScene.tsx';
import { StepControls, useStepPlayer } from './StepScrubber.tsx';
import { VizFrame, type VizMode } from './VizFrame.tsx';

function PlanPlayer({ v, mode }: { v: PlanVisual; mode: VizMode }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const frames = v.frames!;
  const p = useStepPlayer(frames.length, rootRef, { baseMs: frames.length > 30 ? 650 : 1100, autoPlay: mode === 'slide' });
  const f = frames[p.idx]!;
  const last = p.idx === frames.length - 1;
  const unit1 = v.puzzle?.kind === 'crossing' ? (v.puzzle.vehicle === 'gondola' ? 'ride' : 'trip') : ['minutes', 'hours', 'dollars', 'fuel cells'].includes(v.unit) ? 'step' : singularUnit(v.unit);
  const progress = v.unit === 'minutes' ? `${f.total} of ${v.optimum} minutes used` : `${p.idx} of ${frames.length - 1} ${v.unit}`;
  return (
    <div className={cx('vz-plan-player', p.isFs && 'is-fs')} ref={rootRef} tabIndex={0} onKeyDown={p.onKeyDown} aria-label="Optimal plan, step by step">
      <div className="vz-plan-narr" aria-live="polite">
        <span className={cx('vz-plan-step', last && 'done')}>{p.idx === 0 ? 'Start' : last ? 'Solved' : `${unit1} ${p.idx}`}</span>
        <span className="vz-plan-act">{f.action ?? 'The puzzle as given. Press play to watch one optimal plan.'}</span>
        <span className="vz-plan-total tnum">{progress}</span>
      </div>
      <div className="vz-scene">
        <PlanPuzzleScene puzzle={v.puzzle!} frames={frames} idx={p.idx} />
      </div>
      {mode === 'slide' ? (
        <div className="vz-plan-bar" aria-hidden="true">
          <i style={{ width: `${(p.idx / Math.max(1, frames.length - 1)) * 100}%` }} />
        </div>
      ) : (
        <StepControls p={p} onFullscreen={p.toggleFs} tones={frames.map((_, i) => (i === frames.length - 1 ? 'good' : null))} />
      )}
      {mode === 'inspector' && (
        <div className="replay-hint no-broadcast">
          <kbd>Space</kbd> play/pause <kbd>←</kbd>
          <kbd>→</kbd> step <kbd>F</kbd> full screen
        </div>
      )}
    </div>
  );
}

function PuzzleText({ text }: { text: string }) {
  return <div className="vz-puzzle-text">{viewerText(text)}</div>;
}

export function ShortestPlanVisual({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const [v, setV] = useState<PlanVisual | null | undefined>(undefined);
  useEffect(() => {
    // Exhaustive search can take a moment on the largest puzzles: keep it off the first paint.
    let alive = true;
    const t = window.setTimeout(() => {
      let r: PlanVisual | null = null;
      try {
        r = planVisual(input);
      } catch {
        r = null;
      }
      if (alive) setV(r);
    }, 30);
    return () => {
      alive = false;
      window.clearTimeout(t);
    };
  }, [input]);

  if (v === undefined) return <div className="vz vz-loading">Re-solving the puzzle…</div>;
  if (!v) return null;
  const d = v.claimed === null ? null : v.claimed - v.optimum;
  const tone = d === 0 ? 'good' : 'bad';
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={`${v.title} · shortest plan`}
      headline={planHeadline(v)}
      legend={[
        { tone: 'good', label: 'True minimum (answer key)' },
        { tone: 'bad', label: 'Model’s claim, when it is not the minimum' },
        ...(v.frames ? [{ tone: 'count', label: 'Piece moved in this step' }] : []),
      ]}
    >
      <div className="vz-vs">
        <div className={cx('vz-vs-card', d === 0 ? 'good' : 'bad')}>
          <span>Model said</span>
          <b className="tnum">{v.claimed ?? '—'}</b>
          <small>{v.claimed === null ? (v.claimedText ? `“${v.claimedText.slice(0, 40)}”` : 'no answer') : v.unit}</small>
        </div>
        <div className="vz-vs-mid" aria-hidden="true">
          {d === null ? '?' : d === 0 ? '=' : d > 0 ? '>' : '<'}
        </div>
        <div className="vz-vs-card good">
          <span>True minimum</span>
          <b className="tnum">{v.optimum}</b>
          <small>{v.unit}</small>
        </div>
        <p className="vz-vs-rule">
          {d === null
            ? 'No number, no points.'
            : d === 0
              ? 'Exactly the minimum: full marks.'
              : d > 0
                ? `${d === 1 ? `One ${singularUnit(v.unit)}` : `${d} ${v.unit}`} over the minimum scores zero. Only the exact minimum counts.`
                : `No plan that short exists: the minimum is ${v.optimum}. Scores zero.`}
        </p>
      </div>
      {v.frames && v.puzzle ? (
        <PlanPlayer v={v} mode={mode} />
      ) : (
        <div className="vz-plan-fallback">
          <PuzzleText text={input.turns[0] ?? ''} />
          {v.notesPlan ? (
            <div>
              <div className="mini-title">One optimal plan (from the answer key’s notes)</div>
              {v.notesLegend && <p className="vz-hint">Key: {v.notesLegend}</p>}
              <ol className="vz-plan-chips">
                {v.notesPlan.map((s, i) => (
                  <li key={i}>
                    <i className="tnum">{i + 1}</i>
                    {s}
                  </li>
                ))}
              </ol>
            </div>
          ) : (
            <div className="vz-missing">A step-by-step drawing is not available for this puzzle type.</div>
          )}
        </div>
      )}
    </VizFrame>
  );
}
