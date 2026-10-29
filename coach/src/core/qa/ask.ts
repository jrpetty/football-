// "Ask the coach". Common questions are answered locally from the structured
// session data — no API key, no network, no invented numbers. Anything else
// is routed to Claude (if the player enabled cloud features) with a compact,
// structured digest: the model phrases an answer from that evidence, it does
// not observe the game.
//
// When the question is asked matters too: during restricted play the answer is
// held until the match ends (the governor decides, like any other message).

import type { HeatmapResult } from '../analyses/heatmap.ts'
import type { MatchRecord } from '../analyses/matchRecorder.ts'
import type { SessionReport } from '../engine.ts'
import { formatDuration, humanize, mistakeLine, responseLabel } from '../language.ts'
import type { ArsenalItem, BenchmarkSpec, GameMap, GameProfile, GrenadeType } from '../profile/schema.ts'
import { expectedAt } from '../analyses/benchmarks.ts'
import { adviceFor, factorFindings } from '../planner.ts'
import { clock as clockText, learnedFrom, learnedLines, type Learned } from '../analyses/learning.ts'
import { ballisticsLines } from '../knowledge/ballistics.ts'

export type Intent =
  | { readonly kind: 'heatmap'; readonly heatmapId: string }
  | { readonly kind: 'mistakes' }
  | { readonly kind: 'fights' }
  | { readonly kind: 'benchmark'; readonly atSec?: number; readonly target?: boolean }
  | { readonly kind: 'intel' }
  | { readonly kind: 'learned' }
  | { readonly kind: 'arsenal'; readonly itemId: string; readonly build?: boolean; readonly question?: string }
  | { readonly kind: 'map'; readonly mapName: string | null }
  | { readonly kind: 'utility'; readonly mapName: string | null; readonly question: string }
  | { readonly kind: 'summary' }
  | { readonly kind: 'patterns' }
  | { readonly kind: 'stats' }
  | { readonly kind: 'open' }

const HEATMAP_WORDS: readonly [RegExp, readonly string[]][] = [
  [/\b(concede|conceding|conceded|goals? against|let (?:in|goals))\b/i, ['conceded']],
  [/\bwhere (?:do|did) i score|\bmy goals\b|\bscor(?:e|ing) from\b/i, ['scored']],
  [/\b(lose|losing|lost) (?:the )?(?:ball|possession)\b|\bturnovers?\b|\bpass(?:es|ing)?\b.*\b(fail\w*|wrong|unsuccessful|intercepted|misplaced)\b|\b(misplaced|unsuccessful|failed)\b.*\bpass/i, ['turnovers']],
  [/\b(die|dying|died|deaths?|killed)\b/i, ['deaths']],
]

const NADE_WORDS = /\b(smokes?|flash(?:es|bangs?)?|pop ?flash\w*|molly|mollies|molotovs?|incendiar\w*|incs?|he ?(?:grenades?|nades?)|nades?|grenades?|utility|util|line ?ups?|executes?|retakes?)\b/

export function classify(question: string, profile: GameProfile): Intent {
  const q = question.toLowerCase()
  // Grenade lineups on a map: which smokes, flashes or molotovs, from where, for which site; executes and retakes.
  if (profile.knowledge.maps?.some((m) => m.utility?.length) && NADE_WORDS.test(q) && !/\b(differen\w*|compare\w*|versus|vs)\b/.test(q)) {
    const named = mapNamedIn(q, profile)
    if (named?.utility?.length || /\b(line ?ups?|throw|where|executes?|retakes?|for (?:a|b|mid)|(?:a|b) site|site (?:a|b))\b/.test(q)) return { kind: 'utility', mapName: named?.name ?? null, question: q }
  }
  // A weapon, perk, piece of equipment or streak named in the question: how it works, its range, how to beat it.
  const item = arsenalItemIn(q, profile)
  if (item && /\b(how|what|good|best|counter|beat|range|work|use|using|vs|versus|should|ttk|build|loadout|attachments?|stats|damage|strong|weak|meta|explain|tell me|about|kill me|killing me|deal with|stop|does|do|is|can|last|long|far|differen\w*|compare\w*|put out|extinguish\w*)\b/.test(q))
    return { kind: 'arsenal', itemId: item.id, question: q, ...(/\b(build|loadout|attachments?|class|setup|gunsmith)\b/.test(q) ? { build: true } : {}) }
  // The map: where I am, callouts, power positions, angles, routes, objectives.
  const map = mapNamedIn(q, profile)
  if (profile.positioning && (map || /\b(callouts?|power positions?|head ?glitch\w*|sightlines?|angles?|routes?|rotat\w+|lanes?|this map|the map|where am i|which spot|best spots?|hardpoint (?:order|rotation)|flags?|bomb sites?|spawns?)\b/.test(q)))
    return { kind: 'map', mapName: map?.name ?? null }
  // What past games showed: their usual build, my usual build, how I do against this.
  if (profile.learning && /\b(usually|usual|normally|tend to|build orders?|my (?:usual )?build|last (?:few )?(?:games|matches)|past (?:games|matches)|history|compositions?|comps?|what have you learn\w*|what did you learn\w*|how do i do (?:against|vs|on|with)|against (?:them|this|him|her) before)\b/.test(q)) return { kind: 'learned' }
  if (profile.intel && /\b(what (?:are|is) (?:they|he|she|the enemy|my opponent) (?:making|building|doing|going)|their (?:army|units|civ|civilisation|civilization|buildings)|what (?:should|do) i (?:build|make|do)|counter|scout\w*|opponent|their plan|what(?:'s| is) (?:their|the) plan|game plan|strategy)\b/.test(q)) return { kind: 'intel' }
  if (profile.factors?.length && /\b(position\w*|gunfights?|fights?|battles?|engagements?|cover|why (?:do|did|am|do i keep) i\b.*\b(die|dying|lose|losing)|how (?:do|did) i (?:die|lose)|what kills me|keep dying)\b/.test(q)) return { kind: 'fights' }
  if (profile.benchmarks?.length && /\b(villagers?|benchmark|on track|economy|eco|macro|behind)\b/.test(q)) {
    // "How many villagers should I have at ten minutes?": the curve's number, not a judgement.
    const target = /\b(should i (?:have|be at)|supposed to|target|expected|aim for|how many \w+ (?:at|by))\b/.test(q)
    const atSec = clockIn(q)
    return { kind: 'benchmark', ...(target ? { target } : {}), ...(atSec !== null ? { atSec } : {}) }
  }
  const heatmaps = new Set((profile.heatmaps ?? []).map((h) => h.id))
  for (const [re, ids] of HEATMAP_WORDS) {
    const id = ids.find((x) => heatmaps.has(x))
    if (id && re.test(q)) return { kind: 'heatmap', heatmapId: id }
  }
  if (/\b(mistakes?|wrong|bad habits?|what should i (?:work on|improve|fix)|improve)\b/.test(q)) return { kind: 'mistakes' }
  if (/\b(pass(?:ing)? accuracy|possession|shots|stats|statistics|trend)\b/.test(q)) return { kind: 'stats' }
  if (/\b(patterns?|tendenc(?:y|ies)|habits?)\b/.test(q)) return { kind: 'patterns' }
  if (/\b(how (?:did|am|was) i|how'?s it going|summary|recap|how (?:did|do|does|was) (?:the|that|my) (?:match|game)(?: go)?)\b/.test(q)) return { kind: 'summary' }
  return { kind: 'open' }
}

/** A local answer from structured data, or null when the question needs the model. */
export function answerLocally(intent: Intent, profile: GameProfile, report: SessionReport, history: readonly MatchRecord[]): string | null {
  switch (intent.kind) {
    case 'heatmap': {
      const h = report.heatmaps.find((x) => x.spec.id === intent.heatmapId)
      if (h) return heatmapAnswer(h)
      const spec = profile.heatmaps?.find((x) => x.id === intent.heatmapId)
      return spec ? `I have not seen any ${spec.phrase} yet. Start capture and play, and I will track where they happen.` : null
    }
    case 'fights':
      return fightsAnswer(profile, report)
    case 'intel':
      return intelAnswer(report)
    case 'learned':
      return learnedAnswer(profile, report, history)
    case 'arsenal':
      return arsenalAnswer(profile, report, intent.itemId, intent.build === true, intent.question ?? '')
    case 'map':
      return mapAnswer(profile, report, intent.mapName)
    case 'utility':
      return utilityAnswer(profile, report, intent.mapName, intent.question)
    case 'benchmark': {
      const base = profile.benchmarks?.[0]
      // The player's civilisation's own curve where it has one (the session's resolved spec).
      const spec = base ? (report.benchmarks.find((b) => b.spec.id === base.id)?.spec ?? base) : undefined
      if (spec && (intent.target || intent.atSec !== undefined)) return benchmarkTarget(spec, report, intent.atSec ?? null)
      const behind = report.benchmarks.filter((b) => b.worst)
      if (behind.length) return behind.map((b) => [b.worst!.statement, adviceFor(profile, b.spec.playbook ?? null)].filter(Boolean).join(' ')).join(' ')
      const met = report.benchmarks.filter((b) => b.points.length)
      if (met.length) return met.map((b) => `${b.spec.label}: on track at ${b.points.length} of ${b.points.length} checkpoints I could read.`).join(' ')
      return 'I have not read enough of the clock and your numbers yet to compare them with the benchmark. Keep the resource panel and the clock visible.'
    }
    case 'mistakes': {
      const fights = factorFindings(report.factors, 'moderate').filter((f) => f.kind === 'weakness').slice(0, 1)
      const lines = report.mistakes
        .filter((m) => m.totals.count > 0)
        .sort((a, b) => b.totals.totalMs - a.totals.totalMs)
        .slice(0, 3)
        .map((m) => [mistakeLine(m.spec.summary, m.totals), adviceFor(profile, m.spec.playbook ?? null)].filter(Boolean).join(' '))
      lines.unshift(...fights.map((f) => [f.statement, adviceFor(profile, f.playbook)].filter(Boolean).join(' ')))
      for (const b of report.benchmarks.filter((x) => x.worst).slice(0, 1)) lines.push([b.worst!.statement, adviceFor(profile, b.spec.playbook ?? null)].filter(Boolean).join(' '))
      const pattern = report.insights.find((i) => i.verdict === null && (i.insight.grade === 'moderate' || i.insight.grade === 'strong'))
      if (pattern) lines.push(pattern.statement)
      const claims = report.heatmaps.filter((h) => h.claim && h.claim.grade !== 'weak').slice(0, 1).map(heatmapAnswer)
      lines.push(...claims)
      if (lines.length === 0) return 'I have not seen a repeated mistake yet this session. I only count what is visible on screen, so keep playing and ask again later.'
      return `This session: ${lines.join(' ')}`
    }
    case 'summary': {
      const parts: string[] = []
      if (report.score) parts.push(`The score I last read was ${report.score.player}–${report.score.opponent}.`)
      const seen = report.record.won + report.record.lost + report.record.drawn
      if (seen > 1) parts.push(`You won ${report.record.won} of ${seen} rounds I could read.`)
      if (report.matches.length) {
        const wins = report.matches.filter((m) => m.result === 'win').length
        parts.push(`Matches this session: ${report.matches.length}, ${wins} won.`)
      }
      const top = report.heatmaps.find((h) => h.claim)
      if (top) parts.push(heatmapAnswer(top))
      const worst = report.mistakes.filter((m) => m.totals.count > 0).sort((a, b) => b.totals.totalMs - a.totals.totalMs)[0]
      if (worst) parts.push(mistakeLine(worst.spec.summary, worst.totals))
      return parts.length ? parts.join(' ') : 'Not much to report yet: I have not seen enough of this session to summarise it.'
    }
    case 'patterns': {
      const usable = report.insights.filter((i) => i.verdict === null && i.insight.grade !== 'insufficient')
      if (!usable.length) return 'No patterns yet. I need to see the same situation several times, with different responses, before I can compare them.'
      return usable.slice(0, 2).map((i) => i.statement).join(' ')
    }
    case 'stats': {
      const recent = [...history, ...report.matches].filter((m) => m.gameProfileId === profile.id && Object.keys(m.stats).length).slice(-5)
      if (!recent.length) return 'I have no match statistics yet. They come from the end-of-match statistics screen, so leave it on screen for a few seconds.'
      const keys = profile.matchStats?.keys ?? []
      const lines = keys
        .map((k) => {
          const vals = recent.map((m) => m.stats[k.key]).filter((v): v is number => typeof v === 'number')
          if (!vals.length) return null
          const last = vals[vals.length - 1]
          return vals.length > 1 ? `${k.label}: ${fmt(last, k.unit)} last match, ${fmt(avg(vals), k.unit)} on average over ${vals.length}.` : `${k.label}: ${fmt(last, k.unit)}.`
        })
        .filter((x): x is string => !!x)
      return lines.length ? lines.join(' ') : null
    }
    case 'open':
      return null
  }
}

/** What was scouted, what it can make, and what beats it. Only what was on the player's screen. */
function intelAnswer(report: SessionReport): string {
  const i = report.intel
  if (!i || (!i.faction && !i.buildings.length && !i.units.length)) {
    return 'I have not seen their civilisation, buildings or army yet. Scout their base, or let me see the loading screen, and I will tell you what they can make and what beats it.'
  }
  const parts: string[] = []
  if (i.faction) parts.push(`They are ${i.faction.name}${i.faction.status === 'upcoming' ? ' (not released yet, so details are thin)' : ''}.`)
  if (i.opponentAge) parts.push(i.opponentAge.basis === 'seen' ? `They are in the ${i.opponentAge.name}.` : i.opponentAge.basis === 'fielded' ? `They are at least in the ${i.opponentAge.name}.` : `I have not seen their age; assuming the ${i.opponentAge.name}, like you.`)
  if (i.buildings.length) parts.push(`I saw ${i.buildings.slice(0, 4).map((b) => (b.count > 1 ? `${b.count} ${b.name}s` : `a ${b.name}`)).join(', ')}.`)
  if (i.units.length) parts.push(`Units seen: ${i.units.slice(0, 4).map((u) => `${u.name} (up to ${u.count})`).join(', ')}.`)
  const now = i.likely.filter((l) => !l.later)
  const later = i.likely.filter((l) => l.later)
  if (now.length) parts.push(`Those buildings can also make ${now.slice(0, 4).map((l) => l.name).join(', ')}.`)
  if (later.length) parts.push(`Not yet: ${later.slice(0, 3).map((l) => l.name).join(', ')}.`)
  for (const c of i.counters.slice(0, 2)) if (c.build.length) parts.push(`Against ${c.against}: ${c.build.join(' or ')}.`)
  const read = i.reads[0]
  if (read) parts.push(`Likely plan: ${read.name}, from ${read.evidence.join(', ')}. ${read.response.slice(0, 2).join(' ')}`)
  else if (i.usual.length) parts.push(`Nothing points to a plan yet. Usual for them now: ${i.usual.map((u) => u.name).join('; ')}.`)
  if (i.yourPlan?.playAs.length) parts.push(`Your ${i.yourPlan.ageName}: ${i.yourPlan.playAs[0]}`)
  return parts.join(' ')
}

/** How fights are being lost, from killcam/replay tags, with the fixes. */
function fightsAnswer(profile: GameProfile, report: SessionReport): string {
  const tagged = Math.max(0, ...report.factors.map((f) => f.taggedLosses))
  const all = Math.max(0, ...report.factors.map((f) => f.taggedLosses + f.untagged))
  const weak = factorFindings(report.factors, 'weak').filter((f) => f.kind === 'weakness')
  const strong = factorFindings(report.factors, 'moderate').find((f) => f.kind === 'strength')
  if (!weak.length) {
    if (all === 0) return 'I have not seen a fight I could read yet this session.'
    return `I could read the details of ${tagged} of ${all} so far, which is not enough to call a pattern. I read them from killcams and replays, so let those play out when you can.`
  }
  const lines = weak.slice(0, 2).map((f) => `${f.grade === 'weak' ? 'Early sign: ' : ''}${f.statement} ${adviceFor(profile, f.playbook) ?? ''}`.trim())
  if (strong) lines.push(`On the plus side: ${strong.statement[0].toLowerCase()}${strong.statement.slice(1)}`)
  return `${lines.join(' ')} These are read from killcams and replays, so treat them as likely rather than certain.`
}

function heatmapAnswer(h: HeatmapResult): string {
  const sampled = h.spec.sampled ? ' These come from sampled frames, so some are missed.' : ''
  if (h.total === 0) return `I have not seen any ${h.spec.phrase} yet this session.`
  const ranked = Object.entries(h.cells).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1])
  const label = (id: string) => h.zoneSet.cells.find((c) => c.id === id)?.label ?? id
  if (h.located === 0) return `${h.spec.label}: I saw ${h.total === 1 ? 'one' : h.total}, but could not place ${h.total === 1 ? 'it' : 'any of them'}.${sampled}`
  if (h.located === 1) {
    const unplaced = h.total - 1
    return `${h.spec.label}: so far just one I could place, ${label(ranked[0][0])}${unplaced ? ` (${unplaced} more I could not place)` : ''}. One is not a pattern yet.${sampled}`
  }
  const [first, second] = ranked
  let text = `Of ${h.total} ${h.spec.phrase} I saw, ${h.located} could be placed. Most came from ${label(first[0])}: ${first[1]}.`
  if (second) text += ` Next, ${label(second[0])}: ${second[1]}.`
  if (h.topBreakdown && h.spec.breakdown) text += ` ${h.spec.breakdown.label}: mostly ${humanize(h.topBreakdown.value)}, ${h.topBreakdown.count} times.`
  const strength = h.claim ? (h.claim.grade === 'weak' ? ' Early sign only.' : '') : ' Too few to call it a pattern yet.'
  return `${text}${strength} Zones are approximate.${sampled}`
}

// --- Digest for the model -----------------------------------------------------------------

export interface DigestInputs {
  readonly profile: GameProfile
  readonly modeLabel: string | null
  readonly policyLine: string
  readonly report: SessionReport
  readonly history: readonly MatchRecord[]
  readonly notes: { readonly goals: string; readonly notes: string } | null
  readonly research: { readonly text: string; readonly createdAt: string; readonly sources: readonly { readonly url: string; readonly title: string }[] } | null
}

/** A compact, structured, size-bounded description of what the coach has seen. */
export function buildDigest(i: DigestInputs): string {
  const p = i.profile
  const digest = {
    game: p.game.title,
    pack: { id: p.id, depth: p.depth, validation: p.validation.status, limitations: p.limitations },
    mode: i.modeLabel,
    policy: i.policyLine,
    session: {
      score: i.report.score,
      rounds: i.report.record,
      zones: i.report.heatmaps
        .filter((h) => h.total > 0)
        .map((h) => ({
          what: h.spec.phrase,
          total: h.total,
          placed: h.located,
          sampledFrames: h.spec.sampled,
          byZone: Object.fromEntries(Object.entries(h.cells).filter(([, n]) => n > 0).map(([id, n]) => [h.zoneSet.cells.find((c) => c.id === id)?.label ?? id, n])),
          howCreated: h.breakdown,
          claimGrade: h.claim?.grade ?? 'none',
        })),
      game: Object.keys(i.report.context).length ? i.report.context : undefined,
      scouted: i.report.intel ?? undefined,
      learnedFromPastGames: learnedDigest(p, i.report, i.history),
      map: mapDigest(i.report),
      civPlans: civPlans(p, i.report),
      fightsFollowed: i.report.battles.length
        ? {
            won: i.report.battles.filter((b) => b.outcome === 'won').length,
            lost: i.report.battles.filter((b) => b.outcome === 'lost').length,
            unclear: i.report.battles.filter((b) => b.outcome === 'unclear').length,
            readFrom: 'fight frames on screen and the army size (population minus villagers); likely, not certain',
            recent: i.report.battles.slice(-12).map((b) => ({
              atGameSec: b.gameSec,
              outcome: b.outcome,
              army: b.armyBefore !== null && b.armyLow !== null ? `${b.armyBefore} → ${b.armyLow}` : null,
              lastRead: b.lastTrend,
              circumstances: b.attributes,
            })),
          }
        : undefined,
      fights: i.report.factors
        .filter((f) => f.taggedLosses + f.taggedWins + f.untagged > 0)
        .map((f) => ({
          factor: f.spec.label,
          readFrom: 'killcam or replay (probable, not certain)',
          byValue: Object.fromEntries(f.rows.filter((r) => r.losses + r.wins > 0).map((r) => [r.label, f.spec.winEvent ? { lost: r.losses, won: r.wins } : { [f.spec.lossNoun ?? 'deaths']: r.losses }])),
          notReadable: f.untagged,
          findings: f.findings.map((x) => ({ kind: x.kind, grade: x.grade, statement: x.statement, playbook: x.playbook })),
        })),
      benchmarks: i.report.benchmarks
        .filter((b) => b.points.length > 0)
        .map((b) => ({
          benchmark: b.spec.label,
          expected: `${b.spec.expectedPhrase}: ${b.spec.expected.start} + ${b.spec.expected.perMinute}/min`,
          points: b.points.map((pt) => ({ atSec: pt.atSec, actual: pt.actual, expected: pt.expected, behind: pt.behind })),
          readFrom: 'the screen near each checkpoint; only numbers read at least twice and agreeing are kept',
        })),
      mistakes: i.report.mistakes.filter((m) => m.totals.count > 0).map((m) => ({ mistake: m.spec.label, times: m.totals.count, total: formatDuration(m.totals.totalMs) })),
      patterns: i.report.insights
        .filter((x) => x.verdict === null)
        .map((x) => {
          const spec = p.episodes.find((e) => e.id === x.insight.episodeId)!
          return {
            pattern: x.episodeLabel,
            grade: x.insight.grade,
            statement: x.statement,
            worse: { response: responseLabel(spec, x.insight.worse.response), ...x.insight.worse.counts },
            better: { response: responseLabel(spec, x.insight.better.response), ...x.insight.better.counts },
          }
        }),
      matchesThisSession: i.report.matches.map(slimMatch),
    },
    recentMatches: i.history.slice(-10).map(slimMatch),
    playerNotes: i.notes ? { goals: i.notes.goals.slice(0, 600), notes: i.notes.notes.slice(0, 600) } : null,
    research: i.research ? { asOf: i.research.createdAt, brief: i.research.text.slice(0, 3500), sources: i.research.sources.slice(0, 8) } : null,
  }
  return JSON.stringify(digest)
}

/** The opponent's plans around their age and the player's own plan, from the pack's roster (reference, not observation). */
function civPlans(p: GameProfile, report: SessionReport) {
  const i = report.intel
  const factions = p.knowledge.roster?.factions ?? []
  if (!i) return undefined
  const theirs = i.faction ? factions.find((f) => f.id === i.faction!.id) : undefined
  const age = i.opponentAge?.age ?? null
  const near = (a: number) => age === null || Math.abs(a - age) <= 1
  return {
    note: 'Reference plans from guides and pro play; match them to what was seen before calling one.',
    opponent: theirs ? { civ: theirs.name, summary: theirs.playstyle, ages: (theirs.agePlans ?? []).filter((a) => near(a.age)) } : null,
    you: i.yourPlan,
  }
}

/** The longest arsenal name or alias that appears in the question as words. */
function arsenalItemIn(q: string, profile: GameProfile): ArsenalItem | null {
  let best: { item: ArsenalItem; len: number } | null = null
  for (const item of profile.knowledge.arsenal ?? []) {
    for (const n of [item.name, ...(item.aliases ?? [])]) {
      const k = n.toLowerCase()
      // Short names count when they carry a digit (M4); otherwise three letters or more.
      if ((k.length < 3 && !/\d/.test(k)) || k.length < 2 || (best && k.length <= best.len)) continue
      if (new RegExp(`(^|[^a-z0-9])${k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}($|[^a-z0-9])`).test(q)) best = { item, len: k.length }
    }
  }
  return best?.item ?? null
}

function mapNamedIn(q: string, profile: GameProfile): GameMap | null {
  for (const m of profile.knowledge.maps ?? []) for (const n of [m.name, ...(m.aliases ?? [])]) if (n.length >= 4 && new RegExp(`\\b${n.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(q)) return m
  return null
}

const STOP = new Set(['what', 'when', 'where', 'which', 'does', 'with', 'that', 'this', 'from', 'have', 'they', 'your', 'about', 'there', 'their', 'work', 'works', 'grenade', 'grenades'])
const words = (t: string) => new Set((t.toLowerCase().match(/[a-z]{4,}/g) ?? []).filter((w) => !STOP.has(w)).map((w) => w.replace(/(ing|es|s)$/, '')))

/** The item's detailed mechanics that share the most words with the question (none when nothing matches). */
function mechanicsFor(i: ArsenalItem, q: string, max = 2): string[] {
  // The item's own name says nothing about which detail is wanted.
  const own = words([i.name, ...(i.aliases ?? [])].join(' '))
  const want = new Set([...words(q)].filter((w) => !own.has(w)))
  if (!want.size || !i.mechanics?.length) return []
  return i.mechanics
    .map((m) => ({ m, score: [...words(`${m.name} ${m.detail}`)].filter((w) => want.has(w)).length }))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, max)
    .map(({ m }) => `${m.detail}${m.unverified ? ' (Not confirmed for CS2.)' : ''}`)
}

function arsenalAnswer(profile: GameProfile, report: SessionReport, id: string, build: boolean, question = ''): string | null {
  const i = profile.knowledge.arsenal?.find((x) => x.id === id)
  if (!i) return null
  // A specific question about how it works: the matching details first.
  const detail = mechanicsFor(i, question)
  if (detail.length) return `${i.name}: ${detail.join(' ')}`
  const what = [i.class?.replace(/_/g, ' '), i.slot, i.cost].filter(Boolean).join(', ')
  const said = (t: string) => i.summary.toLowerCase().includes(t.toLowerCase().slice(0, 40))
  const parts = [`${i.name}${what ? ` (${what})` : ''}: ${i.summary}`]
  if (i.bestRange) parts.push(`Best at ${i.bestRange} range.`)
  // Numbers the summary did not already say.
  const nums = Object.entries(i.stats ?? {}).flatMap(([k, v]) => (STAT_WORDS[k] && !(k === 'magazine' && /magazine/i.test(i.summary)) && !(k === 'rpm' && i.ballistics) ? [STAT_WORDS[k](v)] : []))
  if (nums.length) parts.push(`${nums.join(', ')}.`)
  // The game's own numbers: hits and time to kill, one-tap range, accuracy.
  if (i.ballistics) parts.push(...ballisticsLines(i).slice(0, 3))
  const strong = (i.strengths ?? []).filter((x) => !said(x)).slice(0, 2)
  if (strong.length) parts.push(`Strong: ${strong.join('; ')}.`)
  if (i.weaknesses?.length) parts.push(`Weak: ${i.weaknesses.slice(0, 2).join('; ')}.`)
  if (i.counters?.length) parts.push(`To beat it: ${i.counters.slice(0, 2).join('; ')}.`)
  // The build only when asked about builds; otherwise a tip on using it.
  const meta = (i.tips ?? []).find((t) => /^meta build/i.test(t))
  const tip = (i.tips ?? []).find((t) => !/^meta build/i.test(t))
  if (build && meta) parts.push(`${meta}.`)
  else if (tip) parts.push(`Tip: ${/[.!?]$/.test(tip) ? tip : `${tip}.`}`)
  // The player's own record with it on this map, when there is one.
  const mine = report.map?.model?.weapons.find((w) => w.name.toLowerCase() === i.name.toLowerCase())
  if (mine) parts.push(`Your record with it on ${report.map!.model!.map}: ${mine.kills} kills, ${mine.deaths} deaths.`)
  if (i.unverified) parts.push('(Some of this is not yet confirmed for the current patch.)')
  return parts.join(' ').replace(/\.\./g, '.')
}

/** Numbers said in words. */
const STAT_WORDS: Readonly<Record<string, (v: number | string) => string>> = {
  rpm: (v) => `${v} rounds a minute`,
  ttkCloseMs: (v) => `kills in about ${v} ms up close`,
  ttkFarMs: (v) => `${v} ms at range`,
  adsMs: (v) => `aims down sights in ${v} ms`,
  magazine: (v) => `${v}-round magazine`,
  numbers: (v) => String(v),
}

const NADE_TYPE: readonly [RegExp, readonly GrenadeType[]][] = [
  [/\bsmokes?\b/, ['smoke']],
  [/\bflash/, ['flash']],
  [/\b(molly|mollies|molotovs?|incendiar\w*|incs?|fire)\b/, ['molotov', 'incendiary']],
  [/\bhe\b|\bhe ?(grenades?|nades?)\b/, ['he']],
  [/\bdecoys?\b/, ['decoy']],
]

/** Grenade lineups on a map, filtered by what the question names: grenade, side, site, callout; or an execute or retake. */
function utilityAnswer(profile: GameProfile, report: SessionReport, named: string | null, q: string): string | null {
  const maps = profile.knowledge.maps ?? []
  const current = report.rounds?.status.map ?? report.map?.position.map ?? report.map?.model?.map ?? null
  const m = maps.find((x) => x.name === (named ?? current))
  if (!m?.utility?.length) return named ? `I have no grenade lineups for ${named}.` : `Which map? I have lineups for ${maps.filter((x) => x.utility?.length).map((x) => x.name).join(', ')}.`
  const side = /\b(ct|counter[- ]?terrorists?|defen[cd]\w*|retakes?)\b/.test(q) ? 'CT' : /\b(t side|as t|terrorists?|attack\w*|executes?)\b/.test(q) ? 'T' : null
  const site = q.match(/\b(?:(a|b) site|site (a|b)|bombsite (a|b)|(?:for|on|onto|to|take|hit|execute|retake|into) (a|b|mid))\b/)
  const siteName = site ? (site[1] ?? site[2] ?? site[3] ?? site[4]).toUpperCase().replace('MID', 'Mid') : null
  const types = NADE_TYPE.find(([re]) => re.test(q))?.[1] ?? null
  // An execute or retake for a site.
  const kind = /\bretakes?\b/.test(q) ? 'retake' : /\bexecutes?\b|\bhit\b|\btake (?:a|b)\b/.test(q) ? 'execute' : null
  if (kind && m.utilityPlans?.length) {
    const plans = m.utilityPlans.filter((p) => p.kind === kind && (!siteName || p.site === siteName))
    const p = plans[0]
    if (p) {
      const byId = new Map(m.utility.map((l) => [l.id, l]))
      const steps = p.utility.map((id, i) => {
        const l = byId.get(id)
        return l ? `${i + 1}, ${l.name} (${l.type} from ${l.from})` : null
      })
      return `${p.name} on ${m.name}: ${p.how} In order: ${steps.filter(Boolean).join('; ')}.${plans.length > 1 ? ` There ${plans.length === 2 ? 'is one more' : `are ${plans.length - 1} more`} in Insights, Grenade lineups.` : ''}`
    }
  }
  // A callout named in the question: lineups landing there (or thrown from there).
  const callouts = m.callouts.flatMap((c) => [c.name, ...(c.aliases ?? [])].map((n) => ({ n: n.toLowerCase(), name: c.name }))).filter((c) => c.n.length >= 3 && !/^(a|b|ct|t) ?site$/.test(c.n))
  const spot = callouts.filter((c) => new RegExp(`(^|[^a-z0-9])${c.n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}($|[^a-z0-9])`).test(q)).sort((a, b) => b.n.length - a.n.length)[0]?.name ?? null
  let list = m.utility.filter((l) => (!types || types.includes(l.type)) && (!side || l.side === side))
  if (spot) {
    const there = list.filter((l) => l.to === spot || l.from === spot)
    if (there.length) list = [...there.filter((l) => l.to === spot), ...there.filter((l) => l.to !== spot)]
  } else if (siteName) {
    const bySite = list.filter((l) => new RegExp(`\\b${siteName}\\b`, 'i').test(`${l.when ?? ''} ${l.to}`))
    if (bySite.length) list = bySite
  }
  if (!list.length) return `I have no ${types ? types[0] : 'grenade'} lineups for that on ${m.name}.`
  // Valve's own lineups first (exact spots), then the rest.
  list = [...list.filter((l) => l.official), ...list.filter((l) => !l.official)]
  const [first, ...rest] = list
  const how = [first.stand ? `Stand: ${first.stand}` : null, first.aim ? `Aim: ${first.aim}` : null, first.throw ? `Throw: ${first.throw.replace(/-/g, ' ')}.` : null].filter(Boolean).join(' ')
  const more = rest.slice(0, 3).map((l) => `${l.name} (from ${l.from}${l.to !== first.to ? ` to ${l.to}` : ''})`)
  return [
    `${first.name} on ${m.name}, ${first.type} from ${first.from}${first.toDetail ? ` onto ${first.toDetail}` : ` to ${first.to}`}: ${first.purpose}`,
    how,
    more.length ? `Also: ${more.join('; ')}.` : null,
    list.length > 4 ? `${list.length - 4} more in Insights, Grenade lineups.` : null,
    first.unverified ? '(That one is not yet confirmed for the current version.)' : null,
  ]
    .filter(Boolean)
    .join(' ')
}

function mapAnswer(profile: GameProfile, report: SessionReport, named: string | null): string {
  const m = report.map
  const ref = (profile.knowledge.maps ?? []).find((x) => x.name === (named ?? m?.model?.map))
  const parts: string[] = []
  if (!named && m?.position.area && m.position.ageMs < 20_000) parts.push(`You are at ${m.position.area}${m.position.elevation && m.position.elevation !== 'ground' ? ` (${m.position.elevation})` : ''}${m.position.map ? ` on ${m.position.map}` : ''}.`)
  if ((!named || named === m?.model?.map) && m?.lines.length) parts.push(...m.lines.slice(0, 3), ...m.fit.slice(0, 1))
  if (ref) {
    if (ref.powerPositions?.length) parts.push(`Power positions on ${ref.name}: ${ref.powerPositions.slice(0, 3).map((p) => `${p.callout} (${p.why.replace(/[.]$/, '')})`).join('; ')}.`)
    if (ref.objectives?.hardpoint?.length) parts.push(`Hardpoint order: ${ref.objectives.hardpoint.join(', ')}.`)
    if (!parts.length && ref.layout) parts.push(`${ref.name}: ${ref.layout}`)
  }
  if (!parts.length) return 'I have not seen enough of this map yet. Play on it with cloud vision on and I will learn where you go, fight and die, and where they shoot from.'
  return parts.join(' ')
}

function mapDigest(report: SessionReport) {
  const m = report.map
  if (!m || (!m.model && !m.position.area)) return undefined
  const ref = m.model?.reference
  return {
    note: 'Learned from the player\'s own screen: areas named by each look, deaths placed where their own view last placed them, killer spots from killcams. Likely, not certain.',
    position: m.position.area ? { map: m.position.map, area: m.position.area, elevation: m.position.elevation, secondsAgo: Math.round(m.position.ageMs / 1000) } : undefined,
    findings: m.lines,
    weaponFit: m.fit,
    areas: m.model?.areas.filter((a) => a.visits || a.deaths || a.kills).slice(0, 15).map((a) => ({ name: a.name, seconds: a.seconds, kills: a.kills, deaths: a.deaths, enemiesSeen: a.enemies, heights: a.elevations })),
    heldAngles: m.model?.sightlines.slice(0, 6),
    routes: m.model?.routes.slice(0, 6),
    killerWeapons: m.model?.killerWeapons.slice(0, 6),
    yourWeapons: m.model?.weapons.slice(0, 4),
    reference: ref
      ? { map: ref.name, layout: ref.layout, powerPositions: ref.powerPositions, sightlines: ref.sightlines?.slice(0, 8), objectives: ref.objectives, modeTips: ref.modeTips, callouts: ref.callouts.map((c) => c.name) }
      : undefined,
  }
}

/** All matches known for learning: saved history and this session's, once each. */
function allMatches(report: SessionReport, history: readonly MatchRecord[]): MatchRecord[] {
  const seen = new Set<string>()
  return [...history, ...report.matches].filter((m) => (seen.has(m.id) ? false : (seen.add(m.id), true)))
}

/** What past games like this one showed (live filter: e.g. the opponent's civilisation), or across all games. */
function learnedNow(profile: GameProfile, report: SessionReport, history: readonly MatchRecord[]): Learned | null {
  const all = allMatches(report, history)
  return (report.learned ? learnedFrom(all, profile, report.learned.filter) : null) ?? learnedFrom(all, profile, {})
}

function learnedAnswer(profile: GameProfile, report: SessionReport, history: readonly MatchRecord[]): string {
  const l = learnedNow(profile, report, history)
  if (!l) {
    return 'I have no past games to learn from yet. Turn on "Remember my games" in Settings and play a few: I learn their usual builds and armies, and yours, from what your screen shows.'
  }
  const lines = [...learnedLines(l, 'them', 3), ...learnedLines(l, 'you', 3)]
  const games = l.matches === 1 ? 'game' : `${l.matches} games`
  const record = l.won + l.lost ? ` You won ${l.won} of ${l.won + l.lost} with a result read.` : ''
  if (!lines.length) return `From your last ${games}${l.filterText ? ` ${l.filterText}` : ''}, I have not seen enough on screen to see a pattern yet.${record}`
  return `From your last ${games}${l.filterText ? ` ${l.filterText}` : ''} (as first seen on your screen): ${lines.join(' ')}${record}`
}

function learnedDigest(profile: GameProfile, report: SessionReport, history: readonly MatchRecord[]) {
  const l = learnedNow(profile, report, history)
  if (!l) return undefined
  return {
    filedUnder: l.filterText || 'all games',
    matches: l.matches,
    won: l.won,
    lost: l.lost,
    note: 'From what the player\'s screen showed in past games: "first seen" times are when it appeared on screen, not when it was made.',
    tracks: l.tracks.map((t) => ({
      what: t.label,
      side: t.side,
      games: t.matches,
      usualOrder: t.order.length ? t.order.map((o) => `${o.name} ${clockText(o.atSec)}`) : undefined,
      items: t.items.slice(0, 8).map((x) => ({
        name: x.name,
        seenInGames: x.seenIn,
        ...(x.firstSec !== null ? { firstSeen: clockText(x.firstSec) } : {}),
        ...(x.peak ? { mostAtOnce: x.peak } : {}),
        ...(t.tally ? { good: x.good, bad: x.bad } : { times: x.total }),
        ...(x.won + x.lost ? { won: x.won, lost: x.lost } : {}),
      })),
    })),
  }
}

function slimMatch(m: MatchRecord) {
  return { endedAt: m.endedAt.slice(0, 10), result: m.result, score: m.score, stats: m.stats, mistakes: m.mistakes, zones: m.zones }
}

function avg(v: readonly number[]): number {
  return v.reduce((a, b) => a + b, 0) / v.length
}

function fmt(v: number, unit?: string): string {
  return `${Number.isInteger(v) ? v : v.toFixed(1)}${unit ?? ''}`
}

const NUMBER_WORDS: Record<string, number> = {
  one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19,
  twenty: 20, 'twenty five': 25, thirty: 30, 'thirty five': 35, forty: 40, 'forty five': 45, fifty: 50, sixty: 60,
}

/** A game time named in a question: "at 10:30", "by 12 minutes", "at ten minutes" (seconds), or null. */
export function clockIn(q: string): number | null {
  const mmss = /\b(\d{1,2}):([0-5]\d)\b/.exec(q)
  if (mmss) return Number(mmss[1]) * 60 + Number(mmss[2])
  const words = Object.keys(NUMBER_WORDS).sort((a, b) => b.length - a.length).join('|')
  const m = new RegExp(`\\b(\\d{1,3}|${words})[ -]?(?:minutes?|mins?|m)\\b`).exec(q)
  if (!m) return null
  const n = /^\d/.test(m[1]) ? Number(m[1]) : NUMBER_WORDS[m[1].replace('-', ' ')]
  return n > 0 && n <= 180 ? n * 60 : null
}

/** What the benchmark curve gives at a time (or at its checkpoints), and what was read near it. */
function benchmarkTarget(spec: BenchmarkSpec, report: SessionReport, atSec: number | null): string {
  const lead = spec.expectedPhrase[0].toUpperCase() + spec.expectedPhrase.slice(1)
  const every = spec.expected.perMinute > 0 ? Math.round(60 / spec.expected.perMinute) : null
  const how = `That is ${spec.expected.start} to start and ${spec.expected.perMinute} a minute${every ? ` (one every ${every} seconds)` : ''}${spec.expected.cap ? `, up to ${spec.expected.cap}` : ''}.`
  if (atSec === null) {
    const at = spec.checkpointsSec.map((cp, i) => `${expectedAt(spec, cp)}${i === 0 ? ` ${spec.noun}` : ''} at ${clockText(cp)}`)
    return `${lead} has ${at.length > 1 ? `${at.slice(0, -1).join(', ')} and ${at[at.length - 1]}` : at[0]}. ${how}`
  }
  let text = `${lead} has ${expectedAt(spec, atSec)} ${spec.noun} at ${clockText(atSec)}. ${how}`
  const read = report.benchmarks.find((b) => b.spec.id === spec.id)?.points.find((p) => Math.abs(p.atSec - atSec) <= 60)
  if (read) text += ` You had ${read.actual} at ${clockText(read.atSec)}.`
  return text
}
