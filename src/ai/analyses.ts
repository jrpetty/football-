// ---------------------------------------------------------------------------
// Builds the prompt (system + user) and a no-API-key heuristic fallback for
// each kind of analysis, from the app's own structured data.
// ---------------------------------------------------------------------------
import type { AppData } from '../types'
import {
  playerAggregate, playerPercentiles, playerRatingTrend, overallRating,
  teamSeasonStats, formationUsage, leaderboard, headToHead, oppositionMoments,
} from '../analytics/selectors'
import { POSITION_LABEL, resultOf, fmtDate, fmtDateShort } from '../utils/format'
import { positionGroupOf } from '../data/formations'
import type { PlayerMatchStat, PositionGroup } from '../types'

export interface Analysis {
  system: string
  user: string
  /** Offline, rules-based fallback shown when no API key is set. */
  heuristic: string
  /** Optional output-length / reasoning-effort overrides for richer analyses. */
  maxTokens?: number
  effort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max'
}

const ANALYST_SYSTEM =
  'You are an elite football (soccer) performance analyst and coach. Write concise, ' +
  'specific, actionable analysis for a coach. Use short markdown sections with **bold** ' +
  'headers and bullet points. Ground every point in the numbers provided — no generic ' +
  'filler. Be direct and practical. Keep the whole response under ~260 words.'

const MATCH_SYSTEM =
  'You are an elite football (soccer) match analyst and head coach preparing the post-match ' +
  'review for your coaching staff. You are given a complete data pack for one game: team stats ' +
  'versus the opponent, the goal/card timeline, every player’s full stat line, unit-by-unit ' +
  'aggregates, and how the performance compares to the season. Deliver a thorough, genuinely ' +
  'insightful tactical breakdown. Use markdown with **bold** section headers and bullet points. ' +
  'Ground EVERY point in the data — cite specific numbers, players and minutes. Explain WHY, not ' +
  'just what. Be candid about what went well and what went wrong. No generic filler. Aim for 450–650 words.'

const n1 = (v: number) => (Math.round(v * 10) / 10).toFixed(1)
const n2 = (v: number) => (Math.round(v * 100) / 100).toFixed(2)

// ---------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------
export function playerAnalysis(data: AppData, playerId: string): Analysis {
  const player = data.players.find((p) => p.id === playerId)!
  const agg = playerAggregate(data, playerId)
  const pct = playerPercentiles(data, playerId)
  const trend = playerRatingTrend(data, playerId).map((t) => t.rating)
  const a = player.attributes
  const strengths = pct.rows.filter((r) => r.percentile >= 70).sort((x, y) => y.percentile - x.percentile)
  const focus = pct.rows.filter((r) => r.percentile <= 35).sort((x, y) => x.percentile - y.percentile)

  const user =
    `Player: ${player.name}, ${POSITION_LABEL[player.position]}, age ${player.age}, ${player.foot}-footed, overall ${overallRating(player)}.\n` +
    `Attributes (0-99): pace ${a.pace}, shooting ${a.shooting}, passing ${a.passing}, dribbling ${a.dribbling}, defending ${a.defending}, physical ${a.physical}.\n` +
    `Season: ${agg.appearances} apps (${agg.starts} starts), ${agg.minutes} mins, ${agg.goals}G ${agg.assists}A, xG ${n1(agg.xg)}, avg rating ${n1(agg.avgRating)}.\n` +
    `Per90: goals ${n2(agg.goalsPer90)}, assists ${n2(agg.assistsPer90)}. Pass acc ${n1(agg.passAccuracy)}%, duels won ${n1(agg.duelWinRate)}%, tackles ${agg.tackles}, interceptions ${agg.interceptions}, key passes ${agg.keyPasses}.\n` +
    `Percentile vs ${pct.group} peers — strengths: ${strengths.map((s) => `${s.label} ${s.percentile}%`).join(', ') || 'none'}. Weak areas: ${focus.map((s) => `${s.label} ${s.percentile}%`).join(', ') || 'none'}.\n` +
    (trend.length ? `Recent ratings: ${trend.map((t) => n1(t)).join(', ')}.\n` : '') +
    `\nWrite a scouting & development report with sections: Role & profile, Strengths, Areas to improve, How to use them tactically, and one specific Training focus.`

  const heuristic =
    `**Role & profile**\n` +
    `${player.name} is a ${POSITION_LABEL[player.position].toLowerCase()} (overall ${overallRating(player)}), ${agg.appearances} apps at ${n1(agg.avgRating)} avg rating this season with ${agg.goals} goals and ${agg.assists} assists.\n\n` +
    `**Strengths**\n` +
    (strengths.length ? strengths.slice(0, 4).map((s) => `- Elite ${s.label.toLowerCase()} (${s.percentile}th pct, ${s.display}).`).join('\n') : '- Balanced profile with no standout percentile metric.') +
    `\n\n**Areas to improve**\n` +
    (focus.length ? focus.slice(0, 3).map((s) => `- ${s.label} sits in the ${s.percentile}th percentile vs ${pct.group} peers — develop toward the squad average.`).join('\n') : '- No major weaknesses vs peers; push for consistency.') +
    `\n\n**Training focus**\n- Target the lowest-percentile area above with position-specific drills, and review match clips of those moments.`

  return { system: ANALYST_SYSTEM, user, heuristic }
}

// ---------------------------------------------------------------------------
// Match
// ---------------------------------------------------------------------------
export function matchAnalysis(data: AppData, matchId: string): Analysis {
  const m = data.matches.find((x) => x.id === matchId)!
  const players = new Map(data.players.map((p) => [p.id, p]))
  const name = (id?: string) => (id && players.get(id)?.name) || 'Unknown'
  const res = resultOf(m.goalsFor, m.goalsAgainst)
  const resWord = res === 'W' ? 'Win' : res === 'D' ? 'Draw' : 'Loss'

  // Team totals derived from our players' stat lines.
  const ps = m.playerStats
  const sum = (f: (s: PlayerMatchStat) => number) => ps.reduce((a, s) => a + f(s), 0)
  const passes = sum((s) => s.passes)
  const passesC = sum((s) => s.passesCompleted)
  const passAcc = passes ? (passesC / passes) * 100 : 0
  const shotsOnTarget = sum((s) => s.shotsOnTarget)
  const tackles = sum((s) => s.tackles)
  const interceptions = sum((s) => s.interceptions)
  const duelsWon = sum((s) => s.duelsWon)
  const duelsTotal = sum((s) => s.duelsTotal)
  const distance = sum((s) => s.distanceKm)
  const keyPasses = sum((s) => s.keyPasses)

  // Goal / card timeline.
  const events = [...m.events].sort((a, b) => a.minute - b.minute)
  const goals = events.filter((e) => e.type === 'goal')
  const assistEvents = events.filter((e) => e.type === 'assist')
  const goalLines = goals.map((g) => {
    const a = assistEvents.find((x) => x.relatedPlayerId === g.playerId && Math.abs(x.minute - g.minute) <= 1)
    return `${g.minute}' ${name(g.playerId)}${a ? ` (assist: ${name(a.playerId)})` : ''}`
  })
  const cards = events
    .filter((e) => e.type === 'yellow' || e.type === 'red')
    .map((e) => `${e.minute}' ${e.type === 'red' ? 'RED card' : 'yellow'} — ${name(e.playerId)}`)

  // Per-player stat lines, best to worst.
  const ratings = [...ps].sort((a, b) => b.rating - a.rating)
  const playerLines = ratings.map((s) => {
    const p = players.get(s.playerId)
    return `#${p?.number ?? '?'} ${name(s.playerId)} (${s.position}, ${s.minutes}'): rating ${n1(s.rating)}, ` +
      `${s.goals}G ${s.assists}A, ${s.shots} shots (${s.shotsOnTarget} on target), xG ${n2(s.xg)}, ` +
      `${s.passesCompleted}/${s.passes} passes, ${s.keyPasses} key passes, ${s.tackles} tackles, ` +
      `${s.interceptions} interceptions, ${s.duelsWon}/${s.duelsTotal} duels, ${n1(s.distanceKm)}km`
  })

  // Unit aggregates.
  const groups: Record<PositionGroup, PlayerMatchStat[]> = { GK: [], DEF: [], MID: [], FWD: [] }
  for (const s of ps) groups[positionGroupOf(s.position)].push(s)
  const unitLine = (g: PositionGroup): string | null => {
    const arr = groups[g]
    if (!arr.length) return null
    const avg = arr.reduce((a, s) => a + s.rating, 0) / arr.length
    const gA = arr.reduce((a, s) => a + s.goals, 0)
    const aA = arr.reduce((a, s) => a + s.assists, 0)
    const def = arr.reduce((a, s) => a + s.tackles + s.interceptions, 0)
    return `${g}: avg rating ${n1(avg)}, ${gA}G ${aA}A, ${def} tackles+interceptions, ${arr.reduce((a, s) => a + s.keyPasses, 0)} key passes`
  }
  const unitLines = (['DEF', 'MID', 'FWD'] as PositionGroup[]).map(unitLine).filter(Boolean) as string[]

  // Season context.
  const season = teamSeasonStats(data.matches)
  const perGameXg = season.played ? season.xgFor / season.played : 0
  const xgDiff = m.xgFor - m.xgAgainst

  const user =
    `MATCH REPORT — ${data.team.name} vs ${m.opponent}\n` +
    `Competition: ${m.competition}. Venue: ${m.venue}. Date: ${fmtDate(m.date)}. Our formation: ${m.formation}.\n` +
    `FINAL SCORE: ${resWord} ${m.goalsFor}-${m.goalsAgainst}.\n\n` +
    `TEAM STATS (us vs opponent):\n` +
    `- Possession: ${m.possession}% vs ${100 - m.possession}%\n` +
    `- Shots: ${m.shotsFor} vs ${m.shotsAgainst}; our shots on target: ${shotsOnTarget}\n` +
    `- Expected goals (xG): ${n1(m.xgFor)} vs ${n1(m.xgAgainst)} — we ${xgDiff >= 0 ? 'created more' : 'were out-created'} by ${n1(Math.abs(xgDiff))}\n` +
    `- Corners: ${m.cornersFor} vs ${m.cornersAgainst}\n` +
    `- Our passing: ${passesC}/${passes} completed (${n1(passAcc)}%), ${keyPasses} key passes\n` +
    `- Duels won: ${duelsWon}/${duelsTotal}, tackles ${tackles}, interceptions ${interceptions}, distance covered ${n1(distance)}km\n\n` +
    `GOAL & CARD TIMELINE (our team — opponent goal minutes are not recorded):\n` +
    `${goalLines.length ? goalLines.map((l) => `- ${l}`).join('\n') : '- No goals scored by us.'}\n` +
    (cards.length ? `${cards.map((l) => `- ${l}`).join('\n')}\n` : '') +
    `We conceded ${m.goalsAgainst} goal(s).\n\n` +
    `UNIT PERFORMANCE:\n${unitLines.map((l) => `- ${l}`).join('\n')}\n\n` +
    `PLAYER STAT LINES (best rating to worst):\n${playerLines.map((l) => `- ${l}`).join('\n')}\n\n` +
    `SEASON CONTEXT: ${season.wins}W-${season.draws}D-${season.losses}L, ${n1(season.ppg)} ppg; the team averages ${n1(perGameXg)} xG/game and ${n1(season.avgPossession)}% possession. Judge whether this display was above or below par.\n` +
    (m.notes ? `\nCOACH'S NOTES: ${m.notes}\n` : '') +
    `\nUsing ALL of the above, produce a FULL tactical breakdown of the game with these markdown sections:\n` +
    `**Game state & story** — how the match unfolded and what the result means in context.\n` +
    `**Attacking** — chance creation, xG vs goals (finishing quality), who created and finished.\n` +
    `**Defending** — how we coped with the opponent, what likely led to the goal(s) conceded or the clean sheet, key defensive contributors.\n` +
    `**Control & midfield** — possession, passing, duels, tempo and who dictated it.\n` +
    `**Standouts & concerns** — 2–3 best performers and 1–2 who struggled, justified by their numbers.\n` +
    `**Tactical adjustments** — concrete changes to make next time.\n` +
    `**Training priorities** — 2–3 specific things to work on this week.\n` +
    `Reference specific numbers, players and minutes throughout. Avoid generic filler.`

  const topCreators = ratings.filter((s) => s.keyPasses > 0).slice(0, 3).map((s) => `${name(s.playerId)} (${s.keyPasses} KP)`)
  const heuristic =
    `**Game state & story**\n` +
    `${resWord} ${m.goalsFor}-${m.goalsAgainst} ${m.venue.toLowerCase()} vs ${m.opponent} (${m.competition}). ${m.possession}% possession and ${n1(m.xgFor)}–${n1(m.xgAgainst)} xG — ${xgDiff >= 0 ? `we out-created them by ${n1(xgDiff)}` : `out-created by ${n1(-xgDiff)}`}.\n\n` +
    `**Attacking**\n- ${m.shotsFor} shots (${shotsOnTarget} on target) worth ${n1(m.xgFor)} xG; scored ${m.goalsFor} (${m.goalsFor >= m.xgFor ? 'clinical — above xG' : 'below xG, finishing to sharpen'}).\n- Creators: ${topCreators.length ? topCreators.join(', ') : 'chances shared across the side'}.\n${goalLines.length ? `- Goals: ${goalLines.join('; ')}.\n` : ''}\n` +
    `**Defending**\n- Conceded ${m.goalsAgainst} on ${n1(m.xgAgainst)} xG against; ${tackles} tackles, ${interceptions} interceptions, ${duelsWon}/${duelsTotal} duels won.\n\n` +
    `**Control & midfield**\n- ${passesC}/${passes} passes (${n1(passAcc)}%), ${keyPasses} key passes, ${n1(distance)}km covered.\n\n` +
    `**Standouts**\n${ratings.slice(0, 3).map((s) => `- ${name(s.playerId)} — ${n1(s.rating)}${s.goals ? `, ${s.goals}G` : ''}${s.assists ? `, ${s.assists}A` : ''}.`).join('\n')}\n\n` +
    `**Concerns**\n${ratings.slice(-2).map((s) => `- ${name(s.playerId)} — ${n1(s.rating)} rating; review involvement/role.`).join('\n')}\n\n` +
    `_Add an Anthropic API key in Data & Export for a full AI tactical breakdown from this data pack._`

  return { system: MATCH_SYSTEM, user, heuristic, maxTokens: 4000, effort: 'high' }
}

// ---------------------------------------------------------------------------
// Team / season
// ---------------------------------------------------------------------------
export function teamAnalysis(data: AppData): Analysis {
  const s = teamSeasonStats(data.matches)
  const forms = formationUsage(data.matches)
  const topScore = leaderboard(data, 'goals', 3)
  const topRated = leaderboard(data, 'avgRating', 3, 3)

  const user =
    `Team: ${data.team.name}, ${data.team.season}. Played ${s.played}: ${s.wins}W ${s.draws}D ${s.losses}L, ${s.points} pts (${n1(s.ppg)} ppg).\n` +
    `Goals ${s.goalsFor} for, ${s.goalsAgainst} against (diff ${s.goalDiff}). xG ${n1(s.xgFor)} for, ${n1(s.xgAgainst)} against. Avg possession ${n1(s.avgPossession)}%. Clean sheets ${s.cleanSheets}, failed to score ${s.failedToScore}.\n` +
    `Formations: ${forms.map((f) => `${f.formation} (${f.count} games, ${n1(f.ppg)} ppg)`).join(', ')}.\n` +
    `Top scorers: ${topScore.map((p) => `${p.player.name} ${p.goals}`).join(', ')}. Top rated: ${topRated.map((p) => `${p.player.name} ${n1(p.avgRating)}`).join(', ')}.\n` +
    `\nWrite a season review with sections: Overview, Attacking, Defending, Best setup, and Priorities for the next block of fixtures.`

  const heuristic =
    `**Overview**\n` +
    `${s.wins}W ${s.draws}D ${s.losses}L from ${s.played} (${n1(s.ppg)} ppg), goal difference ${s.goalDiff >= 0 ? '+' : ''}${s.goalDiff}.\n\n` +
    `**Attacking**\n- ${s.goalsFor} goals on ${n1(s.xgFor)} xG (${s.goalsFor >= s.xgFor ? 'finishing above expectation' : 'underperforming the chances created'}). ${s.failedToScore} blanks.\n\n` +
    `**Defending**\n- ${s.goalsAgainst} conceded on ${n1(s.xgAgainst)} xG, ${s.cleanSheets} clean sheets.\n\n` +
    `**Best setup**\n- ${forms[0] ? `${forms[0].formation} has returned the most (${n1(forms[0].ppg)} ppg over ${forms[0].count} games).` : 'Not enough data to compare formations.'}\n\n` +
    `**Priorities**\n- Lean on ${topScore[0]?.player.name ?? 'your top scorer'} in the final third; address the weakest defensive phase above.`

  return { system: ANALYST_SYSTEM, user, heuristic }
}

// ---------------------------------------------------------------------------
// Opposition scouting
// ---------------------------------------------------------------------------
export function scoutingAnalysis(data: AppData, opponent: string): Analysis {
  const h2h = headToHead(data, opponent)
  const moments = oppositionMoments(data, opponent)
  const momentLines = moments.map((m) => `- [${m.tag.category}] ${m.tag.title}${m.tag.note ? ` — ${m.tag.note}` : ''}`).join('\n')
  const existing = data.scouting.find((sc) => sc.opponent === opponent)

  const user =
    `Opponent: ${opponent}. Head-to-head: played ${h2h.played} (${h2h.wins}W ${h2h.draws}D ${h2h.losses}L), goals ${h2h.goalsFor}-${h2h.goalsAgainst}.\n` +
    (h2h.matches.length ? `Recent meetings: ${h2h.matches.slice(0, 4).map((m) => `${resultOf(m.goalsFor, m.goalsAgainst)} ${m.goalsFor}-${m.goalsAgainst} (${fmtDateShort(m.date)})`).join(', ')}.\n` : '') +
    (existing?.formation ? `Likely shape: ${existing.formation}.\n` : '') +
    (moments.length ? `Tagged opposition footage moments:\n${momentLines}\n` : 'No tagged footage yet.\n') +
    `\nDraft a concise opposition scouting dossier with sections: Threats, Weaknesses to exploit, Their set pieces, and Our game plan.`

  const heuristic =
    `**Head-to-head**\n` +
    `${h2h.played > 0 ? `${h2h.wins}W ${h2h.draws}D ${h2h.losses}L vs ${opponent}, ${h2h.goalsFor}-${h2h.goalsAgainst} on goals.` : `No previous meetings recorded vs ${opponent}.`}\n\n` +
    `**From the footage**\n` +
    (moments.length ? momentLines : '- No opposition moments tagged yet — tag clips as “Opposition” on the Video screen to build this out.') +
    `\n\n**Game plan**\n- Exploit the patterns above; rehearse your set-piece routines and defend theirs with clear assignments.\n\n` +
    `_Add an API key in Data & Export to generate a full written dossier from this data._`

  return { system: ANALYST_SYSTEM, user, heuristic }
}
