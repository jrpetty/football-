/**
 * "Standings after N of M tests": a bar race of every model's running
 * Gauntlet Index between the tests of an episode. The slide opens on the
 * previous standings, the bars grow (or shrink) to the new scores, then the
 * rows slide into their new order and the new leader and biggest mover are
 * called out. The numbers come from src/presenter/standings.ts, which uses the
 * leaderboard's own formula on the recorded per-test scores.
 */
import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import type { StandingsStep } from '../../../../src/presenter/standings.ts';
import { useElementSize, usePrefersReducedMotion } from '../../hooks.ts';
import { cx } from '../ui.tsx';
import { TweenNumber } from '../viz/TweenNumber.tsx';
import { RandomGuessLine } from '../viz/RandomGuessLine.tsx';
import { sfx } from './sfx.ts';
import { useAutoBusy } from './autoPace.ts';
import '../../styles/show.css';

export interface ShowModel {
  label: string;
  vendor: string;
  color: string;
  manual?: boolean;
}

type Phase = 'prev' | 'grow' | 'sorted' | 'called';
/** When each phase starts (ms after the slide appears). */
const AT = { grow: 650, sorted: 2150, called: 3050 };
export const STANDINGS_ANIM_MS = AT.called + 400;

export function Crown({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5Z" fill="currentColor" />
    </svg>
  );
}

export function StandingsSlide({ step, prevBaseline, models, testName }: { step: StandingsStep; prevBaseline: number | null; models: Map<string, ShowModel>; testName: string }) {
  const reduced = usePrefersReducedMotion();
  const [phase, setPhase] = useState<Phase>(reduced ? 'called' : 'prev');
  const first = step.step === 1;
  useAutoBusy(!reduced, STANDINGS_ANIM_MS, step.step);

  useEffect(() => {
    if (reduced) {
      setPhase('called');
      return;
    }
    setPhase('prev');
    const ts = [
      window.setTimeout(() => {
        setPhase('grow');
        sfx.tick();
        sfx.tick(0.11);
        sfx.tick(0.22);
      }, AT.grow),
      window.setTimeout(() => {
        setPhase('sorted');
        if (step.rows.some((r) => r.gained !== 0)) sfx.whoosh();
      }, AT.sorted),
      window.setTimeout(() => {
        setPhase('called');
        if (step.newLeader) sfx.ding();
        else if (step.mover) sfx.tick();
      }, AT.called),
    ];
    return () => ts.forEach((t) => window.clearTimeout(t));
  }, [reduced, step]);

  const n = step.rows.length;
  const [boardRef, boardSize] = useElementSize<HTMLDivElement>();
  const rowH = Math.max(40, Math.min(100, Math.floor((boardSize.height || 600) / Math.max(1, n))));
  // Order on screen: the previous standings until the rows re-sort.
  const prevOrder = first ? step.rows : [...step.rows].sort((a, b) => (a.prevRank ?? 999) - (b.prevRank ?? 999));
  const order = phase === 'prev' || phase === 'grow' ? prevOrder : step.rows;
  const pos = new Map(order.map((r, i) => [r.id, i]));
  const showNew = phase !== 'prev';
  const called = phase === 'called';
  const leader = step.leaderId ? models.get(step.leaderId) : undefined;
  const mover = step.mover ? models.get(step.mover.id) : undefined;
  const baseline = showNew || first ? step.baseline : (prevBaseline ?? step.baseline);

  return (
    <div className="s-standings">
      <div className="sb-head">
        <div className="sb-head-t">
          <div className="p-eyebrow">The race so far</div>
          <h1 className="p-title">
            Standings after <span className="tnum">{step.step}</span> of <span className="tnum">{step.of}</span> {step.of === 1 ? 'test' : 'tests'}
          </h1>
          <div className="p-sub">
            Just played: <b>{testName}</b>
          </div>
        </div>
        <div className={cx('sb-calls', called && 'on')}>
          {leader && (
            <div className="sb-call lead" style={{ ['--c' as string]: leader.color } as CSSProperties}>
              <Crown className="sb-crown" />
              <div>
                <div className="k">{first ? 'First leader' : step.newLeader ? 'New leader' : 'Still leading'}</div>
                <div className="v">{leader.label}</div>
              </div>
            </div>
          )}
          {mover && step.mover && (
            <div className="sb-call mover" style={{ ['--c' as string]: mover.color } as CSSProperties}>
              <span className="sb-up tnum" aria-label={`Up ${step.mover.places} ${step.mover.places === 1 ? 'place' : 'places'}`}>
                ▲{step.mover.places}
              </span>
              <div>
                <div className="k">Biggest mover</div>
                <div className="v">{mover.label}</div>
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="sb-scale" aria-hidden="true">
        <span />
        <span />
        <span className="sb-scale-t">Overall score so far, out of 100</span>
        <span />
      </div>
      <div className="sb-fit" ref={boardRef}>
      <div className="sb-board" style={{ height: n * rowH, ['--rh' as string]: `${rowH}px` } as CSSProperties}>
        <div className="sb-overlay" aria-hidden={baseline === null}>
          <span />
          <span />
          <div className="sb-ovtrack">
            <RandomGuessLine pct={baseline} />
          </div>
          <span />
        </div>
        {step.rows.map((r) => {
          const m = models.get(r.id);
          const i = pos.get(r.id) ?? 0;
          const value = showNew ? r.index : r.prevIndex;
          const unscored = showNew && r.index === null;
          const isLead = called && r.id === step.leaderId;
          const rankShown = phase === 'sorted' || called ? r.rank : first ? null : r.prevRank;
          return (
            <div
              key={r.id}
              className={cx('sb-row', isLead && 'lead', r.index === null && 'none', called && step.mover?.id === r.id && 'moved')}
              style={{ transform: `translateY(${i * rowH}px)`, height: rowH - 10, ['--c' as string]: m?.color ?? 'var(--text-3)' } as CSSProperties}
            >
              <span className="sb-rank tnum">{rankShown ?? '–'}</span>
              <span className="sb-name">
                <i className="sb-sw" aria-hidden="true" />
                <span className="sb-nt">
                  <b>
                    {m?.label ?? r.id}
                    {m?.manual ? '*' : ''}
                  </b>
                  {rowH >= 70 && <small>{m?.vendor}</small>}
                </span>
              </span>
              <span className="sb-track">
                <i className="sb-fill" style={{ width: `${value ?? 0}%` }} />
                <span className="sb-val tnum" style={{ left: `${value ?? 0}%` }}>
                  {unscored ? 'not scored yet' : <TweenNumber value={value ?? 0} decimals={1} durationMs={1300} />}
                </span>
              </span>
              <span className={cx('sb-delta tnum', r.gained > 0 ? 'up' : r.gained < 0 ? 'down' : 'same', called && !first && 'on')}>
                {first ? '' : r.gained > 0 ? `▲${r.gained}` : r.gained < 0 ? `▼${-r.gained}` : '='}
              </span>
            </div>
          );
        })}
      </div>
      </div>
    </div>
  );
}
