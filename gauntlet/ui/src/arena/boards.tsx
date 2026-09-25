/**
 * Game boards drawn from the snapshots the engine stores after every move:
 * Connect Four (discs drop into place) and chess (pieces slide, last move and
 * check highlighted). Both scale to their container.
 */
import type { CSSProperties } from 'react';
import { cx } from '../components/ui.tsx';
import type { C4Snapshot, ChessSnapshot, DebateSnapshot, PokerSnapshot } from './types.ts';
import { PokerSummary, PokerTable, type TablePlayer } from './poker.tsx';
import { DebateStage, DebateSummary, type DebateLiveInfo } from './debate.tsx';

// ─────────────────────────────── Connect Four ───────────────────────────────

export function Connect4Board({ snap, colors, toMove, thinking, className }: { snap: C4Snapshot; colors: [string, string]; toMove?: 0 | 1 | null; thinking?: boolean; className?: string }) {
  const rows = snap?.rows ?? Array.from({ length: 6 }, () => '.......');
  const nRows = rows.length;
  const win = new Set((snap?.win ?? []).map(([c, r]) => `${c},${r}`));
  const last = snap?.last;
  return (
    <div className={cx('c4', className)} role="img" aria-label={`Connect Four board${last ? `, last disc in column ${last.col + 1}` : ''}`}>
      <div className="c4-heads" aria-hidden="true">
        {Array.from({ length: 7 }, (_, c) => (
          <span key={c} className={cx(last?.col === c && 'on')}>
            {c + 1}
          </span>
        ))}
      </div>
      {thinking !== undefined && (
        <div className="c4-hover" aria-hidden="true">
          {thinking && toMove !== null && toMove !== undefined && <span className="c4-ghost" style={{ background: colors[toMove] }} />}
        </div>
      )}
      <div className="c4-frame">
        {rows.map((row, y) =>
          Array.from(row).map((ch, x) => {
            const r = nRows - 1 - y;
            const isLast = last && last.col === x && last.row === r;
            const side = ch === 'X' ? 0 : ch === 'O' ? 1 : null;
            return (
              <div key={`${x}-${y}`} className="c4-cell">
                {side !== null && (
                  <span
                    key={isLast ? `drop-${rows.join('')}` : 'disc'}
                    className={cx('c4-disc', isLast && 'drop', win.has(`${x},${r}`) && 'win')}
                    style={{ background: colors[side], ['--fall' as string]: y + 1 } as CSSProperties}
                  />
                )}
              </div>
            );
          }),
        )}
      </div>
    </div>
  );
}

// ─────────────────────────────── Chess ───────────────────────────────

const GLYPH: Record<string, string> = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' };
const FILES = 'abcdefgh';

function sq(name: string | undefined): { f: number; r: number } | null {
  if (!name) return null;
  return { f: FILES.indexOf(name[0]!), r: Number(name[1]) - 1 };
}

export function ChessBoard({ snap, className }: { snap: ChessSnapshot; className?: string }) {
  const board = snap?.board ?? '.'.repeat(64);
  const from = sq(snap?.last?.from);
  const to = sq(snap?.last?.to);
  const check = snap?.check ?? null;
  const cells = [];
  for (let i = 0; i < 64; i++) {
    const r = 7 - Math.floor(i / 8);
    const f = i % 8;
    const name = `${FILES[f]}${r + 1}`;
    const p = board[i]!;
    const light = (f + r) % 2 === 1;
    const isTo = to && to.f === f && to.r === r;
    const isFrom = from && from.f === f && from.r === r;
    const slide = isTo && from ? ({ ['--dx' as string]: from.f - f, ['--dy' as string]: r - from.r } as CSSProperties) : undefined;
    cells.push(
      <div key={name} className={cx('ch-sq', light ? 'light' : 'dark', (isTo || isFrom) && 'last', check === name && 'check')}>
        {f === 0 && <span className="ch-rank">{r + 1}</span>}
        {r === 0 && <span className="ch-file">{FILES[f]}</span>}
        {p !== '.' && (
          <span key={isTo ? `${snap?.fen}` : 'piece'} className={cx('ch-piece', p === p.toUpperCase() ? 'w' : 'b', isTo && from && 'slide')} style={slide}>
            {GLYPH[p.toLowerCase()]}
          </span>
        )}
      </div>,
    );
  }
  return (
    <div className={cx('chessboard', className)} role="img" aria-label={`Chess position ${snap?.fen ?? ''}`}>
      {cells}
    </div>
  );
}

export interface GameBoardProps {
  gameId: string;
  snap: unknown;
  colors: [string, string];
  toMove?: 0 | 1 | null;
  thinking?: boolean;
  className?: string;
  /** Player names and colours per seat (poker table, debate lecterns). */
  players?: [TablePlayer, TablePlayer];
  /** Compact result instead of the full board (match cards, latest result). */
  summary?: boolean;
  /** Debate: the speech being streamed right now. */
  live?: DebateLiveInfo;
  judgeHref?: string;
}

export function GameBoard({ gameId, snap, colors, toMove, thinking, className, players, summary, live, judgeHref }: GameBoardProps) {
  const who: [TablePlayer, TablePlayer] = players ?? [
    { label: 'Seat 1', color: colors[0] },
    { label: 'Seat 2', color: colors[1] },
  ];
  const kind = (snap as { kind?: string } | null)?.kind;
  if (kind === 'poker') return summary ? <PokerSummary snap={snap as PokerSnapshot} players={who} /> : <PokerTable snap={snap as PokerSnapshot} players={who} thinking={thinking} className={className} />;
  if (kind === 'debate') return summary ? <DebateSummary snap={snap as DebateSnapshot} players={who} /> : <DebateStage snap={snap as DebateSnapshot} players={who} live={live} className={className} judgeHref={judgeHref} />;
  if (gameId === 'chess') return <ChessBoard snap={snap as ChessSnapshot} className={className} />;
  return <Connect4Board snap={snap as C4Snapshot} colors={colors} toMove={toMove} thinking={thinking} className={className} />;
}

/** Small side token: a disc (Connect Four), a king glyph (chess), a chip (poker) or a side badge (debate). */
export function SideToken({ gameId, side, color }: { gameId: string; side: 0 | 1; color: string }) {
  if (gameId === 'poker') return <span className="side-token chip" style={{ ['--c' as string]: color } as CSSProperties} aria-hidden="true" />;
  if (gameId === 'debate' || gameId === 'courtroom') return <span className="side-token badge-side" style={{ background: color }} aria-hidden="true">{gameId === 'courtroom' ? (side === 0 ? 'P' : 'D') : side === 0 ? '+' : '−'}</span>;
  if (gameId === 'chess') return <span className={cx('side-token chess', side === 0 ? 'w' : 'b')} aria-hidden="true">♚</span>;
  return <span className="side-token disc" style={{ background: color }} aria-hidden="true" />;
}
