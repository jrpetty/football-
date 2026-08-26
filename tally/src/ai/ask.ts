// ---------------------------------------------------------------------------
// Asking the till a question.
//
// The model is handed the data pack and told, firmly, that the pack is the
// whole world: every figure must come from it or be arithmetic on it, and a
// question the pack cannot answer gets "the records don't say" rather than a
// plausible guess. A made-up number about her own pub is the one output worse
// than no feature at all.
//
// The pack travels as a system block with a cache breakpoint on it, so a
// follow-up question re-reads it from Anthropic's cache at a tenth of the
// price instead of re-sending the whole year every time. Same key, same
// model choice, same browser-side client as the roll scanning.
// ---------------------------------------------------------------------------

import { loadSettings } from '../storage/settings.ts'

const SYSTEM = `You answer questions about one British pub, from its own till records.

The user message contains a DATA PACK — the pub's saved nights, item sales, prices, cellar and
rota. That pack is your entire knowledge of this pub.

The rules, in order of importance:
- Every figure you state must come from the pack, or be arithmetic on figures from the pack.
  When you calculate, show the working in one short line, naming the dates or lines you used.
- If the pack cannot answer the question, say plainly that the records don't cover it and name
  what is missing. Never estimate, never fill a gap from general knowledge, never guess. The
  pack states its own limits (how many nights it holds, what was left out) — respect them.
- Public dates you may use: you may work out which date a weekday or a holiday falls on (for
  example the August bank holiday) — but the takings for that date must still come from the pack.
- Money is GBP. A negative variance means the drawer was short of what the till expected.
- Be brief and speak like a person, not a report. Lead with the answer in the first sentence,
  then at most a few supporting lines. Plain text only: no markdown, no headers, no tables.
- If the question is not about the pub or its records, say that this box only answers from the
  till records.`

export interface AskTurn {
  role: 'user' | 'assistant'
  text: string
}

interface AskRequest {
  question: string
  /** From buildAskPack — the whole app, bounded and stated. */
  pack: string
  /** Earlier turns of this conversation, oldest first, for follow-ups. */
  history?: readonly AskTurn[]
  signal?: AbortSignal
}

export async function askTally(req: AskRequest): Promise<string> {
  const settings = loadSettings()
  const apiKey = settings.apiKey.trim()
  if (!apiKey) throw new Error('NO_KEY')

  const { default: Anthropic } = await import('@anthropic-ai/sdk')
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true })

  const message = await client.messages.create(
    {
      model: settings.model,
      // Answers are meant to be a few sentences; a question needing more than
      // this is really several questions.
      max_tokens: 1200,
      system: [
        { type: 'text', text: SYSTEM },
        // The breakpoint sits after the pack: the expensive, unchanging prefix
        // is cached and each follow-up pays only for itself.
        { type: 'text', text: `DATA PACK\n\n${req.pack}`, cache_control: { type: 'ephemeral' } },
      ],
      messages: [
        ...(req.history ?? []).map((t) => ({ role: t.role, content: t.text })),
        { role: 'user' as const, content: req.question },
      ],
    },
    { signal: req.signal },
  )

  const text = message.content
    .filter((c) => c.type === 'text')
    .map((c) => (c as { text: string }).text)
    .join('\n')
    .trim()
  if (!text) throw new Error('NO_ANSWER')
  return text
}

/** Failures phrased for somebody at the bar, matching the scanner's voice. */
export function describeAskError(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err)
  if (msg === 'NO_KEY') return 'Add an Anthropic API key in Settings first — the answers come from Claude, on your own key.'
  if (msg === 'NO_ANSWER') return 'Nothing came back. Ask again.'
  if (/abort/i.test(msg)) return 'Cancelled.'
  if (/401|authentication|invalid x-api-key/i.test(msg)) return 'That API key was rejected. Check it in Settings.'
  if (/429|rate.?limit/i.test(msg)) return 'Rate limited — wait a moment and ask again.'
  if (/credit|billing|quota/i.test(msg)) return 'There is a billing problem on that Anthropic account.'
  if (/overloaded/i.test(msg)) return 'Claude is busy just now — ask again in a moment.'
  if (/CORS|Failed to fetch|NetworkError|load failed|Connection error/i.test(msg)) {
    const online = typeof navigator === 'undefined' ? true : navigator.onLine
    return online
      ? 'This page cannot reach Anthropic — a preview link blocks it, and no key will change that. On a real address it will work.'
      : 'No connection. Ask again when the wifi is back.'
  }
  return `Could not get an answer: ${msg}`
}
