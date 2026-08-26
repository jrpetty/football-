// ---------------------------------------------------------------------------
// Matching a name someone wrote down to a name the till prints.
//
// Two photographs feed this: the price board on the wall, and the paper rota in
// the office. Neither is written the way the till writes things. The board says
// "Taddy Lager 4.00"; the till says "PINT TADDY LAGER". The rota says "Kel";
// the app knows "Kelly".
//
// The rule this file exists to enforce is that a wrong match is far worse than
// no match. An unmatched line costs one tap to place by hand. A wrong one puts
// the price of a pint against a half and then quietly reports a loss every
// night for a month. So:
//
//   - scoring is by whole tokens, not by substring, because "GIN" inside
//     "GINGER BEER" is exactly the trap this is here to avoid;
//   - a near-tie between two candidates is reported as ambiguous rather than
//     resolved — "Taddy Lager" genuinely could be the pint or the half, and
//     the honest answer is to ask;
//   - anything below the floor is left unmatched, not forced to its best guess.
// ---------------------------------------------------------------------------

/** Below this, the best candidate is not good enough to offer at all. */
const FLOOR = 0.5

/** Two candidates within this of each other are a coin toss, so neither wins. */
const TIE = 0.12

export type MatchOutcome<T> =
  | { kind: 'matched'; value: T; score: number }
  | { kind: 'ambiguous'; between: T[]; score: number }
  | { kind: 'unmatched' }

/** Upper case, no punctuation, single spaces — the shape both sides compare in. */
export function normalise(text: string): string {
  return text
    .toUpperCase()
    .replace(/[^A-Z0-9 ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function tokens(text: string): string[] {
  return normalise(text).split(' ').filter(Boolean)
}

/**
 * How well a written name matches a printed one, from 0 to 1.
 *
 * Asymmetric on purpose. What matters is how much of the *written* name is
 * accounted for by the printed one — "Taddy Lager" is fully accounted for by
 * "PINT TADDY LAGER" — with a smaller credit for the reverse, so that between
 * two candidates that both contain the written name, the tighter one wins.
 */
export function score(written: string, printed: string): number {
  const a = tokens(written)
  const b = tokens(printed)
  if (a.length === 0 || b.length === 0) return 0

  const bSet = new Set(b)
  const aSet = new Set(a)

  // A token counts if it appears whole on the other side, or is a prefix of one
  // at least four characters long — "LAG" for "LAGER", but never "GIN" for
  // "GINGER", which is a prefix but not of a long enough stem to be safe.
  const covers = (token: string, other: Set<string>): boolean => {
    if (other.has(token)) return true
    if (token.length < 3) return false
    for (const o of other) {
      if (o.length >= 4 && o.startsWith(token) && token.length >= o.length - 2) return true
    }
    return false
  }

  const forward = a.filter((t) => covers(t, bSet)).length / a.length
  const back = b.filter((t) => covers(t, aSet)).length / b.length
  return forward * 0.75 + back * 0.25
}

/**
 * The best candidate for a written name, or an honest refusal.
 *
 * `label` pulls the comparable text out of a candidate, so the same routine
 * serves items with a code and a name, and people with only a name.
 */
export function bestMatch<T>(written: string, candidates: readonly T[], label: (c: T) => string): MatchOutcome<T> {
  if (candidates.length === 0) return { kind: 'unmatched' }

  const scored = candidates
    .map((value) => ({ value, s: score(written, label(value)) }))
    .sort((x, y) => y.s - x.s)

  const top = scored[0]!
  if (top.s < FLOOR) return { kind: 'unmatched' }

  const tied = scored.filter((c) => top.s - c.s <= TIE)
  if (tied.length > 1) return { kind: 'ambiguous', between: tied.map((c) => c.value), score: top.s }

  return { kind: 'matched', value: top.value, score: top.s }
}
