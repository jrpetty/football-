// The only code in Sightline that talks to the network. It runs in the
// Electron main process, holds the player's API key (never sent to the
// renderer), refuses to do anything unless the player switched cloud features
// on, enforces a per-session spend cap, and talks only to api.anthropic.com
// (the base URL is fixed so an environment variable cannot redirect it).
//
// Uses:
//   - describe a sampled frame into the pack's schema (vision recogniser),
//     including what screen it is, so new menus are understood and remembered;
//   - answer a player's question from a structured digest of the session,
//     optionally with the current screen (the player asked about it);
//   - review a moment the player marked (screen + note), after the match;
//   - research a game with web search: a cited brief, and a large structured
//     knowledge base (modes, maps, screens, HUD, roster, mechanics…).

import Anthropic from '@anthropic-ai/sdk'
import { betaZodOutputFormat } from '@anthropic-ai/sdk/helpers/beta/zod'
import { z } from 'zod/v4'
import { costOf, modelPrice, type Usage } from '../../src/core/vision/cost.ts'
import { PHASES, visionSystemPrompt, visionUserText, type PromptExtras, type VisionResponse } from '../../src/core/vision/contract.ts'
import { SCREEN_KINDS } from '../../src/core/vision/screens.ts'
import { cleanKnowledge, KNOWLEDGE_SECTIONS, playbookText, type KnowledgeBase, type KnowledgeSectionId } from '../../src/core/knowledge/base.ts'

/** Prompt budget for the pack's coaching playbook in answers and reviews. */
const PLAYBOOK_CHARS = 32_000
import type { GameProfile } from '../../src/core/profile/schema.ts'
import type { CloudResult, CloudSettings, ResearchBrief } from '../../shared/cloud.ts'

export { DEFAULT_CLOUD } from '../../shared/cloud.ts'
export type { CloudResult, CloudSettings, ResearchBrief }

export const ANTHROPIC_BASE_URL = 'https://api.anthropic.com'

/** The subset of the SDK client this service uses (a fake can be injected in tests). */
export type CloudClient = Pick<Anthropic, 'beta' | 'models'>

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

  /**
   * Check the key with a free call (listing models), whether or not cloud
   * features are switched on. Returns the model ids the key can use.
   */
  async testKey(): Promise<{ ok: true; models: string[] } | { ok: false; error: string }> {
    const key = this.getKey()
    if (!key) return { ok: false, error: 'No API key is set.' }
    try {
      const page = await this.makeClient(key).models.list({ limit: 100 })
      return { ok: true, models: page.data.map((m) => m.id) }
    } catch (err) {
      const e = describeError(err)
      return { ok: false, error: e.error }
    }
  }

  resetSession(): void {
    this.spentUsd = 0
    this.calls = 0
  }

  async analyzeFrame(
    profile: GameProfile,
    image: { readonly base64: string; readonly mediaType: 'image/jpeg' },
    context: Readonly<Record<string, string | number>>,
    extras: PromptExtras = {},
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
        system: [{ type: 'text', text: visionSystemPrompt(profile, extras), cache_control: { type: 'ephemeral' } }],
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

  /**
   * Answer a player's question from a structured session digest — and, when
   * the player asked about what is on screen right now (or reviews a marked
   * moment), from that screen too.
   */
  async ask(
    profile: GameProfile,
    question: string,
    digest: string,
    opts: { image?: { readonly base64: string }; knowledge?: string; review?: boolean } = {},
  ): Promise<CloudResult<{ text: string }>> {
    const ready = this.ready()
    if (!ready.ok) return ready
    const model = this.getSettings().answerModel
    const prompt =
      `Session data (JSON, from what was visible on screen):\n${digest}\n\n` +
      (opts.image ? (opts.review ? 'The image is the screen at a moment the player marked during play.\n' : 'The image is the player\'s screen right now.\n') : '') +
      (opts.review ? `Review this marked moment. ${question.slice(0, 1000)}` : `Player's question: ${question.slice(0, 1000)}`)
    try {
      const msg = await ready.client.beta.messages.create({
        model,
        max_tokens: 16_000,
        ...fallbackParams(model),
        output_config: { effort: 'medium' },
        system: [{ type: 'text', text: answerSystemPrompt(profile, opts.knowledge ?? '', !!opts.review), cache_control: { type: 'ephemeral' } }],
        messages: [
          {
            role: 'user',
            content: opts.image
              ? [
                  { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: opts.image.base64 } },
                  { type: 'text', text: prompt },
                ]
              : prompt,
          },
        ],
      })
      const costUsd = this.charge(model, msg.usage)
      if (msg.stop_reason === 'refusal') return { ok: false, error: 'The model declined to answer that question.' }
      const text = msg.content.flatMap((b) => (b.type === 'text' ? [b.text] : [])).join(' ').trim()
      return text ? { ok: true, costUsd, text } : { ok: false, error: 'Empty answer.' }
    } catch (err) {
      return describeError(err)
    }
  }

  /**
   * A coach's review of the session from the digest and the pack's playbook:
   * headline, strengths, up to three things to work on (evidence, principle,
   * fix, drill). Structured output; the caller validates it again.
   */
  async reviewMatch(profile: GameProfile, digest: string, knowledge: string): Promise<CloudResult<{ review: unknown }>> {
    const ready = this.ready()
    if (!ready.ok) return ready
    const model = this.getSettings().answerModel
    try {
      const msg = await ready.client.beta.messages.parse({
        model,
        max_tokens: 16_000,
        ...fallbackParams(model),
        output_config: { effort: 'medium', format: betaZodOutputFormat(reviewSchema) },
        system: [{ type: 'text', text: answerSystemPrompt(profile, knowledge, true), cache_control: { type: 'ephemeral' } }],
        messages: [
          {
            role: 'user',
            content:
              `Session data (JSON, from what was visible on screen):\n${digest}\n\n` +
              'Write the coach\'s review of this session as structured output, not as speech. ' +
              'headline: one sentence naming the single most important thing, with its count. ' +
              'strengths: up to two things that went well, each with the count that shows it (empty if none). ' +
              'focus: up to three things to work on, most important first, only where the data shows them; evidence quotes the counts exactly; principle says what good players do instead; fix is one imperative sentence; drill is a short practice idea or null; playbook is the matching playbook entry id or null. ' +
              'nextMatch: one sentence to carry into the next match. Fight details read from killcams or replays are probable, not certain: say "likely" where it matters. No causal claims.',
          },
        ],
      })
      const costUsd = this.charge(model, msg.usage)
      if (msg.stop_reason === 'refusal') return { ok: false, error: 'The model declined to review this session.' }
      if (!msg.parsed_output) return { ok: false, error: 'The review did not match the expected shape.' }
      return { ok: true, costUsd, review: msg.parsed_output }
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

  /**
   * Build a large, structured knowledge base for a game: research with web
   * search and fetch (current version, modes, maps, menus, HUD, roster,
   * mechanics, strategy, mistakes), then convert the notes into sections in a
   * few structured calls. Cited and dated. One-off cost per game.
   */
  async buildKnowledge(
    profile: GameProfile,
    focus: string,
    onProgress: (stage: string) => void = () => {},
  ): Promise<CloudResult<{ knowledge: KnowledgeBase }>> {
    const ready = this.ready()
    if (!ready.ok) return ready
    const model = this.getSettings().answerModel
    if (model.startsWith('claude-haiku')) return { ok: false, error: 'Building game knowledge needs Opus or Sonnet (web search with filtering).', fatal: true }
    const title = profile.game.title
    let costUsd = 0
    const sources = new Map<string, string>()
    try {
      // 1. Research.
      onProgress('Researching on the web')
      const messages: Anthropic.Beta.BetaMessageParam[] = [{ role: 'user', content: knowledgeResearchPrompt(profile, focus) }]
      const notes: string[] = []
      for (let turn = 0; turn < 8; turn++) {
        const msg = await ready.client.beta.messages.create({
          model,
          max_tokens: 20_000,
          ...fallbackParams(model),
          output_config: { effort: 'medium' },
          tools: [
            { type: 'web_search_20260209', name: 'web_search', max_uses: 20 },
            { type: 'web_fetch_20260209', name: 'web_fetch', max_uses: 12 },
          ],
          messages,
        })
        costUsd += this.charge(model, msg.usage)
        for (const block of msg.content) {
          if (block.type === 'text') notes.push(block.text)
          if (block.type === 'web_search_tool_result' && Array.isArray(block.content)) {
            for (const r of block.content) if (r.type === 'web_search_result') sources.set(r.url, r.title)
          }
        }
        if (msg.stop_reason === 'refusal') return { ok: false, error: 'The model declined this research request.' }
        if (msg.stop_reason !== 'pause_turn') break
        messages.push({ role: 'assistant', content: msg.content })
        onProgress(`Researching on the web (${sources.size} sources so far)`)
      }
      const research = notes.join('').trim()
      if (research.length < 500) return { ok: false, error: 'The research came back too thin to build a knowledge base.' }

      // 2. Structure, a few sections at a time (the notes are cached between calls).
      const groups: KnowledgeSectionId[][] = [['overview', 'modes', 'maps'], ['screens', 'hud'], ['roster'], ['mechanics', 'strategy', 'mistakes', 'glossary']]
      const sections: unknown[] = []
      let gameVersion = 'unknown'
      for (const [i, group] of groups.entries()) {
        onProgress(`Organising knowledge (${i + 1}/${groups.length})`)
        const msg = await ready.client.beta.messages.parse({
          model,
          max_tokens: 16_000,
          ...fallbackParams(model),
          output_config: { effort: 'low', format: betaZodOutputFormat(knowledgeSchema(group)) },
          system: [
            {
              type: 'text',
              text: `You turn research notes about ${title} into a reference for a gameplay coach that reads the player's screen. Use only facts in the notes; keep each detail to one or two short sentences; mark uncertain facts with "(unverified)". Never include cheats, exploits or anything against the game's rules.\n\nResearch notes:\n${research.slice(0, 120_000)}`,
              cache_control: { type: 'ephemeral' },
            },
          ],
          messages: [{ role: 'user', content: `Fill these sections as completely as the notes allow: ${group.map((g) => `${g} (${KNOWLEDGE_SECTIONS.find((s) => s.id === g)!.title})`).join('; ')}.` }],
        })
        costUsd += this.charge(model, msg.usage)
        if (msg.parsed_output) {
          sections.push(...msg.parsed_output.sections)
          if (msg.parsed_output.gameVersion && gameVersion === 'unknown') gameVersion = msg.parsed_output.gameVersion
        }
      }
      const knowledge = cleanKnowledge(
        { gameVersion, sections },
        { packId: profile.id, gameTitle: title, model, createdAt: new Date().toISOString(), sources: [...sources].map(([url, t]) => ({ url, title: t })) },
      )
      if (!knowledge.sections.length) return { ok: false, error: 'No knowledge could be structured from the research.' }
      return { ok: true, costUsd, knowledge }
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
    screen: z.object({ name: z.string(), kind: z.enum(SCREEN_KINDS as unknown as [string, ...string[]]), summary: z.string() }).nullable(),
  })
}

const reviewSchema = z.object({
  headline: z.string(),
  strengths: z.array(z.string()),
  focus: z.array(
    z.object({
      evidence: z.string(),
      principle: z.string(),
      fix: z.string(),
      drill: z.string().nullable(),
      playbook: z.string().nullable(),
    }),
  ),
  nextMatch: z.string(),
})

function knowledgeSchema(group: readonly KnowledgeSectionId[]) {
  return z.object({
    gameVersion: z.string(),
    sections: z.array(z.object({ id: z.enum(group as unknown as [string, ...string[]]), items: z.array(z.object({ name: z.string(), detail: z.string() })) })),
  })
}

function knowledgeResearchPrompt(profile: GameProfile, focus: string): string {
  const modes = profile.modes.map((m) => m.label).join('; ')
  return [
    `Research ${profile.game.title} thoroughly for a gameplay coach app that watches the player's screen and coaches them. Today's date matters: find the CURRENT version, season and patch, and prefer the newest information.`,
    'Write detailed notes (as long as needed, aim for thoroughness) covering:',
    '1. Overview: current version/season/patch and recent major changes, with dates.',
    `2. Modes (the app knows these: ${modes}) and how each plays, including ranked rules.`,
    '3. Maps: names of current maps/map pool and what is distinctive about each.',
    '4. Menus and screens: main menu structure, loadout/build screens, lobby, loading, scoreboard, end-of-match/summary screens — what each looks like and what information it shows.',
    '5. HUD: where each element sits on screen (minimap, score, timer, health, ammo, resources, population, killfeed…) and any settings that move it.',
    '6. Roster: every weapon/unit/civilisation/class/equipment/streak etc. with its type and role, as complete a list as you can verify.',
    '7. Mechanics and key numbers (costs, timings, caps, damage models) that matter for coaching.',
    '8. Strategy: fundamentals and what good play looks like at different skill levels.',
    '9. Common mistakes and how each one can be spotted on screen.',
    '10. Glossary of community terms.',
    focus ? `The player especially wants: ${focus.slice(0, 300)}.` : '',
    'Use official sources (publisher/developer sites, patch notes) first, then established wikis and respected guides. Note when sources disagree and mark anything uncertain. Do not include cheats, exploits or anything against the game\'s rules.',
  ]
    .filter(Boolean)
    .join('\n')
}

function answerSystemPrompt(profile: GameProfile, knowledge: string, review: boolean): string {
  const concepts = profile.knowledge.concepts.map((c) => `- ${c.title}: ${c.summary}`).join('\n')
  return [
    `You are Sightline, a calm, encouraging gameplay coach for ${profile.game.title}. You answer the player out loud, so use plain spoken sentences, no lists or markdown, at most ${review ? 150 : 120} words.`,
    review
      ? 'For a marked moment: say briefly what was happening on screen, what decision the player appears to be making, whether it looks sound, one better option if there is one, and what to watch for next time. Base it only on what is visible and in the data; say so when the screen cannot tell.'
      : 'If an image of the screen is attached, the player is asking about it: read it carefully (menus, HUD, numbers, the situation) and answer from it. Tell the player what to do when they ask, as clear, practical advice.',
    'You only know what the session data shows: events read from the player\'s screen, counts, zones (approximate), fight circumstances read from killcams or replays (probable, not certain; untagged ones are counted, not guessed), benchmarks against the clock, mistakes, graded patterns, match statistics, the player\'s own notes, and an optional research brief.',
    'When the data shows a weakness, coach it with the matching playbook entry: what good players do instead, the fix, and the drill if there is one. Prefer the playbook over generic tips, and do not present playbook knowledge as something you saw.',
    'Rules: separate what you saw this session ("In this session I saw…") from general advice ("Generally…"). Quote counts exactly as given. Never invent statistics, positions or opponent information. Patterns are associations, never causes: do not say "because" or "caused". If the data cannot answer the question, say so briefly and suggest what to watch next time.',
    profile.knowledge.prohibitedAdvice.length ? `Never give: ${profile.knowledge.prohibitedAdvice.join('; ')}.` : '',
    concepts ? `Coaching concepts for this game (general knowledge, not observations):\n${concepts}` : '',
    profile.knowledge.playbook?.length
      ? `Coaching playbook: how good players and coaches judge play in this game, with fixes (general knowledge from pro, coach and guide sources; not observations):\n${playbookText(profile.knowledge.playbook, PLAYBOOK_CHARS)}`
      : '',
    knowledge ? `Game reference (background knowledge, may be out of date; not observations):\n${knowledge}` : '',
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
