// ---------------------------------------------------------------------------
// Reading a receipt with Claude.
//
// This is the hosted half of the OCR decision (see the README for the full
// comparison). A till roll is a hard case for a conventional scanner —
// thermal, faded, curled, photographed at an angle by someone holding a torch
// — and the thing that actually makes it work is not better character
// recognition but *understanding*: knowing that GROSS TOTAL is the figure and
// SUBTOTAL is not, on a layout that differs for every till in every pub.
//
// Two decisions matter for trust, both borrowed from how the predictor reads a
// team sheet. Claude is asked only to read what is printed — never to add the
// line items up into a total that does not appear on the paper — and the answer
// comes back through a strict tool schema, so the shape is guaranteed rather
// than parsed hopefully out of prose. The figure is then run through the same
// tested money parser everything else uses.
//
// The key is her own, kept in her browser and sent straight to Anthropic. There
// is no server in between, which is why the SDK needs `dangerouslyAllowBrowser`.
// That is the right trade for a single-user app on a static host; it would not
// be for a shared deployment, where this belongs behind a backend. That is
// noted again in the README as the first thing to change for the multi-pub
// version.
// ---------------------------------------------------------------------------

import { parsePence } from '../core/money.ts'
import type { ScanRequest, ScanResult } from './types.ts'
import type { TotalCandidate } from './extractTotal.ts'
import { prepareForVision } from './image.ts'
import { loadSettings, supportsEffort } from '../storage/settings.ts'

const SYSTEM = `You read the total off a photograph of a British pub's paper receipt.

You will be given one image: either an end-of-day till roll (a "Z read") or an end-of-day
report from a card terminal. Report the single figure that represents the session total.

Rules, in order of importance:
- Report only a figure that is actually printed on the paper. Never add the line items up
  yourself, never work a total out from the parts, and never carry over a number from what you
  would expect a pub to take. If no total is printed, say you did not find one.
- Give the figure exactly as printed — same digits, same decimal point, no reformatting, no
  currency symbol added or removed, no thousands separators invented.
- On a till roll the figure wanted is the gross session total: GROSS TOTAL, GRAND TOTAL, TOTAL
  TAKINGS or similar. It is NOT the subtotal, NOT the net-of-VAT figure, NOT the VAT itself,
  and NOT the cash or card split printed underneath.
- On a card terminal report the figure wanted is the total value of sales for the day. It is
  NOT the refunds line, NOT a contactless subtotal, and NOT the terminal or merchant ID.
- Thermal paper fades and curls. If a digit is genuinely ambiguous, set confidence low and say
  which digit in your notes. Do not quietly pick the more likely one.
- List the other labelled amounts you can see in otherAmounts, so she can pick a different one
  if the wrong line was chosen. Same rule: only what is printed.
- If the photograph is too dark, too blurred, cut off, or shows something that is not a
  receipt, set found to false and explain in notes. An honest failure is far more useful than
  a plausible invented number — she will be typing this into her books.`

const TOOL = {
  name: 'report_total',
  description: 'Report the session total read from the receipt image.',
  input_schema: {
    type: 'object' as const,
    properties: {
      found: {
        type: 'boolean',
        description: 'True only if a session total is actually printed and legible.',
      },
      totalAsPrinted: {
        type: 'string',
        description: 'The figure exactly as printed, e.g. "4212.30" or "£4,212.30". Empty if not found.',
      },
      labelAsPrinted: {
        type: 'string',
        description: 'The wording printed next to it, e.g. "GROSS TOTAL". Empty if none.',
      },
      confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
      otherAmounts: {
        type: 'array',
        description: 'Other labelled amounts visible on the receipt.',
        items: {
          type: 'object',
          properties: {
            label: { type: 'string' },
            amount: { type: 'string' },
          },
          required: ['label', 'amount'],
        },
      },
      notes: {
        type: 'string',
        description: 'Anything she should know: glare, a torn roll, an ambiguous digit. May be empty.',
      },
    },
    required: ['found', 'totalAsPrinted', 'labelAsPrinted', 'confidence', 'otherAmounts', 'notes'],
  },
}

interface ToolInput {
  found: boolean
  totalAsPrinted: string
  labelAsPrinted: string
  confidence: 'high' | 'medium' | 'low'
  otherAmounts: Array<{ label: string; amount: string }>
  notes: string
}

/** Strict first: a person's transcription should not need digit repair. */
function readFigure(text: string): number | null {
  return parsePence(text) ?? parsePence(text, { loose: true })
}

export async function scanWithVision(req: ScanRequest): Promise<ScanResult> {
  const settings = loadSettings()
  const apiKey = settings.apiKey.trim()
  if (!apiKey) throw new Error('NO_KEY')

  const { data, mediaType } = await prepareForVision(req.file)

  // Imported on demand so a night spent typing the figures never downloads it.
  const { default: Anthropic } = await import('@anthropic-ai/sdk')
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true })

  const what = req.kind === 'till' ? 'an end-of-day till roll (Z read)' : 'an end-of-day card terminal report'

  const message = await client.messages.create(
    {
      model: settings.model,
      max_tokens: 1000,
      // Copying, not reasoning: low effort is both cheaper and quicker, and
      // thinking stays on, which keeps the model from writing a tool call into
      // its visible text instead of calling the tool.
      ...(supportsEffort(settings.model) ? { output_config: { effort: 'low' as const } } : {}),
      system: SYSTEM,
      tools: [TOOL],
      tool_choice: { type: 'tool', name: 'report_total' },
      messages: [
        {
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: mediaType as 'image/jpeg', data } },
            { type: 'text', text: `This is ${what}. Report the session total.` },
          ],
        },
      ],
    },
    { signal: req.signal },
  )

  const block = message.content.find((c) => c.type === 'tool_use')
  if (!block || block.type !== 'tool_use') throw new Error('NO_ANSWER')
  const input = block.input as ToolInput

  const candidates: TotalCandidate[] = []
  for (const other of input.otherAmounts ?? []) {
    const pence = readFigure(other.amount ?? '')
    if (pence === null) continue
    candidates.push({
      pence,
      label: other.label || 'amount',
      line: `${other.label} ${other.amount}`.trim(),
      score: 0,
      guessed: false,
    })
  }

  if (!input.found) {
    return {
      engine: 'vision',
      pence: null,
      confidence: 'low',
      candidates,
      notes: input.notes || 'No total could be read from that photograph.',
    }
  }

  const pence = readFigure(input.totalAsPrinted)
  if (pence === null) {
    // It read something the money parser will not accept. That disagreement is
    // itself information, so it goes in front of her rather than being forced.
    return {
      engine: 'vision',
      pence: null,
      confidence: 'low',
      candidates,
      notes:
        `Read "${input.totalAsPrinted}" next to "${input.labelAsPrinted}", ` +
        `which is not a valid amount. Please type it in.`,
    }
  }

  const label = input.labelAsPrinted.trim()
  candidates.unshift({
    pence,
    label: label || 'total',
    line: `${label} ${input.totalAsPrinted}`.trim(),
    score: 100,
    guessed: false,
  })

  return {
    engine: 'vision',
    pence,
    confidence: input.confidence ?? 'medium',
    candidates,
    notes: input.notes ?? '',
  }
}
