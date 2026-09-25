/**
 * Heads-up No-Limit Texas Hold'em, duplicate format.
 *
 * One Arena "game" is a session of N hands (blinds 1/2, both stacks reset to
 * 200 every hand). Every deal is drawn from the game seed in `setup`, and the
 * two games of a pairing share that seed with the seats swapped, so each model
 * plays every deal from both seats: the cards even out and only decisions
 * count. The match is scored by total chips won (`scoring: 'margin'`).
 *
 * Hidden information: `prompt`/`view` show a seat only its own hole cards; the
 * dashboard snapshot holds both (the viewer sees both hands).
 */
import { createRng } from '../../core/rng.ts';
import type { ArenaGame, GameConfig, GameOutcome, ParsedMove, Side, TurnPromptContext } from '../types.ts';
import { bestHand, compareScores, fullDeck, pretty } from './poker-eval.ts';

export const STREETS = ['Pre-flop', 'Flop', 'Turn', 'River'] as const;
const BOARD_AT = [0, 3, 4, 5];

export interface PokerConfig extends GameConfig {
  /** Hands per game (a match of two games = 2 × hands). */
  hands: number;
  sb: number;
  bb: number;
  stack: number;
}

export interface PokerAction {
  side: Side;
  street: number;
  kind: 'sb' | 'bb' | 'fold' | 'check' | 'call' | 'bet' | 'raise';
  /** Chips put in by this action. */
  amount: number;
  /** This seat's total bet on the street after the action. */
  to: number;
  allIn: boolean;
}

export interface PokerHand {
  /** 0-based */
  no: number;
  button: Side;
  hole: [string[], string[]];
  /** All five community cards (only the first BOARD_AT[street] are dealt). */
  board: string[];
  street: number;
  stacks: [number, number];
  committed: [number, number];
  bet: [number, number];
  toAct: Side;
  /** Size of the last full bet or raise on this street (the minimum raise increment). */
  lastRaise: number;
  acted: [boolean, boolean];
  actions: PokerAction[];
}

export interface PokerResult {
  no: number;
  button: Side;
  hole: [string[], string[]];
  /** Community cards that were dealt. */
  board: string[];
  winner: Side | null;
  /** Chips won (+) or lost (−) per seat. */
  delta: [number, number];
  pot: number;
  how: 'fold' | 'showdown';
  /** Best hand per seat at showdown. */
  hands?: [{ name: string; cards: string[] }, { name: string; cards: string[] }];
  actions: PokerAction[];
  /** Street the hand ended on. */
  street: number;
}

export interface PokerState {
  cfg: { hands: number; sb: number; bb: number; stack: number };
  decks: string[][];
  hand: PokerHand | null;
  results: PokerResult[];
  net: [number, number];
  /** The hand that the last action finished (shown on the table until the next action). */
  lastEnded: PokerResult | null;
}

/** What the dashboard draws after every action. The viewer sees both hands. */
export interface PokerSnapshot {
  kind: 'poker';
  hands: number;
  /** 1-based hand shown on the table. */
  handNo: number;
  button: Side;
  hole: [string[], string[]];
  board: string[];
  street: string;
  pot: number;
  stacks: [number, number];
  bets: [number, number];
  toAct: Side | null;
  actions: Array<{ side: Side; street: number; text: string; allIn: boolean }>;
  net: [number, number];
  results: Array<{ no: number; delta: [number, number]; winner: Side | null; how: 'fold' | 'showdown' }>;
  /** Set when the shown hand is over. */
  ended: { winner: Side | null; delta: [number, number]; how: 'fold' | 'showdown'; hands?: [{ name: string; cards: string[] }, { name: string; cards: string[] }] } | null;
  over: boolean;
}

const cfgOf = (c: GameConfig) => {
  const p = c as Partial<PokerConfig>;
  return { hands: p.hands ?? 10, sb: p.sb ?? 1, bb: p.bb ?? 2, stack: p.stack ?? 200 };
};

function newHand(s: Pick<PokerState, 'cfg' | 'decks'>, no: number): PokerHand {
  const deck = s.decks[no]!;
  const button = (no % 2) as Side;
  const big = (1 - button) as Side;
  const { sb, bb, stack } = s.cfg;
  const bet: [number, number] = [0, 0];
  bet[button] = sb;
  bet[big] = bb;
  return {
    no,
    button,
    hole: [deck.slice(0, 2), deck.slice(2, 4)],
    board: deck.slice(4, 9),
    street: 0,
    stacks: [stack - bet[0], stack - bet[1]],
    committed: [bet[0], bet[1]],
    bet,
    toAct: button,
    lastRaise: bb,
    acted: [false, false],
    actions: [
      { side: button, street: 0, kind: 'sb', amount: sb, to: sb, allIn: false },
      { side: big, street: 0, kind: 'bb', amount: bb, to: bb, allIn: false },
    ],
  };
}

const maxBet = (h: PokerHand) => Math.max(h.bet[0], h.bet[1]);
export const toCall = (h: PokerHand, side: Side) => Math.max(0, maxBet(h) - h.bet[side]);
const maxTo = (h: PokerHand, side: Side) => h.bet[side] + h.stacks[side];
/** Can this seat raise at all (the opponent still has chips and we have more than the call)? */
const canRaise = (h: PokerHand, side: Side) => h.stacks[1 - side]! > 0 && h.stacks[side] > toCall(h, side);

/** Smallest legal raise-to for the seat (an all-in for less is always allowed). */
export function minRaiseTo(h: PokerHand, side: Side, bb: number): number {
  return Math.min(maxBet(h) + Math.max(h.lastRaise, bb), maxTo(h, side));
}

const pot = (h: PokerHand) => h.committed[0] + h.committed[1];

function actionText(a: PokerAction): string {
  switch (a.kind) {
    case 'sb':
      return `posts small blind ${a.amount}`;
    case 'bb':
      return `posts big blind ${a.amount}`;
    case 'fold':
      return 'folds';
    case 'check':
      return 'checks';
    case 'call':
      return a.allIn ? `calls ${a.amount} (all-in)` : `calls ${a.amount}`;
    case 'bet':
      return a.allIn ? `bets ${a.to} (all-in)` : `bets ${a.to}`;
    case 'raise':
      return a.allIn ? `raises to ${a.to} (all-in)` : `raises to ${a.to}`;
  }
}

function finish(h: PokerHand, how: 'fold' | 'showdown', folder?: Side): PokerResult {
  if (how === 'fold') {
    const w = (1 - folder!) as Side;
    const delta: [number, number] = [0, 0];
    delta[w] = h.committed[folder!];
    delta[folder!] = -h.committed[folder!];
    return { no: h.no, button: h.button, hole: h.hole, board: h.board.slice(0, BOARD_AT[h.street]), winner: w, delta, pot: pot(h), how, actions: h.actions, street: h.street };
  }
  const v0 = bestHand([...h.hole[0], ...h.board]);
  const v1 = bestHand([...h.hole[1], ...h.board]);
  const c = compareScores(v0.score, v1.score);
  // Only the matched amount is at risk; anything over it goes back to its owner.
  const risk = Math.min(h.committed[0], h.committed[1]);
  const winner: Side | null = c > 0 ? 0 : c < 0 ? 1 : null;
  const delta: [number, number] = winner === null ? [0, 0] : winner === 0 ? [risk, -risk] : [-risk, risk];
  return {
    no: h.no,
    button: h.button,
    hole: h.hole,
    board: h.board.slice(),
    winner,
    delta,
    pot: pot(h),
    how,
    hands: [
      { name: v0.name, cards: v0.cards },
      { name: v1.name, cards: v1.cards },
    ],
    actions: h.actions,
    street: 3,
  };
}

function cloneHand(h: PokerHand): PokerHand {
  return { ...h, stacks: [...h.stacks], committed: [...h.committed], bet: [...h.bet], acted: [...h.acted], actions: h.actions.slice() };
}

/** Parse "raise 40", "bet to 12", "raise by 10", "all in", "call", … into a canonical move for this seat. */
export function parsePokerAction(h: PokerHand, side: Side, bb: number, text: string): ParsedMove {
  const t = text
    .toLowerCase()
    .replace(/[*_`"'.!]/g, ' ')
    .replace(/chips?/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const owe = toCall(h, side);
  const hi = maxTo(h, side);
  const lo = minRaiseTo(h, side, bb);
  const menu = legalSummary(h, side, bb);
  if (!t) return { ok: false, error: `The ACTION line was empty. ${menu}` };
  if (/^(all[\s-]?in|allin|shove|jam|push)\b/.test(t)) {
    if (!canRaise(h, side)) return { ok: true, move: owe > 0 ? 'call' : 'check' };
    return { ok: true, move: `raise ${hi}` };
  }
  if (/^fold/.test(t)) {
    if (owe === 0) return { ok: false, error: `There is nothing to call, so you cannot fold: check instead (it is free). ${menu}` };
    return { ok: true, move: 'fold' };
  }
  if (/^check/.test(t)) {
    if (owe > 0) return { ok: false, error: `You cannot check: there is a bet of ${owe} to call. ${menu}` };
    return { ok: true, move: 'check' };
  }
  if (/^call/.test(t)) return { ok: true, move: owe > 0 ? 'call' : 'check' };
  const m = t.match(/^(raise|bet|re-?raise|3-?bet|4-?bet|min-?raise)\b\s*(by|to)?\s*\$?(\d+(?:\.\d+)?)?/);
  if (m) {
    if (!canRaise(h, side)) return { ok: false, error: `You cannot raise now (${h.stacks[1 - side] === 0 ? 'your opponent is all-in' : 'you do not have enough chips'}). ${menu}` };
    if (!m[3]) {
      if (/min/.test(m[1]!)) return { ok: true, move: `raise ${lo}` };
      return { ok: false, error: `Say how much: "raise <amount>" where <amount> is your total bet for this street (from ${lo} to ${hi}). ${menu}` };
    }
    const n = Number(m[3]);
    if (!Number.isInteger(n)) return { ok: false, error: `Chip amounts are whole numbers; "${m[3]}" is not. ${menu}` };
    const to = m[2] === 'by' ? maxBet(h) + n : n;
    if (to > hi) return { ok: false, error: `You only have ${h.stacks[side]} chips behind: the largest raise is to ${hi} (all-in). ${menu}` };
    if (to < lo) return { ok: false, error: `A raise to ${to} is too small: the minimum is to ${lo} (the current bet ${maxBet(h)} plus at least ${Math.max(h.lastRaise, bb)}). "raise <amount>" is your TOTAL bet for this street. ${menu}` };
    return { ok: true, move: `raise ${to}` };
  }
  return { ok: false, error: `"${text.trim().slice(0, 40)}" is not an action. Write one of: fold, check, call, raise <amount>, all-in. ${menu}` };
}

function legalSummary(h: PokerHand, side: Side, bb: number): string {
  const owe = toCall(h, side);
  const parts = owe > 0 ? ['fold', `call (${owe})`] : ['check'];
  if (canRaise(h, side)) {
    const lo = minRaiseTo(h, side, bb);
    const hi = maxTo(h, side);
    parts.push(lo < hi ? `raise to any amount from ${lo} to ${hi}` : `all-in (${hi})`);
    if (lo < hi) parts.push(`all-in (${hi})`);
  }
  return `Legal now: ${parts.join(', ')}.`;
}

/** A few raise sizes for the listed menu (the Random Baseline picks from it); any size in range is legal. */
function raiseMenu(h: PokerHand, side: Side, bb: number): number[] {
  if (!canRaise(h, side)) return [];
  const lo = minRaiseTo(h, side, bb);
  const hi = maxTo(h, side);
  const owe = toCall(h, side);
  const afterCall = pot(h) + owe;
  const sizes = [lo, maxBet(h) + Math.round(afterCall / 2), maxBet(h) + afterCall, hi];
  return [...new Set(sizes.map((x) => Math.max(lo, Math.min(hi, x))))].sort((a, b) => a - b);
}

function apply(s: PokerState, move: string): PokerState {
  const h0 = s.hand;
  if (!h0) throw new Error('Poker: the session is over');
  const h = cloneHand(h0);
  const side = h.toAct;
  const other = (1 - side) as Side;
  const { bb } = s.cfg;
  const owe = toCall(h, side);
  let ended: PokerResult | null = null;
  if (move === 'fold') {
    if (owe === 0) throw new Error('Poker: fold with nothing to call');
    h.actions.push({ side, street: h.street, kind: 'fold', amount: 0, to: h.bet[side], allIn: false });
    ended = finish(h, 'fold', side);
  } else {
    if (move === 'check') {
      if (owe > 0) throw new Error('Poker: illegal check');
      h.actions.push({ side, street: h.street, kind: 'check', amount: 0, to: h.bet[side], allIn: false });
    } else if (move === 'call') {
      if (owe === 0) throw new Error('Poker: nothing to call');
      const add = Math.min(owe, h.stacks[side]);
      h.stacks[side] -= add;
      h.committed[side] += add;
      h.bet[side] += add;
      h.actions.push({ side, street: h.street, kind: 'call', amount: add, to: h.bet[side], allIn: h.stacks[side] === 0 });
    } else {
      const m = move.match(/^raise (\d+)$/);
      if (!m) throw new Error(`Poker: bad move "${move}"`);
      const to = Number(m[1]);
      const lo = minRaiseTo(h, side, bb);
      if (!canRaise(h, side) || to < lo || to > maxTo(h, side)) throw new Error(`Poker: illegal raise to ${to}`);
      const before = maxBet(h);
      const add = to - h.bet[side];
      h.stacks[side] -= add;
      h.committed[side] += add;
      h.bet[side] = to;
      // A short all-in raise does not change the minimum increment.
      if (to - before >= h.lastRaise) h.lastRaise = to - before;
      h.acted[other] = false;
      h.actions.push({ side, street: h.street, kind: before === 0 ? 'bet' : 'raise', amount: add, to, allIn: h.stacks[side] === 0 });
    }
    h.acted[side] = true;
    // Equal starting stacks mean a call always matches the bet (an all-in call included).
    const matched = h.bet[0] === h.bet[1] || (move === 'call' && h.stacks[side] === 0);
    if (matched && h.acted[0] && h.acted[1]) {
      if (h.street === 3 || h.stacks[0] === 0 || h.stacks[1] === 0) {
        h.street = 3;
        ended = finish(h, 'showdown');
      } else {
        h.street++;
        h.bet = [0, 0];
        h.acted = [false, false];
        h.lastRaise = bb;
        h.toAct = (1 - h.button) as Side;
      }
    } else h.toAct = other;
  }
  if (!ended) return { ...s, hand: h, lastEnded: null };
  const results = [...s.results, ended];
  const net: [number, number] = [s.net[0] + ended.delta[0], s.net[1] + ended.delta[1]];
  const next = results.length < s.cfg.hands ? newHand(s, results.length) : null;
  return { ...s, hand: next, results, net, lastEnded: ended };
}

const signed = (n: number) => (n > 0 ? `+${n}` : String(n));

function handHistory(h: PokerHand, side: Side): string {
  const who = (x: Side) => (x === side ? 'You' : 'Opponent');
  const lines: string[] = [];
  for (let st = 0; st <= h.street; st++) {
    const acts = h.actions.filter((a) => a.street === st);
    const cards = st === 0 ? '' : ` [${pretty(h.board.slice(0, BOARD_AT[st]))}]`;
    lines.push(`${STREETS[st]}${cards}: ${acts.length ? acts.map((a) => `${who(a.side)} ${actionText(a)}`).join('; ') : '(no action yet)'}`);
  }
  return lines.join('\n');
}

const RULES = (hands: number, cfg: PokerState['cfg']) =>
  [
    `Heads-up No-Limit Texas Hold'em between two players. This game is ${hands} hands. Both stacks are reset to ${cfg.stack} chips at the start of every hand, so every hand is independent. Your score is the total number of chips you win or lose over all hands.`,
    `Blinds are ${cfg.sb}/${cfg.bb}. The button posts the small blind (${cfg.sb}) and the other player the big blind (${cfg.bb}). The button alternates every hand.`,
    'Each player gets two private hole cards. Five community cards are dealt face up: the flop (3 cards), the turn (1) and the river (1). There is a betting round before the flop and after each of those streets.',
    'Before the flop the button acts first; on the flop, turn and river the big blind acts first.',
    'Actions: fold, check (only when there is nothing to call), call, raise <amount>, all-in. "raise <amount>" means raise TO that total bet for the current street (an opening bet is written the same way). The minimum raise is to the current bet plus the size of the last bet or raise on this street (at least the big blind). You can always go all-in.',
    'If nobody folds, the best five-card hand made from your two hole cards and the five community cards wins the pot at showdown; equal hands split the pot. Hand ranks from high to low: straight flush, four of a kind, full house, flush, straight, three of a kind, two pair, one pair, high card. A-2-3-4-5 is the lowest straight.',
    'Fairness: every deal is also played in another game with the seats and cards swapped, so both players get exactly the same cards over the match. Luck cancels out; only your decisions count.',
  ].join('\n');

function seatView(s: PokerState, side: Side): string {
  const h = s.hand;
  const played = s.results.length;
  const head = `Hand ${played + 1} of ${s.cfg.hands} · your result so far: ${signed(s.net[side])} chips${s.results.length ? ` (last hand: ${signed(s.results[s.results.length - 1]!.delta[side])})` : ''}`;
  if (!h) return `${head}\nThe session is over.`;
  const owe = toCall(h, side);
  return [
    head,
    `You are ${h.button === side ? 'the button (small blind)' : 'the big blind'}.`,
    `Your hole cards: ${pretty(h.hole[side])}   (${h.hole[side].join(' ')})`,
    `Board: ${h.street === 0 ? '(no community cards yet: pre-flop)' : `${pretty(h.board.slice(0, BOARD_AT[h.street]))} (${STREETS[h.street]!.toLowerCase()})`}`,
    `Pot: ${pot(h)} · your stack: ${h.stacks[side]} · opponent stack: ${h.stacks[1 - side]}`,
    `Bets this street: you ${h.bet[side]}, opponent ${h.bet[1 - side]}. ${owe > 0 ? `To call: ${owe}.` : 'Nothing to call.'}`,
    '',
    'Betting so far this hand:',
    handHistory(h, side),
  ].join('\n');
}

export function pokerPrompt(s: PokerState, side: Side, ctx: Pick<TurnPromptContext, 'config'>): string {
  const h = s.hand!;
  const { bb } = s.cfg;
  const owe = toCall(h, side);
  const menu: string[] = owe > 0 ? ['fold', 'call'] : ['check'];
  const sizes = raiseMenu(h, side, bb);
  const hi = maxTo(h, side);
  for (const x of sizes) if (x !== hi) menu.push(`raise ${x}`);
  if (sizes.length) menu.push('all-in');
  const range = canRaise(h, side) && minRaiseTo(h, side, bb) < hi ? `\nAny raise from ${minRaiseTo(h, side, bb)} to ${hi} is legal (${hi} = all-in); the sizes above are only examples.` : '';
  return [
    `You are playing poker against another AI model in the Gauntlet Arena.`,
    `== RULES ==\n${RULES(cfgOf(ctx.config).hands, s.cfg)}`,
    [
      '== HOW TO ANSWER ==',
      'Think briefly, then end your reply with exactly these two lines:',
      'REASON: <one short sentence explaining your decision; viewers see it>',
      'ACTION: <fold | check | call | raise <amount> | all-in>',
      'If your action is illegal or cannot be read, you get one retry with the problem explained. If the retry also fails, you check if that is free, otherwise you fold, and you receive a strike.',
    ].join('\n'),
    `== YOUR SITUATION ==\n${seatView(s, side)}`,
    `== LEGAL ACTIONS ==\n${menu.map((m) => `\`ACTION: ${m}\``).join(' ')}${range}`,
    `It is your turn to act (${STREETS[h.street]!.toLowerCase()}).`,
  ].join('\n\n');
}

function snapshotOf(s: PokerState): PokerSnapshot {
  const results = s.results.map((r) => ({ no: r.no, delta: r.delta, winner: r.winner, how: r.how }));
  const base = { kind: 'poker' as const, hands: s.cfg.hands, net: s.net, results, over: !s.hand };
  const acts = (list: PokerAction[]) => list.map((a) => ({ side: a.side, street: a.street, text: actionText(a), allIn: a.allIn }));
  const e = s.lastEnded;
  if (e) {
    return {
      ...base,
      handNo: e.no + 1,
      button: e.button,
      hole: e.hole,
      board: e.board,
      street: e.how === 'showdown' ? 'Showdown' : STREETS[e.street]!,
      pot: e.pot,
      // Stacks after the pot is pushed to the winner (both started the hand with the same stack).
      stacks: [s.cfg.stack + e.delta[0], s.cfg.stack + e.delta[1]],
      bets: [0, 0],
      toAct: null,
      actions: acts(e.actions),
      ended: { winner: e.winner, delta: e.delta, how: e.how, hands: e.hands },
    };
  }
  const h = s.hand!;
  return {
    ...base,
    handNo: h.no + 1,
    button: h.button,
    hole: h.hole,
    board: h.board.slice(0, BOARD_AT[h.street]),
    street: STREETS[h.street]!,
    pot: pot(h),
    stacks: [...h.stacks],
    bets: [...h.bet],
    toAct: h.toAct,
    actions: acts(h.actions),
    ended: null,
  };
}

export const HAND_CHOICES = ['10', '20', '40', '60'];

export const poker: ArenaGame<PokerState> = {
  id: 'poker',
  name: 'Heads-up Poker',
  version: '1.0.0',
  tagline: "No-Limit Hold'em. Same cards for both. Only decisions count.",
  description:
    "Heads-up No-Limit Texas Hold'em with hidden cards. Every deal is played twice with the cards swapped, so luck cancels out: it tests reading a situation, sizing bets and bluffing under uncertainty.",
  sides: [
    { name: 'Seat 1', color: '#22c55e' },
    { name: 'Seat 2', color: '#a855f7' },
  ],
  rules: RULES(10, { hands: 10, sb: 1, bb: 2, stack: 200 }),
  moveHelp: 'an action: fold, check, call, raise <amount> (your total bet for the street) or all-in, for example "ACTION: raise 6"',
  defaults: { maxPlies: 400, listLegalMoves: true, scoring: 'margin', unit: 'chips', hands: 10, sb: 1, bb: 2, stack: 200 } as PokerConfig,
  estimate: { pliesPerGame: 45, inputTokensPerMove: 1150, outputTokensPerMove: 900 },
  capRule: 'Every hand ends by itself; the safety cap of 400 actions is never reached in practice.',
  engine: 'turns',
  gamesPerMatchOptions: [2],
  suddenDeath: false,
  answerKey: 'ACTION',
  strikesLose: false,
  unit: 'chips',
  options: [
    {
      key: 'hands',
      label: 'Hands per match',
      hint: 'Split over the two games of a pairing; each deal is played twice with cards swapped, so luck cancels out.',
      choices: HAND_CHOICES.map((v) => ({ value: v, label: v, hint: `${Number(v) / 2} duplicate pairs` })),
      default: '20',
    },
  ],
  configure(config, options) {
    const hands = options.hands ?? '20';
    if (!HAND_CHOICES.includes(String(hands))) throw new Error(`Hands per match must be one of ${HAND_CHOICES.join(', ')}`);
    return { ...config, hands: Number(hands) / 2, listLegalMoves: true };
  },
  estimateFor(config) {
    const hands = cfgOf(config).hands;
    return { pliesPerGame: Math.round(hands * 4.5), inputTokensPerMove: 1150, outputTokensPerMove: 900 };
  },

  setup(rng, config) {
    const cfg = cfgOf(config);
    // One independent shuffle per hand, all from the game seed: both games of a pair get identical deals.
    const decks = Array.from({ length: cfg.hands }, (_, i) => createRng(Math.floor(rng.fork(`hand${i}`).next() * 2 ** 32)).shuffle(fullDeck()).slice(0, 9));
    const base = { cfg, decks };
    return { ...base, hand: newHand(base, 0), results: [], net: [0, 0], lastEnded: null };
  },
  toMove: (s) => s.hand?.toAct ?? 0,
  legalMoves(s) {
    const h = s.hand;
    if (!h) return [];
    const side = h.toAct;
    const out = toCall(h, side) > 0 ? ['fold', 'call'] : ['check'];
    for (const x of raiseMenu(h, side, s.cfg.bb)) out.push(`raise ${x}`);
    return out;
  },
  parseMove(s, text) {
    if (!s.hand) return { ok: false, error: 'The session is over.' };
    return parsePokerAction(s.hand, s.hand.toAct, s.cfg.bb, text);
  },
  play: apply,
  outcome(s): GameOutcome | null {
    if (s.hand) return null;
    const [a, b] = s.net;
    if (a === b) return { winner: null, reason: `Level on chips after ${s.cfg.hands} hands` };
    return { winner: a > b ? 0 : 1, reason: `${Math.abs(a)} chips up over ${s.cfg.hands} hands` };
  },
  adjudicate(s) {
    const [a, b] = s.net;
    return { winner: a === b ? null : a > b ? 0 : 1, reason: 'Action cap reached (hand in progress abandoned)' };
  },
  label(s, move) {
    const h = s.hand!;
    const side = h.toAct;
    const st = STREETS[h.street]!;
    let text: string;
    if (move === 'fold') text = 'fold';
    else if (move === 'check') text = 'check';
    else if (move === 'call') text = `call ${Math.min(toCall(h, side), h.stacks[side])}`;
    else {
      const to = Number(move.slice(6));
      text = to === maxTo(h, side) ? `all-in ${to}` : `${maxBet(h) === 0 ? 'bet' : 'raise to'} ${to}`;
    }
    return `H${h.no + 1} ${st}: ${text}`;
  },
  formatHistory: (labels) => (labels.length ? labels.join('\n') : '(no actions yet)'),
  view: seatView,
  snapshot: snapshotOf,
  prompt: (s, side, ctx) => pokerPrompt(s, side, ctx),
  fallbackMove(s) {
    const h = s.hand!;
    return toCall(h, h.toAct) > 0 ? 'fold' : 'check';
  },
  margin: (s) => [s.net[0], s.net[1]],
};
