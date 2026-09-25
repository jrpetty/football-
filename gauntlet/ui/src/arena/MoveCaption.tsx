/**
 * The broadcast caption above a board: one plain-English headline for the
 * move (colour-coded good / bad / neutral), the model's own one-line reason
 * ("why that move"), and a clear label when the move needed an illegal-move
 * retry. Built only from the recorded move.
 */
import type { CSSProperties } from 'react';
import { cx } from '../components/ui.tsx';
import type { ArenaMove } from './types.ts';
import { moveHeadline } from './analysis.ts';

const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);

export function MoveCaption({ gameId, move, prev, names, color, idle }: { gameId: string; move: ArenaMove | undefined; prev: unknown; names: [string, string]; color?: string; idle?: string }) {
  if (!move) {
    return (
      <div className="mcap neutral" role="status">
        <span className="mcap-h">{idle ?? 'Starting position'}</span>
      </div>
    );
  }
  const h = moveHeadline(gameId, move, prev, names);
  const rejected = move.attempts.filter((a) => a.error);
  const showWhy = move.kind !== 'verdict' && !move.opening && gameId !== 'debate' && gameId !== 'courtroom';
  return (
    <div className={cx('mcap', h.tone)} role="status" aria-live="polite" style={{ ['--c' as string]: color ?? 'var(--accent)' } as CSSProperties} key={`${move.ply}-${move.kind ?? ''}`}>
      <span className="mcap-h">
        <span className="mcap-mark" aria-hidden="true">
          {h.tone === 'good' ? '▲' : h.tone === 'bad' ? '▼' : '●'}
        </span>
        {h.text}
      </span>
      {showWhy &&
        (move.note ? (
          <span className="mcap-why">
            <b>Why, in its own words:</b> “{clip(move.note, 180)}”
          </span>
        ) : (
          <span className="mcap-why none">{move.forfeit ? 'No legal move was given, so there is no reason to show.' : 'No reason line in this reply (not recorded).'}</span>
        ))}
      {rejected.length > 0 && (
        <span className={cx('mcap-retry', move.forfeit && 'fail')}>
          <b>{move.forfeit ? 'Illegal twice → strike' : 'Illegal move → retried'}</b>
          {rejected.map((a, i) => (
            <span key={i} className="mcap-try">
              Try {move.attempts.indexOf(a) + 1}: “{clip(a.extracted ?? a.text.trim().split('\n').pop() ?? '', 40) || 'no answer line'}” rejected — {clip(a.error ?? '', 110)}
            </span>
          ))}
          {!move.forfeit && <span className="mcap-try ok">Try {move.attempts.length}: accepted</span>}
        </span>
      )}
    </div>
  );
}
