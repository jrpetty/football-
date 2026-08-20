// ---------------------------------------------------------------------------
// Yellow-card accumulation and suspension risk.
//
// Premier League rules (Football Association disciplinary regulations):
//   5 yellows  before the club's 19th league match -> 1-match ban
//   10 yellows before the club's 32nd league match -> 2-match ban
//   15 yellows at any point in the season          -> 3-match ban
//   20 yellows                                     -> 4 matches and a charge
//
// The counter does not reset after a ban is served: a player who is banned at
// five bookings is next at risk at ten, not at ten more. Getting that wrong
// would make the tracker quietly useless from midseason onward, which is
// exactly when it matters.
//
// Deadlines are in *club matches played*, not calendar dates, so a side with
// games in hand has a different deadline from one that has played more.
// ---------------------------------------------------------------------------

export interface CardThreshold {
  yellows: number
  /** Ban applies only if reached at or before this club-match number. */
  byClubMatch: number | null
  banMatches: number
}

export const CARD_THRESHOLDS: CardThreshold[] = [
  { yellows: 5, byClubMatch: 19, banMatches: 1 },
  { yellows: 10, byClubMatch: 32, banMatches: 2 },
  { yellows: 15, byClubMatch: null, banMatches: 3 },
  { yellows: 20, byClubMatch: null, banMatches: 4 },
]

export interface CardStatus {
  playerId: number
  name: string
  team: string
  yellows: number
  reds: number
  /** Club league matches played so far this season. */
  clubMatchesPlayed: number
  /** The next threshold that can still bite, or null when none can. */
  nextThreshold: CardThreshold | null
  /** Bookings still needed to reach it. */
  yellowsToBan: number | null
  /** True when one more booking triggers a ban. */
  onBrink: boolean
  /** Probability of picking up a booking in the next match. */
  bookingProbability: number
  /** Probability of *triggering a ban* in the next match. */
  banProbability: number
}

/**
 * The next threshold a player can still hit.
 *
 * A threshold whose club-match deadline has already passed is dead for the
 * season and skipped — a player on 4 bookings after their club's 19th match
 * cannot be banned for reaching 5.
 */
export function nextThreshold(yellows: number, clubMatchesPlayed: number): CardThreshold | null {
  for (const t of CARD_THRESHOLDS) {
    if (yellows >= t.yellows) continue
    if (t.byClubMatch !== null && clubMatchesPlayed >= t.byClubMatch) continue
    return t
  }
  return null
}

/**
 * Probability of at least one booking in a match, from a per-90 rate.
 *
 * Bookings are close to Poisson in match time, so P(>=1) = 1 - exp(-rate).
 * Scaled by expected minutes, because a substitute has less time to be booked.
 */
export function bookingProbability(yellowPer90: number, expectedMinutes: number): number {
  const rate = Math.max(0, yellowPer90) * (Math.max(0, expectedMinutes) / 90)
  return 1 - Math.exp(-rate)
}

/** Build a player's card status. */
export function cardStatus(input: {
  playerId: number
  name: string
  team: string
  yellows: number
  reds: number
  clubMatchesPlayed: number
  yellowPer90: number
  expectedMinutes: number
}): CardStatus {
  const t = nextThreshold(input.yellows, input.clubMatchesPlayed)
  const yellowsToBan = t ? t.yellows - input.yellows : null
  const onBrink = yellowsToBan === 1
  const p = bookingProbability(input.yellowPer90, input.expectedMinutes)
  return {
    playerId: input.playerId,
    name: input.name,
    team: input.team,
    yellows: input.yellows,
    reds: input.reds,
    clubMatchesPlayed: input.clubMatchesPlayed,
    nextThreshold: t,
    yellowsToBan,
    onBrink,
    bookingProbability: p,
    // Only a booking that reaches the threshold causes a ban, so this is the
    // booking probability itself when one away, and negligible otherwise.
    banProbability: onBrink ? p : 0,
  }
}

/**
 * Ban length for a red card.
 *
 * The data feed records that a red card happened but not the offence, so the
 * length cannot be known from it. One match is the floor (second yellow, or
 * denying a goalscoring opportunity); violent conduct is three. Assuming the
 * floor keeps the model honest rather than optimistic, and the UI states the
 * assumption rather than hiding it.
 */
export const RED_CARD_ASSUMED_BAN = 1

export interface RedCardBan {
  matches: number
  assumed: boolean
  note: string
}

export function redCardBan(offence?: 'second-yellow' | 'dogso' | 'violent-conduct' | 'foul-language'): RedCardBan {
  switch (offence) {
    case 'violent-conduct':
      return { matches: 3, assumed: false, note: 'Violent conduct — three-match ban' }
    case 'foul-language':
      return { matches: 2, assumed: false, note: 'Offensive language — two-match ban' }
    case 'dogso':
      return { matches: 1, assumed: false, note: 'Denying a goalscoring opportunity — one match' }
    case 'second-yellow':
      return { matches: 1, assumed: false, note: 'Two yellow cards — one match' }
    default:
      return {
        matches: RED_CARD_ASSUMED_BAN,
        assumed: true,
        note: 'Offence not recorded in the data feed; assuming the one-match minimum',
      }
  }
}
