/**
 * The finish-line moment: a full-screen checkered-flag sweep with the winner
 * and the podium, shown once when a live run completes (and on demand). It
 * closes itself after a few seconds, on click or on Escape. Recorded
 * standings only.
 */
import { useEffect } from 'react';
import type { CSSProperties } from 'react';
import { usePrefersReducedMotion } from '../../hooks.ts';
import { TrophySvg } from './TrophySvg.tsx';
import './finish.css';

export interface FinishEntry {
  label: string;
  color: string;
  /** Headline number, e.g. the Gauntlet Index. */
  value: string;
}

export function FinishMoment({ podium, caption, onDone, ms = 6500 }: { podium: FinishEntry[]; caption: string; onDone: () => void; ms?: number }) {
  const reduced = usePrefersReducedMotion();
  useEffect(() => {
    const t = window.setTimeout(onDone, reduced ? Math.min(ms, 4000) : ms);
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onDone();
    };
    window.addEventListener('keydown', key);
    return () => {
      window.clearTimeout(t);
      window.removeEventListener('keydown', key);
    };
  }, [onDone, ms, reduced]);
  const [first, ...rest] = podium;
  if (!first) return null;
  return (
    <div className="fin" role="dialog" aria-modal="true" aria-label={`Finish: ${first.label} wins`} onClick={onDone}>
      <div className="fin-flag top" aria-hidden="true" />
      <div className="fin-flag bottom" aria-hidden="true" />
      <div className="fin-body">
        <span className="fin-k">Finish</span>
        <span className="fin-trophy" aria-hidden="true">
          <TrophySvg />
        </span>
        <strong className="fin-name" style={{ ['--c' as string]: first.color } as CSSProperties}>
          {first.label}
        </strong>
        <span className="fin-v tnum">{first.value}</span>
        {rest.length > 0 && (
          <ol className="fin-rest">
            {rest.slice(0, 2).map((r, i) => (
              <li key={r.label} style={{ ['--c' as string]: r.color, animationDelay: `${1500 + i * 250}ms` } as CSSProperties}>
                <span className="fin-rank">{i + 2}</span>
                <i />
                <b>{r.label}</b>
                <span className="tnum">{r.value}</span>
              </li>
            ))}
          </ol>
        )}
        <span className="fin-cap">{caption}</span>
      </div>
    </div>
  );
}
