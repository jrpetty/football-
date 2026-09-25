/**
 * Arena formats: heads-up poker (hand ranking, dealing determinism, betting
 * legality, pot accounting, duplicate scoring) and debate / courtroom (word
 * limits, blinding, judge-vendor exclusion, majority and split decisions,
 * human judging), each with a fake-model end-to-end run.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Isolate storage and config before any Gauntlet module is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-arena-formats-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  for (let i = 0; i < 4; i++) models.contestants.push({ id: `pbot-${i}`, label: `Poker Bot ${i}`, vendor: i % 2 ? 'Acme' : 'Globex', provider: 'baseline', model: `random-${i}`, color: '#123456', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
  for (let i = 0; i < 4; i++) models.contestants.push({ id: `pp-${i}`, label: `Pricey ${i}`, vendor: 'Test', provider: 'baseline', model: `pricey-${i}`, color: '#654321', enabled: true, pricing: { inputPerM: 1000, outputPerM: 1000, verifiedAt: '2026-01-01' } });
  models.contestants.push({ id: 'judge-acme', label: 'Judge Acme', vendor: 'Acme', provider: 'baseline', model: 'judge-a', color: '#111111', enabled: true, pricing: { inputPerM: 1, outputPerM: 2, verifiedAt: '2026-01-01' } });
  models.contestants.push({ id: 'judge-initech', label: 'Judge Initech', vendor: 'Initech', provider: 'baseline', model: 'judge-i', color: '#222222', enabled: true, pricing: { inputPerM: 1, outputPerM: 2, verifiedAt: '2026-01-01' } });
  writeFileSync(file, JSON.stringify(models));
  const sfile = join(sandbox, 'config', 'settings.json');
  const settings = JSON.parse(readFileSync(sfile, 'utf8'));
  settings.judges = [];
  writeFileSync(sfile, JSON.stringify(settings));
}
const setJudges = (ids: string[]) => {
  const sfile = join(sandbox, 'config', 'settings.json');
  const settings = JSON.parse(readFileSync(sfile, 'utf8'));
  settings.judges = ids;
  writeFileSync(sfile, JSON.stringify(settings));
};

const { bestHand, evaluate5, compareScores, showdown, fullDeck } = await import('../src/arena/games/poker-eval.ts');
const { poker, parsePokerAction } = await import('../src/arena/games/poker.ts');
const { debate, courtroom, cutWords, countWords, cleanSpeech, RUBRIC } = await import('../src/arena/games/debate.ts');
const { MOTIONS, CASES } = await import('../src/arena/games/debate-bank.ts');
const { playTurnGame, extractAnswer } = await import('../src/arena/turns.ts');
const { playJudgedGame } = await import('../src/arena/judged.ts');
const judge = await import('../src/arena/judge.ts');
const { computeState, buildKnockout, buildRoundRobin, gameSeed } = await import('../src/arena/bracket.ts');
const { createRng } = await import('../src/core/rng.ts');
const { createFakeModel } = await import('./helpers/fake-model.ts');
const tournament = await import('../src/arena/tournament.ts');
const store = await import('../src/arena/store.ts');
type PokerState = import('../src/arena/games/poker.ts').PokerState;
type ArenaGameLite = import('../src/arena/types.ts').ArenaGameLite;
type ArenaEntrant = import('../src/arena/types.ts').ArenaEntrant;
type JudgeVerdict = import('../src/arena/types.ts').JudgeVerdict;
type FakeModel = import('./helpers/fake-model.ts').FakeModel;

const H = (s: string) => s.split(' ');
const cat = (s: string) => evaluate5(H(s)).category;
const beats = (a: string, b: string) => compareScores(bestHand(H(a)).score, bestHand(H(b)).score);
const seats = (a: FakeModel, b: FakeModel) => [{ handle: a }, { handle: b }] as [{ handle: FakeModel }, { handle: FakeModel }];
const cfg = (hands: number) => ({ ...poker.defaults, hands });

// ─────────────────────────────── Hand ranking ───────────────────────────────

test('poker eval: every hand rank is recognised and ordered', () => {
  const ladder = [
    ['As Ks Qs Js Ts', 'Straight flush'],
    ['9c 9d 9h 9s 2d', 'Four of a kind'],
    ['Kc Kd Kh 7s 7d', 'Full house'],
    ['Ah 9h 7h 4h 2h', 'Flush'],
    ['9c Td Jh Qs Kd', 'Straight'],
    ['7c 7d 7h Ks 2d', 'Three of a kind'],
    ['Jc Jd 4h 4s Ad', 'Two pair'],
    ['Qc Qd 8h 5s 2d', 'Pair'],
    ['Ac Jd 8h 5s 2d', 'High card'],
  ] as const;
  for (const [hand, name] of ladder) assert.equal(cat(hand), name, hand);
  for (let i = 0; i + 1 < ladder.length; i++) assert.ok(beats(ladder[i]![0], ladder[i + 1]![0]) > 0, `${ladder[i]![1]} beats ${ladder[i + 1]![1]}`);
  assert.equal(evaluate5(H('As Ks Qs Js Ts')).name, 'Royal flush');
  assert.equal(evaluate5(H('Kc Kd Kh 7s 7d')).name, 'Full house, Kings full of Sevens');
  assert.equal(evaluate5(H('6c 6d 6h 2s 2d')).name, 'Full house, Sixes full of Twos');
});

test('poker eval: kickers, wheels and splits', () => {
  // Pair of aces: king kicker beats queen kicker.
  assert.ok(beats('Ac Ad Kh 7s 2d', 'Ah As Qh 7c 2c') > 0);
  // Two pair: same pairs, kicker decides; higher top pair wins regardless of kicker.
  assert.ok(beats('Jc Jd 4h 4s Ad', 'Jh Js 4c 4d Kd') > 0);
  assert.ok(beats('Qc Qd 2h 2s 3d', 'Jh Js Tc Td Ad') > 0);
  // Full house: trips first.
  assert.ok(beats('7c 7d 7h 2s 2d', '6c 6d 6h As Ad') > 0);
  // Flush vs flush: compare card by card.
  assert.ok(beats('Ah Th 7h 4h 2h', 'Ac 9c 8c 6c 5c') > 0);
  // Wheel: A-2-3-4-5 is a five-high straight; 2-3-4-5-6 beats it; it beats three of a kind.
  assert.equal(evaluate5(H('Ac 2d 3h 4s 5d')).name, 'Straight, Five high');
  assert.deepEqual(evaluate5(H('Ac 2d 3h 4s 5d')).score, [4, 5]);
  assert.ok(beats('2c 3d 4h 5s 6d', 'Ac 2d 3h 4s 5d') > 0);
  assert.ok(beats('Ac 2d 3h 4s 5d', 'Kc Kd Kh 4s 2d') > 0);
  assert.equal(evaluate5(H('As 2s 3s 4s 5s')).name, 'Straight flush, Five high');
  assert.equal(evaluate5(H('As 2s 3s 4s 5s')).cards[4], 'As', 'the wheel ace is shown last');
  // Q-K-A-2-3 is not a straight.
  assert.equal(cat('Qc Kd Ah 2s 3d'), 'High card');
  // Seven cards: best five chosen; the board plays for both = split.
  assert.equal(bestHand(H('2c 3d 9h 9s 9d Kc Kh')).category, 'Full house');
  assert.equal(bestHand(H('Ah Kh 2h 7h 9h Jc Qd')).category, 'Flush');
  const split = showdown([H('2c 3d'), H('4h 5s')], H('As Ks Qs Js Ts'));
  assert.equal(split.winner, null, 'royal flush on the board: split pot');
  // Quads on the board: the kicker decides.
  const kick = showdown([H('Ac 2d'), H('Kc Qd')], H('9c 9d 9h 9s 3d'));
  assert.equal(kick.winner, 0);
  // Two players with the same straight split; a higher straight wins.
  assert.equal(showdown([H('Ac 2d'), H('Ad 3c')], H('Th Js Qd Kc 4s')).winner, null);
  assert.equal(showdown([H('9c 2d'), H('Ad 3c')], H('Th Js Qd Kc 4s')).winner, 1);
  assert.throws(() => bestHand(H('Ac Ac 2d 3h 4s')), /Duplicate/);
});

// ─────────────────────────────── Dealing ───────────────────────────────

test('poker: dealing is deterministic per seed, unique cards, both games of a pair get the same deals', () => {
  const a = poker.setup(createRng(123).fork('setup'), cfg(10));
  const b = poker.setup(createRng(123).fork('setup'), cfg(10));
  const c = poker.setup(createRng(124).fork('setup'), cfg(10));
  assert.deepEqual(a.decks, b.decks);
  assert.notDeepEqual(a.decks, c.decks);
  assert.equal(a.decks.length, 10);
  for (const d of a.decks) assert.equal(new Set(d).size, 9);
  assert.equal(new Set(a.decks.map((d) => d.join())).size, 10, 'every hand is a fresh shuffle');
  assert.equal(fullDeck().length, 52);
  // Games 1 and 2 of a pairing share a seed (duplicate format); game 3 gets a new deal.
  assert.equal(gameSeed(1, 'R1-M1', 1, 2), gameSeed(1, 'R1-M1', 2, 2));
  assert.equal(a.hand!.button, 0, 'seat 0 has the button in hand 1');
});

// ─────────────────────────────── Betting ───────────────────────────────

const act = (s: PokerState, text: string) => {
  const p = poker.parseMove(s, text);
  assert.ok(p.ok, `${text}: ${!p.ok ? p.error : ''}`);
  return poker.play(s, (p as { move: string }).move);
};
const fresh = () => poker.setup(createRng(9).fork('setup'), cfg(4));

test('poker: blinds, min-raise rules and clear errors for illegal actions', () => {
  const s = fresh();
  const h = s.hand!;
  assert.deepEqual([h.bet[0], h.bet[1]], [1, 2], 'button posts 1, big blind 2');
  assert.equal(h.toAct, 0, 'button acts first pre-flop');
  assert.deepEqual(poker.legalMoves(s).slice(0, 2), ['fold', 'call']);
  const e = (t: string) => {
    const p = parsePokerAction(h, 0, 2, t);
    assert.ok(!p.ok, t);
    return (p as { error: string }).error;
  };
  assert.match(e('check'), /cannot check: there is a bet of 1 to call/);
  assert.match(e('raise 3'), /too small: the minimum is to 4/);
  assert.match(e('raise 500'), /largest raise is to 200/);
  assert.match(e('raise'), /Say how much/);
  assert.match(e('dance'), /not an action/);
  assert.deepEqual(parsePokerAction(h, 0, 2, 'raise to 6'), { ok: true, move: 'raise 6' });
  assert.deepEqual(parsePokerAction(h, 0, 2, '**Raise** 6 chips'), { ok: true, move: 'raise 6' });
  assert.deepEqual(parsePokerAction(h, 0, 2, 'raise by 4'), { ok: true, move: 'raise 6' }, 'raise BY 4 over the 2 bet');
  assert.deepEqual(parsePokerAction(h, 0, 2, 'All-in!'), { ok: true, move: 'raise 200' });
  assert.deepEqual(parsePokerAction(h, 0, 2, 'min-raise'), { ok: true, move: 'raise 4' });
  // Re-raise: button raises to 6 (raise of 4), the big blind must make it at least 10.
  const s2 = act(s, 'raise 6');
  assert.equal(s2.hand!.toAct, 1);
  const bbErr = parsePokerAction(s2.hand!, 1, 2, 'raise 8');
  assert.ok(!bbErr.ok && /minimum is to 10/.test(bbErr.error));
  assert.ok(parsePokerAction(s2.hand!, 1, 2, 'raise 10').ok);
  // Facing an all-in, you can only call or fold.
  const s3 = act(s, 'all-in');
  const noRaise = parsePokerAction(s3.hand!, 1, 2, 'raise 300');
  assert.ok(!noRaise.ok && /opponent is all-in/.test(noRaise.error));
  assert.deepEqual(parsePokerAction(s3.hand!, 1, 2, 'all in'), { ok: true, move: 'call' });
  assert.deepEqual(poker.legalMoves(s3), ['fold', 'call']);
});

test('poker: the big blind gets the option; post-flop the big blind acts first; checks close a street', () => {
  let s = fresh();
  s = act(s, 'call'); // button limps
  assert.equal(s.hand!.street, 0);
  assert.equal(s.hand!.toAct, 1, 'big blind option');
  const fold = parsePokerAction(s.hand!, 1, 2, 'fold');
  assert.ok(!fold.ok && /nothing to call/.test(fold.error));
  s = act(s, 'check');
  assert.equal(s.hand!.street, 1, 'flop');
  assert.equal(s.hand!.toAct, 1, 'big blind acts first after the flop');
  s = act(s, 'check');
  s = act(s, 'bet 4');
  assert.equal(s.hand!.toAct, 1);
  assert.equal(poker.label(s, 'raise 12'), 'H1 Flop: raise to 12');
  s = act(s, 'raise 12');
  s = act(s, 'call');
  assert.equal(s.hand!.street, 2, 'turn');
  assert.deepEqual([s.hand!.committed[0], s.hand!.committed[1]], [14, 14]);
  const snap = poker.snapshot(s) as { board: string[]; pot: number };
  assert.equal(snap.board.length, 4);
  assert.equal(snap.pot, 28);
});

test('poker: pot accounting for folds, showdowns and all-ins; nets always sum to zero', () => {
  // Fold pre-flop: the button loses its small blind.
  let s = fresh();
  s = act(s, 'fold');
  assert.deepEqual(s.results[0]!.delta, [-1, 1]);
  assert.equal(s.results[0]!.how, 'fold');
  assert.equal(s.hand!.no, 1, 'next hand dealt');
  assert.equal(s.hand!.button, 1, 'the button moves');
  assert.equal(s.hand!.stacks[0] + s.hand!.committed[0], 200, 'stacks reset every hand');
  // Raise, call, then fold to a bet on the flop.
  s = act(s, 'raise 6');
  s = act(s, 'call');
  s = act(s, 'bet 10'); // seat 0 is the big blind in hand 2 and acts first post-flop
  s = act(s, 'fold');
  assert.deepEqual(s.results[1]!.delta, [6, -6], 'the uncalled bet goes back');
  // All-in and call: the board runs out and a showdown decides the whole stack.
  s = act(s, 'all-in');
  s = act(s, 'call');
  const r = s.results[2]!;
  assert.equal(r.how, 'showdown');
  assert.equal(r.board.length, 5);
  assert.ok(r.hands);
  const sd = showdown(r.hole as [string[], string[]], r.board);
  assert.equal(r.winner, sd.winner);
  assert.equal(Math.abs(r.delta[0]), sd.winner === null ? 0 : 200);
  // Check it down to the river.
  s = act(s, 'call');
  for (let i = 0; i < 7; i++) s = act(s, 'check');
  const last = s.results[3]!;
  assert.equal(last.how, 'showdown');
  assert.equal(Math.abs(last.delta[0]), last.winner === null ? 0 : 2);
  assert.equal(s.hand, null);
  assert.equal(s.net[0] + s.net[1], 0);
  assert.deepEqual(poker.margin!(s), s.net);
  const out = poker.outcome(s)!;
  assert.equal(out.winner, s.net[0] === 0 ? null : s.net[0] > 0 ? 0 : 1);
});

test('poker: split pot at showdown returns every chip', () => {
  // Find a seed where the board plays (or both hands tie) by brute force over deals.
  let found = false;
  for (let seed = 1; seed < 4000 && !found; seed++) {
    let s = poker.setup(createRng(seed).fork('setup'), cfg(1));
    const d = s.decks[0]!;
    if (showdown([d.slice(0, 2), d.slice(2, 4)], d.slice(4, 9)).winner !== null) continue;
    s = act(s, 'raise 50');
    s = act(s, 'call');
    for (let i = 0; i < 6; i++) s = act(s, 'check');
    assert.equal(s.results[0]!.winner, null);
    assert.deepEqual(s.results[0]!.delta, [0, 0]);
    found = true;
  }
  assert.ok(found, 'a split pot deal exists');
});

// ─────────────────────────────── Engine (fake models) ───────────────────────────────

/** A deterministic poker bot: raises pocket pairs and aces, calls small bets, else checks/folds. */
const pokerBot = (style: 'tight' | 'loose' | 'caller') =>
  createFakeModel((_s, user) => {
    const hole = user.match(/Your hole cards: .*\((\S\S) (\S\S)\)/);
    const owe = Number(user.match(/To call: (\d+)/)?.[1] ?? 0);
    const strong = hole && (hole[1]![0] === hole[2]![0] || hole[1]![0] === 'A' || hole[2]![0] === 'A');
    const minRaise = user.match(/`ACTION: raise (\d+)`/)?.[1];
    let action = owe > 0 ? 'call' : 'check';
    if (style === 'tight' && strong && minRaise) action = `raise ${minRaise}`;
    else if (style === 'tight' && !strong && owe > 4) action = 'fold';
    else if (style === 'loose' && minRaise && user.length % 3 === 0) action = `raise ${minRaise}`;
    return `Thinking about it.\nREASON: ${strong ? 'Strong starting hand.' : 'Keep the pot small.'}\nACTION: ${action}`;
  });

test('poker engine: hidden cards, REASON notes, a full session and exact replays', async () => {
  const a = pokerBot('tight');
  const b = pokerBot('loose');
  const g = await playTurnGame({ game: poker, config: cfg(6), seed: 42, seats: seats(a, b), maxStrikes: 3 });
  assert.equal(g.margin![0] + g.margin![1], 0);
  assert.equal(g.winner, g.margin![0] === 0 ? null : g.margin![0] > 0 ? 0 : 1);
  assert.match(g.reason, /chips|Level/);
  assert.ok(g.moves.length >= 12);
  assert.ok(g.moves.every((m) => m.note), 'every action carries its one-line reason');
  // Each prompt shows only the acting seat's own cards.
  const state = poker.setup(createRng(42).fork('setup'), cfg(6));
  const mine = state.decks[0]!.slice(0, 2);
  const theirs = state.decks[0]!.slice(2, 4);
  const first = a.calls[0]!.messages[0]!.content;
  assert.ok(first.includes(`(${mine.join(' ')})`), 'own cards shown');
  for (const c of theirs) assert.ok(!first.includes(c), `opponent card ${c} hidden`);
  assert.match(first, /== LEGAL ACTIONS ==/);
  assert.match(first, /REASON: <one short sentence/);
  assert.match(first, /luck cancels out/i);
  const again = await playTurnGame({ game: poker, config: cfg(6), seed: 42, seats: seats(pokerBot('tight'), pokerBot('loose')), maxStrikes: 3 });
  assert.deepEqual(again.moves.map((m) => m.move), g.moves.map((m) => m.move));
});

test('poker engine: illegal replies get a retry, then check-or-fold and a strike, never a strike-out', async () => {
  const garbage = createFakeModel(() => 'I would rather not say.');
  const b = pokerBot('caller');
  const g = await playTurnGame({ game: poker, config: cfg(4), seed: 7, seats: seats(garbage, b), maxStrikes: 1 });
  assert.equal(g.moves.filter((m) => m.side === 0).every((m) => m.forfeit), true);
  assert.equal(g.strikes[0], g.moves.filter((m) => m.side === 0).length);
  assert.ok(g.strikes[0] > 1, 'strikes keep counting without ending the session');
  const firstMove = g.moves[0]!;
  assert.equal(firstMove.move, 'fold', 'facing the big blind with nothing legal: fold');
  assert.match(firstMove.attempts[0]!.error!, /did not contain a "ACTION: <…>" line/);
  assert.match(garbage.calls[1]!.messages[0]!.content, /YOUR PREVIOUS ANSWER WAS REJECTED/);
  // A bad amount is explained on the retry and the fixed answer is accepted.
  let n = 0;
  const fixer = createFakeModel(() => (n++ === 0 ? 'ACTION: raise 3' : 'REASON: fixed\nACTION: call'));
  const h = await playTurnGame({ game: poker, config: cfg(1), seed: 3, seats: seats(fixer, pokerBot('caller')), maxStrikes: 3 });
  assert.match(h.moves[0]!.attempts[0]!.error!, /minimum is to 4/);
  assert.equal(h.moves[0]!.move, 'call');
  assert.match(fixer.calls[1]!.messages[0]!.content, /minimum is to 4/);
  assert.equal(extractAnswer('**ACTION:** `raise 12`', 'ACTION'), 'raise 12');
  assert.equal(extractAnswer('ACTION:\ncall', 'ACTION'), 'call');
});

test('poker duplicate format: identical strategies cancel out exactly over a pair', async () => {
  for (const seed of [1, 2, 3]) {
    const g1 = await playTurnGame({ game: poker, config: cfg(8), seed, seats: seats(pokerBot('tight'), pokerBot('loose')), maxStrikes: 3 });
    // Game 2: same deals, seats swapped. The loose bot now gets the tight bot's cards.
    const g2 = await playTurnGame({ game: poker, config: cfg(8), seed, seats: seats(pokerBot('loose'), pokerBot('tight')), maxStrikes: 3 });
    const tight = g1.margin![0] + g2.margin![1];
    const loose = g1.margin![1] + g2.margin![0];
    assert.equal(tight + loose, 0);
    // Same strategy on both seats: the pair must net exactly zero (luck cancels out).
    const m1 = await playTurnGame({ game: poker, config: cfg(8), seed, seats: seats(pokerBot('tight'), pokerBot('tight')), maxStrikes: 3 });
    const m2 = await playTurnGame({ game: poker, config: cfg(8), seed, seats: seats(pokerBot('tight'), pokerBot('tight')), maxStrikes: 3 });
    assert.equal(m1.margin![0] + m2.margin![1], 0, 'equal play from both seats: the pair nets exactly zero');
  }
});

// ─────────────────────────────── Margin scoring in the bracket ───────────────────────────────

const ent = (id: string, seed: number): ArenaEntrant => ({ id, label: id.toUpperCase(), vendor: 'V', provider: 'p', model: id, color: '#000', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 }, configHash: id, seed, index: null });
const lite = (key: string, matchId: string, gameNo: number, players: [string, string], margin: [number, number], extra: Partial<ArenaGameLite> = {}): ArenaGameLite => ({
  key, tournamentId: 't', matchId, gameNo, seed: 1, players, status: 'ok', winner: margin[0] === margin[1] ? null : margin[0] > margin[1] ? 0 : 1, reason: '', initial: null, strikes: [0, 0], illegal: [0, 0],
  metrics: [{ costUsd: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 1, retries: 0, ms: 0 }, { costUsd: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 1, retries: 0, ms: 0 }],
  startedAt: '', finishedAt: '', plies: 10, lastSnapshot: null, margin, ...extra,
});

test('bracket: poker matches are won on total chips across the duplicate pair, not on games', () => {
  const entrants = [ent('a', 1), ent('b', 2), ent('c', 3), ent('d', 4)];
  const spec = { seed: 1, entrants, settings: { format: 'knockout' as const, gamesPerMatch: 2, suddenDeath: 0, game: { scoring: 'margin' as const, unit: 'chips' } }, matches: buildKnockout(['a', 'b', 'c', 'd']) };
  // R1-M1 is a vs d. a wins game 1 by 30, d wins game 2 by 10: games 1–1, chips +20 for a.
  const st = computeState(spec, [lite('R1-M1-g1', 'R1-M1', 1, ['a', 'd'], [30, -30]), lite('R1-M1-g2', 'R1-M1', 2, ['d', 'a'], [10, -10])]);
  const m = st.matches.find((x) => x.id === 'R1-M1')!;
  assert.deepEqual(m.score, [20, -20]);
  assert.equal(m.winner, 'a');
  assert.equal(m.decidedBy, 'margin');
  assert.equal(m.unit, 'chips');
  assert.equal(m.summary, 'A wins by 20 chips');
  // Level on chips with no sudden death: fewer illegal moves decides.
  const lv = computeState(spec, [lite('R1-M2-g1', 'R1-M2', 1, ['b', 'c'], [5, -5], { illegal: [2, 0] }), lite('R1-M2-g2', 'R1-M2', 2, ['c', 'b'], [5, -5])]);
  const m2 = lv.matches.find((x) => x.id === 'R1-M2')!;
  assert.equal(m2.winner, 'c');
  assert.equal(m2.decidedBy, 'fewer illegal moves');
  assert.match(m2.summary, /level on chips/);
  // Round-robin standings rank on total chips.
  const rr = { ...spec, settings: { ...spec.settings, format: 'round-robin' as const }, matches: buildRoundRobin(['a', 'b', 'c']), entrants: entrants.slice(0, 3) };
  const games = rr.matches.flatMap((mm) => {
    const [x, y] = ['entrant' in mm.a ? mm.a.entrant : '', 'entrant' in mm.b ? mm.b.entrant : ''];
    const edge = (p: string) => (p === 'c' ? 40 : p === 'a' ? 5 : 0);
    const d = edge(x) - edge(y);
    return [lite(`${mm.id}-g1`, mm.id, 1, [x, y], [d, -d]), lite(`${mm.id}-g2`, mm.id, 2, [y, x], [0, 0])];
  });
  const table = computeState(rr, games);
  assert.equal(table.standings[0]!.contestantId, 'c');
  assert.equal(table.standings[0]!.margin, 75);
  assert.equal(table.standings[0]!.points, 75);
  assert.ok(table.complete);
});

// ─────────────────────────────── Debate: word limits ───────────────────────────────

test('debate: word limits are enforced by cutting, with the penalty recorded; speeches are cleaned', () => {
  const words = (n: number) => Array.from({ length: n }, (_, i) => `w${i + 1}`).join(' ');
  assert.equal(countWords('one  two\nthree'), 3);
  assert.equal(cutWords('a b\nc d e', 3), 'a b\nc');
  let s = debate.setup(createRng(1), { ...debate.defaults, topic: 'free-museums' });
  assert.equal(s.topicId, 'free-museums');
  s = debate.play(s, words(200));
  assert.equal(s.speeches[0]!.words, 200);
  assert.equal(s.speeches[0]!.cut, 20);
  assert.equal(countWords(s.speeches[0]!.text), 180);
  s = debate.play(s, words(150));
  assert.equal(s.speeches[1]!.cut, 0);
  s = debate.play(s, '');
  assert.equal(s.speeches[2]!.missing, true);
  assert.equal(debate.toMove(s), 1);
  assert.equal(cleanSpeech('# Opening\n**Members of the jury**, the facts are clear.\n\n(152 words)'), 'Members of the jury, the facts are clear.');
  assert.equal(cleanSpeech('<think>plan</think>SPEECH: Cars out, people in.'), 'Cars out, people in.');
  assert.equal(cleanSpeech('Rebuttal:\nMy opponent is wrong.\nWord count: 4'), 'My opponent is wrong.');
  assert.ok(!debate.parseMove(s, '   ').ok);
  assert.ok(MOTIONS.length >= 12 && CASES.length >= 6);
  for (const m of MOTIONS) assert.ok(m.pro.length >= 2 && m.con.length >= 2, m.id);
  for (const c of CASES) assert.ok(c.exhibits.length >= 4, c.id);
  assert.ok(!('verdict' in (debate.snapshot(s) as object)));
});

test('debate: prompts give the side, the transcript and the limit; courtroom includes every exhibit', () => {
  let s = courtroom.setup(createRng(1), { ...courtroom.defaults, topic: 'missing-violin' });
  const p0 = courtroom.prompt!(s, 0, { config: courtroom.defaults, strikes: [0, 0], maxStrikes: 3 });
  assert.match(p0, /You are the Prosecution/);
  assert.match(p0, /Exhibit E — Bank statement/);
  assert.match(p0, /at most 180 words/);
  s = courtroom.play(s, 'Exhibit B shows the pawn ticket used her student card.');
  const p1 = courtroom.prompt!(s, 1, { config: courtroom.defaults, strikes: [0, 0], maxStrikes: 3 });
  assert.match(p1, /You are the Defence/);
  assert.match(p1, /\[Opening statement · Prosecution\]\nExhibit B shows/);
  // Same seed = same motion for both games of the pair; random picks differ across seeds.
  const topics = new Set([1, 2, 3, 4, 5, 6, 7, 8].map((x) => debate.setup(createRng(x).fork('setup'), debate.defaults).topicId));
  assert.ok(topics.size > 2);
  assert.throws(() => debate.configure!(debate.defaults, { topic: 'nope' }), /Unknown motion/);
});

// ─────────────────────────────── Judges ───────────────────────────────

test('judges: never from either debater’s vendor; the players’ own models never judge', () => {
  const pool = [
    { id: 'j1', label: 'J1', vendor: 'Anthropic', model: 'm1', provider: 'anthropic' },
    { id: 'j2', label: 'J2', vendor: 'OpenAI', model: 'm2', provider: 'openai' },
    { id: 'j3', label: 'J3', vendor: 'Google', model: 'm3', provider: 'google' },
    { id: 'j4', label: 'J4', vendor: 'xAI', model: 'grok', provider: 'xai' },
  ];
  const r = judge.selectArenaPanel(pool, [
    { vendor: 'anthropic', model: 'x', provider: 'anthropic' },
    { vendor: 'Google', model: 'y', provider: 'google' },
  ]);
  assert.deepEqual(r.judges.map((j) => j.id), ['j2', 'j4']);
  assert.deepEqual(r.excludedVendors.sort(), ['Anthropic', 'Google']);
  assert.deepEqual(judge.selectArenaPanel(pool, [{ vendor: 'Other', model: 'grok', provider: 'xai' }]).judges.map((j) => j.id), ['j1', 'j2', 'j3']);
  // Strict: nobody left means no judges (the game waits for a human), never a same-vendor judge.
  assert.equal(judge.selectArenaPanel(pool.slice(0, 1), [{ vendor: 'Anthropic', model: 'x', provider: 'a' }]).judges.length, 0);
});

test('judges: majority, unanimous, split and tied panels', () => {
  const v = (winner: 0 | 1 | null, a = 30, b = 30, error?: string): JudgeVerdict => ({ judgeId: `j${Math.random()}`, judgeLabel: 'J', vendor: 'V', sideA: 0, winner, scores: [{ x: a }, { x: b }], rationale: '', costUsd: 0, ...(error ? { error } : {}) });
  assert.deepEqual(judge.decide([v(0), v(0), v(0)]), { winner: 0, votes: [3, 0], decision: 'Unanimous decision', split: false });
  assert.deepEqual(judge.decide([v(1), v(0), v(1)]), { winner: 1, votes: [1, 2], decision: 'Split decision', split: true });
  assert.equal(judge.decide([v(0, 32, 25), v(1), v(null, 0, 0, 'timeout')]).decision, 'Split decision (on scorecards)', 'failed judges do not vote');
  assert.equal(judge.decide([v(0, 30, 20), v(1, 25, 28)]).winner, 0, 'tied vote: more rubric points wins');
  assert.deepEqual(judge.decide([v(0, 30, 30), v(1, 30, 30)]), { winner: null, votes: [1, 1], decision: 'Draw (split panel, level scorecards)', split: true });
  assert.equal(judge.decide([v(1)]).decision, 'Decision (one judge)');
  assert.equal(judge.judgingFrom([]).status, 'awaiting-human');
});

test('judges: verdict parsing is tolerant but strict about the winner and scores', () => {
  const ok = judge.parseVerdict('Here you go:\n```json\n{"side_a": {"argument": 8, "rebuttal": 7, "evidence": 6, "clarity": 9, "rules": 12}, "side_b": {"argument": 5, "rebuttal": 5, "evidence": 5, "clarity": 5, "rules": 5}, "winner": "Side A", "rationale": "A was sharper."}\n```', RUBRIC);
  assert.ok(ok.ok);
  assert.equal((ok as { winner: string }).winner, 'A');
  assert.equal((ok as { scores: Array<Record<string, number>> }).scores[0]!.rules, 10, 'clamped to 10');
  assert.ok(!judge.parseVerdict('{"side_a": {}, "side_b": {}, "winner": "draw"}', RUBRIC).ok);
  assert.ok(!judge.parseVerdict('I pick A.', RUBRIC).ok);
  assert.ok(!judge.parseVerdict('{"side_a": {"argument": 1}, "side_b": {"argument": 1}, "winner": "B"}', RUBRIC).ok);
});

// A fake judge that always prefers the side whose speeches mention "evidence" most, and records what it saw.
const fakeJudge = (id: string, vendor: string, seen: string[]) => {
  const handle = createFakeModel((_s, user) => {
    seen.push(user);
    const count = (label: string) => [...user.matchAll(new RegExp(`\\[[^\\]]*${label} —[^\\]]*\\]\\n([^\\[]*)`, 'g'))].map((m) => (m[1]!.match(/evidence/g) ?? []).length).reduce((a, b) => a + b, 0);
    const a = count('Side A');
    const b = count('Side B');
    const sc = (n: number) => `{"argument": ${n}, "rebuttal": ${n}, "evidence": ${n}, "clarity": ${n}, "rules": ${n}}`;
    return `{"side_a": ${sc(a >= b ? 8 : 5)}, "side_b": ${sc(a >= b ? 5 : 8)}, "winner": "${a >= b ? 'A' : 'B'}", "rationale": "The winner used the evidence."}`;
  });
  return { id, label: `Judge ${id}`, vendor, handle };
};

test('debate end to end with fake models: blinded judges, majority verdict, strikes for empty speeches', async () => {
  // Seat 0 names itself and its vendor in every speech; seat 1 cites evidence and once replies with nothing.
  const bragger = createFakeModel(() => 'As Claude, made by Anthropic, I say: cars pollute. Meridian Atlas agrees.');
  let n = 0;
  const citer = createFakeModel(() => (n++ === 1 ? '' : 'The evidence shows shops lose customers; the evidence from trials is mixed. GPT-5 would agree.'));
  const seen: string[] = [];
  const judges = [fakeJudge('j1', 'Initech', seen), fakeJudge('j2', 'Umbrella', seen), fakeJudge('j3', 'Hooli', seen)];
  const players = [
    { id: 'claude-x', label: 'Meridian Atlas 4', vendor: 'Anthropic', model: 'claude-x' },
    { id: 'gpt-y', label: 'Kestrel Kite', vendor: 'OpenAI', model: 'gpt-y' },
  ];
  const terms = judge.identityTerms(players);
  const g = await playJudgedGame({
    game: debate,
    config: { ...debate.defaults, topic: 'car-free-centres' },
    seed: 11,
    seats: seats(bragger, citer),
    maxStrikes: 3,
    judge: (state) => judge.runJudgeStep({ spec: debate.judge!, state, seed: 11, judges, redactTerms: terms }),
  });
  assert.equal(g.moves.length, 7, 'six speeches and the verdict');
  assert.equal(g.moves[6]!.kind, 'verdict');
  assert.equal(g.strikes[1], 0, 'an empty reply gets a retry first');
  assert.equal(g.moves[3]!.attempts.length, 2);
  assert.equal(g.judging!.status, 'judged');
  assert.equal(g.winner, 1, 'the side that used evidence wins');
  assert.equal(g.judging!.decision, 'Unanimous decision');
  assert.deepEqual(g.judging!.votes, [0, 3]);
  assert.match(g.reason, /Unanimous decision \(3–0\)/);
  assert.equal(seen.length, 3);
  for (const text of seen) {
    for (const name of ['Claude', 'Anthropic', 'Meridian', 'Atlas', 'GPT-5', 'OpenAI', 'Kestrel', 'claude-x', 'gpt-y']) assert.ok(!new RegExp(name, 'i').test(text), `judge saw "${name}"`);
    assert.match(text, /\[name removed\]/);
    assert.match(text, /Side A is the/);
  }
  // Side A is not always the same seat across judges and games (seeded, per judge).
  const maps = new Set<number>();
  for (let seed = 1; seed < 20; seed++) for (const j of ['j1', 'j2', 'j3']) maps.add(judge.sideAFor(seed, j));
  assert.equal(maps.size, 2);
  // Judge verdicts are mapped back to seats: every judge voted for seat 1 whatever letter it saw.
  assert.ok(g.judging!.verdicts.every((v) => v.winner === 1));
  // Without a judge panel the game waits for a human.
  const w = await playJudgedGame({ game: debate, config: debate.defaults, seed: 2, seats: seats(bragger, citer), maxStrikes: 3 });
  assert.equal(w.judging!.status, 'awaiting-human');
  assert.equal(w.winner, null);
  assert.equal(w.reason, 'Awaiting human judging');
  // Two empty replies: "no speech" and a strike (the judges see the gap).
  const silent = createFakeModel(() => '');
  const x = await playJudgedGame({ game: debate, config: debate.defaults, seed: 3, seats: seats(silent, citer), maxStrikes: 3 });
  assert.equal(x.strikes[0], 3);
  assert.ok((x.moves[0]!.snapshot as { speeches: Array<{ missing?: boolean }> }).speeches[0]!.missing);
});

test('judges: a split panel and a spending-cap stop during judging', async () => {
  const say = (w: 'A' | 'B') => createFakeModel(() => `{"side_a": {"argument": 7, "rebuttal": 7, "evidence": 7, "clarity": 7, "rules": 7}, "side_b": {"argument": 6, "rebuttal": 6, "evidence": 6, "clarity": 6, "rules": 6}, "winner": "${w}", "rationale": "ok"}`);
  let s = debate.setup(createRng(1), debate.defaults);
  for (let i = 0; i < 6; i++) s = debate.play(s, 'A fine speech.');
  // Judges answer by letter; the engine maps letters back to seats using each judge's own Side A.
  const seed = 5;
  const js = ['a', 'b', 'c'].map((id) => ({ id, label: id, vendor: 'V', handle: say(judge.sideAFor(seed, id) === 0 ? 'A' : 'B') }));
  js[2]!.handle = say(judge.sideAFor(seed, 'c') === 0 ? 'B' : 'A');
  const r = await judge.runJudgeStep({ spec: debate.judge!, state: s, seed, judges: js, redactTerms: [] });
  assert.equal(r.winner, 0);
  assert.equal(r.decision, 'Split decision');
  assert.deepEqual(r.votes, [2, 1]);
  class BudgetReached extends Error {
    constructor() {
      super('cap');
      this.name = 'BudgetReached';
    }
  }
  let calls = 0;
  await assert.rejects(
    judge.runJudgeStep({ spec: debate.judge!, state: s, seed, judges: js, redactTerms: [], beforeCall: () => { if (calls++ >= 1) throw new BudgetReached(); } }),
    /cap/,
  );
});

// ─────────────────────────────── Tournaments ───────────────────────────────

test('tournament: poker end to end with the Random Baseline; estimates scale with hands', async () => {
  const e10 = tournament.estimateTournament({ game: 'poker', contestantIds: ['pbot-0', 'pbot-1'], options: { hands: '10' } });
  const e40 = tournament.estimateTournament({ game: 'poker', contestantIds: ['pbot-0', 'pbot-1'], options: { hands: '40' } });
  assert.equal(e10.games, 2);
  assert.ok(e40.moves > e10.moves * 3);
  assert.notEqual(e10.fingerprint, e40.fingerprint);
  assert.throws(() => tournament.planTournament({ game: 'poker', contestantIds: ['pbot-0', 'pbot-1'], gamesPerMatch: 4 }), /exactly 2 games/);
  assert.throws(() => tournament.planTournament({ game: 'poker', contestantIds: ['pbot-0', 'pbot-1'], options: { hands: '7' } }), /Hands per match/);
  assert.equal(tournament.planTournament({ game: 'poker', contestantIds: ['pbot-0', 'pbot-1', 'pbot-2', 'pbot-3'] }).settings.suddenDeath, 0);
  const id = tournament.startTournament({ game: 'poker', contestantIds: ['pbot-0', 'pbot-1', 'pbot-2', 'pbot-3'], seeding: 'manual', concurrency: 2, options: { hands: '10' } });
  await tournament.waitForTournament(id);
  const d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'completed', d.manifest.error);
  assert.ok(d.state.champion);
  for (const g of store.readGames(id)) {
    assert.equal(g.status, 'ok');
    assert.equal(g.margin![0] + g.margin![1], 0);
    assert.ok(g.moves.every((m) => !m.forfeit), 'the Random Baseline only picks listed actions');
    assert.equal((g.moves[g.moves.length - 1]!.snapshot as { results: unknown[] }).results.length, 5);
  }
  const final = d.state.matches.find((m) => m.roundName === 'Final')!;
  assert.equal(final.unit, 'chips');
  const full = tournament.gameRecord(id, 'R1-M1-g1')!;
  assert.match(full.transcripts[0][0]!.messages[0]!.content, /Heads-up No-Limit Texas Hold'em/);
});

test('tournament: debate without judges waits for a human; blinded packet; human verdict completes it', async () => {
  setJudges([]);
  const est = tournament.estimateTournament({ game: 'debate', contestantIds: ['pbot-0', 'pbot-1'], options: { topic: 'free-museums' } });
  assert.equal(est.judgeCalls, 0);
  assert.ok(est.warnings.some((w) => /human judging/.test(w)));
  const id = tournament.startTournament({ game: 'debate', contestantIds: ['pbot-0', 'pbot-1'], seeding: 'manual', options: { topic: 'free-museums' } });
  await tournament.waitForTournament(id);
  let d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'interrupted');
  assert.match(d.manifest.error ?? '', /2 judged games are waiting for human judging/);
  assert.equal(d.state.awaitingJudges, 2);
  assert.equal(d.state.complete, false);
  const packets = tournament.judgingPackets(id);
  assert.equal(packets.length, 2);
  for (const p of packets) {
    assert.match(p.material, /Cities|museums/i);
    for (const name of ['Poker Bot', 'Globex', 'Acme', 'pbot-']) assert.ok(!p.material.includes(name), `packet leaks ${name}`);
    assert.match(p.material, /Side A is the/);
  }
  assert.throws(() => tournament.submitHumanVerdict(id, 'R1-M1-g1', { winner: 'C' as 'A' }), /"A" or "B"/);
  const first = tournament.submitHumanVerdict(id, 'R1-M1-g1', { winner: 'A', rationale: 'Clearer case.', scores: { side_a: { argument: 8, rebuttal: 7, evidence: 7, clarity: 8, rules: 9 }, side_b: { argument: 6, rebuttal: 6, evidence: 5, clarity: 6, rules: 9 } } });
  assert.equal(first.record.status, 'ok');
  assert.equal(first.record.judging!.verdicts[0]!.human, true);
  assert.ok(first.record.judging!.verdicts[0]!.scores);
  d = tournament.tournamentDetail(id)!;
  assert.match(d.manifest.error ?? '', /1 judged game is waiting/);
  tournament.submitHumanVerdict(id, 'R1-M1-g2', { winner: 'B' });
  d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'completed');
  assert.ok(d.state.complete);
  assert.throws(() => tournament.submitHumanVerdict(id, 'R1-M1-g2', { winner: 'A' }), /not waiting/);
  // The verdict step of the replay now carries the human decision; the cost was not double counted.
  const rec = tournament.gameRecord(id, 'R1-M1-g1')!;
  assert.equal(rec.moves[rec.moves.length - 1]!.kind, 'verdict');
  assert.match(rec.moves[rec.moves.length - 1]!.label, /Decision \(one judge\)/);
  assert.equal(store.spentUsd(id), 0);
});

test('tournament: judged games with a judge panel; same-vendor judges are left out and judge cost is estimated', async () => {
  setJudges(['judge-acme', 'judge-initech']);
  // pbot-1 is from Acme: only the Initech judge may judge pbot-0 vs pbot-1.
  const est = tournament.estimateTournament({ game: 'courtroom', contestantIds: ['pbot-0', 'pbot-1'], options: { topic: 'chess-engine' } });
  assert.equal(est.judgeCalls, 2, 'one eligible judge × two games');
  assert.ok((est.judgeCostUsd ?? 0) > 0);
  assert.ok(est.estCostUsd >= (est.judgeCostUsd ?? 0));
  assert.deepEqual(est.judges!.map((j) => j.id).sort(), ['judge-acme', 'judge-initech']);
  const id = tournament.startTournament({ game: 'courtroom', contestantIds: ['pbot-0', 'pbot-1'], seeding: 'manual', options: { topic: 'chess-engine' } });
  await tournament.waitForTournament(id);
  const g = tournament.gameRecord(id, 'R1-M1-g1')!;
  // The Random Baseline judge answers with filler: its verdict is unreadable, so the game waits for a human.
  assert.equal(g.status, 'awaiting-judges');
  assert.deepEqual(g.judging!.verdicts.map((v) => v.judgeId), ['judge-initech@judge']);
  assert.deepEqual(g.judging!.excludedVendors, ['Acme']);
  assert.match(g.judging!.note ?? '', /Every judge failed/);
  assert.equal(g.judging!.transcript.length, 1);
  assert.ok(g.judging!.transcript.every((e) => e.judge));
  const prompt = g.judging!.transcript[0]!.messages[0]!.content;
  for (const name of ['Poker Bot', 'Globex', 'Acme']) assert.ok(!prompt.includes(name));
  assert.match(prompt, /Exhibit A — Engine-match analysis/);
  setJudges([]);
});

test('tournament: planned judge warnings when every judge shares a debater’s vendor', () => {
  setJudges(['judge-acme']);
  const plan = tournament.planTournament({ game: 'debate', contestantIds: ['pbot-0', 'pbot-1'] });
  assert.ok(plan.warnings.some((w) => /same vendor as Poker Bot 1/.test(w)));
  setJudges([]);
});

test('spending cap: overspend is bounded by one in-flight call per concurrent game', async () => {
  const cap = 3;
  const id = tournament.startTournament({ game: 'connect4', contestantIds: ['pp-0', 'pp-1', 'pp-2', 'pp-3'], seeding: 'manual', concurrency: 2, maxCostUsd: cap });
  await tournament.waitForTournament(id);
  const d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'cancelled');
  const calls = store.readGames(id).flatMap((g) => [...g.transcripts[0], ...g.transcripts[1]]);
  const biggest = Math.max(...calls.map((c) => c.costUsd));
  assert.ok(d.state.costUsd >= cap, 'the cap was reached');
  assert.ok(d.state.costUsd <= cap + 2 * biggest + 1e-9, `spent ${d.state.costUsd} with a ${cap} cap: at most one call per concurrent game over`);
});
