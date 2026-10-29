// Match summaries for Discord: the webhook check and the message.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isDiscordWebhook, matchSummary } from '../src/core/notify.ts'
import { sanitizeSettings, DEFAULT_SETTINGS } from '../src/core/settings.ts'
import type { MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const aoe4 = builtInProfile('aoe4')
const HOOK = 'https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz_ABCDEF-123'

test('only a Discord webhook address is accepted, in the settings too', () => {
  assert.ok(isDiscordWebhook(HOOK))
  assert.ok(isDiscordWebhook(HOOK.replace('discord.com', 'ptb.discord.com')))
  for (const bad of ['http://discord.com/api/webhooks/1234567/abcdefghijklmnopqrstuvwxyz', 'https://evil.example/api/webhooks/123456/abcdefghijklmnopqrstuvwxyz', `${HOOK}?wait=true`, 'https://discord.com.evil.io/api/webhooks/123456/abcdefghijklmnopqrstuvwxyz', 42]) assert.ok(!isDiscordWebhook(bad), String(bad))
  assert.equal(sanitizeSettings({ discord: { webhook: 'https://evil.example/x', afterMatch: true } }).discord.webhook, null)
  assert.equal(sanitizeSettings({ discord: { webhook: HOOK, afterMatch: false } }).discord.webhook, HOOK)
  assert.deepEqual(DEFAULT_SETTINGS.discord, { webhook: null, afterMatch: true })
})

test('the summary: result, villagers against the curve, ages, fights, mistakes, the review and the practice focus', () => {
  const record = {
    schema: 1,
    id: 'm1',
    gameProfileId: 'aoe4',
    profileVersion: aoe4.version,
    modeId: 'skirmish-vs-ai',
    endedAt: '2026-10-29T12:00:00Z',
    durationSec: 1460,
    result: 'win',
    score: null,
    rounds: { won: 1, lost: 0, drawn: 0, unread: 0 },
    stats: {},
    mistakes: { housed: { count: 2, totalSec: 40 }, 'idle-villagers': { count: 0, totalSec: 0 } },
    zones: {},
    benchmarks: { 'villager-curve': [{ atSec: 300, actual: 21, expected: 21 }, { atSec: 600, actual: 31, expected: 36 }] },
    battles: { won: 3, lost: 1, unclear: 0 },
    readings: { t: [], values: {}, changes: [[275, 'age', 'feudal'], [760, 'age', 'castle']] },
    endedManually: false,
  } as unknown as MatchRecord
  const review = { headline: 'x', strengths: [], focus: [{ evidence: 'At 10:00 you had 31 villagers; 36 is the curve.', principle: '', fix: 'Queue one or two villagers each time.', drill: null, playbook: null }], nextMatch: 'Keep the Town Center busy.', author: 'local' as const }
  const text = matchSummary(aoe4, record, review, { practice: 'Practice: villagers at 10:00. Target 33 villagers. Game 2 of 3.' })
  assert.match(text, /^\*\*Age of Empires IV\*\*: Skirmish vs AI · Won · 24 min/)
  assert.match(text, /Villagers against the clock: 21 at 5:00 \(21\), 31 at 10:00 \(36\)/)
  assert.match(text, /Ages: Feudal 4:35, Castle 12:40/)
  assert.match(text, /Fights followed: 3 won, 1 lost/)
  assert.match(text, /Mistakes seen: Housed ×2$/m)
  assert.match(text, /\*\*To work on\*\*\n1\. At 10:00 you had 31 villagers; 36 is the curve\. Queue one or two villagers each time\./)
  assert.match(text, /\*\*Next match:\*\* Keep the Town Center busy\./)
  assert.match(text, /Practice: villagers at 10:00/)
  assert.ok(text.length <= 1900)
  assert.doesNotMatch(text, BANNED_CLAIMS)
  assert.doesNotMatch(text, /@everyone|@here/)
})
