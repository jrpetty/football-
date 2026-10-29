// A match summary for Discord (opt-in): the result, the main numbers the coach
// read, and the review's points to work on, sent to a Discord webhook the
// player made for their own server or channel. Discord delivers it to their
// phone like any message. Only this text is sent.

import type { MatchRecord } from './analyses/matchRecorder.ts'
import type { GameProfile } from './profile/schema.ts'
import type { MatchReview } from './review.ts'

/** A Discord webhook address: discord.com (or its ptb/canary hosts) /api/webhooks/<id>/<token>. */
export const DISCORD_WEBHOOK = /^https:\/\/(?:ptb\.|canary\.)?(?:discord|discordapp)\.com\/api\/webhooks\/\d{5,25}\/[\w-]{20,120}$/

export function isDiscordWebhook(v: unknown): v is string {
  return typeof v === 'string' && v.length <= 220 && DISCORD_WEBHOOK.test(v)
}

/** Discord's limit is 2,000 characters a message. */
const MAX = 1900

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`
const RESULT: Record<string, string> = { win: 'Won', loss: 'Lost', draw: 'Drawn', unknown: 'Result not read' }

export function matchSummary(profile: GameProfile, record: MatchRecord, review: MatchReview | null, extra: { practice?: string | null } = {}): string {
  const mode = profile.modes.find((m) => m.id === record.modeId)?.label.replace(/\s*\([^)]*\)\s*$/, '')
  const head = [mode, RESULT[record.result] ?? record.result, record.durationSec ? `${Math.round(record.durationSec / 60)} min` : null].filter(Boolean).join(' · ')
  const lines: string[] = [`**${profile.game.title}**: ${head}`]
  if (record.score) lines.push(`Score ${record.score.player}–${record.score.opponent}`)
  for (const [id, points] of Object.entries(record.benchmarks ?? {})) {
    const spec = profile.benchmarks?.find((b) => b.id === id)
    if (!spec || !points.length) continue
    lines.push(`${spec.label}: ${points.map((p) => `${p.actual} at ${clock(p.atSec)} (${p.expected})`).join(', ')}`)
  }
  const ages = (record.readings?.changes ?? []).filter(([, k, v]) => k === 'age' && v !== 'dark').map(([t, , v]) => `${v[0].toUpperCase()}${v.slice(1)} ${clock(t)}`)
  if (ages.length) lines.push(`Ages: ${ages.join(', ')}`)
  if (record.battles && record.battles.won + record.battles.lost > 0) lines.push(`Fights followed: ${record.battles.won} won, ${record.battles.lost} lost`)
  const mistakes = Object.entries(record.mistakes)
    .filter(([, m]) => m.count > 0)
    .sort((a, b) => b[1].count - a[1].count)
    .slice(0, 3)
    .map(([id, m]) => `${profile.mistakes?.find((x) => x.id === id)?.label ?? id} ×${m.count}`)
  if (mistakes.length) lines.push(`Mistakes seen: ${mistakes.join(', ')}`)
  if (record.truth) {
    const j = record.truth.lines.filter((l) => l.ok !== null)
    if (j.length) lines.push(`Checked against the real game: ${j.filter((l) => l.ok).length} of ${j.length} readings right`)
  }
  if (review) {
    if (review.focus.length) {
      lines.push('', '**To work on**')
      review.focus.forEach((f, i) => lines.push(`${i + 1}. ${f.evidence}${f.fix ? ` ${f.fix}` : ''}`))
    }
    if (review.nextMatch) lines.push('', `**Next match:** ${review.nextMatch}`)
  }
  if (extra.practice) lines.push('', extra.practice)
  let text = lines.join('\n')
  if (text.length > MAX) text = `${text.slice(0, MAX - 1)}…`
  return text
}
