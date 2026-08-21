// ---------------------------------------------------------------------------
// Reading a team sheet from a photograph.
//
// Confirmed line-ups are the one input this model cannot get: they are
// published about an hour before kickoff, long after the forecast is written.
// A reader looking at a broadcast graphic on their television has information
// the pipeline does not, and this is the shortest path from their screen into
// the model.
//
// Two decisions matter for trustworthiness. Claude is asked only to *read* the
// names it can see — no guessing at unreadable text, no inferring a likely XI —
// and the result is returned through a strict tool schema, so the shape is
// guaranteed rather than parsed hopefully out of prose. Turning those names
// into players is done afterwards by code that can be tested and that reports
// what it could not resolve (see core/lineupMatch.ts).
// ---------------------------------------------------------------------------

import { getApiKey, getModel } from './client.ts'
import type { MatchCandidate } from '../core/lineupMatch.ts'

/** Anthropic accepts these; anything else has to be converted first. */
const SUPPORTED = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'] as const

/** Long edge Anthropic recommends; larger costs tokens without helping. */
const MAX_EDGE = 1568

export interface ReadLineupsRequest {
  file: File | Blob
  homeName: string
  awayName: string
  homeSquad: readonly MatchCandidate[]
  awaySquad: readonly MatchCandidate[]
  signal?: AbortSignal
}

export interface ReadLineupsResult {
  /** Names exactly as printed, in the order read. */
  homeStarters: string[]
  awayStarters: string[]
  /** Whether the image was legible enough to trust. */
  confidence: 'high' | 'medium' | 'low'
  /** Anything Claude wants to flag — glare, a cropped side, a substitutes list. */
  notes: string
  /** True when it could not tell which side was which. */
  sidesUncertain: boolean
}

/**
 * Shrink and re-encode before upload.
 *
 * A phone photograph is routinely 4000px and several megabytes; sending it
 * whole is slow, costs tokens and gains nothing, and can exceed the request
 * limit outright.
 */
async function prepareImage(file: File | Blob): Promise<{ data: string; mediaType: string }> {
  const bitmap = await createImageBitmap(file)
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Could not read that image in this browser.')
  ctx.drawImage(bitmap, 0, 0, width, height)
  bitmap.close?.()

  // JPEG keeps a screenshot of a team sheet perfectly legible at a fraction
  // of the size.
  const dataUrl = canvas.toDataURL('image/jpeg', 0.9)
  const comma = dataUrl.indexOf(',')
  return { data: dataUrl.slice(comma + 1), mediaType: 'image/jpeg' }
}

function squadList(squad: readonly MatchCandidate[]): string {
  return squad
    .slice(0, 40)
    .map((p) => `${p.fullName || p.name} (${p.position})`)
    .join(', ')
}

const SYSTEM = `You read association football team sheets from images.

You will be given a picture — a television graphic, a screenshot, a photograph of a programme —
and the two squads involved. Report the starting eleven printed for each side.

Rules, in order of importance:
- Report only names you can actually read in the image. Never infer a likely line-up, never fill a
  side out to eleven from your own knowledge of the clubs, and never carry a name over from what
  you expect. An incomplete honest reading is far more useful than a plausible invented one.
- Give each name as printed, including any initial. Do not expand, translate or correct spelling.
- Starting elevens only. If substitutes are shown, leave them out and say so in your notes.
- The squad lists are given only to help you resolve difficult spelling. A name that is not in
  them may still be correct — report what you see.
- If you cannot tell which side is which, set sidesUncertain and make your best assignment.
- Set confidence low if the image is blurred, cropped, partially obscured or you are unsure of
  several names.`

const TOOL = {
  name: 'report_lineups',
  description: 'Report the starting elevens read from the image.',
  strict: true,
  input_schema: {
    type: 'object' as const,
    properties: {
      homeStarters: {
        type: 'array',
        items: { type: 'string' },
        description: 'Names printed for the home side, exactly as shown.',
      },
      awayStarters: {
        type: 'array',
        items: { type: 'string' },
        description: 'Names printed for the away side, exactly as shown.',
      },
      confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
      sidesUncertain: {
        type: 'boolean',
        description: 'True if it was not clear which eleven belongs to which club.',
      },
      notes: {
        type: 'string',
        description: 'Anything the reader should know: glare, a cropped side, substitutes shown.',
      },
    },
    required: ['homeStarters', 'awayStarters', 'confidence', 'sidesUncertain', 'notes'],
    additionalProperties: false,
  },
}

/** Read both starting elevens out of an image. */
export async function readLineupsFromImage(req: ReadLineupsRequest): Promise<ReadLineupsResult> {
  const apiKey = getApiKey()
  if (!apiKey) throw new Error('NO_KEY')

  const type = (req.file as File).type || ''
  if (type && !SUPPORTED.includes(type as (typeof SUPPORTED)[number]) && !type.startsWith('image/')) {
    throw new Error('That file is not an image.')
  }

  const { data, mediaType } = await prepareImage(req.file)

  const { default: Anthropic } = await import('@anthropic-ai/sdk')
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true })

  const model = getModel()
  const thinkingCapable = model !== 'claude-haiku-4-5'

  // `strict` guarantees the returned input validates against the schema, and
  // forcing the tool guarantees there is one. That pairing could not be
  // exercised from the development sandbox, whose egress policy blocks the
  // API, so a rejection of `strict` is treated as recoverable rather than
  // fatal: the request is retried without it and the defensive parsing below
  // carries the load. Losing a guarantee is better than losing the feature.
  const send = (strict: boolean) =>
    client.messages.create(
      {
        model,
        max_tokens: 2000,
        system: SYSTEM,
        tools: [strict ? TOOL : { ...TOOL, strict: undefined }],
        // Force the tool so the answer always arrives structured.
        tool_choice: { type: 'tool', name: 'report_lineups' },
        ...(thinkingCapable
          ? { thinking: { type: 'adaptive' as const }, output_config: { effort: 'medium' as const } }
          : {}),
        messages: [
          {
            role: 'user',
            content: [
              { type: 'image', source: { type: 'base64', media_type: mediaType as 'image/jpeg', data } },
              {
                type: 'text',
                text:
                  `Match: ${req.homeName} (home) versus ${req.awayName} (away).\n\n` +
                  `${req.homeName} squad: ${squadList(req.homeSquad)}\n\n` +
                  `${req.awayName} squad: ${squadList(req.awaySquad)}\n\n` +
                  `Read the starting elevens from the image.`,
              },
            ],
          },
        ],
      },
      { signal: req.signal },
    )

  let response
  try {
    response = await send(true)
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    // Only a schema/parameter rejection is worth retrying; an auth or rate
    // problem would fail again identically.
    if (/400|strict|schema|invalid_request/i.test(message)) {
      response = await send(false)
    } else {
      throw err
    }
  }

  if (response.stop_reason === 'refusal') throw new Error('REFUSED')

  const call = response.content.find((b) => b.type === 'tool_use')
  if (!call || call.type !== 'tool_use') {
    throw new Error('Claude did not return a line-up from that image.')
  }

  // Strict tool use guarantees the shape, but this is data crossing a network
  // boundary and the cost of trusting it blindly is a broken page.
  const raw = call.input as Partial<ReadLineupsResult>
  const asNames = (v: unknown): string[] =>
    Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim() !== '') : []

  return {
    homeStarters: asNames(raw.homeStarters),
    awayStarters: asNames(raw.awayStarters),
    confidence: raw.confidence === 'high' || raw.confidence === 'low' ? raw.confidence : 'medium',
    sidesUncertain: raw.sidesUncertain === true,
    notes: typeof raw.notes === 'string' ? raw.notes : '',
  }
}
