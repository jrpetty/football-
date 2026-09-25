/**
 * Poker cards and hand evaluation (pure, no dependencies).
 *
 * Cards are two-character strings: rank "23456789TJQKA" + suit "shdc", e.g.
 * "As" (ace of spades), "Td" (ten of diamonds). The best five of seven cards
 * is found by trying all 21 five-card combinations, which is simple enough to
 * be obviously correct and fast enough for a few thousand showdowns.
 */

export const RANKS = '23456789TJQKA';
export const SUITS = 'shdc';
export const SUIT_SYMBOL: Record<string, string> = { s: '♠', h: '♥', d: '♦', c: '♣' };
const RANK_NAME: Record<string, string> = { '2': 'Two', '3': 'Three', '4': 'Four', '5': 'Five', '6': 'Six', '7': 'Seven', '8': 'Eight', '9': 'Nine', T: 'Ten', J: 'Jack', Q: 'Queen', K: 'King', A: 'Ace' };
const PLURAL: Record<string, string> = { Six: 'Sixes' };
const plural = (r: string) => PLURAL[RANK_NAME[r]!] ?? `${RANK_NAME[r]}s`;

export const CATEGORY = ['High card', 'Pair', 'Two pair', 'Three of a kind', 'Straight', 'Flush', 'Full house', 'Four of a kind', 'Straight flush'] as const;

export function fullDeck(): string[] {
  const out: string[] = [];
  for (const s of SUITS) for (const r of RANKS) out.push(r + s);
  return out;
}

/** 2..14 */
export const rankValue = (card: string): number => RANKS.indexOf(card[0]!) + 2;

/** "A♠ K♦" */
export function pretty(cards: string[]): string {
  return cards.map((c) => `${c[0] === 'T' ? '10' : c[0]}${SUIT_SYMBOL[c[1]!] ?? c[1]}`).join(' ');
}

export interface HandValue {
  /** [category 0..8, tie-break ranks…]; compare lexicographically. */
  score: number[];
  /** "Full house, Kings full of Sevens" */
  name: string;
  category: (typeof CATEGORY)[number];
  /** The best five cards, strongest first. */
  cards: string[];
}

const valueName = (v: number) => RANKS[v - 2]!;

/** Evaluate exactly five cards. */
export function evaluate5(cards: string[]): HandValue {
  if (cards.length !== 5) throw new Error('evaluate5 needs 5 cards');
  const vals = cards.map(rankValue).sort((a, b) => b - a);
  const flush = cards.every((c) => c[1] === cards[0]![1]);
  const counts = new Map<number, number>();
  for (const v of vals) counts.set(v, (counts.get(v) ?? 0) + 1);
  // Groups sorted by size, then rank: e.g. full house K K K 7 7 → [[3,13],[2,7]]
  const groups = [...counts.entries()].map(([v, n]) => [n, v] as [number, number]).sort((a, b) => b[0] - a[0] || b[1] - a[1]);
  const distinct = [...new Set(vals)];
  let straightHigh = 0;
  if (distinct.length === 5) {
    if (vals[0]! - vals[4]! === 4) straightHigh = vals[0]!;
    // The wheel: A-2-3-4-5 is a five-high straight.
    else if (vals[0] === 14 && vals[1] === 5 && vals[4] === 2) straightHigh = 5;
  }
  const order = (hv: number[]) => {
    // Cards in display order: by group, then rank (the wheel's ace goes last).
    const rest = cards.slice();
    const out: string[] = [];
    for (const v of hv) {
      const want = v === 1 ? 14 : v;
      const i = rest.findIndex((c) => rankValue(c) === want);
      if (i >= 0) out.push(...rest.splice(i, 1));
    }
    return out.concat(rest);
  };
  const R = (v: number) => RANK_NAME[valueName(v)]!;
  const P = (v: number) => plural(valueName(v));
  if (straightHigh && flush) {
    const seq = straightHigh === 5 ? [5, 4, 3, 2, 1] : [0, 1, 2, 3, 4].map((i) => straightHigh - i);
    return { score: [8, straightHigh], category: 'Straight flush', name: straightHigh === 14 ? 'Royal flush' : `Straight flush, ${R(straightHigh)} high`, cards: order(seq) };
  }
  if (groups[0]![0] === 4) {
    const quad = groups[0]![1];
    const kick = groups[1]![1];
    return { score: [7, quad, kick], category: 'Four of a kind', name: `Four of a kind, ${P(quad)}`, cards: order([quad, quad, quad, quad, kick]) };
  }
  if (groups[0]![0] === 3 && groups[1]![0] === 2) {
    const t = groups[0]![1];
    const p = groups[1]![1];
    return { score: [6, t, p], category: 'Full house', name: `Full house, ${P(t)} full of ${P(p)}`, cards: order([t, t, t, p, p]) };
  }
  if (flush) return { score: [5, ...vals], category: 'Flush', name: `Flush, ${R(vals[0]!)} high`, cards: order(vals) };
  if (straightHigh) {
    const seq = straightHigh === 5 ? [5, 4, 3, 2, 1] : [0, 1, 2, 3, 4].map((i) => straightHigh - i);
    return { score: [4, straightHigh], category: 'Straight', name: `Straight, ${R(straightHigh)} high`, cards: order(seq) };
  }
  if (groups[0]![0] === 3) {
    const t = groups[0]![1];
    const kick = groups.slice(1).map((g) => g[1]);
    return { score: [3, t, ...kick], category: 'Three of a kind', name: `Three of a kind, ${P(t)}`, cards: order([t, t, t, ...kick]) };
  }
  if (groups[0]![0] === 2 && groups[1]![0] === 2) {
    const hi = groups[0]![1];
    const lo = groups[1]![1];
    const kick = groups[2]![1];
    return { score: [2, hi, lo, kick], category: 'Two pair', name: `Two pair, ${P(hi)} and ${P(lo)}`, cards: order([hi, hi, lo, lo, kick]) };
  }
  if (groups[0]![0] === 2) {
    const p = groups[0]![1];
    const kick = groups.slice(1).map((g) => g[1]);
    return { score: [1, p, ...kick], category: 'Pair', name: `Pair of ${P(p)}`, cards: order([p, p, ...kick]) };
  }
  return { score: [0, ...vals], category: 'High card', name: `${R(vals[0]!)} high`, cards: order(vals) };
}

/** Negative when a < b, positive when a > b, 0 for a tie. */
export function compareScores(a: number[], b: number[]): number {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

/** Best five-card hand out of five to seven cards. */
export function bestHand(cards: string[]): HandValue {
  if (cards.length < 5 || cards.length > 7) throw new Error('bestHand needs 5 to 7 cards');
  if (new Set(cards).size !== cards.length) throw new Error(`Duplicate card in ${cards.join(' ')}`);
  let best: HandValue | null = null;
  const n = cards.length;
  for (let a = 0; a < n; a++)
    for (let b = a + 1; b < n; b++)
      for (let c = b + 1; c < n; c++)
        for (let d = c + 1; d < n; d++)
          for (let e = d + 1; e < n; e++) {
            const v = evaluate5([cards[a]!, cards[b]!, cards[c]!, cards[d]!, cards[e]!]);
            if (!best || compareScores(v.score, best.score) > 0) best = v;
          }
  return best!;
}

/** Showdown between two hole-card pairs on a five-card board: 0, 1 or null (split pot). */
export function showdown(hole: [string[], string[]], board: string[]): { winner: 0 | 1 | null; hands: [HandValue, HandValue] } {
  const h0 = bestHand([...hole[0], ...board]);
  const h1 = bestHand([...hole[1], ...board]);
  const c = compareScores(h0.score, h1.score);
  return { winner: c > 0 ? 0 : c < 0 ? 1 : null, hands: [h0, h1] };
}
