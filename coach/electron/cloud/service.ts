// The only code in Sightline that talks to the network. It runs in the
// Electron main process, holds the player's API key (never sent to the
// renderer), refuses to do anything unless the player switched cloud features
// on, enforces a per-session spend cap, and talks only to api.anthropic.com
// (the base URL is fixed so an environment variable cannot redirect it).
//
// Three uses:
//   - describe a sampled frame into the pack's schema (vision recogniser);
//   - answer a player's question from a structured digest of the session;
//   - research a game with web search, producing a cited, dated brief.

import Anthropic from '@anthropic-ai/sdk'
import { betaZodOutputFormat } from '@anthropic-ai/sdk/helpers/beta/zod'
import { z } from 'zod/v4'
import { costOf, modelPrice, type Usage } from '../../src/core/vision/cost.ts'
import { PHASES, visionSystemPrompt, visionUserText, type VisionResponse } from '../../src/core/vision/contract.ts'
import type { GameProfile } from '../../src/core/profile/schema.ts'
import type { CloudResult, CloudSettings, ResearchBrief } from '../../shared/cloud.ts'

export { DEFAULT_CLOUD } from '../../shared/cloud.ts'
export type { CloudResult, CloudSettings, ResearchBrief }

export const ANTHROPIC_BASE_URL = 'https://api.anthropic.com'

/** The subset of the SDK client this service uses (a fake can be injected in tests). */
export type CloudClient = Pick<Anthropic, 'beta'>

export class CloudService {
  private readonly getKey: () => string | null
  private readonly getSettings: () => CloudSettings
  private readonly makeClient: (key: string) => CloudClient
  private client: { key: string; client: CloudClient } | null = null
  private spentUsd = 0
  private calls = 0

  constructor(
    getKey: () => string | null,
    getSettings: () => CloudSettings,
    makeClient: (key: string) => CloudClient = (apiKey) => new Anthropic({ apiKey, baseURL: ANTHROPIC_BASE_URL, maxRetries: 1, timeout: 30_000 }),
  ) {
    this.getKey = getKey
    this.getSettings = getSettings
    this.makeClient = makeClient
  }

  get spend(): { usd: number; calls: number; capUsd: number } {
    return { usd: this.spentUsd, calls: this.calls, capUsd: this.getSettings().sessionCapUsd }
  }

  resetSession(): void {
    this.spentUsd = 0
    this.calls = 0
  }

  async analyzeFrame(
    profile: GameProfile,
    image: { readonly base64: string; readonly mediaType: 'image/jpeg' },
    context: Readonly<Record<string, string | number>>,
  ): Promise<CloudResult<{ response: VisionResponse }>> {
    const ready = this.ready()
    if (!ready.ok) return ready
    const model = this.getSettings().visionModel
    try {
      const msg = await ready.client.beta.messages.parse({
        model,
        max_tokens: 4000,
        ...fallbackParams(model),
        output_config: { effort: 'low', format: betaZodOutputFormat(visionSchema(profile)) },
        system: [{ type: 'text', text: visionSystemPrompt(profile), cache_control: { type: 'ephemeral' } }],
        messages: [
          {
            role: 'user',
            content: [
              { type: 'image', source: { type: 'base64', media_type: image.mediaType, data: image.base64 } },
              { type: 'text', text: visionUserText(profile, context) },
            ],
          },
        ],
      })
      const costUsd = this.charge(model, msg.usage)
      if (msg.stop_reason === 'refusal') return { ok: false, error: 'The model declined to describe this frame.' }
      if (!msg.parsed_output) return { ok: false, error: 'The model response did not match the schema.' }
      return { ok: true, costUsd, response: msg.parsed_output }
    } catch (err) {
      return describeError(err)
    }
  }

  /** Answer a player's question from a structured session digest. */
  async ask(profile: GameProfile, question: string, digest: string): Promise<CloudResult<{ text: string }>> {
    const ready = this.ready()
    if (!ready.ok) return ready
    const model = this.getSettings().answerModel
    try {
      const msg = await ready.client.beta.messages.create({
        model,
        max_tokens: 16_000,
        ...fallbackParams(model),
        output_config: { effort: 'medium' },
        system: [{ type: 'text', text: answerSystemPrompt(profile), cache_control: { type: 'ephemeral' } }],
        messages: [{ role: 'user', content: `Session data (JSON, from what was visible on screen):\n${digest}\n\nPlayer's question: ${question.slice(0, 1000)}` }],
      })
      const costUsd = this.charge(model, msg.usage)
      if (msg.stop_reason === 'refusal') return { ok: false, error: 'The model declined to answer that question.' }
      const text = msg.content.flatMap((b) => (b.type === 'text' ? [b.text] : [])).join(' ').trim()
      return text ? { ok: true, costUsd, text } : { ok: false, error: 'Empty answer.' }
    } catch (err) {
      return describeError(err)
    }
  }

  /** Research a game on the web: fundamentals, common mistakes, what good looks like, dated patch notes, with sources. */
  async research(gameTitle: string, focus: string): Promise<CloudResult<{ brief: ResearchBrief }>> {
    const ready = this.ready()
    if (!ready.ok) return ready
    const model = this.getSettings().answerModel
    if (model.startsWith('claude-haiku')) return { ok: false, error: 'Research needs Opus or Sonnet (web search with filtering).', fatal: true }
    const messages: Anthropic.Beta.BetaMessageParam[] = [
      {
        role: 'user',
        content:
          `Research ${gameTitle.slice(0, 120)} for a player who wants coaching${focus ? ` (their focus: ${focus.slice(0, 300)})` : ''}. ` +
          'Write a brief of at most 450 words with four headed sections: Fundamentals that rarely change; Common mistakes and how to spot them on screen; What good play looks like; Current patch or meta notes (each with the date or version it applies to). ' +
          'Prefer official sources, established guides and respected players. Say when sources disagree. Do not include exploits or anything against the game\'s rules.',
      },
    ]
    try {
      let costUsd = 0
      const sources = new Map<string, string>()
      const texts: string[] = []
      for (let turn = 0; turn < 4; turn++) {
        const msg = await ready.client.beta.messages.create({
          model,
          max_tokens: 16_000,
          ...fallbackParams(model),
          output_config: { effort: 'medium' },
          tools: [{ type: 'web_search_20260209', name: 'web_search', max_uses: 6 }],
          messages,
        })
        costUsd += this.charge(model, msg.usage)
        for (const block of msg.content) {
          if (block.type === 'text') texts.push(block.text)
          if (block.type === 'web_search_tool_result' && Array.isArray(block.content)) {
            for (const r of block.content) if (r.type === 'web_search_result') sources.set(r.url, r.title)
          }
        }
        if (msg.stop_reason === 'refusal') return { ok: false, error: 'The model declined this research request.' }
        if (msg.stop_reason !== 'pause_turn') break
        messages.push({ role: 'assistant', content: msg.content })
      }
      const text = texts.join('').trim()
      if (!text) return { ok: false, error: 'No research text came back.' }
      return {
        ok: true,
        costUsd,
        brief: { gameTitle, text, sources: [...sources].slice(0, 20).map(([url, title]) => ({ url, title })), createdAt: new Date().toISOString(), model },
      }
    } catch (err) {
      return describeError(err)
    }
  }

  private ready(): { ok: true; client: CloudClient } | { ok: false; error: string; fatal: true } {
    const s = this.getSettings()
    if (!s.enabled) return { ok: false, error: 'Cloud features are switched off.', fatal: true }
    if (this.spentUsd >= s.sessionCapUsd) {
      return { ok: false, error: `Session spend cap of $${s.sessionCapUsd.toFixed(2)} reached; cloud calls are paused until the next session.`, fatal: true }
    }
    const key = this.getKey()
    if (!key) return { ok: false, error: 'No API key is set.', fatal: true }
    if (this.client?.key !== key) this.client = { key, client: this.makeClient(key) }
    return { ok: true, client: this.client.client }
  }

  private charge(model: string, usage: Usage): number {
    const c = costOf(model, usage)
    this.spentUsd += c
    this.calls++
    return c
  }
}

/** Server-side refusal fallback where the model supports it (not Haiku). */
function fallbackParams(model: string): { betas?: Anthropic.Beta.AnthropicBeta[]; fallbacks?: 'default' } {
  return modelPrice(model).fallbacks ? { betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' } : {}
}

/** The structured-output schema for one pack: enums where the pack defines them. */
export function visionSchema(profile: GameProfile) {
  const v = profile.vision!
  const keys = v.state.map((s) => s.key)
  const types = v.events.map((e) => e.type)
  const cells = [...new Set((profile.zones ?? []).flatMap((z) => z.cells.map((c) => c.id)))]
  const attrKeys = [...new Set(v.events.flatMap((e) => (e.attributes ?? []).map((a) => a.key)))]
  const oneOf = (list: string[]) => (list.length ? z.enum(list as [string, ...string[]]) : z.string())
  return z.object({
    phase: z.enum(PHASES as [string, ...string[]]),
    phaseConfidence: z.number(),
    visibleOutcome: z.enum(['success', 'failure', 'unknown']).nullable(),
    visibleHealth: z.number().nullable(),
    state: z.array(z.object({ key: oneOf(keys), value: z.string(), confidence: z.number() })),
    events: z.array(
      z.object({
        type: oneOf(types),
        zone: oneOf(cells).nullable(),
        attributes: z.array(z.object({ key: oneOf(attrKeys), value: z.string() })),
        confidence: z.number(),
        evidence: z.string(),
      }),
    ),
    unreadable: z.string(),
  })
}

function answerSystemPrompt(profile: GameProfile): string {
  const concepts = profile.knowledge.concepts.map((c) => `- ${c.title}: ${c.summary}`).join('\n')
  return [
    `You are Sightline, a calm gameplay coach for ${profile.game.title}. You answer the player's questions out loud, so use plain spoken sentences, no lists or markdown, at most 120 words.`,
    'You only know what the session data shows: events read from the player\'s screen, counts, zones (approximate), mistakes, graded patterns, match statistics, the player\'s own notes, and an optional research brief.',
    'Rules: separate what you saw this session ("In this session I saw…") from general advice ("Generally…"). Quote counts exactly as given. Never invent statistics, positions or opponent information. Patterns are associations, never causes: do not say "because" or "caused". If the data cannot answer the question, say so briefly and suggest what to watch next time.',
    profile.knowledge.prohibitedAdvice.length ? `Never give: ${profile.knowledge.prohibitedAdvice.join('; ')}.` : '',
    concepts ? `Coaching concepts for this game (general knowledge, not observations):\n${concepts}` : '',
  ]
    .filter(Boolean)
    .join('\n\n')
}

function describeError(err: unknown): { ok: false; error: string; retryAfterMs?: number; fatal?: boolean } {
  if (err instanceof Anthropic.AuthenticationError) return { ok: false, error: 'The API key was rejected.', fatal: true }
  if (err instanceof Anthropic.PermissionDeniedError) return { ok: false, error: 'This API key cannot use that model.', fatal: true }
  if (err instanceof Anthropic.RateLimitError) {
    const header = err.headers?.get('retry-after')
    const seconds = header ? Number(header) : NaN
    return { ok: false, error: 'Rate limited by the API.', retryAfterMs: Number.isFinite(seconds) ? seconds * 1000 : 30_000 }
  }
  if (err instanceof Anthropic.BadRequestError) return { ok: false, error: `The API rejected the request: ${err.message}` }
  if (err instanceof Anthropic.APIConnectionError) return { ok: false, error: 'Could not reach the API (network).' }
  if (err instanceof Anthropic.APIError) return { ok: false, error: `API error ${err.status ?? ''}: ${err.message}` }
  return { ok: false, error: err instanceof Error ? err.message : String(err) }
}
