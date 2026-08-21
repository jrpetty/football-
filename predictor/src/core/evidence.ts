// ---------------------------------------------------------------------------
// Turning the model's arithmetic into readable reasons.
//
// Each bullet is derived from the lambda term ledger or from measured form,
// never written to fit a conclusion. Weights are expressed in *goals* rather
// than log units, because "this absence costs Arsenal 0.18 goals" is something
// a reader can actually judge, and it is the same number the model used.
//
// This layer is deliberately independent of any language model: it needs no
// API key, costs nothing, is reproducible, and can be audited against the
// prediction. The optional Claude narrative sits on top of these bullets
// rather than replacing them.
// ---------------------------------------------------------------------------

import { clamp } from './math.ts'
import type { FixturePrediction } from './predict.ts'
import type { TeamAvailability } from './availability.ts'
import type { RestProfile } from './congestion.ts'

export type Side = 'home' | 'away' | 'draw' | 'neutral'

export type EvidenceCategory =
  | 'strength'
  | 'availability'
  | 'form'
  | 'rest'
  | 'venue'
  | 'discipline'
  | 'uncertainty'

export interface Evidence {
  key: string
  headline: string
  detail: string
  favours: Side
  /** Impact in goals; used for ranking and for the magnitude label. */
  weight: number
  magnitude: 'major' | 'notable' | 'minor'
  category: EvidenceCategory
}

/** Recent form for one team, measured over a trailing window. */
export interface TeamForm {
  team: string
  matches: number
  goalsFor: number
  goalsAgainst: number
  xgFor: number
  xgAgainst: number
  points: number
  /** Most recent results, newest first: 'W' | 'D' | 'L'. */
  recent: string[]
  /** Home-only or away-only points per game, whichever applies to this fixture. */
  venuePpg: number | null
  venueMatches: number
}

export interface EvidenceContext {
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

function magnitudeOf(goals: number): Evidence['magnitude'] {
  const g = Math.abs(goals)
  return g >= 0.25 ? 'major' : g >= 0.1 ? 'notable' : 'minor'
}

/**
 * Convert a log-lambda term into a goals impact.
 *
 * A term shifts lambda multiplicatively, so its effect in goals depends on the
 * lambda it acts on: the same log shift is worth more to a side already
 * expected to score twice than to one expected to score half a goal.
 */
function goalsImpact(lambda: number, logTerm: number): number {
  return lambda - lambda * Math.exp(-logTerm)
}

/** Build the ranked evidence for a fixture. */
export function buildEvidence(ctx: EvidenceContext): Evidence[] {
  const { prediction: p, homeName, awayName } = ctx
  const out: Evidence[] = []

  const termsByKey = new Map(p.terms.map((t) => [t.key, t]))

  // --- Core strength -------------------------------------------------------
  const attack = termsByKey.get('attack')
  const defence = termsByKey.get('defence')
  if (attack && defence) {
    const homeEdge = attack.home + defence.home
    const awayEdge = attack.away + defence.away
    const gap = homeEdge - awayEdge
    const goals = Math.abs(p.lambdaHome - p.lambdaAway)
    const favours: Side = gap > 0 ? 'home' : 'away'
    const stronger = gap > 0 ? homeName : awayName
    const weaker = gap > 0 ? awayName : homeName
    out.push({
      key: 'rating-gap',
      headline: `${stronger} are the stronger side on ratings`,
      detail:
        `Attack and defence ratings put ${stronger} ${Math.abs(gap).toFixed(2)} ahead of ${weaker} on the log scale, ` +
        `which becomes an expected ${p.lambdaHome.toFixed(2)}-${p.lambdaAway.toFixed(2)} scoreline.`,
      favours,
      weight: goals,
      magnitude: magnitudeOf(goals),
      category: 'strength',
    })
  }

  // --- Home advantage ------------------------------------------------------
  const homeAdv = termsByKey.get('home-advantage')
  if (homeAdv && homeAdv.home > 0.01) {
    const goals = goalsImpact(p.lambdaHome, homeAdv.home)
    out.push({
      key: 'home-advantage',
      headline: `Home advantage at ${homeName}`,
      detail:
        `Playing at home is worth about ${Math.round((Math.exp(homeAdv.home) - 1) * 100)}% to ${homeName}'s scoring rate ` +
        `(roughly ${goals.toFixed(2)} goals), fitted across the whole league rather than assumed.`,
      favours: 'home',
      weight: goals,
      magnitude: magnitudeOf(goals),
      category: 'venue',
    })
  }

  // --- This ground specifically --------------------------------------------
  //
  // Distinct from the bullet above: that one is the league-wide effect of
  // playing at home, this one is how far this particular ground departs from
  // it. Both are fitted, and both are controlled for opponent — unlike the raw
  // venue record further down, which is the club's results as they came.
  const venueTerm = termsByKey.get('venue-record')
  if (venueTerm && Math.abs(venueTerm.home) + Math.abs(venueTerm.away) > 0.02) {
    const scoring = venueTerm.home
    const suppression = -venueTerm.away
    const total = scoring + suppression
    const fortress = total > 0
    const goals = Math.abs(goalsImpact(p.lambdaHome, scoring)) + Math.abs(goalsImpact(p.lambdaAway, -suppression))
    const parts: string[] = []
    if (Math.abs(scoring) > 0.01) {
      parts.push(`score ${Math.abs(Math.round((Math.exp(scoring) - 1) * 100))}% ${scoring > 0 ? 'more' : 'less'}`)
    }
    if (Math.abs(suppression) > 0.01) {
      parts.push(
        `concede ${Math.abs(Math.round((Math.exp(-suppression) - 1) * 100))}% ${suppression > 0 ? 'fewer' : 'more'}`,
      )
    }
    out.push({
      key: 'venue-effect',
      headline: `${homeName}'s home record is ${fortress ? 'better' : 'worse'} than their rating alone implies`,
      detail:
        `Over and above the league-wide home advantage, ${homeName} ${parts.join(' and ')} at this ground ` +
        `once the strength of their visitors is divided out — worth about ${goals.toFixed(2)} goals here. ` +
        `Fitted from their own home matches and shrunk toward the league figure, because nineteen home ` +
        `games a season is a small sample.`,
      favours: fortress ? 'home' : 'away',
      weight: goals,
      magnitude: magnitudeOf(goals),
      category: 'venue',
    })
  }

  // --- Availability --------------------------------------------------------
  const describeAbsences = (
    avail: TeamAvailability,
    teamName: string,
    side: 'home' | 'away',
  ): void => {
    if (avail.unknown || avail.missing.length === 0) return
    const named = avail.missing.slice(0, 3)
    const attackTerm = termsByKey.get('availability-attack')
    const logShift = attackTerm ? (side === 'home' ? attackTerm.home : attackTerm.away) : 0
    const lambda = side === 'home' ? p.lambdaHome : p.lambdaAway
    const goals = Math.abs(goalsImpact(lambda, logShift))
    const list = named
      .map((m) => `${m.name}${m.reason === 'available' ? '' : ` (${m.reason})`}`)
      .join(', ')
    const more = avail.missing.length > named.length ? ` and ${avail.missing.length - named.length} more` : ''
    out.push({
      key: `absences-${side}`,
      headline: `${teamName} are missing ${avail.missing.length} player${avail.missing.length === 1 ? '' : 's'}`,
      detail:
        `Out: ${list}${more}. Weighted by how much of ${teamName}'s expected goal involvement they carry, ` +
        `this reduces their attacking output by about ${(100 * (1 - avail.attackMultiplier)).toFixed(0)}% ` +
        `(${goals.toFixed(2)} goals).`,
      favours: side === 'home' ? 'away' : 'home',
      weight: goals,
      magnitude: magnitudeOf(goals),
      category: 'availability',
    })
  }
  describeAbsences(ctx.homeAvailability, homeName, 'home')
  describeAbsences(ctx.awayAvailability, awayName, 'away')

  // --- Rest ---------------------------------------------------------------
  const restTerm = termsByKey.get('rest')
  if (restTerm) {
    const homeRest = ctx.homeRest
    const awayRest = ctx.awayRest
    const diff = (homeRest.daysRest ?? 7) - (awayRest.daysRest ?? 7)
    if (Math.abs(diff) >= 1.5) {
      const favours: Side = diff > 0 ? 'home' : 'away'
      const fresher = diff > 0 ? homeName : awayName
      const tireder = diff > 0 ? awayName : homeName
      const goals = Math.abs(
        goalsImpact(diff > 0 ? p.lambdaAway : p.lambdaHome, diff > 0 ? restTerm.away : restTerm.home),
      )
      out.push({
        key: 'rest-gap',
        headline: `${fresher} are the fresher side`,
        detail:
          `${fresher} have had ${(diff > 0 ? homeRest.daysRest : awayRest.daysRest)?.toFixed(0) ?? '?'} days since their last match, ` +
          `against ${(diff > 0 ? awayRest.daysRest : homeRest.daysRest)?.toFixed(0) ?? '?'} for ${tireder}.`,
        favours,
        weight: Math.max(goals, 0.02),
        magnitude: magnitudeOf(goals),
        category: 'rest',
      })
    }
  }

  // --- Form and regression -------------------------------------------------
  const describeForm = (form: TeamForm | null, teamName: string, side: 'home' | 'away'): void => {
    if (!form || form.matches < 4) return

    // Goals running well ahead of or behind xG tends to revert; that is one of
    // the more reliable signals available and a common source of upsets.
    const gfPerGame = form.goalsFor / form.matches
    const xgPerGame = form.xgFor / form.matches
    const overPerformance = gfPerGame - xgPerGame
    if (Math.abs(overPerformance) >= 0.25) {
      const overShooting = overPerformance > 0
      out.push({
        key: `xg-regression-${side}`,
        headline: overShooting
          ? `${teamName} have been finishing above expectation`
          : `${teamName} have been finishing below expectation`,
        detail:
          `Over their last ${form.matches} matches ${teamName} have scored ${gfPerGame.toFixed(2)} per game from ` +
          `${xgPerGame.toFixed(2)} expected — ${Math.abs(overPerformance).toFixed(2)} ${overShooting ? 'above' : 'below'} ` +
          `chance quality. That gap usually narrows, which points ${overShooting ? 'down' : 'up'} for them.`,
        // Overperformance is a warning sign, so it favours the opponent.
        favours: overShooting ? (side === 'home' ? 'away' : 'home') : side,
        weight: Math.abs(overPerformance) * 0.4,
        magnitude: magnitudeOf(Math.abs(overPerformance) * 0.4),
        category: 'form',
      })
    }

    // Defensive form against expectation.
    const gaPerGame = form.goalsAgainst / form.matches
    const xgaPerGame = form.xgAgainst / form.matches
    if (Math.abs(gaPerGame - xgaPerGame) >= 0.3) {
      const lucky = gaPerGame < xgaPerGame
      out.push({
        key: `xga-regression-${side}`,
        headline: lucky
          ? `${teamName} have conceded fewer than the chances suggest`
          : `${teamName} have been punished more than the chances suggest`,
        detail:
          `${teamName} have conceded ${gaPerGame.toFixed(2)} per game from ${xgaPerGame.toFixed(2)} expected against. ` +
          `${lucky ? 'That is flattering their defence and tends to correct.' : 'That is harsher than their defending deserved and tends to correct.'}`,
        favours: lucky ? (side === 'home' ? 'away' : 'home') : side,
        weight: Math.abs(gaPerGame - xgaPerGame) * 0.3,
        magnitude: magnitudeOf(Math.abs(gaPerGame - xgaPerGame) * 0.3),
        category: 'form',
      })
    }

    // Straight recent results.
    if (form.recent.length >= 4) {
      const wins = form.recent.filter((r) => r === 'W').length
      const losses = form.recent.filter((r) => r === 'L').length
      if (wins >= 3 || losses >= 3) {
        const good = wins > losses
        out.push({
          key: `form-run-${side}`,
          headline: `${teamName} are ${good ? 'in good form' : 'struggling for results'}`,
          detail:
            `Last ${form.recent.length}: ${form.recent.join(' ')} — ` +
            `${form.points} points from ${form.matches} (${(form.points / form.matches).toFixed(2)} per game).`,
          favours: good ? side : side === 'home' ? 'away' : 'home',
          weight: 0.06,
          magnitude: 'minor',
          category: 'form',
        })
      }
    }

    // Venue-specific record, when there is enough of it to mean something.
    if (form.venuePpg !== null && form.venueMatches >= 5) {
      const strong = form.venuePpg >= 1.8
      const weak = form.venuePpg <= 0.9
      if (strong || weak) {
        out.push({
          key: `venue-record-${side}`,
          headline: `${teamName} are ${strong ? 'strong' : 'poor'} ${side === 'home' ? 'at home' : 'on the road'}`,
          detail:
            `${form.venuePpg.toFixed(2)} points per game across ${form.venueMatches} ` +
            `${side === 'home' ? 'home' : 'away'} matches. This is the raw record, not adjusted for who ` +
            `they played; the model's own venue effect is the adjusted version.`,
          favours: strong ? side : side === 'home' ? 'away' : 'home',
          weight: 0.05,
          magnitude: 'minor',
          category: 'venue',
        })
      }
    }
  }
  describeForm(ctx.homeForm, homeName, 'home')
  describeForm(ctx.awayForm, awayName, 'away')

  // --- Uncertainty ---------------------------------------------------------
  if (ctx.homePromoted || ctx.awayPromoted) {
    const who = ctx.homePromoted && ctx.awayPromoted ? 'Both sides' : ctx.homePromoted ? homeName : awayName
    out.push({
      key: 'promoted-uncertainty',
      headline: `${who} newly promoted — wider range of outcomes`,
      detail:
        `Promoted clubs average 0.71x the league's attacking output and concede 1.23x as much, but the spread is huge: ` +
        `of 33 promoted seasons measured here, 19 finished under a point per game and 5 cleared 1.30. ` +
        `The model widens its scoreline distribution to match, so the result is genuinely less predictable.`,
      favours: 'neutral',
      weight: 0.08,
      magnitude: 'notable',
      category: 'uncertainty',
    })
  }

  // Rank by impact, keeping the strongest signal first.
  out.sort((a, b) => b.weight - a.weight)
  return out
}

/** Evidence pointing toward a given side. */
export function evidenceFor(evidence: readonly Evidence[], side: Side): Evidence[] {
  return evidence.filter((e) => e.favours === side)
}

/** A one-line summary of where the weight of evidence sits. */
export function evidenceBalance(evidence: readonly Evidence[]): { home: number; away: number } {
  let home = 0
  let away = 0
  for (const e of evidence) {
    if (e.favours === 'home') home += e.weight
    else if (e.favours === 'away') away += e.weight
  }
  const total = home + away
  return total > 0
    ? { home: clamp(home / total, 0, 1), away: clamp(away / total, 0, 1) }
    : { home: 0.5, away: 0.5 }
}
