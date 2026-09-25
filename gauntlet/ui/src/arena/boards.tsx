/**
 * Game boards drawn from the snapshots the engine stores after every move:
 * Connect Four (discs drop into place, "wins next move" columns marked, the
 * winning four swept by a line) and chess (SVG pieces slide, last-move arrow,
 * check, captured pieces and a material bar). Both scale to their container.
 */
import type { CSSProperties } from 'react';
import { cx } from '../components/ui.tsx';
import type { C4Snapshot, ChessSnapshot, DebateSnapshot, PokerSnapshot } from './types.ts';
import { PokerSummary, PokerTable, type TablePlayer } from './poker.tsx';
import { DebateStage, DebateSummary, type DebateLiveInfo } from './debate.tsx';
import { ChessPieceSvg, PIECE_NAME } from '../components/viz/ChessPieceSvg.tsx';
import { ColorLegend } from '../components/viz/ColorLegend.tsx';
import { c4Threats, chessMaterial } from './analysis.ts';
import './visual.css';

// ─────────────────────────────── Connect Four ───────────────────────────────

/** Frame geometry in container units (see .c4-frame in arena.css): padding 2.4, gap 1.3, 7 columns. */
const C4_PAD = 2.4;
const C4_GAP = 1.3;
const C4_CELL = (100 - 2 * C4_PAD - 6 * C4_GAP) / 7;
const c4Center = (i: number) => C4_PAD + i * (C4_CELL + C4_GAP) + C4_CELL / 2;

export function Connect4Board({
  snap,
  colors,
  toMove,
  thinking,
  className,
  threats: showThreats = true,
  names,
}: {
  snap: C4Snapshot;
  colors: [string, string];
  toMove?: 0 | 1 | null;
  thinking?: boolean;
  className?: string;
  /** Mark the columns where a disc would win next move (off on small summary boards). */
  threats?: boolean;
  names?: [string, string];
}) {
  const rows = snap?.rows ?? Array.from({ length: 6 }, () => '.......');
  const nRows = rows.length;
  const winLine = snap?.win ?? [];
  const winIdx = new Map(winLine.map(([c, r], i) => [`${c},${r}`, i]));
  const last = snap?.last;
  const threats: [number[], number[]] = showThreats ? c4Threats(snap) : [[], []];
  const who = (s: 0 | 1) => names?.[s] ?? (s ? 'Yellow' : 'Red');
  const threatText = ([0, 1] as const)
    .filter((s) => threats[s].length)
    .map((s) => `${who(s)} wins next move in column ${threats[s].map((c) => c + 1).join(' or ')}`)
    .join('; ');
  /** Landing row (top-first index) of each column, or -1 when full. */
  const landing = Array.from({ length: 7 }, (_, c) => {
    for (let y = nRows - 1; y >= 0; y--) if (rows[y]![c] === '.') return y;
    return -1;
  });
  const frameH = 2 * C4_PAD + nRows * C4_CELL + (nRows - 1) * C4_GAP;
  const pt = ([c, r]: [number, number]) => `${c4Center(c).toFixed(2)},${(C4_PAD + (nRows - 1 - r) * (C4_CELL + C4_GAP) + C4_CELL / 2).toFixed(2)}`;
  return (
    <div className={cx('c4', className)} role="img" aria-label={`Connect Four board${last ? `, last disc in column ${last.col + 1}` : ''}${threatText ? `. ${threatText}` : ''}`}>
      <div className="c4-heads" aria-hidden="true">
        {Array.from({ length: 7 }, (_, c) => {
          const by = ([0, 1] as const).filter((s) => threats[s].includes(c));
          return (
            <span key={c} className={cx(last?.col === c && 'on', by.length > 0 && 'threat')}>
              {c + 1}
              {by.length > 0 && (
                <span className="c4-threat">
                  {by.map((s) => (
                    <i key={s} style={{ background: colors[s] }} />
                  ))}
                  WIN
                </span>
              )}
            </span>
          );
        })}
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
            const wi = winIdx.get(`${x},${r}`);
            const spot = side === null && landing[x] === y ? ([0, 1] as const).filter((s) => threats[s].includes(x)) : [];
            return (
              <div key={`${x}-${y}`} className="c4-cell">
                {side !== null && (
                  <span
                    key={isLast ? `drop-${rows.join('')}` : 'disc'}
                    className={cx('c4-disc', isLast && 'drop', wi !== undefined && 'win')}
                    style={{ background: colors[side], ['--fall' as string]: y + 1, ['--wi' as string]: wi ?? 0 } as CSSProperties}
                  />
                )}
                {spot.length > 0 && <span className={cx('c4-spot', spot.length > 1 && 'both')} style={{ ['--c' as string]: colors[spot[0]!], ['--c2' as string]: colors[spot[spot.length - 1]!] } as CSSProperties} />}
              </div>
            );
          }),
        )}
        {winLine.length === 4 && (
          <svg className="c4-winline" viewBox={`0 0 100 ${frameH.toFixed(2)}`} aria-hidden="true" key={rows.join('')}>
            <polyline points={[winLine[0]!, winLine[3]!].map(pt).join(' ')} pathLength={1} />
          </svg>
        )}
      </div>
    </div>
  );
}

/** What the marks on a Connect Four board mean (shown under the board). */
export function Connect4Legend({ colors, names, snap }: { colors: [string, string]; names: [string, string]; snap: C4Snapshot }) {
  const t = c4Threats(snap);
  return (
    <ColorLegend
      className="c4-legend"
      label="What the marks mean"
      items={[
        {
          mark: (
            <span className="lg-win" aria-hidden="true">
              WIN
            </span>
          ),
          label: 'a disc here wins next move',
        },
        { color: colors[0], shape: 'ring', label: `${names[0]}’s winning spot${t[0].length ? ` (column ${t[0].map((c) => c + 1).join(', ')})` : ''}` },
        { color: colors[1], shape: 'ring', label: `${names[1]}’s winning spot${t[1].length ? ` (column ${t[1].map((c) => c + 1).join(', ')})` : ''}` },
        ...(snap?.win ? [{ mark: <span className="lg-four" aria-hidden="true" />, label: 'the winning four' }] : []),
      ]}
    />
  );
}

// ─────────────────────────────── Chess ───────────────────────────────

const FILES = 'abcdefgh';

function sq(name: string | undefined): { f: number; r: number } | null {
  if (!name) return null;
  return { f: FILES.indexOf(name[0]!), r: Number(name[1]) - 1 };
}

export function ChessBoard({ snap, className, arrow: showArrow = true }: { snap: ChessSnapshot; className?: string; arrow?: boolean }) {
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
    const color = p === p.toUpperCase() ? 'w' : 'b';
    cells.push(
      <div key={name} className={cx('ch-sq', light ? 'light' : 'dark', (isTo || isFrom) && 'last', check === name && 'check')}>
        {f === 0 && <span className="ch-rank">{r + 1}</span>}
        {r === 0 && <span className="ch-file">{FILES[f]}</span>}
        {p !== '.' && (
          <span key={isTo ? `${snap?.fen}` : 'piece'} className={cx('ch-piece', color, isTo && from && 'slide')} style={slide}>
            <ChessPieceSvg piece={p} color={color} />
          </span>
        )}
        {check === name && <span className="ch-check-tag">CHECK</span>}
      </div>,
    );
  }
  let arrow: { x1: number; y1: number; x2: number; y2: number } | null = null;
  if (showArrow && from && to) {
    const x1 = from.f + 0.5;
    const y1 = 7 - from.r + 0.5;
    const X = to.f + 0.5;
    const Y = 7 - to.r + 0.5;
    const len = Math.hypot(X - x1, Y - y1) || 1;
    // Stop the shaft short so the arrow head lands on the square's centre.
    arrow = { x1, y1, x2: X - ((X - x1) / len) * 0.3, y2: Y - ((Y - y1) / len) * 0.3 };
  }
  return (
    <div className={cx('chessboard', className)} role="img" aria-label={`Chess position ${snap?.fen ?? ''}${snap?.last ? `; last move ${snap.last.from} to ${snap.last.to}` : ''}${check ? `; the king on ${check} is in check` : ''}`}>
      {cells}
      {arrow && (
        <svg className="ch-arrow" viewBox="0 0 8 8" aria-hidden="true" key={snap?.fen}>
          <line x1={arrow.x1} y1={arrow.y1} x2={arrow.x2} y2={arrow.y2} pathLength={1} />
          <polygon points="0,-0.22 0.34,0 0,0.22" transform={`translate(${arrow.x2} ${arrow.y2}) rotate(${(Math.atan2(arrow.y2 - arrow.y1, arrow.x2 - arrow.x1) * 180) / Math.PI})`} />
        </svg>
      )}
    </div>
  );
}

/** Pieces one side has captured, most valuable first, with its material lead. */
export function CapturedTray({ pieces, color, lead, label }: { pieces: string[]; color: 'w' | 'b'; lead: number; label: string }) {
  return (
    <div className="ch-tray" aria-label={`${label} ${pieces.length ? pieces.map((p) => PIECE_NAME[p]).join(', ') : 'nothing'}${lead > 0 ? `; ahead by ${lead} points of material` : ''}`}>
      <span className="ch-tray-l ellipsis">{label}</span>
      <span className="ch-tray-p">
        {pieces.length === 0 ? <span className="muted">nothing yet</span> : pieces.map((p, i) => <ChessPieceSvg key={`${p}${i}`} piece={p} color={color} className={cx('ch-tray-i', i > 0 && pieces[i - 1] !== p && 'gap')} />)}
      </span>
      {lead > 0 && <b className="ch-tray-lead tnum">+{lead}</b>}
    </div>
  );
}

/** Vertical material bar (points of material, not an engine evaluation): White fills from the bottom. */
export function MaterialBar({ diff, points }: { diff: number; points: [number, number] }) {
  const clamp = Math.max(-15, Math.min(15, diff));
  const pct = 50 + (clamp / 15) * 45;
  return (
    <div className="ch-mbar" role="img" aria-label={`Material: White ${points[0]}, Black ${points[1]}`} title="Material count (pawn 1, knight 3, bishop 3, rook 5, queen 9). Not an engine evaluation.">
      <span className="ch-mbar-n tnum">{diff < 0 ? `+${-diff}` : ''}</span>
      <span className="ch-mbar-track">
        <i style={{ height: `${pct}%` }} />
        <u />
      </span>
      <span className="ch-mbar-n tnum">{diff > 0 ? `+${diff}` : diff === 0 ? '=' : ''}</span>
    </div>
  );
}

/** Chess stage: a side rail beside the board with Black's captures (top), the material bar and White's captures (bottom). */
export function ChessStage({ snap, names, className }: { snap: ChessSnapshot; names: [string, string]; className?: string }) {
  const m = chessMaterial(snap);
  return (
    <div className={cx('ch-stage', className)}>
      <div className="ch-rail">
        <CapturedTray pieces={m.captured[1]} color="w" lead={-m.diff} label={`${names[1]} (Black) took`} />
        <MaterialBar diff={m.diff} points={m.points} />
        <CapturedTray pieces={m.captured[0]} color="b" lead={m.diff} label={`${names[0]} (White) took`} />
      </div>
      <ChessBoard snap={snap} className={className} />
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
  const names: [string, string] = [who[0].label, who[1].label];
  const kind = (snap as { kind?: string } | null)?.kind;
  if (kind === 'poker') return summary ? <PokerSummary snap={snap as PokerSnapshot} players={who} /> : <PokerTable snap={snap as PokerSnapshot} players={who} thinking={thinking} className={className} />;
  if (kind === 'debate') return summary ? <DebateSummary snap={snap as DebateSnapshot} players={who} /> : <DebateStage snap={snap as DebateSnapshot} players={who} live={live} className={className} judgeHref={judgeHref} />;
  if (gameId === 'chess') return summary ? <ChessBoard snap={snap as ChessSnapshot} className={className} arrow={false} /> : <ChessStage snap={snap as ChessSnapshot} names={names} className={className} />;
  return <Connect4Board snap={snap as C4Snapshot} colors={colors} toMove={toMove} thinking={thinking} className={className} threats={!summary} names={names} />;
}

/** Small side token: a disc (Connect Four), a king (chess), a chip (poker) or a side badge (debate). */
export function SideToken({ gameId, side, color }: { gameId: string; side: 0 | 1; color: string }) {
  if (gameId === 'poker') return <span className="side-token chip" style={{ ['--c' as string]: color } as CSSProperties} aria-hidden="true" />;
  if (gameId === 'debate' || gameId === 'courtroom') return <span className="side-token badge-side" style={{ background: color }} aria-hidden="true">{gameId === 'courtroom' ? (side === 0 ? 'P' : 'D') : side === 0 ? '+' : '−'}</span>;
  if (gameId === 'chess')
    return (
      <span className={cx('side-token chess', side === 0 ? 'w' : 'b')} aria-hidden="true">
        <ChessPieceSvg piece="k" color={side === 0 ? 'w' : 'b'} />
      </span>
    );
  return <span className="side-token disc" style={{ background: color }} aria-hidden="true" />;
}
