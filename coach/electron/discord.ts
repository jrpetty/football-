// Posting a match summary to the player's own Discord webhook (opt-in). Only
// the address saved in the settings is used, only https to Discord, plain
// text with mentions turned off, and nothing is read back but the status.

import { isDiscordWebhook } from '../src/core/notify.ts'

export async function discordPost(webhook: unknown, text: unknown): Promise<{ ok: true } | { ok: false; error: string }> {
  if (!isDiscordWebhook(webhook)) return { ok: false, error: 'Set a Discord webhook first (Settings → Match summaries).' }
  if (typeof text !== 'string' || !text.trim()) return { ok: false, error: 'Nothing to send.' }
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), 10_000)
  try {
    const res = await fetch(webhook, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'Sightline Coach', content: text.slice(0, 1990), allowed_mentions: { parse: [] } }),
      signal: ctrl.signal,
      redirect: 'error',
    })
    if (res.ok) return { ok: true }
    return { ok: false, error: res.status === 404 || res.status === 401 ? 'Discord does not know that webhook (deleted?). Make a new one.' : res.status === 429 ? 'Discord asked to slow down. Try again in a minute.' : `Discord answered ${res.status}.` }
  } catch {
    return { ok: false, error: 'Could not reach Discord.' }
  } finally {
    clearTimeout(t)
  }
}
