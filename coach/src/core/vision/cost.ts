// Cost estimates for cloud vision. Prices are Anthropic's published
// first-party rates as cached in the build reference (2026-10-06); they can
// change, so the UI always calls these *estimates* and links to the pricing
// page. Image tokens follow Anthropic's rule of thumb (width × height / 750).

export interface ModelPrice {
  readonly id: string
  readonly label: string
  /** USD per million tokens. */
  readonly input: number
  readonly output: number
  readonly cacheRead: number
  /** Server-side refusal fallback is available for this model. */
  readonly fallbacks: boolean
}

export const MODELS: readonly ModelPrice[] = [
  { id: 'claude-opus-5-5', label: 'Claude Opus 5.5 (most capable)', input: 4, output: 20, cacheRead: 0.2, fallbacks: true },
  { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5', input: 2, output: 10, cacheRead: 0.2, fallbacks: true },
  { id: 'claude-haiku-5-5', label: 'Claude Haiku 5.5 (fastest, cheapest)', input: 0.1, output: 0.5, cacheRead: 0.01, fallbacks: false },
]

export const DEFAULT_MODEL = 'claude-opus-5-5'
export const PRICING_URL = 'https://www.anthropic.com/pricing'

export function modelPrice(id: string): ModelPrice {
  return MODELS.find((m) => m.id === id) ?? MODELS[0]
}

export interface Usage {
  readonly input_tokens: number
  readonly output_tokens: number
  readonly cache_read_input_tokens?: number | null
  readonly cache_creation_input_tokens?: number | null
}

/** Estimated USD for one response's usage. */
export function costOf(model: string, u: Usage): number {
  const p = modelPrice(model)
  const cacheRead = u.cache_read_input_tokens ?? 0
  const cacheWrite = u.cache_creation_input_tokens ?? 0
  return (u.input_tokens * p.input + cacheWrite * p.input * 1.25 + cacheRead * p.cacheRead + u.output_tokens * p.output) / 1_000_000
}

export function imageTokens(width: number, height: number): number {
  return Math.ceil((width * height) / 750)
}

/** Rough USD per hour of play at a sampling interval, assuming a cached system prompt. */
export function estimatePerHour(model: string, intervalMs: number, imageWidth: number, imageHeight: number): number {
  const perCall = costOf(model, {
    input_tokens: imageTokens(imageWidth, imageHeight) + 150,
    cache_read_input_tokens: 1500,
    output_tokens: 350,
  })
  return perCall * (3_600_000 / intervalMs)
}
