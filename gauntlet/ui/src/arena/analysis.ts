/**
 * Read-only analysis of recorded Arena snapshots for the on-screen story:
 * Connect Four threats ("drop here to win"), chess material and captures,
 * and one plain-English headline per move. Everything is computed from the
 * stored snapshots (no engine, nothing invented), so it can never change a
 * game or its fingerprint.
 */
import type { ArenaMove, C4Snapshot, ChessSnapshot, DebateSnapshot, PokerSnapshot, Side } from './types.ts';
import { PIECE_NAME, PIECE_VALUE } from '../components/viz/chessPieceInfo.ts';

// ─────────────────────────────── Connect Four ───────────────────────────────

const MARKS = ['X', 'O'] as const;

/** cells[row][col], row 0 = bottom. */
function c4Cells(snap: C4Snapshot | null | undefined): string[][] {
  const rows = snap?.rows ?? Array.from({ length: 6 }, () => '.......');
  return [...rows].reverse().map((r) => Array.from(r));
}

function fourAt(cells: string[][], col: number, row: number, mark: string): boolean {
  const R = cells.length;
  const C = cells[0]?.length ?? 7;
  const at = (c: number, r: number) => (c >= 0 && c < C && r >= 0 && r < R ? cells[r]![c] : '');
  for (const [dc, dr] of [
    [1, 0],
    [0, 1],
    [1, 1],
    [1, -1],
  ] as const) {
    let n = 1;
    for (let k = 1; k < 4 && at(col + dc * k, row + dr * k) === mark; k++) n++;
    for (let k = 1; k < 4 && at(col - dc * k, row - dr * k) === mark; k++) n++;
    if (n >= 4) return true;
  }
  return false;
}

/** Columns (0-based) where each side would complete four with its next disc. */
export function c4Threats(snap: C4Snapshot | null | undefined): [number[], number[]] {
  const out: [number[], number[]] = [[], []];
  if (!snap || snap.win) return out;
  const cells = c4Cells(snap);
  const C = cells[0]?.length ?? 7;
  for (let c = 0; c < C; c++) {
    let h = 0;
    while (h < cells.length && cells[h]![c] !== '.') h++;
    if (h >= cells.length) continue;
    for (const s of [0, 1] as const) {
      cells[h]![c] = MARKS[s];
      if (fourAt(cells, c, h, MARKS[s])) out[s].push(c);
      cells[h]![c] = '.';
    }
  }
  return out;
}

// ─────────────────────────────── Chess ───────────────────────────────

const START_COUNT: Record<string, number> = { p: 8, n: 2, b: 2, r: 2, q: 1, k: 1 };

export interface ChessMaterial {
  /** Material points per side (P1 N3 B3 R5 Q9). */
  points: [number, number];
  /** Pieces each side has captured (the opponent's missing pieces), most valuable first. */
  captured: [string[], string[]];
  /** White minus Black. */
  diff: number;
}

export function chessMaterial(snap: ChessSnapshot | null | undefined): ChessMaterial {
  const board = snap?.board ?? '';
  const count: [Record<string, number>, Record<string, number>] = [{}, {}];
  for (const ch of board) {
    if (ch === '.') continue;
    const side = ch === ch.toUpperCase() ? 0 : 1;
    const p = ch.toLowerCase();
    count[side][p] = (count[side][p] ?? 0) + 1;
  }
  const points: [number, number] = [0, 0];
  for (const s of [0, 1] as const) for (const [p, n] of Object.entries(count[s])) points[s] += (PIECE_VALUE[p] ?? 0) * n;
  // A side "captured" the opponent's pieces that are missing from the start (promotions can hide a pawn loss; counts never go negative).
  const captured: [string[], string[]] = [[], []];
  for (const s of [0, 1] as const) {
    const opp = count[(1 - s) as Side];
    for (const p of ['q', 'r', 'b', 'n', 'p']) {
      const missing = Math.max(0, START_COUNT[p]! - (opp[p] ?? 0));
      for (let i = 0; i < missing; i++) captured[s].push(p);
    }
  }
  return { points, captured, diff: points[0] - points[1] };
}

/** The piece standing on a square of a snapshot board ('' when empty). */
export function pieceOn(snap: ChessSnapshot | null | undefined, square: string | undefined): string {
  if (!snap?.board || !square) return '';
  const f = 'abcdefgh'.indexOf(square[0]!);
  const r = Number(square[1]) - 1;
  if (f < 0 || r < 0 || r > 7) return '';
  const ch = snap.board[(7 - r) * 8 + f]!;
  return ch === '.' ? '' : ch;
}

// ─────────────────────────────── Headlines ───────────────────────────────

export type Tone = 'good' | 'bad' | 'neutral';

export interface MoveHeadline {
  text: string;
  tone: Tone;
}

const cols = (xs: number[]) => xs.map((c) => c + 1).join(' and ');

/**
 * One plain-English sentence for the move that produced `snap` from `prev`.
 * `names` are the labels of seat 0 and seat 1.
 */
export function moveHeadline(gameId: string, move: ArenaMove, prev: unknown, names: [string, string]): MoveHeadline {
  const me = names[move.side];
  const opp = names[1 - move.side]!;
  if (move.kind === 'verdict') return { text: move.label, tone: 'neutral' };
  if (move.opening) return { text: `Random opening move ${move.label} (played by the harness, not a model)`, tone: 'neutral' };
  if (gameId === 'connect4') {
    const snap = move.snapshot as C4Snapshot;
    const col = Number(move.move) - 1;
    const before = c4Threats(prev as C4Snapshot);
    const after = c4Threats(snap);
    if (move.forfeit) return { text: `${me} gave two illegal replies: the harness dropped a random disc in column ${col + 1} and gave a strike`, tone: 'bad' };
    if (snap?.win) return { text: `${me} drops in column ${col + 1} and connects four!`, tone: 'good' };
    const mine = before[move.side];
    if (mine.length && !mine.includes(col)) return { text: `${me} misses a win in column ${cols(mine)} and plays column ${col + 1}`, tone: 'bad' };
    const theirs = before[1 - move.side]!;
    const oppNext = after[1 - move.side]!;
    if (theirs.includes(col) && !oppNext.length) return { text: `${me} blocks ${opp}’s winning spot in column ${col + 1}`, tone: 'good' };
    if (oppNext.length) return { text: `${me} plays column ${col + 1}, but ${opp} can win next move in column ${cols(oppNext)}`, tone: 'bad' };
    const newMine = after[move.side].filter((c) => !mine.includes(c));
    if (newMine.length) return { text: `${me} plays column ${col + 1} and threatens to win in column ${cols(newMine)}`, tone: 'good' };
    return { text: `${me} drops a disc in column ${col + 1}`, tone: 'neutral' };
  }
  if (gameId === 'chess') {
    const snap = move.snapshot as ChessSnapshot;
    const p = prev as ChessSnapshot;
    const san = move.label;
    if (move.forfeit) return { text: `${me} gave two illegal replies: the harness played a random legal move (${san}) and gave a strike`, tone: 'bad' };
    const moved = pieceOn(p, snap?.last?.from);
    let taken = pieceOn(p, snap?.last?.to);
    if (!taken && moved.toLowerCase() === 'p' && snap?.last && snap.last.from[0] !== snap.last.to[0]) taken = moved === 'P' ? 'p' : 'P'; // en passant
    const parts: string[] = [];
    if (/^O-O-O/.test(san)) parts.push(`${me} castles queenside`);
    else if (/^O-O/.test(san)) parts.push(`${me} castles kingside`);
    else parts.push(`${me} plays ${san}`);
    if (taken) parts.push(`takes a ${PIECE_NAME[taken.toLowerCase()]}`);
    const promo = /=([QRBN])/.exec(san);
    if (promo) parts.push(`promotes to a ${PIECE_NAME[promo[1]!.toLowerCase()]}`);
    if (san.includes('#')) parts.push('checkmate!');
    else if (snap?.check) parts.push('check!');
    const tone: Tone = san.includes('#') || (taken && (PIECE_VALUE[taken.toLowerCase()] ?? 0) >= 3) || promo ? 'good' : 'neutral';
    return { text: parts.join(' — '), tone };
  }
  if (gameId === 'poker') {
    const snap = move.snapshot as PokerSnapshot;
    const what = move.label.replace(/^H\d+ [^:]+: /, '');
    const street = /^H\d+ ([^:]+):/.exec(move.label)?.[1]?.toLowerCase() ?? '';
    if (move.forfeit) return { text: `${me} gave two illegal replies: the harness played “${what}” and gave a strike`, tone: 'bad' };
    const verb = what.replace(/^bet/, 'bets').replace(/^raise/, 'raises').replace(/^call/, 'calls').replace(/^check/, 'checks').replace(/^fold/, 'folds').replace(/^all-in (\d+)/, 'goes all-in ($1)');
    const base = `${me} ${verb}${street ? ` on the ${street}` : ''}`;
    const e = snap?.ended;
    if (e) {
      if (e.winner === null) return { text: `${base}: split pot`, tone: 'neutral' };
      if (e.how === 'fold' && e.winner !== move.side) return { text: `${base}: ${names[e.winner]} takes the pot (+${Math.abs(e.delta[e.winner])} chips)`, tone: 'bad' };
      const w = names[e.winner];
      const n = Math.abs(e.delta[e.winner]);
      const how = e.how === 'fold' ? `${names[1 - e.winner]} folds: ${w} takes ${n} chips` : `showdown: ${w} wins ${n} chips with ${e.hands?.[e.winner]?.name ?? 'the better hand'}`;
      return { text: `${base} — ${how}`, tone: e.winner === move.side ? 'good' : 'bad' };
    }
    return { text: base, tone: /all-in|raise/.test(what) ? 'good' : /fold/.test(what) ? 'bad' : 'neutral' };
  }
  // Debate / courtroom
  const snap = move.snapshot as DebateSnapshot;
  const sp = snap?.speeches?.[snap.speeches.length - 1];
  if (sp) {
    const round = snap.roundNames[sp.round] ?? `Round ${sp.round + 1}`;
    if (sp.missing) return { text: `${me} (${snap.sides[move.side]}) gave no ${round.toLowerCase()}: a strike`, tone: 'bad' };
    const ex = [...new Set([...sp.text.matchAll(/Exhibits? ([A-E](?:\s*(?:,|and|&)\s*[A-E])*)/g)].flatMap((m) => m[1]!.match(/[A-E]/g) ?? []))].sort();
    const cite = snap.variant === 'courtroom' ? (ex.length ? ` · cites Exhibit${ex.length > 1 ? 's' : ''} ${ex.join(', ')}` : ' · cites no exhibits') : '';
    const cut = sp.cut > 0 ? ` · ${sp.cut} words over the limit (cut)` : '';
    return { text: `${me} (${snap.sides[move.side]}): ${round.toLowerCase()} · ${sp.words}/${sp.limit} words${cite}${cut}`, tone: sp.cut > 0 ? 'bad' : 'neutral' };
  }
  return { text: `${me}: ${move.label}`, tone: 'neutral' };
}

// ─────────────────────────────── Poker ───────────────────────────────

/** Hand categories from weakest to strongest (names as the evaluator writes them). */
export const HAND_LADDER = ['High card', 'Pair', 'Two pair', 'Three of a kind', 'Straight', 'Flush', 'Full house', 'Four of a kind', 'Straight flush'];

/** Position of an evaluator hand name ("Two pair, Kings and Fours") on HAND_LADDER. */
export function handCategory(name: string): number {
  if (/^(Straight flush|Royal flush)/.test(name)) return 8;
  if (name.startsWith('Four of a kind')) return 7;
  if (name.startsWith('Full house')) return 6;
  if (name.startsWith('Flush')) return 5;
  if (name.startsWith('Straight')) return 4;
  if (name.startsWith('Three of a kind')) return 3;
  if (name.startsWith('Two pair')) return 2;
  if (name.startsWith('Pair')) return 1;
  return 0;
}

/** How many chips to draw for an amount: grows quickly at first, then slowly (max 12). */
export function chipCount(amount: number): number {
  if (!(amount > 0)) return 0;
  return Math.min(12, 1 + Math.floor(Math.sqrt(amount / 2)));
}
