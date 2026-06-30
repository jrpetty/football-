// ---------------------------------------------------------------------------
// Builds the prompt (system + user) and a no-API-key heuristic fallback for
// each kind of analysis, from the app's own structured data.
// ---------------------------------------------------------------------------
import type { AppData } from '../types'
import {
  playerAggregate, playerPercentiles, playerRatingTrend, overallRating,
  teamSeasonStats, formationUsage, leaderboard, headToHead, oppositionMoments,
} from '../analytics/selectors'
import { POSITION_LABEL, resultOf, fmtDateShort } from '../utils/format'

export interface Analysis {
  system: string
  user: string
  /** Offline, rules-based fallback shown when no API key is set. */
  heuristic: string
}

const ANALYST_SYSTEM =
  'You are an elite football (soccer) performance analyst and coach. Write concise, ' +
  'specific, actionable analysis for a coach. Use short markdown sections with **bold** ' +
  'headers and bullet points. Ground every point in the numbers provided — no generic ' +
  'filler. Be direct and practical. Keep the whole response under ~260 words.'

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
  const name = (id: string) => data.players.find((p) => p.id === id)?.name ?? 'Unknown'
  const res = resultOf(m.goalsFor, m.goalsAgainst)
  const ratings = [...m.playerStats].sort((x, y) => y.rating - x.rating)
  const top = ratings.slice(0, 3)
  const low = ratings.slice(-2)
  const scorers = m.events.filter((e) => e.type === 'goal').map((e) => name(e.playerId))

  const user =
    `Match: ${m.venue} vs ${m.opponent}, ${m.competition}. Result: ${res} ${m.goalsFor}-${m.goalsAgainst}. Formation ${m.formation}.\n` +
    `Team stats (us vs them): possession ${m.possession}% vs ${100 - m.possession}%, shots ${m.shotsFor} vs ${m.shotsAgainst}, xG ${n1(m.xgFor)} vs ${n1(m.xgAgainst)}, corners ${m.cornersFor} vs ${m.cornersAgainst}.\n` +
    (scorers.length ? `Scorers: ${scorers.join(', ')}.\n` : 'No goals scored.\n') +
    `Top performers: ${top.map((s) => `${name(s.playerId)} ${n1(s.rating)}`).join(', ')}. Lowest: ${low.map((s) => `${name(s.playerId)} ${n1(s.rating)}`).join(', ')}.\n` +
    (m.notes ? `Coach notes: ${m.notes}\n` : '') +
    `\nWrite a match analysis with sections: Verdict, What worked, What didn't, Key moments/decisions, and Fixes for next time.`

  const xgDiff = m.xgFor - m.xgAgainst
  const heuristic =
    `**Verdict**\n` +
    `${res === 'W' ? 'Win' : res === 'D' ? 'Draw' : 'Loss'} ${m.goalsFor}-${m.goalsAgainst} ${m.venue.toLowerCase()} vs ${m.opponent}. ` +
    `${m.possession}% possession and ${n1(m.xgFor)}–${n1(m.xgAgainst)} xG (${xgDiff >= 0 ? 'out-created' : 'out-created by'} the opponent by ${n1(Math.abs(xgDiff))}).\n\n` +
    `**What worked**\n- ${top.map((s) => name(s.playerId)).join(', ')} were the standout performers.\n${m.goalsFor > m.xgFor ? '- Clinical finishing — outscored the xG.' : '- Created chances steadily through the formation shape.'}\n\n` +
    `**What to fix**\n${m.goalsAgainst > 0 ? `- Conceded ${m.goalsAgainst}; tighten the defensive transitions.` : '- Kept a clean sheet — maintain the defensive structure.'}\n- ${low.map((s) => name(s.playerId)).join(' and ')} need support or rotation.`

  return { system: ANALYST_SYSTEM, user, heuristic }
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
