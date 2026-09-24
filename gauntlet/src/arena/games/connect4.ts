/**
 * Connect Four (7 columns × 6 rows). Red (seat 0) drops first; four in a row
 * horizontally, vertically or diagonally wins; a full board is a draw.
 */
import type { ArenaGame, GameOutcome, ParsedMove, Side } from '../types.ts';

export const COLS = 7;
export const ROWS = 6;

export interface C4State {
  /** cells[row][col], row 0 = bottom. '' empty, 'X' Red, 'O' Yellow. */
  cells: string[][];
  turn: Side;
  plies: number;
  last: { col: number; row: number } | null;
}

export interface C4Snapshot {
  /** Top row first, one char per column: '.', 'X' (Red), 'O' (Yellow). */
  rows: string[];
  last: { col: number; row: number } | null;
  /** Cells of the winning line as [col, row] (row 0 = bottom). */
  win: Array<[number, number]> | null;
}

const MARK = ['X', 'O'] as const;

function empty(): string[][] {
  return Array.from({ length: ROWS }, () => Array.from({ length: COLS }, () => ''));
}

function height(s: C4State, col: number): number {
  let h = 0;
  while (h < ROWS && s.cells[h]![col]) h++;
  return h;
}

/** The winning line, if any. */
export function findWin(cells: string[][]): { mark: string; line: Array<[number, number]> } | null {
  const dirs: Array<[number, number]> = [
    [1, 0],
    [0, 1],
    [1, 1],
    [1, -1],
  ];
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const m = cells[r]![c];
      if (!m) continue;
      for (const [dc, dr] of dirs) {
        const line: Array<[number, number]> = [[c, r]];
        for (let k = 1; k < 4; k++) {
          const cc = c + dc * k;
          const rr = r + dr * k;
          if (cc < 0 || cc >= COLS || rr < 0 || rr >= ROWS || cells[rr]![cc] !== m) break;
          line.push([cc, rr]);
        }
        if (line.length === 4) return { mark: m, line };
      }
    }
  }
  return null;
}

const WORDS: Record<string, number> = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7 };

export const connect4: ArenaGame<C4State> = {
  id: 'connect4',
  name: 'Connect Four',
  version: '1.0.0',
  tagline: 'Four in a row. Seven columns. No second chances.',
  description:
    'The classic vertical four-in-a-row game on a 7×6 board. It tests whether a model can read a grid, see threats coming and plan a few moves ahead.',
  sides: [
    { name: 'Red', color: '#e5484d' },
    { name: 'Yellow', color: '#f5c518' },
  ],
  rules: [
    'Connect Four is played on a board 7 columns wide and 6 rows tall. Columns are numbered 1 to 7 from left to right.',
    'Red (X) and Yellow (O) take turns. Red moves first. On your turn you drop one disc into a column that is not full; it falls to the lowest empty cell of that column.',
    'The first player to get four of their discs in a straight line (horizontal, vertical or diagonal) wins immediately.',
    'If the board fills up with no four-in-a-row, the game is a draw.',
  ].join('\n'),
  moveHelp: 'the column number from 1 to 7, for example "MOVE: 4"',
  defaults: { maxPlies: 42, listLegalMoves: true },
  estimate: { pliesPerGame: 30, inputTokensPerMove: 650, outputTokensPerMove: 1200 },
  capRule: 'Connect Four always ends within 42 moves (the board is full), so no move cap is needed.',

  setup() {
    return { cells: empty(), turn: 0, plies: 0, last: null };
  },
  toMove: (s) => s.turn,
  legalMoves(s) {
    if (findWin(s.cells)) return [];
    const out: string[] = [];
    for (let c = 0; c < COLS; c++) if (height(s, c) < ROWS) out.push(String(c + 1));
    return out;
  },
  parseMove(s, text): ParsedMove {
    const t = text.trim().toLowerCase();
    let col: number | null = null;
    const digit = t.match(/\d+/);
    if (digit) col = Number(digit[0]);
    else {
      const word = t.match(/\b(one|two|three|four|five|six|seven)\b/);
      if (word) col = WORDS[word[1]!]!;
      else if (/^[a-g]$/.test(t)) col = t.charCodeAt(0) - 96;
    }
    if (col === null) return { ok: false, error: `"${text.trim().slice(0, 40)}" is not a column number. Write a single number from 1 to 7.` };
    if (col < 1 || col > COLS) return { ok: false, error: `Column ${col} does not exist. Columns are numbered 1 to 7.` };
    if (height(s, col - 1) >= ROWS) return { ok: false, error: `Column ${col} is full. Choose a column that still has space.` };
    return { ok: true, move: String(col) };
  },
  play(s, move) {
    const col = Number(move) - 1;
    const row = height(s, col);
    if (!(col >= 0 && col < COLS) || row >= ROWS) throw new Error(`Illegal Connect Four move ${move}`);
    const cells = s.cells.map((r) => r.slice());
    cells[row]![col] = MARK[s.turn];
    return { cells, turn: (1 - s.turn) as Side, plies: s.plies + 1, last: { col, row } };
  },
  outcome(s): GameOutcome | null {
    const w = findWin(s.cells);
    if (w) return { winner: w.mark === 'X' ? 0 : 1, reason: 'Four in a row' };
    if (s.plies >= COLS * ROWS) return { winner: null, reason: 'Board full' };
    return null;
  },
  adjudicate() {
    return { winner: null, reason: 'Move cap reached' };
  },
  label: (_s, move) => move,
  formatHistory(labels) {
    if (!labels.length) return '(no moves yet)';
    return labels.map((l, i) => `${i + 1}. ${i % 2 === 0 ? 'Red' : 'Yellow'} ${l}`).join('\n');
  },
  view(s, side) {
    const lines = [' 1 2 3 4 5 6 7'];
    for (let r = ROWS - 1; r >= 0; r--) lines.push('|' + s.cells[r]!.map((m) => m || '.').join(' ') + '|');
    lines.push('+-------------+');
    lines.push(`X = Red, O = Yellow, . = empty. You are ${side === 0 ? 'Red (X)' : 'Yellow (O)'}.`);
    return lines.join('\n');
  },
  snapshot(s): C4Snapshot {
    const rows: string[] = [];
    for (let r = ROWS - 1; r >= 0; r--) rows.push(s.cells[r]!.map((m) => m || '.').join(''));
    return { rows, last: s.last, win: findWin(s.cells)?.line ?? null };
  },
};
