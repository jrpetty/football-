// ---------------------------------------------------------------------------
// Optional Claude narrative.
//
// The deterministic evidence bullets are the product; this turns them into
// prose for anyone who would rather read a paragraph than a list. It is
// strictly additive — the site is fully useful with no API key, and every
// number Claude is given comes from the model rather than from Claude.
//
// The key is the reader's own, kept in their browser's localStorage and sent
// straight to Anthropic. There is no server in between, which is why the SDK
// needs `dangerouslyAllowBrowser`. That is the right trade for a static site
// where each reader supplies their own credentials; it would not be for a
// shared deployment, where this belongs behind a backend.
// ---------------------------------------------------------------------------

import type { FixtureArtifact } from '../core/schema.ts'
import { clubName } from '../config/teams.ts'

const KEY_STORAGE = 'premoracle.ai.key'
const MODEL_STORAGE = 'premoracle.ai.model'

export interface AiModel {
  id: string
  label: string
  hint: string
}

export const AI_MODELS: AiModel[] = [
  { id: 'claude-opus-5', label: 'Claude Opus 5', hint: 'Most capable — best analysis' },
  { id: 'claude-sonnet-5', label: 'Claude Sonnet 5', hint: 'Balanced quality and speed' },
  { id: 'claude-haiku-4-5', label: 'Claude Haiku 4.5', hint: 'Fastest and cheapest' },
]

const DEFAULT_MODEL = 'claude-opus-5'

export function getApiKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? ''
  } catch {
    return ''
  }
}
export function setApiKey(key: string): void {
  try {
    localStorage.setItem(KEY_STORAGE, key.trim())
  } catch {
    /* private browsing */
  }
}
export function hasApiKey(): boolean {
  return getApiKey().trim().length > 10
}
export function getModel(): string {
  try {
    return localStorage.getItem(MODEL_STORAGE) || DEFAULT_MODEL
  } catch {
    return DEFAULT_MODEL
  }
}
export function setModel(id: string): void {
  try {
    localStorage.setItem(MODEL_STORAGE, id)
  } catch {
    /* private browsing */
  }
}

const SYSTEM = `You are a football analyst writing a short preview of one Premier League match.

You will be given the output of a statistical model: its probabilities, its expected goals, and
the ranked factors it derived, each already quantified. Write two or three tight paragraphs a
knowledgeable fan would enjoy.

Rules:
- Use only the numbers provided. Never invent a statistic, a result, a transfer, or a quote.
- The model's probabilities are the forecast. Do not contradict them or substitute your own.
- Say plainly when a match is close or genuinely uncertain. Do not manufacture confidence.
- Name the specific mechanism by which the underdog could win, if the data supports one.
- No betting advice, no tips, no suggested stakes. This is an analysis experiment, not a tipping service.
- British football register. No headings, no bullet points, no preamble — just the prose.`

/** Everything Claude is told about a fixture, drawn entirely from the model. */
function buildPrompt(f: FixtureArtifact): string {
  const home = clubName(f.home)
  const away = clubName(f.away)
  const pct = (x: number): string => `${Math.round(x * 100)}%`

  const lines: string[] = [
    `Match: ${home} (home) v ${away} (away), kickoff ${new Date(f.kickoff).toUTCString()}.`,
    '',
    'Model forecast:',
    `- ${home} win ${pct(f.probs.home)}, draw ${pct(f.probs.draw)}, ${away} win ${pct(f.probs.away)}`,
    `- Expected goals: ${home} ${f.expectedGoalsHome.toFixed(2)}, ${away} ${f.expectedGoalsAway.toFixed(2)}`,
    `- Most likely scorelines: ${f.topScorelines.slice(0, 4).map((s) => `${s.home}-${s.away} (${pct(s.prob)})`).join(', ')}`,
    `- Both teams to score ${pct(f.btts)}, over 2.5 goals ${pct(f.over25)}`,
    `- Clean sheet: ${home} ${pct(f.cleanSheetHome)}, ${away} ${pct(f.cleanSheetAway)}`,
    `- Model confidence in this fixture: ${pct(f.confidence)}`,
    '',
    'Factors the model derived, strongest first (impact in goals):',
    ...f.evidence.map(
      (e) => `- [${e.favours}, ${e.weight.toFixed(2)} goals] ${e.headline}. ${e.detail}`,
    ),
    '',
    `Upset assessment: ${f.upset.rating}. ${f.upset.summary}`,
  ]

  if (f.upset.mechanisms.length > 0) {
    lines.push('Routes to an upset:')
    lines.push(...f.upset.mechanisms.map((m) => `- ${m.label}: ${m.detail}`))
  }

  const missing = [...f.homeMissing, ...f.awayMissing]
  if (missing.length > 0) {
    lines.push('', 'Unavailable players:')
    lines.push(
      ...missing.map(
        (m) => `- ${m.name} (${m.position}, ${m.reason}) — ${(m.attackShare * 100).toFixed(1)}% of his side's attacking value. ${m.news}`,
      ),
    )
  }

  const scorers = [...f.homePlayers, ...f.awayPlayers]
    .sort((a, b) => b.goal - a.goal)
    .slice(0, 5)
  if (scorers.length > 0) {
    lines.push('', 'Most likely scorers:')
    lines.push(...scorers.map((p) => `- ${p.name} (${clubName(p.team)}) ${pct(p.goal)}`))
  }

  if (f.cardWatch.length > 0) {
    lines.push('', 'One booking from a suspension:')
    lines.push(
      ...f.cardWatch.map(
        (c) => `- ${c.name} (${clubName(c.team)}), ${c.yellows} bookings, ${pct(c.bookingProbability)} chance of another here`,
      ),
    )
  }

  return lines.join('\n')
}

export interface NarrativeRequest {
  fixture: FixtureArtifact
  onText: (full: string) => void
  signal?: AbortSignal
}

/**
 * Stream a narrative preview, returning the finished text.
 *
 * The SDK is imported dynamically so it never enters the main bundle — a
 * reader who does not use this feature does not pay for it.
 */
export async function streamNarrative(req: NarrativeRequest): Promise<string> {
  const apiKey = getApiKey()
  if (!apiKey) throw new Error('NO_KEY')

  const { default: Anthropic } = await import('@anthropic-ai/sdk')
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true })

  const model = getModel()
  // Haiku does not take the thinking/effort parameters; the others benefit
  // from adaptive thinking on a task that weighs several factors at once.
  const thinkingCapable = model !== 'claude-haiku-4-5'

  const stream = client.messages.stream(
    {
      model,
      max_tokens: 2000,
      system: SYSTEM,
      messages: [{ role: 'user', content: buildPrompt(req.fixture) }],
      ...(thinkingCapable
        ? { thinking: { type: 'adaptive' as const }, output_config: { effort: 'medium' as const } }
        : {}),
    },
    { signal: req.signal },
  )

  let full = ''
  stream.on('text', (delta) => {
    full += delta
    req.onText(full)
  })

  const final = await stream.finalMessage()
  if (final.stop_reason === 'refusal') {
    throw new Error('REFUSED')
  }
  return full.trim()
}

/** Turn an unknown failure into something a reader can act on. */
export function describeAiError(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err)
  if (msg === 'NO_KEY') return 'Add an Anthropic API key to generate a written preview.'
  if (msg === 'REFUSED') return 'Claude declined to write this one. The model numbers above are unaffected.'
  if (/401|authentication|invalid x-api-key/i.test(msg)) return 'That API key was rejected (401).'
  if (/403|permission/i.test(msg)) return 'That key cannot use this model (403). Try a different one.'
  if (/429|rate.?limit/i.test(msg)) return 'Rate limited (429) — give it a moment.'
  if (/abort/i.test(msg)) return 'Cancelled.'
  if (/CORS|Failed to fetch|NetworkError/i.test(msg)) return 'Could not reach the Claude API from this browser.'
  if (/credit|billing|quota/i.test(msg)) return 'There is a billing or credit problem on that Anthropic account.'
  return `Could not generate a preview: ${msg}`
}
