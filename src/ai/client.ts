// ---------------------------------------------------------------------------
// Client-side Claude integration via a direct fetch to the Messages API. The
// user supplies their own API key, stored only in this browser's localStorage
// and sent directly to Anthropic — there is no server in between. We use the
// documented `anthropic-dangerous-direct-browser-access` header (which enables
// CORS for browser callers). For shared/production use, proxy through a backend
// so the key isn't exposed in the browser.
// ---------------------------------------------------------------------------

const API_URL = 'https://api.anthropic.com/v1/messages'

const KEY_LS = 'gaffer.ai.key'
const MODEL_LS = 'gaffer.ai.model'

export interface AiModel {
  id: string
  label: string
  hint: string
}

export const AI_MODELS: AiModel[] = [
  { id: 'claude-opus-4-8', label: 'Claude Opus 4.8', hint: 'Most capable — best analysis' },
  { id: 'claude-sonnet-5', label: 'Claude Sonnet 5', hint: 'Balanced quality and speed' },
  { id: 'claude-haiku-4-5', label: 'Claude Haiku 4.5', hint: 'Fastest and cheapest' },
]

const DEFAULT_MODEL = 'claude-opus-4-8'

export function getApiKey(): string {
  try { return localStorage.getItem(KEY_LS) ?? '' } catch { return '' }
}
export function setApiKey(key: string): void {
  try { localStorage.setItem(KEY_LS, key.trim()) } catch { /* ignore */ }
}
export function getModel(): string {
  try { return localStorage.getItem(MODEL_LS) || DEFAULT_MODEL } catch { return DEFAULT_MODEL }
}
export function setModel(model: string): void {
  try { localStorage.setItem(MODEL_LS, model) } catch { /* ignore */ }
}
export function hasApiKey(): boolean {
  return getApiKey().trim().length > 10
}
export function modelLabel(id: string): string {
  return AI_MODELS.find((m) => m.id === id)?.label ?? id
}

export interface AnalysisRequest {
  system: string
  user: string
  /** Called with the full accumulated text on each streamed delta. */
  onText: (full: string) => void
  signal?: AbortSignal
}

/** Stream a Claude analysis, returning the full text. Throws on error. */
export async function streamAnalysis(req: AnalysisRequest): Promise<string> {
  const apiKey = getApiKey()
  if (!apiKey) throw new Error('NO_KEY')
  const model = getModel()

  // Adaptive thinking improves analytical quality on the thinking-capable models;
  // Haiku doesn't take the effort/adaptive params, so send a plain request there.
  const thinkingCapable = model.startsWith('claude-opus') || model === 'claude-sonnet-5'
  const body: Record<string, unknown> = {
    model,
    max_tokens: 3000,
    stream: true,
    system: req.system,
    messages: [{ role: 'user', content: req.user }],
  }
  if (thinkingCapable) {
    body.thinking = { type: 'adaptive' }
    body.output_config = { effort: 'medium' }
  }

  const resp = await fetch(API_URL, {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true',
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
    signal: req.signal,
  })

  if (!resp.ok || !resp.body) {
    let detail = `HTTP ${resp.status}`
    try {
      const j = await resp.json()
      const m = (j as { error?: { message?: string } })?.error?.message
      if (m) detail = `${resp.status} ${m}`
    } catch { /* non-JSON error body */ }
    throw new Error(detail)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let full = ''

  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      const t = line.trim()
      if (!t.startsWith('data:')) continue
      const payload = t.slice(5).trim()
      if (!payload || payload === '[DONE]') continue
      let ev: { type?: string; delta?: { type?: string; text?: string }; error?: { message?: string } }
      try {
        ev = JSON.parse(payload)
      } catch {
        continue
      }
      if (ev.type === 'content_block_delta' && ev.delta?.type === 'text_delta') {
        full += ev.delta.text ?? ''
        req.onText(full)
      } else if (ev.type === 'error') {
        throw new Error(ev.error?.message ?? 'stream error')
      }
    }
  }
  return full.trim()
}

/** Turn an unknown error into a friendly, actionable message. */
export function describeAiError(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err)
  if (msg === 'NO_KEY') return 'Add your Anthropic API key in Data & Export to enable AI analysis.'
  if (/401|authentication|invalid x-api-key/i.test(msg)) return 'That API key was rejected (401). Check it in Data & Export.'
  if (/403|permission/i.test(msg)) return 'Your API key lacks permission for this model (403). Try a different model.'
  if (/429|rate.?limit/i.test(msg)) return 'Rate limited (429) — wait a moment and try again.'
  if (/CORS|Failed to fetch|NetworkError|ERR_/i.test(msg)) return 'Network/CORS error reaching the Claude API. Check your connection (and that direct browser access isn’t blocked).'
  if (/credit|billing|quota/i.test(msg)) return 'Billing/credit issue on your Anthropic account.'
  return `AI request failed: ${msg}`
}
