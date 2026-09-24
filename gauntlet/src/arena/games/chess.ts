/**
 * Chess with full legal move generation: castling (never out of, through or
 * into check), en passant, promotion, check, checkmate, stalemate, the
 * 50-move rule, threefold repetition and insufficient material. Written from
 * scratch (no dependency) and verified against the standard perft node counts
 * in test/arena.test.ts.
 *
 * Moves are accepted in UCI ("e2e4", "e7e8q") or SAN ("Nf3", "exd5", "O-O",
 * "e8=Q+"), with common decorations stripped. A game that reaches the move cap
 * is adjudicated on material (see `capRule`).
 */
import type { ArenaGame, GameConfig, GameOutcome, ParsedMove } from '../types.ts';

type Color = 'w' | 'b';

export interface ChessState {
  /** 64 squares, index = rank * 8 + file (a1 = 0, h1 = 7, a8 = 56). 'PNBRQK' White, 'pnbrqk' Black, '' empty. */
  board: string[];
  turn: Color;
  /** Remaining castling rights, subset of "KQkq" ("" = none). */
  castling: string;
  /** En passant target square, or -1. */
  ep: number;
  halfmove: number;
  fullmove: number;
  plies: number;
  /** Repetition keys of every position so far, including the current one. */
  keys: string[];
  last: { from: number; to: number } | null;
}

export interface ChessSnapshot {
  fen: string;
  /** 64 chars from a8 to h1 (rank 8 first), '.' = empty. */
  board: string;
  last: { from: string; to: string } | null;
  /** Square of the king in check, if any. */
  check: string | null;
  turn: Color;
}

interface Move {
  from: number;
  to: number;
  promo?: string;
  /** 'n' normal, 'c' capture, 'e' en passant, 'k' / 'q' castling, 'd' double pawn push. */
  flag: 'n' | 'c' | 'e' | 'k' | 'q' | 'd';
}

export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

const FILES = 'abcdefgh';
export const sqName = (sq: number) => `${FILES[sq & 7]}${(sq >> 3) + 1}`;
export function sqIndex(name: string): number {
  const f = FILES.indexOf(name[0] ?? '');
  const r = Number(name[1]) - 1;
  return f >= 0 && r >= 0 && r < 8 ? r * 8 + f : -1;
}

const colorOf = (p: string): Color | null => (!p ? null : p === p.toUpperCase() ? 'w' : 'b');
const other = (c: Color): Color => (c === 'w' ? 'b' : 'w');

const KNIGHT = [
  [1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2],
] as const;
const KING = [
  [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1],
] as const;
const ROOK_DIRS = [
  [1, 0], [-1, 0], [0, 1], [0, -1],
] as const;
const BISHOP_DIRS = [
  [1, 1], [1, -1], [-1, 1], [-1, -1],
] as const;

function at(f: number, r: number): number {
  return f >= 0 && f < 8 && r >= 0 && r < 8 ? r * 8 + f : -1;
}

/** Is `sq` attacked by any piece of colour `by`? */
export function attacked(board: string[], sq: number, by: Color): boolean {
  const f = sq & 7;
  const r = sq >> 3;
  const up = by === 'w' ? 'P' : 'p';
  // A white pawn attacks upwards, so it sits one rank below the square.
  const pr = by === 'w' ? r - 1 : r + 1;
  for (const df of [-1, 1]) {
    const s = at(f + df, pr);
    if (s >= 0 && board[s] === up) return true;
  }
  const n = by === 'w' ? 'N' : 'n';
  for (const [df, dr] of KNIGHT) {
    const s = at(f + df, r + dr);
    if (s >= 0 && board[s] === n) return true;
  }
  const k = by === 'w' ? 'K' : 'k';
  for (const [df, dr] of KING) {
    const s = at(f + df, r + dr);
    if (s >= 0 && board[s] === k) return true;
  }
  const rq = by === 'w' ? ['R', 'Q'] : ['r', 'q'];
  for (const [df, dr] of ROOK_DIRS) {
    for (let i = 1; ; i++) {
      const s = at(f + df * i, r + dr * i);
      if (s < 0) break;
      const p = board[s]!;
      if (p) {
        if (rq.includes(p)) return true;
        break;
      }
    }
  }
  const bq = by === 'w' ? ['B', 'Q'] : ['b', 'q'];
  for (const [df, dr] of BISHOP_DIRS) {
    for (let i = 1; ; i++) {
      const s = at(f + df * i, r + dr * i);
      if (s < 0) break;
      const p = board[s]!;
      if (p) {
        if (bq.includes(p)) return true;
        break;
      }
    }
  }
  return false;
}

function kingSquare(board: string[], c: Color): number {
  return board.indexOf(c === 'w' ? 'K' : 'k');
}

export function inCheck(s: Pick<ChessState, 'board' | 'turn'>): boolean {
  const k = kingSquare(s.board, s.turn);
  return k >= 0 && attacked(s.board, k, other(s.turn));
}

function pseudoMoves(s: ChessState): Move[] {
  const out: Move[] = [];
  const { board, turn } = s;
  const enemy = other(turn);
  for (let sq = 0; sq < 64; sq++) {
    const p = board[sq]!;
    if (!p || colorOf(p) !== turn) continue;
    const f = sq & 7;
    const r = sq >> 3;
    const type = p.toUpperCase();
    if (type === 'P') {
      const dir = turn === 'w' ? 1 : -1;
      const startRank = turn === 'w' ? 1 : 6;
      const lastRank = turn === 'w' ? 7 : 0;
      const push = (to: number, flag: Move['flag']) => {
        if (to >> 3 === lastRank) for (const promo of ['q', 'r', 'b', 'n']) out.push({ from: sq, to, promo, flag });
        else out.push({ from: sq, to, flag });
      };
      const one = at(f, r + dir);
      if (one >= 0 && !board[one]) {
        push(one, 'n');
        const two = at(f, r + 2 * dir);
        if (r === startRank && two >= 0 && !board[two]) out.push({ from: sq, to: two, flag: 'd' });
      }
      for (const df of [-1, 1]) {
        const to = at(f + df, r + dir);
        if (to < 0) continue;
        if (board[to] && colorOf(board[to]!) === enemy) push(to, 'c');
        else if (to === s.ep) out.push({ from: sq, to, flag: 'e' });
      }
    } else if (type === 'N' || type === 'K') {
      for (const [df, dr] of type === 'N' ? KNIGHT : KING) {
        const to = at(f + df, r + dr);
        if (to < 0) continue;
        const q = board[to]!;
        if (!q) out.push({ from: sq, to, flag: 'n' });
        else if (colorOf(q) === enemy) out.push({ from: sq, to, flag: 'c' });
      }
      if (type === 'K') {
        const home = turn === 'w' ? 4 : 60;
        if (sq === home && !attacked(board, home, enemy)) {
          const [kr, qr] = turn === 'w' ? ['K', 'Q'] : ['k', 'q'];
          const rook = turn === 'w' ? 'R' : 'r';
          if (s.castling.includes(kr) && board[home + 3] === rook && !board[home + 1] && !board[home + 2] && !attacked(board, home + 1, enemy) && !attacked(board, home + 2, enemy)) {
            out.push({ from: home, to: home + 2, flag: 'k' });
          }
          if (s.castling.includes(qr) && board[home - 4] === rook && !board[home - 1] && !board[home - 2] && !board[home - 3] && !attacked(board, home - 1, enemy) && !attacked(board, home - 2, enemy)) {
            out.push({ from: home, to: home - 2, flag: 'q' });
          }
        }
      }
    } else {
      const dirs = type === 'R' ? ROOK_DIRS : type === 'B' ? BISHOP_DIRS : [...ROOK_DIRS, ...BISHOP_DIRS];
      for (const [df, dr] of dirs) {
        for (let i = 1; ; i++) {
          const to = at(f + df * i, r + dr * i);
          if (to < 0) break;
          const q = board[to]!;
          if (!q) out.push({ from: sq, to, flag: 'n' });
          else {
            if (colorOf(q) === enemy) out.push({ from: sq, to, flag: 'c' });
            break;
          }
        }
      }
    }
  }
  return out;
}

/** Board after a move (no bookkeeping). */
function moveBoard(board: string[], m: Move, turn: Color): string[] {
  const b = board.slice();
  const piece = b[m.from]!;
  b[m.from] = '';
  b[m.to] = m.promo ? (turn === 'w' ? m.promo.toUpperCase() : m.promo) : piece;
  if (m.flag === 'e') b[m.to + (turn === 'w' ? -8 : 8)] = '';
  if (m.flag === 'k') {
    b[m.from + 1] = b[m.from + 3]!;
    b[m.from + 3] = '';
  } else if (m.flag === 'q') {
    b[m.from - 1] = b[m.from - 4]!;
    b[m.from - 4] = '';
  }
  return b;
}

const legalCache = new WeakMap<ChessState, Move[]>();

function legal(s: ChessState): Move[] {
  const hit = legalCache.get(s);
  if (hit) return hit;
  const moves = pseudoMoves(s).filter((m) => {
    const b = moveBoard(s.board, m, s.turn);
    const k = kingSquare(b, s.turn);
    return !attacked(b, k, other(s.turn));
  });
  legalCache.set(s, moves);
  return moves;
}

const uci = (m: Move) => `${sqName(m.from)}${sqName(m.to)}${m.promo ?? ''}`;

function positionKey(s: Omit<ChessState, 'keys'>): string {
  // The en passant square only matters (FIDE 9.2) when an en passant capture is actually legal.
  const epRelevant = s.ep >= 0 && legal({ ...s, keys: [] } as ChessState).some((m) => m.flag === 'e');
  return `${s.board.map((p) => p || '.').join('')} ${s.turn} ${s.castling || '-'} ${epRelevant ? sqName(s.ep) : '-'}`;
}

export function fromFen(fen: string): ChessState {
  const [placement, turn = 'w', castling = '-', ep = '-', half = '0', full = '1'] = fen.trim().split(/\s+/);
  const board: string[] = Array.from({ length: 64 }, () => '');
  const ranks = (placement ?? '').split('/');
  if (ranks.length !== 8) throw new Error(`Invalid FEN: ${fen}`);
  ranks.forEach((row, i) => {
    let f = 0;
    for (const ch of row) {
      if (/\d/.test(ch)) f += Number(ch);
      else board[(7 - i) * 8 + f++] = ch;
    }
  });
  const base = {
    board,
    turn: (turn === 'b' ? 'b' : 'w') as Color,
    castling: castling === '-' ? '' : castling,
    ep: ep === '-' ? -1 : sqIndex(ep),
    halfmove: Number(half) || 0,
    fullmove: Number(full) || 1,
    plies: 0,
    last: null,
  };
  return { ...base, keys: [positionKey(base)] };
}

export function toFen(s: ChessState): string {
  const rows: string[] = [];
  for (let r = 7; r >= 0; r--) {
    let row = '';
    let gap = 0;
    for (let f = 0; f < 8; f++) {
      const p = s.board[r * 8 + f]!;
      if (!p) gap++;
      else {
        if (gap) row += gap;
        gap = 0;
        row += p;
      }
    }
    if (gap) row += gap;
    rows.push(row);
  }
  return `${rows.join('/')} ${s.turn} ${s.castling || '-'} ${s.ep >= 0 ? sqName(s.ep) : '-'} ${s.halfmove} ${s.fullmove}`;
}

function applyMove(s: ChessState, m: Move): ChessState {
  const piece = s.board[m.from]!;
  const board = moveBoard(s.board, m, s.turn);
  let castling = s.castling;
  if (piece === 'K') castling = castling.replace(/[KQ]/g, '');
  if (piece === 'k') castling = castling.replace(/[kq]/g, '');
  const touch = (sq: number, right: string) => {
    if (m.from === sq || m.to === sq) castling = castling.replace(right, '');
  };
  touch(0, 'Q');
  touch(7, 'K');
  touch(56, 'q');
  touch(63, 'k');
  const capture = m.flag === 'c' || m.flag === 'e';
  const next = {
    board,
    turn: other(s.turn),
    castling,
    ep: m.flag === 'd' ? (m.from + m.to) / 2 : -1,
    halfmove: capture || piece.toUpperCase() === 'P' ? 0 : s.halfmove + 1,
    fullmove: s.turn === 'b' ? s.fullmove + 1 : s.fullmove,
    plies: s.plies + 1,
    last: { from: m.from, to: m.to },
  };
  return { ...next, keys: [...s.keys, positionKey(next)] };
}

function findMove(s: ChessState, id: string): Move | undefined {
  return legal(s).find((m) => uci(m) === id);
}

/** Standard Algebraic Notation of a legal move in this position (with + / #). */
export function toSan(s: ChessState, id: string): string {
  const m = findMove(s, id);
  if (!m) return id;
  let san: string;
  if (m.flag === 'k') san = 'O-O';
  else if (m.flag === 'q') san = 'O-O-O';
  else {
    const piece = s.board[m.from]!.toUpperCase();
    const capture = m.flag === 'c' || m.flag === 'e';
    if (piece === 'P') {
      san = `${capture ? `${FILES[m.from & 7]}x` : ''}${sqName(m.to)}${m.promo ? `=${m.promo.toUpperCase()}` : ''}`;
    } else {
      const rivals = legal(s).filter((o) => o.to === m.to && o.from !== m.from && s.board[o.from]!.toUpperCase() === piece);
      let dis = '';
      if (rivals.length) {
        const sameFile = rivals.some((o) => (o.from & 7) === (m.from & 7));
        const sameRank = rivals.some((o) => o.from >> 3 === m.from >> 3);
        if (!sameFile) dis = FILES[m.from & 7]!;
        else if (!sameRank) dis = String((m.from >> 3) + 1);
        else dis = sqName(m.from);
      }
      san = `${piece}${dis}${capture ? 'x' : ''}${sqName(m.to)}`;
    }
  }
  const after = applyMove(s, m);
  if (inCheck(after)) san += legal(after).length === 0 ? '#' : '+';
  return san;
}

const VALUES: Record<string, number> = { P: 1, N: 3, B: 3, R: 5, Q: 9, K: 0 };

export function material(board: string[]): { w: number; b: number } {
  let w = 0;
  let b = 0;
  for (const p of board) {
    if (!p) continue;
    const v = VALUES[p.toUpperCase()] ?? 0;
    if (p === p.toUpperCase()) w += v;
    else b += v;
  }
  return { w, b };
}

/** Neither side can possibly checkmate: K v K, K+minor v K, K+B v K+B with same-coloured bishops. */
export function insufficientMaterial(board: string[]): boolean {
  const pieces: Array<{ p: string; sq: number }> = [];
  board.forEach((p, sq) => {
    if (p && p.toUpperCase() !== 'K') pieces.push({ p, sq });
  });
  if (pieces.length === 0) return true;
  if (pieces.length === 1) return ['N', 'B'].includes(pieces[0]!.p.toUpperCase());
  if (pieces.every((x) => x.p.toUpperCase() === 'B')) {
    const shade = (sq: number) => ((sq & 7) + (sq >> 3)) % 2;
    return new Set(pieces.map((x) => shade(x.sq))).size === 1;
  }
  return false;
}

// ─────────────────────────────── Move parsing ───────────────────────────────

const SAN_RE = /^([NBRQK])?([a-h])?([1-8])?([a-h][1-8])([QRBN])?$/;

function sideName(c: Color) {
  return c === 'w' ? 'White' : 'Black';
}

/** Parse UCI or SAN text into a canonical UCI move id. Exported for tests. */
export function parseChessMove(s: ChessState, raw: string): ParsedMove {
  let t = raw.trim();
  // Markdown, quotes, move numbers ("12." / "12..."), trailing commentary.
  t = t.replace(/[*_`"'“”‘’]/g, '').trim();
  t = t.replace(/^\d+\s*\.+\s*/, '');
  t = t.replace(/^(white|black)\s*:?\s*/i, '');
  t = t.replace(/^(i\s+)?(will\s+|'ll\s+)?(play|move|go with|choose)\s+/i, '');
  // "e2 e4" / "e2 - e4" / "e2 to e4"
  const spaced = t.match(/^([a-h][1-8])\s*(?:-|to|x)?\s*([a-h][1-8])(?:\s*=?\s*([qrbnQRBN])\b)?/i);
  let token = spaced ? `${spaced[1]}${spaced[2]}${spaced[3] ?? ''}` : (t.split(/[\s,;()]+/)[0] ?? '');
  token = token.replace(/[.!?:]+$/, '').replace(/(e\.p\.?|ep)$/i, '');
  // Coordinates in capitals ("E2E4") are still coordinates.
  if (/^[a-h][1-8][-x:]?[a-h][1-8]=?[qrbn]?[+#]?$/i.test(token)) token = token.toLowerCase();
  if (!token) return { ok: false, error: 'The MOVE line is empty. Write one move, for example "MOVE: e2e4" or "MOVE: Nf3".' };
  const moves = legal(s);
  const who = sideName(s.turn);

  // Castling
  const castle = token.replace(/[+#]/g, '').toUpperCase().replace(/0/g, 'O');
  if (/^O-?O-?O$/.test(castle) || /^O-?O$/.test(castle)) {
    const flag = castle.replace(/-/g, '').length === 3 ? 'q' : 'k';
    const m = moves.find((x) => x.flag === flag);
    if (m) return { ok: true, move: uci(m) };
    return { ok: false, error: `${who} cannot castle ${flag === 'k' ? 'kingside (O-O)' : 'queenside (O-O-O)'} in this position (the king or rook has moved, a square between them is occupied or attacked, or the king is in check).` };
  }

  // UCI / long algebraic: e2e4, e2-e4, e7e8q, e7e8=Q, Ng1f3, Ng1-f3, e4xd5
  const long = token.replace(/[+#]/g, '').match(/^[NBRQKP]?([a-h][1-8])[-x:]?([a-h][1-8])=?([qrbnQRBN])?$/);
  if (long) {
    const from = sqIndex(long[1]!.toLowerCase());
    const to = sqIndex(long[2]!.toLowerCase());
    const promo = long[3]?.toLowerCase();
    const cands = moves.filter((m) => m.from === from && m.to === to);
    if (!cands.length) {
      const p = s.board[from];
      if (!p) return { ok: false, error: `There is no piece on ${long[1]}. It is ${who}'s move.` };
      if (colorOf(p) !== s.turn) return { ok: false, error: `The piece on ${long[1]} belongs to ${sideName(other(s.turn))}; you are playing ${who}.` };
      return { ok: false, error: `${long[1]}${long[2]} is not a legal move for ${who} in this position${inCheck(s) ? ' (you are in check)' : ''}.` };
    }
    if (cands.length > 1) {
      if (!promo) return { ok: false, error: `${long[1]}${long[2]} is a promotion: add the piece, e.g. "${long[1]}${long[2]}q" for a queen.` };
      const m = cands.find((x) => x.promo === promo);
      return m ? { ok: true, move: uci(m) } : { ok: false, error: `Unknown promotion piece "${long[3]}". Use q, r, b or n.` };
    }
    return { ok: true, move: uci(cands[0]!) };
  }

  // SAN
  const bare = token.replace(/[+#x:=]/g, '').replace(/^P(?=[a-h])/, '').replace(/([1-8])([qrbn])$/, (_, d: string, p: string) => d + p.toUpperCase());
  const attempts = [bare];
  if (/^[nrqk][a-h1-8]/.test(bare)) attempts.push(bare[0]!.toUpperCase() + bare.slice(1));
  if (/^b[a-h1-8]/.test(bare)) attempts.push('B' + bare.slice(1));
  let ambiguous: Move[] | null = null;
  for (const a of attempts) {
    const m = a.match(SAN_RE);
    if (!m) continue;
    const piece = m[1] ?? 'P';
    const to = sqIndex(m[4]!);
    const promo = m[5]?.toLowerCase();
    const cands = moves.filter(
      (x) =>
        x.to === to &&
        s.board[x.from]!.toUpperCase() === piece &&
        (!m[2] || FILES[x.from & 7] === m[2]) &&
        (!m[3] || String((x.from >> 3) + 1) === m[3]) &&
        (!x.promo || !promo || x.promo === promo),
    );
    if (cands.length === 1) return { ok: true, move: uci(cands[0]!) };
    if (cands.length > 1) {
      if (cands.every((x) => x.promo) && !promo) return { ok: false, error: `${token} is a promotion: add the piece, e.g. "${m[4]}=Q".` };
      ambiguous = cands;
    }
  }
  if (ambiguous) {
    const opts = ambiguous.map((m) => toSan(s, uci(m))).join(' or ');
    return { ok: false, error: `"${token}" is ambiguous: it could mean ${opts}. Say which piece (or use UCI like ${uci(ambiguous[0]!)}).` };
  }
  if (attempts.some((a) => SAN_RE.test(a))) return { ok: false, error: `${token} is not a legal move for ${who} in this position${inCheck(s) ? ' (you are in check)' : ''}.` };
  return { ok: false, error: `Could not read "${token.slice(0, 40)}" as a chess move. Use UCI (e2e4, e7e8q) or SAN (Nf3, exd5, O-O).` };
}

// ─────────────────────────────── Game definition ───────────────────────────────

function board(s: ChessState): string {
  const lines = ['    a b c d e f g h', '  +-----------------+'];
  for (let r = 7; r >= 0; r--) {
    const row: string[] = [];
    for (let f = 0; f < 8; f++) row.push(s.board[r * 8 + f] || '.');
    lines.push(`${r + 1} | ${row.join(' ')} | ${r + 1}`);
  }
  lines.push('  +-----------------+', '    a b c d e f g h');
  return lines.join('\n');
}

const CAP_MARGIN = 3;

export const chess: ArenaGame<ChessState> = {
  id: 'chess',
  name: 'Chess',
  version: '1.0.0',
  tagline: 'The oldest benchmark there is. Every move must be legal.',
  description:
    'Standard chess with every rule enforced by the harness: castling, en passant, promotion, check, checkmate, stalemate and the draw rules. It tests board reading, legal-move discipline and tactics.',
  sides: [
    { name: 'White', color: '#f4f1ea' },
    { name: 'Black', color: '#1f2328' },
  ],
  rules: [
    'Standard FIDE chess rules apply. White moves first.',
    'Castling, en passant and promotion are allowed. You may not make a move that leaves your own king in check; you may not castle out of, through or into check.',
    'Checkmate wins. Stalemate is a draw. The game is also drawn automatically by threefold repetition, by the 50-move rule (50 moves by each side with no capture or pawn move) and when neither side has enough material to checkmate.',
    `If the game reaches the move cap without a result, it is adjudicated on material (pawn 1, knight 3, bishop 3, rook 5, queen 9): a side ahead by at least ${CAP_MARGIN} points wins, otherwise it is a draw.`,
    'The board is shown with White at the bottom. Uppercase letters are White pieces, lowercase are Black (K king, Q queen, R rook, B bishop, N knight, P pawn), "." is an empty square.',
  ].join('\n'),
  moveHelp: 'UCI coordinates such as "MOVE: e2e4" (promotion: "MOVE: e7e8q"), or SAN such as "MOVE: Nf3" or "MOVE: O-O"',
  defaults: { maxPlies: 120, listLegalMoves: true },
  estimate: { pliesPerGame: 90, inputTokensPerMove: 1100, outputTokensPerMove: 2500 },
  capRule: `After the move cap (120 half-moves by default) the game is decided on material: pawn 1, knight 3, bishop 3, rook 5, queen 9. A lead of ${CAP_MARGIN}+ points wins; anything less is a draw.`,

  setup(_rng, config: GameConfig) {
    return fromFen(typeof config.startFen === 'string' && config.startFen ? config.startFen : START_FEN);
  },
  toMove: (s) => (s.turn === 'w' ? 0 : 1),
  legalMoves: (s) => legal(s).map(uci),
  parseMove: parseChessMove,
  play(s, move) {
    const m = findMove(s, move);
    if (!m) throw new Error(`Illegal chess move ${move} in ${toFen(s)}`);
    return applyMove(s, m);
  },
  outcome(s): GameOutcome | null {
    if (legal(s).length === 0) {
      if (inCheck(s)) return { winner: s.turn === 'w' ? 1 : 0, reason: 'Checkmate' };
      return { winner: null, reason: 'Stalemate' };
    }
    if (insufficientMaterial(s.board)) return { winner: null, reason: 'Insufficient material' };
    if (s.halfmove >= 100) return { winner: null, reason: '50-move rule' };
    const key = s.keys[s.keys.length - 1];
    if (s.keys.filter((k) => k === key).length >= 3) return { winner: null, reason: 'Threefold repetition' };
    return null;
  },
  adjudicate(s) {
    const { w, b } = material(s.board);
    if (w - b >= CAP_MARGIN) return { winner: 0, reason: `Move cap: White ahead on material ${w}–${b}` };
    if (b - w >= CAP_MARGIN) return { winner: 1, reason: `Move cap: Black ahead on material ${b}–${w}` };
    return { winner: null, reason: `Move cap: material close (${w}–${b}), draw` };
  },
  label: (s, move) => toSan(s, move),
  formatHistory(labels) {
    if (!labels.length) return '(no moves yet)';
    const out: string[] = [];
    for (let i = 0; i < labels.length; i += 2) out.push(`${i / 2 + 1}. ${labels[i]}${labels[i + 1] ? ` ${labels[i + 1]}` : ''}`);
    return out.join(' ');
  },
  view(s, side) {
    const { w, b } = material(s.board);
    return [
      board(s),
      `FEN: ${toFen(s)}`,
      `Side to move: ${s.turn === 'w' ? 'White' : 'Black'} (you are ${side === 0 ? 'White' : 'Black'}).`,
      `Castling rights: ${s.castling || 'none'}. En passant square: ${s.ep >= 0 ? sqName(s.ep) : 'none'}. Half-moves since last capture or pawn move: ${s.halfmove}.`,
      `Material: White ${w}, Black ${b}.`,
      inCheck(s) ? 'YOUR KING IS IN CHECK.' : '',
    ]
      .filter(Boolean)
      .join('\n');
  },
  snapshot(s): ChessSnapshot {
    let b = '';
    for (let r = 7; r >= 0; r--) for (let f = 0; f < 8; f++) b += s.board[r * 8 + f] || '.';
    const k = kingSquare(s.board, s.turn);
    return {
      fen: toFen(s),
      board: b,
      last: s.last ? { from: sqName(s.last.from), to: sqName(s.last.to) } : null,
      check: inCheck(s) && k >= 0 ? sqName(k) : null,
      turn: s.turn,
    };
  },
};

/** Count leaf nodes of the legal move tree (move-generator correctness check). */
export function perft(s: ChessState, depth: number): number {
  if (depth === 0) return 1;
  const moves = legal(s);
  if (depth === 1) return moves.length;
  let n = 0;
  for (const m of moves) {
    const piece = s.board[m.from]!;
    const next = { board: moveBoard(s.board, m, s.turn), turn: other(s.turn), castling: s.castling, ep: m.flag === 'd' ? (m.from + m.to) / 2 : -1, halfmove: 0, fullmove: 1, plies: 0, keys: [], last: null } as ChessState;
    let c = s.castling;
    if (piece === 'K') c = c.replace(/[KQ]/g, '');
    if (piece === 'k') c = c.replace(/[kq]/g, '');
    for (const [sq, right] of [[0, 'Q'], [7, 'K'], [56, 'q'], [63, 'k']] as const) if (m.from === sq || m.to === sq) c = c.replace(right, '');
    next.castling = c;
    n += perft(next, depth - 1);
  }
  return n;
}
