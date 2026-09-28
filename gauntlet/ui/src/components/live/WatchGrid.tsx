/**
 * "Watch it think": one tile per model with the answer typing in live, the
 * case it is on in plain English, a clock, a token counter and a cost ticker.
 * When a case is graded the tile flashes the verdict, then moves on.
 */
import { useLayoutEffect, useRef } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { fmtClock, fmtCost, fmtInt } from '../../format.ts';
import { caseLong, type CommentaryContext } from './commentary.ts';
import { gridCols, splitTail, tileView, type WatchState, type WatchTile, type WatchVerdict } from './watchModel.ts';
import '../../styles/live-watch.css';

const HALF = (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" strokeWidth="2.6" />
    <path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor" />
  </svg>
);

/** Verdict stamp: the recorded status and score in words, with the matching colour token. */
export function verdictInfo(v: Pick<WatchVerdict, 'status' | 'score'>, program = false): { tone: 'good' | 'bad' | 'partial' | 'neutral'; mark: ReactNode; word: string; score: string | null } {
  const X = <Icon.X />;
  if (v.status === 'timeout') return { tone: 'bad', mark: <Icon.Clock />, word: 'Out of time', score: '0' };
  if (v.status === 'error') return { tone: 'neutral', mark: <Icon.Alert />, word: 'Error · not counted', score: null };
  if (v.status === 'refusal') return { tone: 'bad', mark: X, word: 'Refused', score: '0' };
  if (v.status === 'skipped') return { tone: 'neutral', mark: <Icon.EyeOff />, word: 'Skipped · not scored', score: null };
  if (v.status === 'pending-human') return { tone: 'neutral', mark: <Icon.Eye />, word: 'Sent to a human judge', score: null };
  if (v.score === null) return { tone: 'neutral', mark: <Icon.Info />, word: 'Not scored', score: null };
  const s = Math.round(v.score * 100);
  if (program) {
    // Simulations are scored on a scale, not right/wrong.
    if (v.score >= 0.999) return { tone: 'good', mark: <Icon.Check />, word: 'Perfect run', score: String(s) };
    if (v.score >= 0.7) return { tone: 'good', mark: <Icon.Check />, word: 'Good run', score: String(s) };
    if (v.score >= 0.4) return { tone: 'partial', mark: HALF, word: 'Mixed run', score: String(s) };
    return { tone: 'bad', mark: X, word: 'Poor run', score: String(s) };
  }
  if (v.score >= 0.999) return { tone: 'good', mark: <Icon.Check />, word: 'Correct', score: String(s) };
  if (v.score <= 0.001) return { tone: 'bad', mark: X, word: 'Wrong', score: '0' };
  return { tone: v.score >= 0.5 ? 'partial' : 'bad', mark: v.score >= 0.5 ? HALF : X, word: v.score >= 0.5 ? 'Partly right' : 'Mostly wrong', score: String(s) };
}

function Stream({ text, typing }: { text: string; typing: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [text]);
  const { head, tail } = splitTail(text, 2);
  return (
    <div ref={ref} className="wt-stream" aria-live="off">
      <pre>
        {head && <span className="wt-old">{head}</span>}
        <span className="wt-new">{tail}</span>
        {typing && <span className="wt-caret" aria-hidden="true" />}
      </pre>
    </div>
  );
}

export function WatchTileCard({ t, now, ctx, testName, leader, rank, currentTest }: { t: WatchTile; now: number; ctx: CommentaryContext; testName: (id: string) => string; leader: boolean; rank: number | null; currentTest?: string | null }) {
  const v = tileView(t, now);
  const onScreen = v.phase === 'verdict' ? v.verdict : v.job;
  const program = !!onScreen && ctx.tests.find((x) => x.id === onScreen.testId)?.kind === 'program';
  const vi = v.phase === 'verdict' && v.verdict ? verdictInfo(v.verdict, program) : null;
  // The page headline already names the test most models are on: tiles only repeat it when they are elsewhere.
  const showTest = !!onScreen && onScreen.testId !== currentTest;
  const step = v.phase === 'verdict' ? v.verdict?.step : v.job?.step;
  return (
    <article
      className={cx('wt', `ph-${v.phase}`, vi && `v-${vi.tone}`, leader && 'leader', t.baseline && 'baseline')}
      style={{ '--c': t.color } as CSSProperties}
      aria-label={`${t.label}: ${v.phase}`}
    >
      <header className="wt-head">
        <span className="wt-rank tnum" title={leader ? 'Leader: highest average score so far' : 'Live rank by average score'}>
          {leader ? (
            <svg viewBox="0 0 24 24" aria-label="Leader">
              <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5Z" fill="currentColor" />
            </svg>
          ) : (
            rank ?? '–'
          )}
        </span>
        <span className="wt-sw" aria-hidden="true" />
        <span className="wt-name" title={t.baseline ? `${t.label}: answers at random, the score to beat` : t.label}>
          {t.label}
        </span>
        <span className="wt-mean tnum" title="Average score so far, out of 100">
          {v.mean === null ? '—' : Math.round(v.mean * 100)}
          <small>avg</small>
        </span>
      </header>
      <div className="wt-case">
        {onScreen ? (
          <>
            {showTest && <span className="wt-test">{testName(onScreen.testId)}</span>}
            <span className={cx('wt-q', !showTest && 'solo')}>
              {caseLong(ctx, onScreen.testId, onScreen.caseId, onScreen.repeat)}
              {step ? ` · ${step}` : ''}
            </span>
          </>
        ) : v.phase === 'finished' ? (
          <span className="wt-test">
            <Icon.Flag /> Finished all {fmtInt(t.total)} cases
          </span>
        ) : v.phase === 'waiting-manual' ? (
          <span className="wt-test">Waiting for a pasted reply</span>
        ) : (
          <span className="wt-test muted">Next case starting…</span>
        )}
      </div>
      <div className="wt-body">
        {v.phase === 'thinking' ? (
          <div className="wt-thinking" role="status">
            <span className="wt-dots" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            <b>Thinking…</b>
            <small>No text yet. The model may be reasoning before it writes; that part is not shown.</small>
          </div>
        ) : v.phase === 'waiting-manual' ? (
          <div className="wt-thinking" role="status">
            <Icon.Copy />
            <b>Waiting for a pasted reply</b>
            <small>This model is answered by hand in the Manual Inbox.</small>
          </div>
        ) : v.text ? (
          <Stream text={v.text} typing={v.phase === 'typing'} />
        ) : (
          <div className="wt-thinking idle">{v.phase === 'finished' ? <b>Done</b> : <small>Waiting for the next case…</small>}</div>
        )}
        {vi && v.verdict && (
          <div className={cx('wt-verdict', `v-${vi.tone}`)} key={v.verdict.key} role="status">
            <span className="wt-v-main">
              <span className="wt-v-mark" aria-hidden="true">
                {vi.mark}
              </span>
              <b>{vi.word}</b>
              {vi.score !== null && (
                <span className="wt-v-score tnum">
                  {vi.score}
                  <small>/100</small>
                </span>
              )}
            </span>
            {v.verdict.summary && <span className="wt-v-sum">{v.verdict.summary}</span>}
          </div>
        )}
      </div>
      <footer className="wt-meta tnum">
        <span title={v.phase === 'verdict' ? 'Time this case took' : 'Time on this case so far'}>
          <Icon.Clock />
          {fmtClock(v.elapsedMs)}
        </span>
        {v.phase !== 'waiting-manual' && v.phase !== 'thinking' && (
          <span title={v.tokensExact ? 'Output tokens, as recorded' : 'Estimated from the text so far (about 4 characters per token); the recorded count replaces it when the case is graded'}>
            <Icon.Layers />
            {v.tokensExact ? '' : '≈ '}
            {fmtInt(v.tokens)} tokens
          </span>
        )}
        <span title={v.spendExact ? 'This model’s spend so far, as recorded' : 'Recorded spend plus an estimate for the answer being written'}>
          {v.spendExact ? '' : '≈ '}
          {fmtCost(v.spend)} spent
        </span>
        {v.phase === 'typing' && v.others > 0 && <span className="wt-more">+{v.others} more in progress</span>}
      </footer>
    </article>
  );
}

export function WatchGrid({ watch, ctx, now, testName, compact, currentTest }: { watch: WatchState; ctx: CommentaryContext; now: number; testName: (id: string) => string; compact?: boolean; currentTest?: string | null }) {
  const tiles = [...watch.tiles.values()];
  const ranked = tiles.filter((t) => !t.baseline && t.scored).sort((a, b) => b.sum / b.scored - a.sum / a.scored);
  const cols = gridCols(tiles.length);
  const rows = Math.ceil(tiles.length / cols);
  return (
    <div className={cx('watch-grid', compact && 'compact', `cols-${cols}`)} style={{ '--cols': cols, '--rows': rows } as CSSProperties}>
      {tiles.map((t) => {
        const r = ranked.indexOf(t);
        return <WatchTileCard key={t.id} t={t} now={now} ctx={ctx} testName={testName} leader={r === 0 && ranked.length > 1} rank={r >= 0 ? r + 1 : null} currentTest={currentTest} />;
      })}
    </div>
  );
}
