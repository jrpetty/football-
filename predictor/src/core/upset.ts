// ---------------------------------------------------------------------------
// Upset detection.
//
// "Could there be an upset?" deserves a better answer than a low probability
// on the underdog. Two matches can both have a 22% underdog and be completely
// different propositions: one where the favourite is rested, full-strength and
// creating more than they finish, and one where the favourite is missing two
// starters, running above expectation and playing their third match in eight
// days. The second is where upsets actually come from.
//
// So this reports the honest probability *and* names the specific mechanisms
// working against the favourite, each tied to a number. The probability is
// never inflated to make a match sound exciting — the vulnerability score is
// kept separate precisely so the forecast stays calibrated.
// ---------------------------------------------------------------------------

import { clamp } from './math.ts'
import type { FixturePrediction } from './predict.ts'
import type { TeamForm } from './evidence.ts'
import type { TeamAvailability } from './availability.ts'
import type { RestProfile } from './congestion.ts'

export interface UpsetMechanism {
  key: string
  label: string
  detail: string
  /** 0-1 contribution to the vulnerability score. */
  strength: number
}

export interface UpsetAnalysis {
  /** The side the model favours. */
  favourite: string
  underdog: string
  favouriteSide: 'home' | 'away'
  /** Probability the underdog wins outright. */
  upsetProbability: number
  /** Probability the underdog avoids defeat. */
  notLoseProbability: number
  /** How exposed the favourite looks, 0-1. Not a probability. */
  vulnerability: number
  /** Banding for the UI. */
  rating: 'toss-up' | 'unlikely' | 'live' | 'dangerous'
  /** Gap between the two win probabilities — how clear the favourite is. */
  margin: number
  mechanisms: UpsetMechanism[]
  /** The most likely scoreline in which the underdog wins. */
  upsetScoreline: { home: number; away: number; prob: number }
  /** One-sentence summary of the route to an upset. */
  summary: string
}

export interface UpsetContext {
  prediction: FixturePrediction
  homeName: string
  awayName: string
  homeAvailability: TeamAvailability
  awayAvailability: TeamAvailability
  homeRest: RestProfile
  awayRest: RestProfile
  homeForm: TeamForm | null
  awayForm: TeamForm | null
  homePromoted: boolean
  awayPromoted: boolean
}

export function analyseUpset(ctx: UpsetContext): UpsetAnalysis {
  const p = ctx.prediction
  const homeIsFavourite = p.probs.home >= p.probs.away
  const favouriteSide: 'home' | 'away' = homeIsFavourite ? 'home' : 'away'
  const favourite = homeIsFavourite ? ctx.homeName : ctx.awayName
  const underdog = homeIsFavourite ? ctx.awayName : ctx.homeName

  const upsetProbability = homeIsFavourite ? p.probs.away : p.probs.home
  const notLoseProbability = upsetProbability + p.probs.draw

  const favAvailability = homeIsFavourite ? ctx.homeAvailability : ctx.awayAvailability
  const dogAvailability = homeIsFavourite ? ctx.awayAvailability : ctx.homeAvailability
  const favRest = homeIsFavourite ? ctx.homeRest : ctx.awayRest
  const dogRest = homeIsFavourite ? ctx.awayRest : ctx.homeRest
  const favForm = homeIsFavourite ? ctx.homeForm : ctx.awayForm
  const dogForm = homeIsFavourite ? ctx.awayForm : ctx.homeForm

  const mechanisms: UpsetMechanism[] = []

  // 1. The favourite is missing people who matter.
  if (!favAvailability.unknown && favAvailability.attackMultiplier < 0.97) {
    const lost = (1 - favAvailability.attackMultiplier) * 100
    const names = favAvailability.missing.slice(0, 2).map((m) => m.name).join(' and ')
    mechanisms.push({
      key: 'favourite-absences',
      label: 'Favourite weakened by absences',
      detail:
        `${favourite} are without ${names || 'key players'}, cutting their expected attacking output by ` +
        `${lost.toFixed(0)}%. Their margin for error shrinks with it.`,
      strength: clamp(lost / 20, 0, 1),
    })
  }

  // 2. The favourite has been scoring more than their chances warrant.
  if (favForm && favForm.matches >= 5) {
    const over = favForm.goalsFor / favForm.matches - favForm.xgFor / favForm.matches
    if (over >= 0.25) {
      mechanisms.push({
        key: 'favourite-overperforming',
        label: 'Favourite finishing above expectation',
        detail:
          `${favourite} are scoring ${over.toFixed(2)} goals per game more than their chance quality supports. ` +
          `Finishing runs like that regress, and the correction often lands in a match like this.`,
        strength: clamp(over / 0.6, 0, 1),
      })
    }
  }

  // 3. The underdog has been creating more than the scoreline shows.
  if (dogForm && dogForm.matches >= 5) {
    const under = dogForm.xgFor / dogForm.matches - dogForm.goalsFor / dogForm.matches
    if (under >= 0.2) {
      mechanisms.push({
        key: 'underdog-underperforming',
        label: 'Underdog due a bounce in front of goal',
        detail:
          `${underdog} are creating ${under.toFixed(2)} expected goals per game more than they are scoring. ` +
          `The chances are being made; the finishing has lagged.`,
        strength: clamp(under / 0.5, 0, 1),
      })
    }
  }

  // 4. The favourite is short of rest.
  if (favRest.daysRest !== null && dogRest.daysRest !== null) {
    const gap = dogRest.daysRest - favRest.daysRest
    if (gap >= 2 && favRest.daysRest <= 4.5) {
      mechanisms.push({
        key: 'favourite-tired',
        label: 'Favourite on a short turnaround',
        detail:
          `${favourite} have had ${favRest.daysRest.toFixed(0)} days since their last match against ` +
          `${dogRest.daysRest.toFixed(0)} for ${underdog}${favRest.matchesIn14Days >= 3 ? `, and this is their ${favRest.matchesIn14Days + 1}th match in a fortnight` : ''}.`,
        strength: clamp(gap / 5, 0, 1),
      })
    }
  }

  // 5. The underdog is hard to beat where this match is being played.
  if (dogForm && dogForm.venuePpg !== null && dogForm.venueMatches >= 5 && dogForm.venuePpg >= 1.5) {
    mechanisms.push({
      key: 'underdog-venue',
      label: `Underdog strong ${homeIsFavourite ? 'on the road' : 'at home'}`,
      detail:
        `${underdog} have taken ${dogForm.venuePpg.toFixed(2)} points per game from ` +
        `${dogForm.venueMatches} matches ${homeIsFavourite ? 'away' : 'at home'} — better than their overall record suggests.`,
      strength: clamp((dogForm.venuePpg - 1.2) / 1.0, 0, 1),
    })
  }

  // 6. The underdog missing players cuts the other way.
  if (!dogAvailability.unknown && dogAvailability.attackMultiplier < 0.95) {
    mechanisms.push({
      key: 'underdog-absences',
      label: 'Underdog also depleted',
      detail:
        `${underdog} are missing ${dogAvailability.missing.length} player${dogAvailability.missing.length === 1 ? '' : 's'} of their own, ` +
        `which works against the upset.`,
      strength: -clamp((1 - dogAvailability.attackMultiplier) / 0.2, 0, 0.6),
    })
  }

  // 7. Genuine uncertainty in the ratings themselves.
  if (ctx.homePromoted || ctx.awayPromoted) {
    mechanisms.push({
      key: 'rating-uncertainty',
      label: 'Ratings still uncertain',
      detail:
        `A newly promoted side is involved, so the ratings carry real uncertainty and the range of ` +
        `plausible scorelines is wider than usual.`,
      strength: 0.35,
    })
  }

  // 8. It is simply a close match.
  if (Math.abs(p.probs.home - p.probs.away) < 0.12) {
    mechanisms.push({
      key: 'tight-margin',
      label: 'Barely separable',
      detail:
        `The model splits these sides by under two percentage points on the win probability — ` +
        `"upset" is arguably the wrong word for this one.`,
      strength: 0.5,
    })
  }

  const positive = mechanisms.filter((m) => m.strength > 0)
  const negative = mechanisms.filter((m) => m.strength < 0)
  const rawVulnerability =
    positive.reduce((s, m) => s + m.strength, 0) / Math.max(2.5, positive.length + 1.5) +
    negative.reduce((s, m) => s + m.strength, 0) * 0.15
  const vulnerability = clamp(rawVulnerability, 0, 1)

  mechanisms.sort((a, b) => Math.abs(b.strength) - Math.abs(a.strength))

  // Most likely scoreline in which the underdog actually wins.
  const upsetScoreline = homeIsFavourite ? p.modalScore.away : p.modalScore.home

  // "Upset" only means something when there is a favourite to upset. If the two
  // win probabilities sit within a few points of each other there is no
  // underdog, just a close match — flagging that as upset risk would fire on
  // most of the card and make the badge worthless.
  const margin = Math.abs(p.probs.home - p.probs.away)
  const CLEAR_FAVOURITE = 0.15

  const rating: UpsetAnalysis['rating'] =
    margin < CLEAR_FAVOURITE
      ? 'toss-up'
      : upsetProbability >= 0.3
        ? 'dangerous'
        : upsetProbability >= 0.22
          ? 'live'
          : 'unlikely'

  const lead = positive[0]
  const summary =
    rating === 'toss-up'
      ? `There is no real favourite here — ${(p.probs.home * 100).toFixed(0)}% plays ` +
        `${(p.probs.away * 100).toFixed(0)}%, close enough that neither result would be a surprise.` +
        (lead ? ` ${lead.detail}` : '')
      : rating === 'unlikely'
        ? `${favourite} are a clear favourite and little undermines them; an upset would be a genuine surprise.`
        : lead
          ? `${underdog} win ${(upsetProbability * 100).toFixed(0)}% of the time here, and ${lead.label.toLowerCase()} is the clearest route: ` +
            `${upsetScoreline.home}-${upsetScoreline.away} is the most likely shape of it.`
          : `${underdog} win ${(upsetProbability * 100).toFixed(0)}% of the time, most often ${upsetScoreline.home}-${upsetScoreline.away}.`

  return {
    favourite,
    underdog,
    favouriteSide,
    upsetProbability,
    notLoseProbability,
    vulnerability,
    rating,
    mechanisms,
    upsetScoreline,
    summary,
    margin,
  }
}
