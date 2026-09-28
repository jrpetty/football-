/**
 * The suspense reveal: the final ranking, revealed from last place to first,
 * one model per keypress. Each reveal puts the model in the spotlight with
 * its final Gauntlet Index counting up, and its best and worst test. Places
 * 3 and 2 step onto the podium, a drumroll builds, and the winner gets the
 * trophy and a confetti burst. Recorded numbers only (the run's leaderboard).
 */
import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { LeaderboardRow } from '../../types.ts';
import { bestAndWorst, revealState, type TestPick } from '../../../../src/presenter/standings.ts';
import { usePrefersReducedMotion } from '../../hooks.ts';
import { cx } from '../ui.tsx';
import { TweenNumber } from '../viz/TweenNumber.tsx';
import { TrophySvg } from '../viz/TrophySvg.tsx';
import { RandomGuessLine } from '../viz/RandomGuessLine.tsx';
import { Confetti } from './Confetti.tsx';
import { sfx } from './sfx.ts';
import { useAutoBusy } from './autoPace.ts';
import '../../styles/show.css';

const COUNT_MS = 1500;
export const DRUMROLL_MS = 2600;

export function ordinal(n: number): string {
  const s = n % 100 >= 11 && n % 100 <= 13 ? 'th' : n % 10 === 1 ? 'st' : n % 10 === 2 ? 'nd' : n % 10 === 3 ? 'rd' : 'th';
  return `${n}${s}`;
}

/** Counts up from 0 to `value` once, on mount. */
function CountUp({ value, decimals = 1, durationMs = COUNT_MS }: { value: number; decimals?: number; durationMs?: number }) {
  const [v, setV] = useState(0);
  useEffect(() => {
    const t = window.setTimeout(() => setV(value), 60);
    return () => window.clearTimeout(t);
  }, [value]);
  return <TweenNumber value={v} decimals={decimals} durationMs={durationMs} />;
}

function Pick({ kind, t }: { kind: 'best' | 'worst'; t: TestPick | null }) {
  if (!t) return null;
  return (
    <div className={cx('pd-pick', kind)}>
      <span className="k">{kind === 'best' ? 'Best test' : 'Worst test'}</span>
      <span className="n">{t.name}</span>
      <b className="tnum">{Math.round(t.score * 100)}</b>
    </div>
  );
}

function Spotlight({ row, place, tests, baseline, winner }: { row: LeaderboardRow; place: number; tests: Array<{ id: string; name: string }>; baseline: number | null; winner: boolean }) {
  const { best, worst } = bestAndWorst(row, tests);
  const idx = typeof row.index === 'number' ? row.index : 0;
  const [bar, setBar] = useState(0);
  useEffect(() => {
    const t = window.setTimeout(() => setBar(idx), 60);
    return () => window.clearTimeout(t);
  }, [idx]);
  const m = row.medals ?? { gold: 0, silver: 0, bronze: 0 };
  return (
    <div className={cx('pd-spot', winner && 'winner', place <= 3 && `p${place}`)} style={{ ['--c' as string]: row.color } as CSSProperties}>
      <div className="pd-place">{winner ? 'The winner' : `${ordinal(place)} place`}</div>
      {winner && (
        <span className="pd-trophy" aria-hidden="true">
          <TrophySvg />
        </span>
      )}
      <div className="pd-who">
        <i className="pd-sw" aria-hidden="true" />
        <div className="pd-nt">
          <b>
            {row.label}
            {row.manual ? '*' : ''}
          </b>
          <small>{row.vendor}</small>
        </div>
      </div>
      <div className="pd-score">
        <span className="pd-num tnum">{typeof row.index === 'number' ? <CountUp value={row.index} /> : '—'}</span>
        <span className="pd-of">out of 100</span>
      </div>
      <div className="pd-bar" aria-hidden="true">
        <i className="pd-fill" style={{ width: `${bar}%` }} />
        <RandomGuessLine pct={baseline} placement="below" />
      </div>
      <div className="pd-picks">
        <Pick kind="best" t={best} />
        <Pick kind="worst" t={worst} />
      </div>
      {(m.gold > 0 || m.silver > 0 || m.bronze > 0) && (
        <div className="pd-medals">
          Test wins: <b className="tnum">{m.gold}</b> gold · <b className="tnum">{m.silver}</b> silver · <b className="tnum">{m.bronze}</b> bronze
        </div>
      )}
    </div>
  );
}

function PodiumBlock({ row, place, shown, drum }: { row: LeaderboardRow | undefined; place: 1 | 2 | 3; shown: boolean; drum?: boolean }) {
  const medal = place === 1 ? 'gold' : place === 2 ? 'silver' : 'bronze';
  return (
    <div className={cx('pd-block', `b${place}`, shown && 'on', drum && 'drum')} style={{ ['--c' as string]: row?.color ?? 'var(--text-3)' } as CSSProperties}>
      <div className="pd-stand">
        {shown && row ? (
          <div className="pd-bwho">
            {place === 1 && (
              <span className="pd-btrophy" aria-hidden="true">
                <TrophySvg />
              </span>
            )}
            <b>
              {row.label}
              {row.manual ? '*' : ''}
            </b>
            <span className="tnum">{typeof row.index === 'number' ? row.index.toFixed(1) : '—'}</span>
          </div>
        ) : (
          <div className="pd-bq">?</div>
        )}
      </div>
      <div className={cx('pd-step', medal)}>
        <span className="tnum">{place}</span>
      </div>
    </div>
  );
}

export function PodiumSlide({ rows, tests, baseline, reveal }: { rows: LeaderboardRow[]; tests: Array<{ id: string; name: string }>; baseline: number | null; reveal: number }) {
  const n = rows.length;
  const st = revealState(n, reveal);
  const reduced = usePrefersReducedMotion();
  const prevReveal = useRef(reveal);
  const byPlace = (p: number) => rows[p - 1];
  const current = st.current ? byPlace(st.current) : undefined;
  useAutoBusy(st.current !== null || st.drumroll, st.drumroll ? DRUMROLL_MS : COUNT_MS, reveal);

  // Sound cues only when stepping forward.
  useEffect(() => {
    const forward = reveal > prevReveal.current;
    prevReveal.current = reveal;
    if (!forward) return;
    if (st.winner) sfx.fanfare();
    else if (st.drumroll) sfx.drumroll(DRUMROLL_MS);
    else if (st.current !== null && st.current <= 3) sfx.ding();
    else if (st.current !== null) sfx.tick();
  }, [reveal, st.winner, st.drumroll, st.current]);

  const ladder = rows.slice(3);
  return (
    <div className={cx('s-podium', st.winner && 'is-winner', st.drumroll && 'is-drum')}>
      <div className="pd-head">
        <div className="p-eyebrow">The verdict</div>
        <h1 className="p-title">{st.winner ? `${rows[0]?.label ?? ''} wins!` : 'And the winner is…'}</h1>
        <div className="p-sub">
          Final ranking by Gauntlet Index, revealed from last place to first · {n} {n === 1 ? 'model' : 'models'}
        </div>
      </div>
      <div className="pd-grid">
        <div className="pd-left">
          {current && st.current !== null ? (
            <Spotlight key={current.contestantId} row={current} place={st.current} tests={tests} baseline={baseline} winner={st.winner} />
          ) : st.drumroll ? (
            <div className="pd-spot pd-drumroll" aria-live="polite">
              <div className="pd-drum" aria-hidden="true">
                <i />
                <i />
                <i />
              </div>
              <div className="pd-drum-t">And the winner is…</div>
              <div className="pd-drum-s">
                {rows[1] ? (
                  <>
                    Only one model scored higher than <b>{rows[1].label}</b>
                  </>
                ) : (
                  'One model left to reveal'
                )}
              </div>
            </div>
          ) : (
            <div className="pd-spot pd-intro">
              <div className="pd-intro-n tnum">{n}</div>
              <div className="pd-intro-t">{n === 1 ? 'model' : 'models'} · one winner</div>
              <p>We count down from last place. The overall score is the Gauntlet Index: every category averaged into one number out of 100.</p>
            </div>
          )}
        </div>
        <div className="pd-right">
          <div className={cx('pd-podium', n < 3 && `n${n}`)}>
            {n >= 2 && <PodiumBlock row={byPlace(2)} place={2} shown={st.shownFrom <= 2} />}
            <PodiumBlock row={byPlace(1)} place={1} shown={st.shownFrom <= 1} drum={st.drumroll} />
            {n >= 3 && <PodiumBlock row={byPlace(3)} place={3} shown={st.shownFrom <= 3} />}
          </div>
          {ladder.length > 0 && (
            <ol className={cx('pd-ladder', ladder.length > 5 && 'two')} style={{ ['--lr' as string]: ladder.length > 5 ? Math.ceil(ladder.length / 2) : ladder.length } as CSSProperties}>
              {ladder.map((r, i) => {
                const place = i + 4;
                const shown = st.shownFrom <= place;
                return (
                  <li key={r.contestantId} className={cx(shown ? 'on' : 'hidden', st.current === place && 'now')} style={{ ['--c' as string]: r.color } as CSSProperties}>
                    <span className="pl tnum">{ordinal(place)}</span>
                    {shown ? (
                      <>
                        <i className="sw" aria-hidden="true" />
                        <b>
                          {r.label}
                          {r.manual ? '*' : ''}
                        </b>
                        <span className="sc tnum">{typeof r.index === 'number' ? r.index.toFixed(1) : '—'}</span>
                      </>
                    ) : (
                      <span className="q">?</span>
                    )}
                  </li>
                );
              })}
            </ol>
          )}
        </div>
      </div>
      {st.winner && !reduced && rows[0] && <Confetti colors={['#f2c14e', '#ffe58a', rows[0].color, '#22d3ee', '#a78bfa', '#ffffff']} />}
    </div>
  );
}
