/**
 * The Arena: game rules (Connect Four, chess incl. perft), move extraction from
 * messy model replies, the match engine (retries, strikes, determinism),
 * bracket seeding / byes / tie-breaks, and a full fake-model tournament.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Isolate run storage and model config before any Gauntlet module is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-arena-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  for (let i = 0; i < 5; i++) models.contestants.push({ id: `bot-${i}`, label: `Bot ${i}`, vendor: 'Test', provider: 'baseline', model: `random-${i}`, color: '#123456', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
  models.contestants.push({ id: 'bot-pricey', label: 'Pricey Bot', vendor: 'Test', provider: 'baseline', model: 'random-p', color: '#654321', enabled: true, pricing: { inputPerM: 1_000_000, outputPerM: 0, verifiedAt: '2026-01-01' } });
  writeFileSync(file, JSON.stringify(models));
}

const { connect4, findWin } = await import('../src/arena/games/connect4.ts');
const { chess, fromFen, perft, parseChessMove, toFen, toSan, START_FEN } = await import('../src/arena/games/chess.ts');
const { extractMove, buildMovePrompt } = await import('../src/arena/prompt.ts');
const { playGame } = await import('../src/arena/match.ts');
const { buildKnockout, buildRoundRobin, computeState, seedOrder, gameKey, playableSlots } = await import('../src/arena/bracket.ts');
const { createRng } = await import('../src/core/rng.ts');
const { createFakeModel } = await import('./helpers/fake-model.ts');
const tournament = await import('../src/arena/tournament.ts');
const store = await import('../src/arena/store.ts');
type ArenaGameLite = import('../src/arena/types.ts').ArenaGameLite;
type ArenaEntrant = import('../src/arena/types.ts').ArenaEntrant;

const rng = createRng(1);
const c4Play = (moves: number[]) => moves.reduce((s, m) => connect4.play(s, String(m)), connect4.setup(rng, connect4.defaults));
const chessPlay = (fen: string, moves: string[]) =>
  moves.reduce((s, m) => {
    const p = chess.parseMove(s, m);
    assert.ok(p.ok, `move ${m} should be legal in ${toFen(s)}: ${!p.ok ? p.error : ''}`);
    return chess.play(s, (p as { move: string }).move);
  }, fromFen(fen));

// ─────────────────────────────── Connect Four ───────────────────────────────

test('connect4: horizontal, vertical and both diagonal wins', () => {
  // Red 1,2,3,4 along the bottom; Yellow stacks on 1..3.
  assert.deepEqual(connect4.outcome(c4Play([1, 1, 2, 2, 3, 3, 4])), { winner: 0, reason: 'Four in a row' });
  // Yellow vertical in column 7.
  assert.equal(connect4.outcome(c4Play([1, 7, 2, 7, 1, 7, 2, 7]))?.winner, 1);
  // Rising diagonal for Red: (1,0) (2,1) (3,2) (4,3)
  const rising = c4Play([1, 2, 2, 3, 3, 4, 3, 4, 4, 7, 4]);
  assert.equal(connect4.outcome(rising)?.winner, 0);
  assert.equal(findWin(rising.cells)!.line.length, 4);
  // Falling diagonal for Red: (4,0) (3,1) (2,2) (1,3)
  const falling = c4Play([4, 3, 3, 2, 2, 1, 2, 1, 1, 7, 1]);
  assert.equal(connect4.outcome(falling)?.winner, 0);
  // No win yet mid-game.
  assert.equal(connect4.outcome(c4Play([4, 4, 3])), null);
});

test('connect4: full board with no four is a draw', () => {
  // Columns filled in pairs so no line of four ever forms.
  const order = [1, 2, 1, 2, 1, 2, 2, 1, 2, 1, 2, 1, 3, 4, 3, 4, 3, 4, 4, 3, 4, 3, 4, 3, 5, 6, 5, 6, 5, 6, 6, 5, 6, 5, 6, 5, 7, 7, 7, 7, 7, 7];
  let s = connect4.setup(rng, connect4.defaults);
  for (const m of order) {
    assert.equal(connect4.outcome(s), null, `game ended early before ${m}`);
    s = connect4.play(s, String(m));
  }
  assert.deepEqual(connect4.outcome(s), { winner: null, reason: 'Board full' });
  assert.equal(connect4.legalMoves(s).length, 0);
});

test('connect4: move parsing accepts messy text and explains errors', () => {
  const s = c4Play([4, 4, 4, 4, 4, 4]);
  assert.deepEqual(connect4.parseMove(s, '3'), { ok: true, move: '3' });
  assert.deepEqual(connect4.parseMove(s, 'column 5, blocking the threat'), { ok: true, move: '5' });
  assert.deepEqual(connect4.parseMove(s, 'two'), { ok: true, move: '2' });
  assert.deepEqual(connect4.parseMove(s, '7.'), { ok: true, move: '7' });
  const full = connect4.parseMove(s, '4');
  assert.ok(!full.ok && /full/.test(full.error));
  const off = connect4.parseMove(s, '9');
  assert.ok(!off.ok && /does not exist/.test(off.error));
  const junk = connect4.parseMove(s, 'the middle');
  assert.ok(!junk.ok && /not a column/.test(junk.error));
  assert.deepEqual(connect4.legalMoves(s), ['1', '2', '3', '5', '6', '7']);
});

// ─────────────────────────────── Chess ───────────────────────────────

test('chess: perft node counts match the published values', () => {
  const cases: Array<[string, number[]]> = [
    [START_FEN, [20, 400, 8902]],
    ['r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1', [48, 2039, 97862]], // "Kiwipete": castling, pins, en passant
    ['8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1', [14, 191, 2812, 43238]], // en passant discovered checks
    ['r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1', [6, 264, 9467]], // promotions
    ['rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8', [44, 1486, 62379]],
  ];
  for (const [fen, counts] of cases) counts.forEach((n, i) => assert.equal(perft(fromFen(fen), i + 1), n, `${fen} depth ${i + 1}`));
});

test('chess: no castling through, out of or into check', () => {
  // Black rook on f8 attacks f1: kingside castling passes through check; queenside is fine.
  const s = fromFen('5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1');
  const through = parseChessMove(s, 'O-O');
  assert.ok(!through.ok && /cannot castle kingside/.test(through.error));
  assert.deepEqual(parseChessMove(s, 'O-O-O'), { ok: true, move: 'e1c1' });
  assert.deepEqual(parseChessMove(s, '0-0-0'), { ok: true, move: 'e1c1' });
  // Rook on g8 attacks g1: castling into check.
  assert.ok(!parseChessMove(fromFen('6rk/8/8/8/8/8/8/R3K2R w KQ - 0 1'), 'e1g1').ok);
  // In check from e8: no castling at all.
  const check = fromFen('4r2k/8/8/8/8/8/8/R3K2R w KQ - 0 1');
  assert.ok(!parseChessMove(check, 'O-O').ok);
  assert.ok(!parseChessMove(check, 'O-O-O').ok);
  // Castling moves the rook too, and removes both rights.
  const after = chess.play(s, 'e1c1');
  assert.equal(after.board[3], 'R');
  assert.equal(after.board[0], '');
  assert.equal(after.castling, '');
  // A rook captured on its home square loses that right.
  const cap = chessPlay('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1', ['Rxa8+']);
  assert.equal(cap.castling, 'Kk');
});

test('chess: en passant only immediately after the double push', () => {
  const s = chessPlay(START_FEN, ['e4', 'a6', 'e5', 'f5']);
  assert.equal(toSan(s, 'e5f6'), 'exf6');
  const ep = chess.play(s, (parseChessMove(s, 'exf6') as { move: string }).move);
  assert.equal(ep.board[37], '', 'captured pawn on f5 is removed');
  assert.equal(ep.board[45], 'P');
  // One move later the right is gone.
  const late = chessPlay(START_FEN, ['e4', 'f5', 'e5', 'a6', 'a3', 'a5']);
  assert.ok(!parseChessMove(late, 'exf6').ok);
});

test('chess: promotion in UCI and SAN, including underpromotion', () => {
  const s = fromFen('8/P7/8/8/8/8/8/k6K w - - 0 1');
  assert.deepEqual(parseChessMove(s, 'a7a8q'), { ok: true, move: 'a7a8q' });
  assert.deepEqual(parseChessMove(s, 'a8=Q'), { ok: true, move: 'a7a8q' });
  assert.deepEqual(parseChessMove(s, 'a8Q+'), { ok: true, move: 'a7a8q' });
  assert.deepEqual(parseChessMove(s, 'a8=N'), { ok: true, move: 'a7a8n' });
  const missing = parseChessMove(s, 'a7a8');
  assert.ok(!missing.ok && /promotion/.test(missing.error));
  assert.equal(chess.play(s, 'a7a8n').board[56], 'N');
  assert.equal(toSan(s, 'a7a8q'), 'a8=Q+');
});

test('chess: checkmate, stalemate and the automatic draws', () => {
  const mate = chessPlay(START_FEN, ['f3', 'e5', 'g4']);
  assert.equal(toSan(mate, 'd8h4'), 'Qh4#');
  assert.deepEqual(chess.outcome(chess.play(mate, 'd8h4')), { winner: 1, reason: 'Checkmate' });
  assert.deepEqual(chess.outcome(fromFen('7k/5Q2/6K1/8/8/8/8/8 b - - 0 1')), { winner: null, reason: 'Stalemate' });
  const rep = chessPlay(START_FEN, ['Nf3', 'Nf6', 'Ng1', 'Ng8', 'Nf3', 'Nf6', 'Ng1']);
  assert.equal(chess.outcome(rep), null, 'only twice so far');
  assert.deepEqual(chess.outcome(chessPlay(START_FEN, ['Nf3', 'Nf6', 'Ng1', 'Ng8', 'Nf3', 'Nf6', 'Ng1', 'Ng8'])), { winner: null, reason: 'Threefold repetition' });
  assert.deepEqual(chess.outcome(chessPlay('4k3/8/8/8/8/8/4R3/4K3 w - - 99 80', ['Ra2'])), { winner: null, reason: '50-move rule' });
  assert.deepEqual(chess.outcome(fromFen('4k3/8/8/8/8/8/8/3BK3 w - - 0 1')), { winner: null, reason: 'Insufficient material' });
  assert.deepEqual(chess.outcome(fromFen('4kb2/8/8/8/8/8/8/2B1K3 w - - 0 1')), { winner: null, reason: 'Insufficient material' }, 'same-coloured bishops');
  assert.equal(chess.outcome(fromFen('4k1b1/8/8/8/8/8/8/2B1K3 w - - 0 1')), null, 'opposite-coloured bishops can still mate');
  assert.equal(chess.outcome(fromFen('4k3/8/8/8/8/8/4P3/4K3 w - - 0 1')), null);
});

test('chess: pins, ambiguity and illegal-move explanations', () => {
  // Bishop e2 pinned by the rook on e8: it may not move.
  const pinned = fromFen('4r1k1/8/8/8/8/8/4B3/4K3 w - - 0 1');
  const p = parseChessMove(pinned, 'Bd3');
  assert.ok(!p.ok && /not a legal move/.test(p.error));
  const two = chessPlay(START_FEN, ['Nf3', 'e5', 'd3', 'e4']);
  assert.deepEqual(parseChessMove(two, 'Nbd2'), { ok: true, move: 'b1d2' });
  assert.deepEqual(parseChessMove(two, 'Nfd2'), { ok: true, move: 'f3d2' });
  const amb = parseChessMove(two, 'Nd2');
  assert.ok(!amb.ok && /ambiguous/.test(amb.error) && /Nbd2/.test(amb.error));
  assert.equal(toSan(two, 'b1d2'), 'Nbd2');
  const wrongSide = parseChessMove(fromFen(START_FEN), 'e7e5');
  assert.ok(!wrongSide.ok && /belongs to Black/.test(wrongSide.error));
  const empty = parseChessMove(fromFen(START_FEN), 'e3e4');
  assert.ok(!empty.ok && /no piece on e3/.test(empty.error));
});

test('chess: messy model notation is understood', () => {
  const s = fromFen(START_FEN);
  for (const [text, move] of [
    ['e4', 'e2e4'], ['e2e4', 'e2e4'], ['E2E4', 'e2e4'], ['e2-e4', 'e2e4'], ['e2 e4', 'e2e4'], ['**Nf3**', 'g1f3'], ['`Nf3`', 'g1f3'],
    ['nf3', 'g1f3'], ['Ng1-f3', 'g1f3'], ['1. e4', 'e2e4'], ['Nf3!', 'g1f3'], ['Nf3 (developing)', 'g1f3'], ['I will play Nc3', 'b1c3'], ['"d4"', 'd2d4'],
  ] as const) {
    assert.deepEqual(parseChessMove(s, text), { ok: true, move }, text);
  }
  assert.ok(!parseChessMove(s, 'resign').ok);
  assert.ok(!parseChessMove(s, '').ok);
});

test('chess: move cap is adjudicated on material with a 3-point margin', () => {
  assert.equal(chess.adjudicate(fromFen('4k3/8/8/8/8/8/8/R3K3 w - - 0 1')).winner, 0);
  assert.equal(chess.adjudicate(fromFen('4k3/8/8/8/8/8/P7/4K3 w - - 0 1')).winner, null, 'one pawn is not enough');
  assert.equal(chess.adjudicate(fromFen('3qk3/8/8/8/8/8/8/R3K3 w - - 0 1')).winner, 1);
});

// ─────────────────────────────── Move extraction ───────────────────────────────

test('extractMove handles messy model replies', () => {
  assert.equal(extractMove('I think the centre is best.\nMOVE: 4'), '4');
  assert.equal(extractMove('**MOVE:** e4'), 'e4');
  assert.equal(extractMove('**MOVE**: Nf3'), 'Nf3');
  assert.equal(extractMove('Move: `e2e4`'), 'e2e4');
  assert.equal(extractMove('move : 3'), '3');
  assert.equal(extractMove('MOVE:\n\n  e4  \n'), 'e4');
  assert.equal(extractMove('First idea MOVE: 3 … no wait.\nMOVE: 5'), '5', 'the last MOVE line wins');
  assert.equal(extractMove('MOVE: <4>'), '4');
  assert.equal(extractMove('MOVE：5'), '5', 'full-width colon');
  assert.equal(extractMove('I would REMOVE: nothing'), null);
  assert.equal(extractMove('The best move is e4.'), null);
  assert.equal(extractMove(''), null);
});

test('prompts contain rules, history, position, legal moves and the retry explanation', () => {
  const s = c4Play([4, 3]);
  const p = buildMovePrompt({ game: connect4, state: s, side: 0, config: connect4.defaults, labels: ['4', '3'], strikes: [1, 0], maxStrikes: 3 });
  assert.match(p, /== RULES ==/);
  assert.match(p, /1\. Red 4\n2\. Yellow 3/);
  assert.match(p, /\|\. \. O X \. \. \.\|/);
  assert.match(p, /`MOVE: 1` `MOVE: 2`/);
  assert.match(p, /Strikes so far: you 1, your opponent 0/);
  assert.doesNotMatch(p, /REJECTED/);
  const r = buildMovePrompt({ game: connect4, state: s, side: 0, config: connect4.defaults, labels: ['4', '3'], strikes: [0, 0], maxStrikes: 3, retry: { reply: 'MOVE: 9', extracted: '9', error: 'Column 9 does not exist.' } });
  assert.match(r, /PREVIOUS ANSWER WAS REJECTED[\s\S]*MOVE: 9[\s\S]*Column 9 does not exist/);
  // Only legal moves are backticked (the Random Baseline picks from backticks).
  const ticks = [...buildMovePrompt({ game: chess, state: fromFen(START_FEN), side: 0, config: chess.defaults, labels: [], strikes: [0, 0], maxStrikes: 3 }).matchAll(/`([^`]+)`/g)];
  assert.equal(ticks.length, 20);
  assert.ok(ticks.every((m) => /^MOVE: [a-h][1-8][a-h][1-8]$/.test(m[1]!)));
});

// ─────────────────────────────── Match engine ───────────────────────────────

type Handle = import('../src/core/types.ts').ModelHandle;
const S = (a: Handle, b: Handle): [{ handle: Handle }, { handle: Handle }] => [{ handle: a }, { handle: b }];

/** Plays the first legal column from the backticked list. */
const firstLegal = () => createFakeModel((_s, user) => `Let me think.\nMOVE: ${user.match(/`MOVE: (\w+)`/)![1]}`);

test('match: a rejected move gets one retry with the error explained', async () => {
  let n = 0;
  const flaky = createFakeModel((_s, user) => {
    n++;
    if (/REJECTED/.test(user)) return 'Sorry.\nMOVE: 1';
    return n === 1 ? 'I play the ninth column. MOVE: 9' : 'MOVE: 1';
  });
  const g = await playGame({ game: connect4, config: { ...connect4.defaults, maxPlies: 2 }, seed: 5, seats: S(flaky, firstLegal()), maxStrikes: 3 });
  assert.equal(g.moves[0]!.attempts.length, 2);
  assert.match(g.moves[0]!.attempts[0]!.error!, /Column 9 does not exist/);
  assert.equal(g.moves[0]!.forfeit, false);
  assert.equal(g.illegal[0], 1);
  assert.equal(g.strikes[0], 0);
  assert.match(flaky.calls[1]!.messages[0]!.content, /Column 9 does not exist/);
  assert.equal(g.reason, 'Move cap reached');
});

test('match: two failures play a random legal move and give a strike; 3 strikes lose', async () => {
  const garbage = createFakeModel(() => 'I refuse to follow the format.');
  const g = await playGame({ game: connect4, config: connect4.defaults, seed: 9, seats: S(garbage, firstLegal()), maxStrikes: 3 });
  assert.equal(g.winner, 1);
  assert.match(g.reason, /Red made 3 illegal moves/);
  assert.deepEqual(g.strikes, [3, 0]);
  assert.equal(g.illegal[0], 6);
  const forfeits = g.moves.filter((m) => m.side === 0);
  assert.equal(forfeits.length, 3);
  assert.ok(forfeits.every((m) => m.forfeit));
  assert.equal(forfeits[2]!.move, '', 'the final strike is not played on the board');
  assert.ok(forfeits.slice(0, 2).every((m) => ['1', '2', '3', '4', '5', '6', '7'].includes(m.move)));
  assert.match(garbage.calls[0]!.messages[0]!.content, /Strikes so far: you 0/);
  assert.match(garbage.calls[2]!.messages[0]!.content, /Strikes so far: you 1/);
});

test('match: empty and truncated replies are explained', async () => {
  const replies = [{ text: '' }, { text: 'Long thinking…', stopReason: 'max_tokens' as const }];
  let i = 0;
  const m = createFakeModel(() => replies[i++ % 2]!);
  const g = await playGame({ game: connect4, config: { ...connect4.defaults, maxPlies: 1 }, seed: 1, seats: S(m, firstLegal()), maxStrikes: 3 });
  assert.equal(g.moves[0]!.attempts[0]!.error, 'The reply was empty.');
  assert.match(g.moves[0]!.attempts[1]!.error!, /cut off/);
});

test('match: same seed and same players replay identically; a scripted winner wins', async () => {
  const policy = () => createFakeModel((_s, user) => {
    const legal = [...user.matchAll(/`MOVE: (\w+)`/g)].map((m) => m[1]!);
    const r = createRng(user.length);
    return `MOVE: ${r.pick(legal)}`;
  });
  const a = await playGame({ game: chess, config: { ...chess.defaults, maxPlies: 30 }, seed: 3, seats: S(policy(), policy()), maxStrikes: 3 });
  const b = await playGame({ game: chess, config: { ...chess.defaults, maxPlies: 30 }, seed: 3, seats: S(policy(), policy()), maxStrikes: 3 });
  assert.deepEqual(a.moves.map((m) => m.move), b.moves.map((m) => m.move));
  assert.equal(a.moves.length, 30);
  // Red always plays column 1; Yellow always column 2 → Red connects four vertically.
  const col = (c: number) => createFakeModel(() => `MOVE: ${c}`);
  const w = await playGame({ game: connect4, config: connect4.defaults, seed: 1, seats: S(col(1), col(2)), maxStrikes: 3 });
  assert.equal(w.winner, 0);
  assert.equal(w.moves.length, 7);
});

test('match: random opening plies are identical for both players and marked', async () => {
  const g = await playGame({ game: connect4, config: connect4.defaults, seed: 77, seats: S(firstLegal(), firstLegal()), maxStrikes: 3, openingPlies: 2 });
  const h = await playGame({ game: connect4, config: connect4.defaults, seed: 77, seats: S(firstLegal(), firstLegal()), maxStrikes: 3, openingPlies: 2 });
  assert.ok(g.moves[0]!.opening && g.moves[1]!.opening && !g.moves[2]!.opening);
  assert.deepEqual(g.moves.slice(0, 2).map((m) => m.move), h.moves.slice(0, 2).map((m) => m.move));
});

// ─────────────────────────────── Brackets ───────────────────────────────

test('bracket: standard seeding, byes for top seeds, round names', () => {
  assert.deepEqual(seedOrder(8), [1, 8, 4, 5, 2, 7, 3, 6]);
  assert.deepEqual(seedOrder(4), [1, 4, 2, 3]);
  const ids = Array.from({ length: 16 }, (_, i) => `m${i + 1}`);
  const ko16 = buildKnockout(ids);
  assert.equal(ko16.length, 15);
  assert.deepEqual(ko16[0]!.a, { entrant: 'm1' });
  assert.deepEqual(ko16[0]!.b, { entrant: 'm16' });
  assert.deepEqual([...new Set(ko16.map((m) => m.roundName))], ['Round of 16', 'Quarter-finals', 'Semi-finals', 'Final']);
  const ko5 = buildKnockout(ids.slice(0, 5));
  const byes = ko5.filter((m) => m.round === 1 && ('bye' in m.a || 'bye' in m.b));
  assert.equal(byes.length, 3);
  const byeSeeds = byes.map((m) => ('entrant' in m.a ? m.a.entrant : ''));
  assert.deepEqual(byeSeeds.sort(), ['m1', 'm2', 'm3']);
  const ko2 = buildKnockout(['x', 'y']);
  assert.equal(ko2.length, 1);
  assert.equal(ko2[0]!.roundName, 'Final');
});

test('bracket: round-robin pairs everyone exactly once', () => {
  for (const n of [2, 4, 5, 8]) {
    const ids = Array.from({ length: n }, (_, i) => `p${i}`);
    const rr = buildRoundRobin(ids);
    assert.equal(rr.length, (n * (n - 1)) / 2);
    const pairs = new Set(rr.map((m) => [(m.a as { entrant: string }).entrant, (m.b as { entrant: string }).entrant].sort().join('-')));
    assert.equal(pairs.size, rr.length);
  }
});

function entrants(ids: string[]): ArenaEntrant[] {
  return ids.map((id, i) => ({ id, label: id.toUpperCase(), vendor: 'T', provider: 'baseline', model: id, color: '#000000', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 }, configHash: id, seed: i + 1, index: null }));
}

function lite(key: string, players: [string, string], winner: 0 | 1 | null, extra: { illegal?: [number, number]; cost?: [number, number] } = {}): ArenaGameLite {
  const m = (c: number) => ({ costUsd: c, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 1, retries: 0, ms: 0 });
  const [matchId, g] = key.split(/-g(?=\d+$)/);
  return { key, tournamentId: 't', matchId: matchId!, gameNo: Number(g), seed: 1, players, status: 'ok', winner, reason: 'x', initial: null, strikes: [0, 0], illegal: extra.illegal ?? [0, 0], metrics: [m(extra.cost?.[0] ?? 0), m(extra.cost?.[1] ?? 0)], startedAt: '', finishedAt: '', plies: 10, lastSnapshot: null };
}

test('bracket: winners advance, sides swap each game, sudden death then tie-breaks', () => {
  const spec = { seed: 1, entrants: entrants(['a', 'b', 'c', 'd']), settings: { format: 'knockout' as const, gamesPerMatch: 2, suddenDeath: 1 }, matches: buildKnockout(['a', 'b', 'c', 'd']) };
  let s = computeState(spec, []);
  const m1 = s.matches.find((m) => m.id === 'R1-M1')!;
  assert.deepEqual(m1.players, ['a', 'd']);
  assert.deepEqual(m1.games.map((g) => g.players), [['a', 'd'], ['d', 'a']]);
  assert.equal(m1.games[0]!.seed, m1.games[1]!.seed, 'colour-swapped games share a seed');
  assert.equal(s.matches.find((m) => m.id === 'R2-M1')!.status, 'waiting');
  assert.equal(playableSlots(s, new Set()).length, 4);

  // a beats d 2–0; b and c split 1–1 → sudden death.
  const games = [lite('R1-M1-g1', ['a', 'd'], 0), lite('R1-M1-g2', ['d', 'a'], 1), lite('R1-M2-g1', ['b', 'c'], 0), lite('R1-M2-g2', ['c', 'b'], 0)];
  s = computeState(spec, games);
  assert.equal(s.matches[0]!.winner, 'a');
  assert.equal(s.matches[0]!.summary, 'A wins 2–0');
  const m2 = s.matches.find((m) => m.id === 'R1-M2')!;
  assert.equal(m2.status, 'playing');
  assert.equal(m2.games.length, 3);
  assert.ok(m2.games[2]!.suddenDeath);
  assert.deepEqual(playableSlots(s, new Set()).map((x) => x.key), ['R1-M2-g3']);

  // Sudden-death draw → tie-break on fewer illegal moves (c made fewer).
  const sdDraw = lite('R1-M2-g3', ['b', 'c'], null, { illegal: [2, 0] });
  s = computeState(spec, [...games, sdDraw]);
  const decided = s.matches.find((m) => m.id === 'R1-M2')!;
  assert.equal(decided.winner, 'c');
  assert.equal(decided.decidedBy, 'fewer illegal moves');
  assert.match(decided.summary, /on fewer illegal moves/);
  // Equal illegal moves → lower cost.
  s = computeState(spec, [...games, lite('R1-M2-g3', ['b', 'c'], null, { cost: [0.1, 0.5] })]);
  assert.equal(s.matches.find((m) => m.id === 'R1-M2')!.decidedBy, 'lower cost');
  assert.equal(s.matches.find((m) => m.id === 'R1-M2')!.winner, 'b');
  // Everything equal → higher seed (b is seed 2).
  s = computeState(spec, [...games, lite('R1-M2-g3', ['b', 'c'], null)]);
  assert.equal(s.matches.find((m) => m.id === 'R1-M2')!.decidedBy, 'higher seed');
  // Sudden-death win decides the match; the final becomes ready.
  s = computeState(spec, [...games, lite('R1-M2-g3', ['b', 'c'], 1)]);
  assert.equal(s.matches.find((m) => m.id === 'R1-M2')!.winner, 'c');
  assert.equal(s.matches.find((m) => m.id === 'R1-M2')!.decidedBy, 'sudden-death');
  const final = s.matches.find((m) => m.id === 'R2-M1')!;
  assert.deepEqual(final.players, ['a', 'c']);
  assert.equal(final.status, 'ready');
  s = computeState(spec, [...games, lite('R1-M2-g3', ['b', 'c'], 1), lite(gameKey('R2-M1', 1), ['a', 'c'], 1), lite(gameKey('R2-M1', 2), ['c', 'a'], 0)]);
  assert.equal(s.champion, 'c');
  assert.equal(s.runnerUp, 'a');
  assert.ok(s.complete);
  assert.equal(s.standings[0]!.contestantId, 'c');
  assert.equal(s.standings[1]!.contestantId, 'a');
});

test('bracket: round-robin standings use points, then head-to-head', () => {
  const ids = ['a', 'b', 'c'];
  const spec = { seed: 1, entrants: entrants(ids), settings: { format: 'round-robin' as const, gamesPerMatch: 2, suddenDeath: 0 }, matches: buildRoundRobin(ids) };
  const games: ArenaGameLite[] = [];
  for (const m of computeState(spec, []).matches) {
    for (const g of m.games) {
      const [p0, p1] = g.players;
      // a beats everyone; b and c draw both games.
      const winner = p0 === 'a' ? 0 : p1 === 'a' ? 1 : null;
      games.push(lite(g.key, [p0, p1], winner));
    }
  }
  const s = computeState(spec, games);
  assert.ok(s.complete);
  assert.equal(s.champion, 'a');
  assert.equal(s.standings[0]!.points, 4);
  assert.equal(s.standings[1]!.points, 1);
  const drawn = s.matches.find((m) => m.players.includes('b') && m.players.includes('c'))!;
  assert.equal(drawn.winner, null);
  assert.match(drawn.summary, /draw 1–1/);
});

// ─────────────────────────────── Full tournament ───────────────────────────────

test('tournament: estimate, a full fake-model knockout end to end, resume is a no-op', async () => {
  const est = tournament.estimateTournament({ game: 'connect4', contestantIds: ['bot-0', 'bot-1', 'bot-2', 'bot-pricey'], seeding: 'manual' });
  assert.equal(est.games, 6);
  assert.equal(est.maxGames, 12);
  assert.ok(est.estCostUsd > 0, 'the priced bot has a cost');
  assert.ok(est.estCostUsdHigh >= est.estCostUsd);
  assert.equal(est.perContestant.find((p) => p.contestantId === 'bot-0')!.perMoveUsd, 0);

  const id = tournament.startTournament({ game: 'connect4', contestantIds: ['bot-0', 'bot-1', 'bot-2', 'bot-3'], seeding: 'manual', concurrency: 4, name: 'Test cup' });
  const seen: string[] = [];
  tournament.subscribeTournament(id, (e) => seen.push(e.type));
  await tournament.waitForTournament(id);
  const d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'completed');
  assert.ok(d.state.complete);
  assert.ok(d.state.champion);
  assert.equal(d.manifest.entrants[0]!.id, 'bot-0', 'manual seeding keeps the order');
  assert.ok(existsSync(join(sandbox, 'data', 'arena', id, 'games.jsonl')));
  for (const t of ['game.started', 'game.move', 'game.finished', 'match.finished', 'tournament.status']) assert.ok(seen.includes(t), `event ${t}`);
  const full = tournament.gameRecord(id, 'R1-M1-g1')!;
  assert.equal(full.transcripts[0].length + full.transcripts[1].length >= full.moves.filter((m) => !m.opening).length, true, 'every move has its prompt/response recorded');
  assert.match(full.transcripts[0][0]!.messages[0]!.content, /You are playing Connect Four/);
  assert.equal(full.transcripts[0][0]!.label, 'Red · move 1');
  assert.ok(full.moves.every((m) => m.forfeit === false), 'the Random Baseline only picks listed legal moves');
  const list = tournament.listTournaments();
  assert.equal(list[0]!.id, id);
  assert.equal(list[0]!.champion, d.state.champion);
  // Nothing left to play: resuming completes immediately without new games.
  const before = store.readGames(id).length;
  tournament.resumeTournament(id);
  await tournament.waitForTournament(id);
  assert.equal(store.readGames(id).length, before);
});

test('tournament: the spending cap stops mid-game and resume with a higher cap continues', async () => {
  const id = tournament.startTournament({ game: 'connect4', contestantIds: ['bot-pricey', 'bot-4'], seeding: 'manual', maxCostUsd: 0.5, concurrency: 1 });
  await tournament.waitForTournament(id);
  const d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'cancelled');
  assert.match(d.manifest.error ?? '', /Budget cap of \$0\.50 reached/);
  assert.equal(d.state.gamesDone, 0);
  assert.ok(d.state.costUsd > 0.5, 'the partial game spend is counted');
  assert.equal(store.readGames(id)[0]!.status, 'cancelled');
  // Changing the game code would block resume; unchanged code resumes.
  tournament.resumeTournament(id, { maxCostUsd: null });
  await tournament.waitForTournament(id);
  const after = tournament.tournamentDetail(id)!;
  assert.equal(after.manifest.status, 'completed');
  assert.ok(after.state.champion);
});

test('tournament: invalid requests are refused with clear errors', () => {
  assert.throws(() => tournament.planTournament({ game: 'go', contestantIds: ['bot-0', 'bot-1'] }), /Unknown arena game/);
  assert.throws(() => tournament.planTournament({ game: 'chess', contestantIds: ['bot-0'] }), /at least 2/);
  assert.throws(() => tournament.planTournament({ game: 'chess', contestantIds: ['bot-0', 'bot-1'], gamesPerMatch: 3 }), /2, 4 or 6/);
  assert.throws(() => tournament.planTournament({ game: 'chess', contestantIds: ['bot-0', 'nope'] }), /Unknown model/);
  const plan = tournament.planTournament({ game: 'chess', contestantIds: ['bot-0', 'bot-1', 'bot-2'], seeding: 'manual' });
  assert.ok(plan.warnings.some((w) => /byes/.test(w)));
  assert.equal(plan.fingerprint, tournament.planTournament({ game: 'chess', contestantIds: ['bot-2', 'bot-1', 'bot-0'], seeding: 'manual' }).fingerprint, 'fingerprint = conditions, not entrants');
  assert.notEqual(plan.fingerprint, tournament.planTournament({ game: 'chess', contestantIds: ['bot-0', 'bot-1', 'bot-2'], gamesPerMatch: 4 }).fingerprint);
});
