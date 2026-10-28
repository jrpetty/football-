// What cloud calls cost. Prices are Anthropic's published first-party list
// prices per million tokens, as checked on PRICES_CHECKED_AT; they can change,
// so the app calls every figure an estimate, shows the date and links to the
// pricing page. Costs are computed from the API's own token counts for each
// response: input, cache writes (5-minute 1.25x, 1-hour 2x input), cache reads,
// output (which includes thinking), and server-side web searches.

export const PRICES_CHECKED_AT = '2026-10-28'
export const PRICING_URL = 'https://www.anthropic.com/pricing'
/** Server-side web search, per search. */
export const WEB_SEARCH_USD = 10 / 1000

export interface ModelPrice {
  readonly id: string
  readonly label: string
  /** USD per million tokens. */
  readonly input: number
  readonly output: number
  readonly cacheRead: number
  /** Above this many prompt tokens (input + cache), a model may bill a higher rate. */
  readonly longContext?: { readonly above: number; readonly input: number; readonly output: number; readonly cacheRead: number }
  /** Server-side refusal fallback is available for this model. */
  readonly fallbacks: boolean
}

/** The models the player can pick. */
export const MODELS: readonly ModelPrice[] = [
  { id: 'claude-opus-5-5', label: 'Claude Opus 5.5 (most capable)', input: 4, output: 20, cacheRead: 0.2, fallbacks: true },
  { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5', input: 2, output: 10, cacheRead: 0.2, fallbacks: true },
  {
    id: 'claude-haiku-5-5',
    label: 'Claude Haiku 5.5 (fastest, cheapest)',
    input: 0.1,
    output: 0.5,
    cacheRead: 0.01,
    longContext: { above: 100_000, input: 0.5, output: 2.5, cacheRead: 0.05 },
    fallbacks: false,
  },
]

/** Models a request can be served by after a server-side fallback (not selectable). */
const FALLBACK_PRICES: readonly ModelPrice[] = [
  { id: 'claude-opus-5', label: 'Claude Opus 5', input: 5, output: 25, cacheRead: 0.5, fallbacks: false },
  { id: 'claude-opus-4-8', label: 'Claude Opus 4.8', input: 5, output: 25, cacheRead: 0.5, fallbacks: false },
  { id: 'claude-sonnet-5', label: 'Claude Sonnet 5', input: 2, output: 10, cacheRead: 0.2, fallbacks: false },
]

export const DEFAULT_MODEL = 'claude-opus-5-5'

/** A selectable model's price (the default model's when unknown). */
export function modelPrice(id: string): ModelPrice {
  return MODELS.find((m) => m.id === id) ?? MODELS[0]
}

/** The price for the model that actually served a response (dated ids such as "claude-sonnet-5-5-20261001" included), or null. */
export function servedPrice(id: string | null | undefined): ModelPrice | null {
  if (!id) return null
  return [...MODELS, ...FALLBACK_PRICES].find((m) => id === m.id || id.startsWith(`${m.id}-2`)) ?? null
}

export interface Usage {
  readonly input_tokens: number
  readonly output_tokens: number
  readonly cache_read_input_tokens?: number | null
  readonly cache_creation_input_tokens?: number | null
  /** Cache writes by lifetime, when the API reports it. */
  readonly cache_creation?: { readonly ephemeral_5m_input_tokens?: number | null; readonly ephemeral_1h_input_tokens?: number | null } | null
  readonly server_tool_use?: { readonly web_search_requests?: number | null } | null
  readonly output_tokens_details?: { readonly thinking_tokens?: number | null } | null
}

/**
 * USD for one response's usage at the price of `model` (pass the model that
 * actually served it). Haiku 5.5 bills its higher rate for the whole request
 * when the prompt is over 100K tokens.
 */
export function costOf(model: string, u: Usage): number {
  const base = servedPrice(model) ?? modelPrice(model)
  const cacheRead = u.cache_read_input_tokens ?? 0
  const cacheWrite = u.cache_creation_input_tokens ?? 0
  const prompt = u.input_tokens + cacheRead + cacheWrite
  const p = base.longContext && prompt > base.longContext.above ? { ...base, ...base.longContext } : base
  const write1h = Math.min(cacheWrite, u.cache_creation?.ephemeral_1h_input_tokens ?? 0)
  const write5m = cacheWrite - write1h
  const tokens = u.input_tokens * p.input + write5m * p.input * 1.25 + write1h * p.input * 2 + cacheRead * p.cacheRead + u.output_tokens * p.output
  return tokens / 1_000_000 + (u.server_tool_use?.web_search_requests ?? 0) * WEB_SEARCH_USD
}

/** Image tokens: about one per 28×28-pixel patch (1280×720 is about 1,200). */
export function imageTokens(width: number, height: number): number {
  return Math.ceil(width / 28) * Math.ceil(height / 28)
}

/** Output tokens a screen reading writes (the structured reading itself, measured on the packs). */
export const LOOK_OUTPUT_TOKENS = 350

/**
 * Thinking a screen reading may add before it is measured: Opus 5.5 always
 * thinks a little at low effort; Sonnet and Haiku only when thinking is not
 * off. An allowance for the upper end of an estimate, not a measurement.
 */
export function thinkingAllowance(model: string, thinkingOff: boolean): number {
  if (model.startsWith('claude-opus')) return 250
  return thinkingOff ? 0 : 150
}

/** USD for one look, from its parts. */
export function lookCost(model: string, parts: { readonly imageTokens: number; readonly textTokens?: number; readonly cachedTokens: number; readonly outputTokens?: number }): number {
  return costOf(model, { input_tokens: parts.imageTokens + (parts.textTokens ?? 150), cache_read_input_tokens: parts.cachedTokens, output_tokens: parts.outputTokens ?? LOOK_OUTPUT_TOKENS })
}

/**
 * Cached system-prompt tokens per frame: the pack's screen and HUD notes, plus
 * the roster of unit and building names for strategy packs, or the arsenal and
 * the current map's callouts for shooters (about four characters per token).
 */
export function cachedPromptTokens(pack: { readonly knowledge: { readonly roster?: unknown; readonly arsenal?: readonly unknown[]; readonly maps?: readonly unknown[] } }): number {
  const k = pack.knowledge
  if (k.roster) return 15_000
  // An arsenal and the current map's callouts: about 25,000 characters with the screen notes.
  if (k.arsenal?.length || k.maps?.length) return 8_000
  return 5_000
}

/** Rough USD per hour of play at a sampling interval, with the system prompt read from the cache (thinking not included). */
export function estimatePerHour(model: string, intervalMs: number, imageWidth: number, imageHeight: number, cachedTokens = 5_000): number {
  return lookCost(model, { imageTokens: imageTokens(imageWidth, imageHeight), cachedTokens }) * (3_600_000 / intervalMs)
}

/**
 * An hour of screen reading as a range: the low end without thinking, the
 * high end with the thinking allowance; every interval sends a look (in
 * menus and pauses fewer are sent).
 */
export function estimateRange(
  model: string,
  opts: { readonly intervalMs: number; readonly width: number; readonly height: number; readonly cachedTokens: number; readonly thinkingOff: boolean },
): { readonly low: number; readonly high: number; readonly perLook: number; readonly looksPerHour: number } {
  const looksPerHour = 3_600_000 / opts.intervalMs
  const img = imageTokens(opts.width, opts.height)
  const perLook = lookCost(model, { imageTokens: img, cachedTokens: opts.cachedTokens })
  const withThinking = lookCost(model, { imageTokens: img, cachedTokens: opts.cachedTokens, outputTokens: LOOK_OUTPUT_TOKENS + thinkingAllowance(model, opts.thinkingOff) })
  return { low: perLook * looksPerHour, high: withThinking * looksPerHour, perLook, looksPerHour }
}
