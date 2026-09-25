/** Shared Arena pieces: versus header, move list, thinking stream, clocks. */
import { useEffect, useRef } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { GAMES } from '../../../src/arena/games/index.ts';
import { cx } from '../components/ui.tsx';
import { fmtClock, fmtCost } from '../format.ts';
import { useNow } from '../hooks.ts';
import { SideToken } from './boards.tsx';
import type { ArenaEntrant, ArenaMove, SideMetrics } from './types.ts';
import type { TablePlayer } from './poker.tsx';

/** Compact per-move time: 0.8s, 7.2s, 42s, 3m05s. */
export function secs(ms: number | null | undefined): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms)) return '';
  if (ms < 10_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  const t = Math.round(ms / 1000);
  return `${Math.floor(t / 60)}m${String(t % 60).padStart(2, '0')}s`;
}

export function sidesOf(gameId: string): Array<{ name: string; color: string }> {
  return GAMES[gameId]?.sides ?? [
    { name: 'First', color: '#e5484d' },
    { name: 'Second', color: '#3b82f6' },
  ];
}

export function gameName(gameId: string): string {
  return GAMES[gameId]?.name ?? gameId;
}

/** Engine of a game: 'board' (Connect Four, chess), 'turns' (poker) or 'debate' (debate, courtroom). */
export function engineOf(gameId: string): 'board' | 'turns' | 'debate' {
  return GAMES[gameId]?.engine ?? 'board';
}

/** Names and colours per seat for tables and lecterns. */
export function seatPlayers(ents: Map<string, ArenaEntrant>, players: readonly [string, string] | readonly (string | null)[]): [TablePlayer, TablePlayer] {
  const one = (id: string | null | undefined, i: number): TablePlayer => {
    const e = id ? ents.get(id) : undefined;
    return { label: e?.label ?? id ?? `Seat ${i + 1}`, color: e?.color ?? (i ? '#a855f7' : '#22c55e') };
  };
  return [one(players[0], 0), one(players[1], 1)];
}

/** Match score: "1½" for points, "+34" for margin games (chips). */
export function fmtScore(x: number, unit?: string): string {
  if (!unit) {
    const w = Math.floor(x);
    return x - w ? `${w || ''}½` : String(w);
  }
  return x > 0 ? `+${x}` : String(x);
}

/** What one game is called on screen. */
export function playsWord(gameId: string): { move: string; moves: string } {
  const e = engineOf(gameId);
  return e === 'turns' ? { move: 'action', moves: 'actions' } : e === 'debate' ? { move: 'speech', moves: 'speeches' } : { move: 'move', moves: 'moves' };
}

function Strikes({ n, max, lose = true }: { n: number; max: number; lose?: boolean }) {
  if (!lose) {
    return (
      <span className={cx('vs-strike-n tnum', n > 0 && 'on')} title="Strikes: decisions where both attempts failed (a check or fold was played instead)">
        ✕ {n}
      </span>
    );
  }
  return (
    <span className="vs-strikes" title={`${n} of ${max} strikes (a strike = two failed attempts at one move; ${max} lose the game)`} aria-label={`${n} of ${max} strikes`}>
      {Array.from({ length: max }, (_, i) => (
        <i key={i} className={cx(i < n && 'on')} />
      ))}
    </span>
  );
}

export interface VersusProps {
  gameId: string;
  players: [string, string];
  entrants: Map<string, ArenaEntrant>;
  metrics?: [SideMetrics, SideMetrics];
  strikes?: [number, number];
  maxStrikes: number;
  /** Seat whose turn it is (live), or null. */
  toMove?: 0 | 1 | null;
  /** ISO time the seat to move started thinking (live clock). */
  turnStartedAt?: string;
  winner?: 0 | 1 | null;
  finished?: boolean;
  center?: ReactNode;
  big?: boolean;
}

export function Versus(p: VersusProps) {
  const sides = sidesOf(p.gameId);
  const now = useNow(p.toMove !== null && p.toMove !== undefined && !p.finished ? 250 : null);
  const side = (i: 0 | 1) => {
    const e = p.entrants.get(p.players[i]);
    const m = p.metrics?.[i];
    const active = p.toMove === i && !p.finished;
    const turnMs = active && p.turnStartedAt ? Math.max(0, now - new Date(p.turnStartedAt).getTime()) : null;
    const won = p.finished && p.winner === i;
    const lost = p.finished && p.winner !== null && p.winner !== undefined && p.winner !== i;
    return (
      <div className={cx('vs-side', i === 1 && 'right', active && 'active', won && 'won', lost && 'lost')} style={{ ['--c' as string]: e?.color ?? 'var(--text-3)' } as CSSProperties}>
        <div className="vs-name-row">
          <SideToken gameId={p.gameId} side={i} color={sides[i]!.color} />
          <span className="vs-bar" aria-hidden="true" />
          <div className="vs-names">
            <strong className="vs-label">{e?.label ?? p.players[i]}</strong>
            <span className="vs-sub">
              {sides[i]!.name} · {e?.vendor ?? ''}
              {e?.manual ? ' · manual' : ''}
            </span>
          </div>
          {won && <span className="vs-flag win">WINNER</span>}
        </div>
        <div className="vs-stats">
          {active ? (
            <span className="vs-thinking">
              <span className="dots" aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
              thinking <b className="tnum">{fmtClock(turnMs)}</b>
            </span>
          ) : (
            <span className="vs-stat" title="Total thinking time">
              ⏱ <b className="tnum">{fmtClock(m?.ms ?? 0)}</b>
            </span>
          )}
          <span className="vs-stat" title="Spent by this model in this game">
            <b className="tnum">{fmtCost(m?.costUsd ?? 0)}</b>
          </span>
          <Strikes n={p.strikes?.[i] ?? 0} max={p.maxStrikes} lose={GAMES[p.gameId]?.strikesLose !== false} />
        </div>
      </div>
    );
  };
  return (
    <div className={cx('versus', p.big && 'big')}>
      {side(0)}
      <div className="vs-center">{p.center ?? <span className="vs-vs">VS</span>}</div>
      {side(1)}
    </div>
  );
}

export function MoveList({ gameId, moves, current, onSelect, live }: { gameId: string; moves: ArenaMove[]; current?: number; onSelect?: (i: number) => void; live?: boolean }) {
  const ref = useRef<HTMLOListElement>(null);
  const sides = sidesOf(gameId);
  useEffect(() => {
    const el = ref.current?.querySelector('.on');
    if (el) el.scrollIntoView({ block: 'nearest' });
    else if (live && ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [current, moves.length, live]);
  if (!moves.length) return <div className="mv-empty muted">No moves yet.</div>;
  return (
    <ol className={cx('mv-list', gameId === 'chess' && 'pairs')} ref={ref}>
      {moves.map((m, i) => {
        const rejected = m.attempts.filter((a) => a.error).length;
        if (m.kind === 'verdict') {
          return (
            <li key={m.ply} className={cx('mv verdict', i === current && 'on')}>
              <button type="button" onClick={() => onSelect?.(i)} disabled={!onSelect}>
                <span className="mv-n">⚖</span>
                <span className="mv-l">{m.label}</span>
              </button>
            </li>
          );
        }
        return (
          <li key={m.ply} className={cx('mv', i === current && 'on', m.forfeit && 'forfeit', m.opening && 'opening', m.side === 1 && 'second')}>
            <button type="button" onClick={() => onSelect?.(i)} disabled={!onSelect} title={m.opening ? 'Random opening move played by the harness (sudden-death game)' : m.forfeit ? 'Both attempts failed: a random legal move was played and a strike given' : rejected ? `${rejected} rejected attempt(s) before this move` : undefined}>
              {(gameId !== 'chess' || m.side === 0) && <span className="mv-n tnum">{gameId === 'chess' ? `${Math.ceil(m.ply / 2)}.` : `${m.ply}.`}</span>}
              <span className="mv-dot" style={{ background: sides[m.side]!.color }} aria-hidden="true" />
              <span className="mv-l">{m.move ? m.label : '✕ strike'}</span>
              {m.forfeit && (m.move || engineOf(gameId) !== 'board') && <span className="mv-tag bad">{engineOf(gameId) === 'board' ? 'random' : 'strike'}</span>}
              {!m.forfeit && rejected > 0 && <span className="mv-tag warn">retry</span>}
              {m.opening && <span className="mv-tag">opening</span>}
              <span className="mv-t tnum">{m.opening ? '' : secs(m.ms)}</span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}

/** The move's reasoning: rejected attempts in red with the reason, then the accepted reply. */
export function MoveReasoning({ move, label }: { move: ArenaMove; label: string }) {
  if (move.kind === 'verdict') return <div className="mv-reason muted">The judges’ decision is shown on the stage: each judge’s scorecard, pick and reasons. Judges saw a blinded transcript (Side A / Side B, no model names).</div>;
  if (move.opening) return <div className="mv-reason muted">Random opening move played by the harness so the sudden-death game does not replay game 1. Both models face the same opening.</div>;
  return (
    <div className="mv-reason">
      {move.note && (
        <div className="mv-note">
          <span className="eyebrow">In one line</span> {move.note}
        </div>
      )}
      {move.attempts.map((a, i) => (
        <div key={i} className={cx('mv-attempt', a.error && 'rejected')}>
          <div className="mv-a-head">
            <span>
              {label} · attempt {i + 1}
            </span>
            <span className="tnum muted">{secs(a.ms)}</span>
          </div>
          <pre>{a.text || '(empty reply)'}</pre>
          {a.error && (
            <div className="mv-err">
              <b>Rejected:</b> {a.error}
            </div>
          )}
        </div>
      ))}
      {move.forfeit && (
        <div className="mv-err">
          {/^H\d+ /.test(move.label)
            ? `Both attempts failed, so the harness played "${move.label.replace(/^H\d+ [^:]+: /, '')}" (check if free, otherwise fold) and gave a strike.`
            : move.kind === undefined && !move.move && move.label.includes('no speech')
              ? 'Both attempts failed, so this speech is missing (the judges see the gap) and a strike was given.'
              : `Both attempts failed, so a random legal move ${move.move ? `(${move.label}) ` : ''}was played and a strike was given.`}
        </div>
      )}
    </div>
  );
}

export function Thinking({ text, who, color }: { text: string; who: string; color?: string }) {
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [text]);
  return (
    <div className="thinking" style={{ ['--c' as string]: color ?? 'var(--accent)' } as CSSProperties}>
      <div className="thinking-head">
        <span className="dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
        <span>
          <b>{who}</b> is thinking…
        </span>
      </div>
      <pre ref={ref} aria-live="off">
        {text || <span className="muted">Waiting for the first words of the reply…</span>}
        <span className="caret" aria-hidden="true" />
      </pre>
    </div>
  );
}
