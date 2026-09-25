/**
 * Mock-mode policies for the poker and debate / courtroom Arena formats: a
 * strength-based poker player, a speech writer that builds arguments from the
 * motion bank, and a simulated judge panel that goes through the REAL panel
 * selection, blinding (Side A/B per judge) and majority logic of
 * src/arena/judge.ts. Everything is seeded, so the demo is identical on every load.
 */
import { bestHand, rankValue } from '../../../src/arena/games/poker-eval.ts';
import { toCall, type PokerState } from '../../../src/arena/games/poker.ts';
import { ORDER, countWords, type DebateState } from '../../../src/arena/games/debate.ts';
import { caseById, motionById } from '../../../src/arena/games/debate-bank.ts';
import { judgingFrom, selectArenaPanel, sideAFor } from '../../../src/arena/judge.ts';
import type { Rng } from '../types.ts';
import type { ArenaGame, ArenaJudging, JudgeVerdict, Side } from '../arena/types.ts';
import type { ContestantView } from '../types.ts';

// ─────────────────────────────── Poker ───────────────────────────────

/** 0..1 rough hand strength for the seat to act. */
function handStrength(s: PokerState, side: Side): number {
  const h = s.hand!;
  const hole = h.hole[side];
  const board = h.board.slice(0, [0, 3, 4, 5][h.street]);
  if (!board.length) {
    const [a, b] = hole.map(rankValue).sort((x, y) => y - x) as [number, number];
    const pair = a === b;
    const suited = hole[0]![1] === hole[1]![1];
    return Math.min(1, (pair ? 0.5 + a / 28 : (a + b) / 40) + (suited ? 0.05 : 0) + (a - b === 1 ? 0.03 : 0));
  }
  const v = bestHand([...hole, ...board]);
  const cat = v.score[0]!;
  return Math.min(1, [0.15, 0.45, 0.65, 0.75, 0.82, 0.86, 0.92, 0.97, 1][cat]! + (cat === 1 ? (v.score[1]! - 8) / 40 : 0));
}

const WHY = {
  raiseStrong: ['Strong hand: build the pot.', 'I am ahead most of the time here; raise for value.', 'Top of my range, so I raise.'],
  bluff: ['A small bluff: my opponent has shown weakness.', 'Represent strength on a scary board.', 'Semi-bluff to take the pot now.'],
  call: ['Good enough to call at this price.', 'The pot odds justify a call.', 'Calling keeps weaker hands in.'],
  check: ['Check and see a free card.', 'No reason to build the pot with a medium hand.', 'Pot control: check.'],
  fold: ['Too weak to continue against this bet.', 'The price is too high for this hand; fold.', 'Behind most of the time: fold.'],
};

export function pokerChoice(s: PokerState, strength: number, rng: Rng): { move: string; note: string; text: string } {
  const h = s.hand!;
  const side = h.toAct;
  const legal = (() => {
    const owe = toCall(h, side);
    const out = owe > 0 ? ['fold', 'call'] : ['check'];
    const raises = [...new Set(legalRaises(s))];
    return { owe, out, raises };
  })();
  const hs = handStrength(s, side);
  const noise = (rng.next() - 0.5) * (1.1 - strength) * 0.8;
  const eff = hs + noise;
  const potOdds = legal.owe / Math.max(1, legal.owe + h.committed[0] + h.committed[1]);
  let move: string;
  let why: string;
  if (legal.raises.length && eff > 0.72) {
    move = `raise ${eff > 0.93 && rng.chance(0.4) ? legal.raises[legal.raises.length - 1] : legal.raises[Math.min(legal.raises.length - 1, rng.chance(0.5) ? 1 : 0)]}`;
    why = rng.pick(WHY.raiseStrong);
  } else if (legal.raises.length && eff < 0.3 && rng.chance(0.08 + strength * 0.12)) {
    move = `raise ${legal.raises[0]}`;
    why = rng.pick(WHY.bluff);
  } else if (legal.owe > 0) {
    if (eff > potOdds + 0.12 || legal.owe <= 2) {
      move = 'call';
      why = rng.pick(WHY.call);
    } else {
      move = 'fold';
      why = rng.pick(WHY.fold);
    }
  } else {
    move = 'check';
    why = rng.pick(WHY.check);
  }
  if (strength === 0) {
    const all = [...legal.out, ...legal.raises.map((r) => `raise ${r}`)];
    move = rng.pick(all);
    why = 'Picked one of the listed actions at random.';
  }
  const shown = move.startsWith('raise') && Number(move.slice(6)) === h.bet[side] + h.stacks[side] ? 'all-in' : move;
  const cards = h.hole[side].join(' ');
  const text = strength === 0 ? `I will try this.\nACTION: ${shown}` : `My cards: ${cards}. Pot ${h.committed[0] + h.committed[1]}, ${legal.owe ? `${legal.owe} to call` : 'nothing to call'}.\n${why}\nREASON: ${why}\nACTION: ${shown}`;
  return { move, note: why, text };
}

function legalRaises(s: PokerState): number[] {
  // The same menu the prompt lists (min raise, ~half pot, ~pot, all-in).
  const h = s.hand!;
  const side = h.toAct;
  if (!(h.stacks[1 - side]! > 0 && h.stacks[side] > toCall(h, side))) return [];
  const maxBet = Math.max(h.bet[0], h.bet[1]);
  const hi = h.bet[side] + h.stacks[side];
  const lo = Math.min(maxBet + Math.max(h.lastRaise, s.cfg.bb), hi);
  const after = h.committed[0] + h.committed[1] + toCall(h, side);
  return [...new Set([lo, maxBet + Math.round(after / 2), maxBet + after, hi].map((x) => Math.max(lo, Math.min(hi, x))))].sort((a, b) => a - b);
}

// ─────────────────────────────── Speeches ───────────────────────────────

const OPEN = ['Thank you.', 'Good evening.', 'Let me start with what matters.', 'Members of the panel, thank you.'];
const LINK = ['First,', 'Second,', 'Third,', 'Finally,'];
const FILL = [
  'That is not a small detail; it changes how people actually live.',
  'The question is not whether this is perfect, but whether it is better than what we have now.',
  'Every serious study of the question points the same way, even if the effect size varies.',
  'Ask who pays the price when we get this wrong.',
  'We should judge the policy by its results, not by its intentions.',
  'This is where the other side’s case is weakest.',
  'Real places have tried this, and the lessons are clear enough to act on.',
  'Nothing in my opponent’s case answers that point.',
];

const COURT_FILL = [
  'Look at what the exhibits actually say, not what my opponent wishes they said.',
  'The timeline matters here, and it is written down in the case file.',
  'Suspicion is not proof, and a coincidence is not a confession.',
  'Nothing in the other side’s account explains that fact.',
  'Put the exhibits side by side and the picture is clear.',
  'The burden of proof has not moved.',
];

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

function padTo(sentences: string[], target: number, rng: Rng, court: boolean): string {
  const out = sentences.slice();
  const pool = rng.shuffle(court ? COURT_FILL : FILL);
  let i = 0;
  // No sentence twice; when the pool runs out the speech is simply shorter.
  while (countWords(out.join(' ')) < target && i < pool.length) out.splice(Math.max(1, out.length - 1), 0, pool[i++]!);
  return out.join(' ');
}

/** A speech for the seat to speak, built from the motion's (or case's) own points. Weaker models ramble past the limit more often. */
export function speechFor(s: DebateState, strength: number, rng: Rng): string {
  const [side, round] = ORDER[s.speeches.length]!;
  const limit = s.limits[round]!;
  const court = s.variant === 'courtroom';
  const m = court ? undefined : motionById(s.topicId);
  const c = court ? caseById(s.topicId) : undefined;
  const mine = court ? (side === 0 ? c!.prosecution : c!.defence) : side === 0 ? m!.pro : m!.con;
  const theirs = court ? (side === 0 ? c!.defence : c!.prosecution) : side === 0 ? m!.con : m!.pro;
  const ex = c ? rng.shuffle(c.exhibits) : [];
  const exRef = (i: number) => (c ? ` Exhibit ${ex[i % ex.length]!.id} (${ex[i % ex.length]!.title.toLowerCase()}) shows it.` : '');
  let parts: string[];
  if (round === 0) {
    parts = [
      rng.pick(OPEN),
      court ? (side === 0 ? `The evidence proves that the defendant is guilty of ${c!.charge.charAt(0).toLowerCase() + c!.charge.slice(1)}.` : 'The prosecution has a story, not proof.') : side === 0 ? `We believe that ${m!.title.charAt(0).toLowerCase() + m!.title.slice(1)}.` : `We oppose the motion that ${m!.title.charAt(0).toLowerCase() + m!.title.slice(1)}.`,
      ...mine.map((p, i) => `${LINK[i]} ${p}.${exRef(i)}`),
      side === 0 ? 'That is our case.' : 'For these reasons we oppose.',
    ];
  } else if (round === 1) {
    parts = [
      `My opponent says ${theirs[0]}.`,
      strength > 0.6 ? `But that misses the point: ${mine[1] ?? mine[0]}.${exRef(1)}` : `That is simply wrong.`,
      `They also claim ${theirs[1] ?? theirs[0]}.`,
      strength > 0.75 ? `Even if that were true, ${mine[2] ?? mine[0]}, which matters more.${exRef(2)}` : `We disagree.`,
    ];
  } else {
    parts = [
      'To sum up.',
      `${cap(mine[0]!)}.`,
      strength > 0.5 ? `The other side never answered that ${mine[mine.length - 1]}.` : 'Our case stands.',
      court ? (side === 0 ? 'The only reasonable verdict is guilty.' : 'There is reasonable doubt, so you must acquit.') : side === 0 ? 'Please support the motion.' : 'Please oppose the motion.',
    ];
  }
  // Strong debaters land just under the limit; weaker ones sometimes run over (and get cut).
  const over = rng.chance((1 - strength) * 0.45);
  const target = over ? limit + 12 + Math.round(rng.next() * 40) : Math.round(limit * (0.72 + rng.next() * 0.24));
  return padTo(parts, target, rng, court);
}

// ─────────────────────────────── Judges ───────────────────────────────

const RATIONALE = [
  (w: string, l: string) => `${w} answered the other side point by point; ${l} repeated its opening instead of rebutting.`,
  (w: string) => `${w} used concrete evidence and stayed within the word limits.`,
  (w: string, l: string) => `Closer than it looks, but ${w}’s closing tied the case together while ${l} left the key objection unanswered.`,
  (w: string) => `${w} was clearer and more disciplined; its best point was never answered.`,
];
const COURT_RATIONALE = [
  (w: string, l: string) => `${w} cited the exhibits accurately and explained their limits; ${l} leaned on a reading the exhibits do not support.`,
  (w: string) => `${w} built its case on the timeline in the exhibits, not on speculation.`,
  (w: string, l: string) => `${l} ignored the strongest exhibit against it; ${w} confronted it head on.`,
];

/**
 * A judge panel for a finished debate in mock mode: the real panel selection
 * (no judge from a debater's vendor), per-judge Side A/B mapping and majority
 * logic, with scores driven by the debaters' strengths plus noise.
 */
export function simulateJudging(opts: {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  game: ArenaGame<any>;
  state: DebateState;
  seed: number;
  players: [ContestantView, ContestantView];
  strengths: [number, number];
  pool: ContestantView[];
  rng: Rng;
  noJudges?: boolean;
}): ArenaJudging {
  const { judges, excludedVendors } = selectArenaPanel(opts.noJudges ? [] : opts.pool, opts.players);
  if (!judges.length) return judgingFrom([], { excludedVendors, note: opts.noJudges ? 'No judge models are configured (config/settings.json → judges). A person can judge it on the human judging screen.' : 'Every configured judge is from the same vendor as a debater. A person can judge it on the human judging screen.' });
  const court = opts.state.variant === 'courtroom';
  const penalty = [0, 1].map((s) => opts.state.speeches.filter((x) => x.side === s).reduce((a, x) => a + (x.cut ? 1 : 0) + (x.missing ? 2 : 0), 0));
  const verdicts: JudgeVerdict[] = judges.map((j) => {
    const r = opts.rng.fork(j.id);
    const score = (base: number) => Math.max(1, Math.min(10, Math.round(base + (r.next() - 0.5) * 3)));
    const sc = ([0, 1] as const).map((s) => {
      const q = 4 + opts.strengths[s] * 5;
      return { argument: score(q), rebuttal: score(q - 0.5), evidence: score(q - (court ? 0 : 0.8)), clarity: score(q + 0.3), rules: Math.max(1, 9 - penalty[s]! * 2) };
    }) as unknown as [Record<string, number>, Record<string, number>];
    const tot = sc.map((x) => Object.values(x).reduce((a, b) => a + b, 0));
    const winner: Side = tot[0]! === tot[1]! ? (r.chance(0.5) ? 0 : 1) : tot[0]! > tot[1]! ? 0 : 1;
    const w = `Side ${sideAFor(opts.seed, `${j.id}@judge`) === winner ? 'A' : 'B'}`;
    const l = w === 'Side A' ? 'Side B' : 'Side A';
    const why = court ? r.pick(COURT_RATIONALE)(w, l) : r.pick(RATIONALE)(w, l);
    return {
      judgeId: `${j.id}@judge`,
      judgeLabel: j.label,
      vendor: j.vendor,
      sideA: sideAFor(opts.seed, `${j.id}@judge`),
      winner,
      scores: sc,
      rationale: why,
      costUsd: Math.round(((2400 * j.pricing.inputPerM + 700 * j.pricing.outputPerM) / 1e6) * 1e6) / 1e6,
    };
  });
  return judgingFrom(verdicts, { excludedVendors });
}
