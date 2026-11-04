// Claude API access, straight from the browser. The key you enter lives only in
// this browser's localStorage and is sent only to api.anthropic.com. For a
// shared deployment, put a small proxy in front instead of exposing a key.

import Anthropic from '@anthropic-ai/sdk'

export interface ModelInfo {
  id: string
  label: string
  blurb: string
  input: number // $ per million input tokens
  output: number
  cacheRead: number
  fallback: boolean // supports server-side `fallbacks: "default"`
}

export const MODELS: ModelInfo[] = [
  { id: 'claude-opus-5-5', label: 'Claude Opus 5.5', blurb: 'Deepest, most characterful minds', input: 4, output: 20, cacheRead: 0.2, fallback: true },
  { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5', blurb: 'Fast and capable — a great balance', input: 2, output: 10, cacheRead: 0.2, fallback: true },
  { id: 'claude-haiku-5-5', label: 'Claude Haiku 5.5', blurb: 'Fastest and cheapest — big populations', input: 0.1, output: 0.5, cacheRead: 0.01, fallback: false },
  { id: 'claude-fable-5-1', label: 'Claude Fable 5.1', blurb: 'Anthropic’s most capable model (premium)', input: 10, output: 50, cacheRead: 0.25, fallback: true },
]

export const DEFAULT_MODEL = 'claude-opus-5-5'

export function modelInfo(id: string): ModelInfo {
  return MODELS.find((m) => m.id === id) ?? MODELS[0]
}

export type Effort = 'low' | 'medium' | 'high'

export interface AiSettings {
  apiKey: string
  enabled: boolean
  model: string
  effort: Effort
  maxConcurrent: number
  maxPerMinute: number
  budgetUsd: number
  thinkEveryHours: number
  maxMinds: number
  reflections: boolean
  chronicler: boolean
}

export const DEFAULT_SETTINGS: AiSettings = {
  apiKey: '',
  enabled: true,
  model: DEFAULT_MODEL,
  effort: 'low',
  maxConcurrent: 4,
  maxPerMinute: 12,
  budgetUsd: 5,
  thinkEveryHours: 6,
  maxMinds: 16,
  reflections: true,
  chronicler: true,
}

const LS = 'genesis.ai.settings'

export function loadSettings(): AiSettings {
  try {
    const raw = localStorage.getItem(LS)
    if (raw) return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) }
  } catch { /* storage unavailable */ }
  return { ...DEFAULT_SETTINGS }
}
export function saveSettings(s: AiSettings) {
  try { localStorage.setItem(LS, JSON.stringify(s)) } catch { /* ignore */ }
}

export interface Usage {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
}

export function costOf(model: string, u: Usage): number {
  const m = modelInfo(model)
  return (u.input * m.input + u.output * m.output + u.cacheRead * m.cacheRead + u.cacheWrite * m.input * 1.25) / 1e6
}

let client: Anthropic | null = null
let clientKey = ''
function getClient(key: string): Anthropic {
  if (!client || clientKey !== key) {
    client = new Anthropic({ apiKey: key, dangerouslyAllowBrowser: true, maxRetries: 2, timeout: 120_000 })
    clientKey = key
  }
  return client
}

export interface CallOpts {
  key: string
  model: string
  effort: Effort
  system: string
  user: string
  schema?: Record<string, unknown>
  maxTokens?: number
  signal?: AbortSignal
}

export interface CallResult {
  text: string
  usage: Usage
  model: string
  stop: string
}

export class AiError extends Error {
  kind: 'auth' | 'rate' | 'refusal' | 'overloaded' | 'bad' | 'network' | 'other'
  constructor(kind: AiError['kind'], msg: string) {
    super(msg)
    this.kind = kind
  }
}

/** One Messages API call with a cached system prompt and (optionally) a JSON schema. */
export async function callClaude(o: CallOpts): Promise<CallResult> {
  const info = modelInfo(o.model)
  const c = getClient(o.key)
  try {
    const resp = await c.beta.messages.create(
      {
        model: o.model,
        max_tokens: o.maxTokens ?? 8000,
        system: [{ type: 'text', text: o.system, cache_control: { type: 'ephemeral' } }],
        messages: [{ role: 'user', content: o.user }],
        output_config: { effort: o.effort, ...(o.schema ? { format: { type: 'json_schema', schema: o.schema } } : {}) },
        ...(info.fallback ? { betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' as const } : {}),
      },
      { signal: o.signal },
    )
    const usage: Usage = {
      input: resp.usage.input_tokens ?? 0,
      output: resp.usage.output_tokens ?? 0,
      cacheRead: resp.usage.cache_read_input_tokens ?? 0,
      cacheWrite: resp.usage.cache_creation_input_tokens ?? 0,
    }
    if (resp.stop_reason === 'refusal') throw Object.assign(new AiError('refusal', 'The model declined this request.'), { usage })
    let text = ''
    for (const b of resp.content) if (b.type === 'text') text += b.text
    if (resp.stop_reason === 'max_tokens' && o.schema) throw Object.assign(new AiError('bad', 'Response was cut off (max_tokens).'), { usage })
    return { text, usage, model: resp.model ?? o.model, stop: resp.stop_reason ?? '' }
  } catch (e) {
    if (e instanceof AiError) throw e
    if (e instanceof Anthropic.AuthenticationError) throw new AiError('auth', 'Your API key was rejected (401). Check it in Settings.')
    if (e instanceof Anthropic.PermissionDeniedError) throw new AiError('auth', `This key can't use ${info.label} (403). Try another model.`)
    if (e instanceof Anthropic.RateLimitError) throw new AiError('rate', 'Rate limited (429) — slowing down.')
    if (e instanceof Anthropic.BadRequestError) throw new AiError('bad', `Request rejected: ${e.message}`)
    if (e instanceof Anthropic.InternalServerError) throw new AiError('overloaded', 'Anthropic API is busy — retrying soon.')
    if (e instanceof Anthropic.APIConnectionError) throw new AiError('network', 'Could not reach the Claude API (network or CORS).')
    if (e instanceof Anthropic.APIError) throw new AiError('other', `API error ${e.status}: ${e.message}`)
    throw new AiError('other', e instanceof Error ? e.message : String(e))
  }
}

/** Parse a JSON object out of model text (structured outputs make this exact; be tolerant anyway). */
export function parseJson<T>(text: string): T | null {
  try {
    return JSON.parse(text) as T
  } catch {
    const m = /\{[\s\S]*\}/.exec(text)
    if (m) {
      try { return JSON.parse(m[0]) as T } catch { /* fallthrough */ }
    }
    return null
  }
}
