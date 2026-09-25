/**
 * Visual pass (Arena, Fix the Bug replay, live views): the read-only analysis
 * behind the on-screen story. Connect Four "wins next move" columns, chess
 * material and captures (cross-checked against the engine), one plain-English
 * headline per move, poker hand ranks, the code tokenizer used for diffs, and
 * the Fix the Bug "bug the visible tests didn't show" / budget helpers. These
 * are UI-only: they read recorded snapshots and never change a game.
 */
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { connect4 } from '../src/arena/games/connect4.ts';
import { chess, material } from '../src/arena/games/chess.ts';
import { createRng } from '../src/core/rng.ts';
import type { ArenaMove } from '../src/arena/types.ts';
import type { C4Snapshot } from '../src/arena/games/connect4.ts';
import type { ChessSnapshot } from '../src/arena/games/chess.ts';
import type { DebateSnapshot } from '../src/arena/games/debate.ts';
import type { PokerSnapshot } from '../src/arena/games/poker.ts';
import type { ReplayFrame } from '../src/core/types.ts';
import { HAND_LADDER, c4Threats, chessMaterial, chipCount, handCategory, moveHeadline, pieceOn } from '../ui/src/arena/analysis.ts';
import { langOf, tokenizeLine } from '../ui/src/components/viz/codeTokens.ts';
import { missedBug, spentActions } from '../ui/src/components/codeAgentStory.ts';

const NAMES: [string, string] = ['Alpha', 'Bravo'];

function move(side: 0 | 1, mv: string, label: string, snapshot: unknown, extra: Partial<ArenaMove> = {}): ArenaMove {
  return { ply: 1, side, move: mv, label, attempts: [{ text: `MOVE: ${mv}`, extracted: mv, ms: 1 }], forfeit: false, ms: 1, costUsd: 0, inputTokens: 0, outputTokens: 0, snapshot, ...extra };
}

/** Plays Connect Four columns (1-based) and returns every snapshot (index 0 = empty board). */
function c4Line(cols: number[]): C4Snapshot[] {
  let s = connect4.setup(createRng(1), connect4.defaults);
  const out = [connect4.snapshot(s) as C4Snapshot];
  for (const c of cols) {
    s = connect4.play(s, String(c));
    out.push(connect4.snapshot(s) as C4Snapshot);
  }
  return out;
}

describe('Connect Four threats', () => {
  it('finds nothing on an empty board and nothing once the game is won', () => {
    assert.deepEqual(c4Threats(c4Line([])[0]), [[], []]);
    const won = c4Line([1, 2, 1, 2, 1, 2, 1]);
    assert.ok(won.at(-1)!.win);
    assert.deepEqual(c4Threats(won.at(-1)), [[], []]);
  });
  it('marks the column where a disc would complete four, for each side', () => {
    // Red: 1,1,1 stacked in column 1 → wins in column 1. Yellow: 2,3,4 on the bottom row → wins in column 5.
    const snaps = c4Line([1, 2, 1, 3, 1, 4]);
    const [red, yellow] = c4Threats(snaps.at(-1));
    assert.deepEqual(red, [0]);
    assert.ok(yellow.includes(4), `yellow threats ${yellow}`);
  });
  it('tells a win, a block, a missed win and a set-up apart', () => {
    const snaps = c4Line([1, 2, 1, 3, 1, 4]);
    const before = snaps.at(-1)!;
    const winS = c4Line([1, 2, 1, 3, 1, 4, 1]).at(-1)!;
    assert.match(moveHeadline('connect4', move(0, '1', '1', winS), before, NAMES).text, /connects four/);
    assert.equal(moveHeadline('connect4', move(0, '1', '1', winS), before, NAMES).tone, 'good');
    const missS = c4Line([1, 2, 1, 3, 1, 4, 7]).at(-1)!;
    const miss = moveHeadline('connect4', move(0, '7', '7', missS), before, NAMES);
    assert.match(miss.text, /misses a win in column 1/);
    assert.equal(miss.tone, 'bad');
    // Yellow stacks three in column 2; Red (1, 3, 5 on the bottom row, no threat) drops on top to block.
    const yb = c4Line([1, 2, 3, 2, 5, 2]);
    assert.deepEqual(c4Threats(yb.at(-1)), [[], [1]]);
    const blockS = c4Line([1, 2, 3, 2, 5, 2, 2]).at(-1)!;
    const block = moveHeadline('connect4', move(0, '2', '2', blockS), yb.at(-1), NAMES);
    assert.equal(block.text, 'Alpha blocks Bravo’s winning spot in column 2');
    assert.equal(block.tone, 'good');
  });
  it('labels a forfeit and a harness opening move plainly', () => {
    const s = c4Line([4]);
    assert.match(moveHeadline('connect4', move(0, '4', '4', s[1], { forfeit: true }), s[0], NAMES).text, /two illegal replies/);
    assert.match(moveHeadline('connect4', move(0, '4', '4', s[1], { opening: true }), s[0], NAMES).text, /harness/);
  });
});

describe('chess material and captures', () => {
  function line(ids: string[]): { snaps: ChessSnapshot[]; labels: string[] } {
    let s = chess.setup(createRng(1), chess.defaults);
    const snaps = [chess.snapshot(s) as ChessSnapshot];
    const labels: string[] = [];
    for (const id of ids) {
      labels.push(chess.label(s, id));
      s = chess.play(s, id);
      snaps.push(chess.snapshot(s) as ChessSnapshot);
    }
    return { snaps, labels };
  }
  it('starts level with nothing captured and agrees with the engine count', () => {
    const m = chessMaterial(line([]).snaps[0]);
    assert.deepEqual(m.points, [39, 39]);
    assert.equal(m.diff, 0);
    assert.deepEqual(m.captured, [[], []]);
  });
  it('counts a capture for the side that made it', () => {
    // 1. e4 d5 2. exd5 — White takes a pawn.
    const { snaps, labels } = line(['e2e4', 'd7d5', 'e4d5']);
    const m = chessMaterial(snaps.at(-1));
    assert.deepEqual(m.captured, [['p'], []]);
    assert.equal(m.diff, 1);
    const board = [...snaps.at(-1)!.board].map((c) => (c === '.' ? '' : c));
    const eng = material(board);
    assert.equal(m.points[0] - m.points[1], eng.w - eng.b);
    assert.equal(pieceOn(snaps.at(-1), 'd5'), 'P');
    const h = moveHeadline('chess', move(0, 'e4d5', labels[2]!, snaps[3]), snaps[2], NAMES);
    assert.match(h.text, /Alpha plays exd5 — takes a pawn/);
  });
  it('announces check and checkmate', () => {
    // Fool's mate: 1. f3 e5 2. g4 Qh4#
    const { snaps, labels } = line(['f2f3', 'e7e5', 'g2g4', 'd8h4']);
    const h = moveHeadline('chess', move(1, 'd8h4', labels[3]!, snaps[4]), snaps[3], NAMES);
    assert.match(h.text, /checkmate!/);
    assert.equal(h.tone, 'good');
  });
});

describe('poker', () => {
  it('places evaluator hand names on the ladder', () => {
    assert.equal(HAND_LADDER.length, 9);
    assert.equal(handCategory('King high'), 0);
    assert.equal(handCategory('Pair of Tens'), 1);
    assert.equal(handCategory('Two pair, Tens and Eights'), 2);
    assert.equal(handCategory('Straight, Nine high'), 4);
    assert.equal(handCategory('Straight flush, Nine high'), 8);
    assert.equal(handCategory('Royal flush'), 8);
    assert.equal(handCategory('Full house, Kings full of Fours'), 6);
  });
  it('draws a chip pile that grows with the amount and is capped', () => {
    assert.equal(chipCount(0), 0);
    assert.equal(chipCount(-5), 0);
    assert.ok(chipCount(2) >= 1);
    assert.ok(chipCount(400) >= chipCount(40));
    assert.equal(chipCount(1e6), 12);
  });
  it('writes a fold and a showdown as one sentence', () => {
    const base: PokerSnapshot = { kind: 'poker', hands: 5, handNo: 2, button: 0, hole: [['2c', '5c'], ['6s', '8s']], board: [], street: 'Flop', pot: 6, stacks: [198, 202], bets: [0, 0], toAct: null, actions: [], net: [-2, 2], results: [], ended: { winner: 1, delta: [-2, 2], how: 'fold' }, over: false };
    const fold = moveHeadline('poker', move(0, 'fold', 'H2 Flop: fold', base), null, NAMES);
    assert.equal(fold.text, 'Alpha folds on the flop: Bravo takes the pot (+2 chips)');
    const sd: PokerSnapshot = { ...base, ended: { winner: 1, delta: [-200, 200], how: 'showdown', hands: [{ name: 'Pair of Tens', cards: [] }, { name: 'Two pair, Tens and Eights', cards: [] }] } };
    const h = moveHeadline('poker', move(1, 'call', 'H2 Flop: call 182', sd), null, NAMES);
    assert.equal(h.text, 'Bravo calls 182 on the flop — showdown: Bravo wins 200 chips with Two pair, Tens and Eights');
    assert.equal(h.tone, 'good');
  });
});

describe('debate headline', () => {
  it('names the side, the round, the words and the exhibits cited', () => {
    const snap: DebateSnapshot = {
      kind: 'debate',
      variant: 'courtroom',
      topicId: 'missing-violin',
      title: 'The Missing Violin',
      sides: ['Prosecution', 'Defence'],
      roundNames: ['Opening statement', 'The evidence', 'Closing argument'],
      limits: [180, 150, 120],
      speeches: [{ side: 0, round: 0, text: 'Exhibit A shows it, and Exhibits C and B confirm it.', words: 171, limit: 180, cut: 0 }],
      next: { side: 1, round: 0 },
    };
    const h = moveHeadline('courtroom', move(0, 'speech', 'Opening', snap), null, NAMES);
    assert.equal(h.text, 'Alpha (Prosecution): opening statement · 171/180 words · cites Exhibits A, B, C');
    const cut = { ...snap, speeches: [{ ...snap.speeches[0]!, words: 200, cut: 20 }] };
    assert.equal(moveHeadline('courtroom', move(0, 'speech', 'Opening', cut), null, NAMES).tone, 'bad');
  });
});

describe('code tokenizer (diff colouring)', () => {
  const lines = [
    'return Math.round(amount * 100); // cents',
    "const s = 'it\\'s'; let n = 1_000.5e3;",
    'export function taxFor(items) { return items.map((x) => x.cents); }',
    '"unterminated string',
    '',
    'def f(x):  # comment',
  ];
  it('never loses or reorders text', () => {
    for (const l of lines) for (const lang of ['js', 'py', 'md']) assert.equal(tokenizeLine(l, lang).map((t) => t.t).join(''), l);
  });
  it('classifies keywords, calls, strings, numbers and comments', () => {
    const t = tokenizeLine('return Math.round(amount * 100); // cents', 'js');
    const cls = (s: string) => t.find((x) => x.t === s)?.c;
    assert.equal(cls('return'), 'kw');
    assert.equal(cls('Math'), 'type');
    assert.equal(cls('round'), 'fn');
    assert.equal(cls('100'), 'num');
    assert.equal(cls('// cents'), 'com');
    assert.equal(tokenizeLine("x = 'a'", 'js').find((x) => x.c === 'str')?.t, "'a'");
    assert.equal(tokenizeLine('a # b', 'py').at(-1)?.c, 'com');
    assert.equal(tokenizeLine('a # b', 'js').some((x) => x.c === 'com'), false);
    assert.deepEqual(tokenizeLine('# Title', 'md'), [{ t: '# Title' }]);
  });
  it('picks the language from the file name', () => {
    assert.equal(langOf('src/money.js'), 'js');
    assert.equal(langOf('lib/x.ts'), 'js');
    assert.equal(langOf('tool.py'), 'py');
    assert.equal(langOf('README.md'), 'md');
  });
});

describe('Fix the Bug replay helpers', () => {
  it('flags a bug the visible tests did not show only when every visible test passes', () => {
    assert.equal(missedBug({ tests: { passed: 9, total: 9, failing: [], ranThisStep: true }, hidden: { passed: 11, total: 13, before: 4 } }), 2);
    assert.equal(missedBug({ tests: { passed: 8, total: 9, failing: ['x'], ranThisStep: true }, hidden: { passed: 11, total: 13, before: 4 } }), 0);
    assert.equal(missedBug({ tests: { passed: 9, total: 9, failing: [], ranThisStep: true }, hidden: { passed: 13, total: 13, before: 4 } }), 0);
    assert.equal(missedBug({ tests: { passed: 9, total: 9, failing: [], ranThisStep: true } }), 0);
    assert.equal(missedBug({}), 0);
  });
  it('counts actions by kind and admits actions that have no frame', () => {
    const f = (kind: string, used: number): ReplayFrame => ({ step: used, code: { kind: kind as never, files: [], actions: { used, budget: 30 }, tokens: { used: 0, budget: 1 } } });
    const frames = [f('start', 0), f('tests', 1), f('read', 2), f('edit', 3), f('tests', 4), f('final', 4)];
    const all = spentActions(frames, frames.length - 1, 4);
    assert.equal(all.acts.length, 4);
    assert.equal(all.counts.get('tests'), 2);
    assert.equal(all.unseen, 0);
    const part = spentActions(frames, 2, 2);
    assert.equal(part.acts.length, 2);
    // A replay that stops early while the counter says 30 were used.
    assert.equal(spentActions(frames, frames.length - 1, 30).unseen, 26);
  });
});
