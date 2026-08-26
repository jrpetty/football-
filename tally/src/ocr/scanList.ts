// ---------------------------------------------------------------------------
// Reading the two bits of paper that are not the till roll.
//
// The price board on the wall, and the rota on the office door. Both are lists
// of "a name, and some figures beside it", both are otherwise a long evening of
// typing, and both follow the same rule as the till roll: Claude copies, tested
// code interprets, and nothing is written to the app until a person has looked
// at what it found.
//
// The confirmation step is not politeness. A misread price sits in the book
// reporting a loss every night until somebody questions it, and a misread rota
// puts the wrong person on the wrong night for ever. So both of these return a
// proposal, and the screens make applying it a deliberate act.
// ---------------------------------------------------------------------------

import { prepareForVision } from './image.ts'
import { loadSettings, effectiveEngine } from '../storage/settings.ts'

/** One line off the price board, as written. */
export interface ScannedPrice {
  name: string
  /** Pence. The model is asked for the figure as printed; this is parsed here. */
  pence: number
  note?: string
}

/** One line off a delivery note, as written. */
export interface ScannedDelivery {
  name: string
  /** How many of whatever unit the note lists — casks, cases, bottles. */
  quantity: number
  /** The unit as written: "kil", "firkin", "case", "bottles". May be empty. */
  unit: string
}

/** One line off the paper rota, as written. */
export interface ScannedShift {
  name: string
  /** The day as written — "Mon", "Monday", "Fri 29". Resolved to a date later. */
  day: string
  /** "18:00", or empty when the rota only says who, not when. */
  start: string
  end: string
}

const PRICE_TOOL = {
  name: 'report_price_board',
  description: 'Return every priced line on the board, exactly as written.',
  input_schema: {
    type: 'object' as const,
    properties: {
      prices: {
        type: 'array',
        description: 'One entry per priced line. Empty if nothing is readable.',
        items: {
          type: 'object',
          properties: {
            name: { type: 'string', description: 'The drink or item as written, including the measure if given.' },
            price: { type: 'string', description: 'The price exactly as printed, e.g. "4.00" or "£4". Never rounded or adjusted.' },
            note: { type: 'string', description: 'Only if something is unclear about this line. Usually empty.' },
          },
          required: ['name', 'price', 'note'],
        },
      },
      notes: { type: 'string', description: 'Glare, a cut-off edge, handwriting you could not read. May be empty.' },
    },
    required: ['prices', 'notes'],
  },
}

const DELIVERY_TOOL = {
  name: 'report_delivery_note',
  description: 'Return every stock line on the delivery note, exactly as written.',
  input_schema: {
    type: 'object' as const,
    properties: {
      lines: {
        type: 'array',
        description: 'One entry per product line. Empty if nothing is readable.',
        items: {
          type: 'object',
          properties: {
            name: { type: 'string', description: 'The product as written on the note.' },
            quantity: { type: 'string', description: 'How many, exactly as printed. Just the figure.' },
            unit: { type: 'string', description: 'The unit as written — "kil", "firkin", "case", "btl". Empty if the note does not say.' },
          },
          required: ['name', 'quantity', 'unit'],
        },
      },
      notes: { type: 'string', description: 'Anything unclear, or lines you deliberately left out. May be empty.' },
    },
    required: ['lines', 'notes'],
  },
}

const DELIVERY_SYSTEM = `You read a photograph of a brewery delivery note or invoice for a British pub.

Copy out every line of stock delivered. Do not interpret, convert or total.

- One entry per product line, named as the note names it.
- Copy the quantity exactly as printed — the number of containers, not the number of pints. If the note says 2 KIL TADDY, the quantity is 2 and the unit is "kil".
- Copy the unit as written: kil, firkin, keg, case, btl, each. Leave it empty if the note does not give one.
- Ignore anything that is not stock: delivery charges, cask deposits, VAT lines, totals, account numbers.
- If a line is a credit or a return (a negative quantity, "RET", "CREDIT"), leave it out and say so in notes — booking a return in as a delivery would overstate the cellar.
- If you cannot read a quantity with confidence, leave the line out and say so.`

const ROTA_TOOL = {
  name: 'report_rota',
  description: 'Return every shift on the rota, exactly as written.',
  input_schema: {
    type: 'object' as const,
    properties: {
      shifts: {
        type: 'array',
        description: 'One entry per person per day they are working.',
        items: {
          type: 'object',
          properties: {
            name: { type: 'string', description: 'The person as written on the rota.' },
            day: { type: 'string', description: 'The day column this shift is in, as written — "Mon", "Friday", "Sat 29".' },
            start: { type: 'string', description: '24-hour "HH:MM" if a start time is given, otherwise empty.' },
            end: { type: 'string', description: '24-hour "HH:MM" if a finish time is given, otherwise empty. "close" is empty.' },
          },
          required: ['name', 'day', 'start', 'end'],
        },
      },
      notes: { type: 'string', description: 'Anything unclear — crossings out, an unreadable name. May be empty.' },
    },
    required: ['shifts', 'notes'],
  },
}

const PRICE_SYSTEM = `You read a photograph of a British pub's price list — a board, a printed sheet, or a handwritten one.

Copy out every priced line. Do not interpret, convert or tidy.

- Keep the name as written, including the measure: "Pint Taddy Lager", "Half Alpine", "175ml House Wine". If the board groups a drink with two prices (pint and half), that is TWO entries, each named with its measure.
- Copy the price exactly as printed. Never round, never convert, never work out a missing price from the others.
- If a line has no price against it, leave it out — this is a price list, not a menu.
- If you cannot read a price with confidence, leave that line out and say so in notes. A missing line costs one tap to add; a wrong one is believed for months.`

const ROTA_SYSTEM = `You read a photograph of a pub's staff rota — usually a grid with people down one side and days across the top, often handwritten.

Copy out every shift. Do not interpret or tidy.

- One entry per person per day they are marked as working.
- Copy the day exactly as the column is labelled: "Mon", "Monday", "Sat 29".
- Copy the name exactly as written, even if shortened ("Kel", "Dave S").
- Times: give 24-hour "HH:MM". "6" in an evening column is "18:00"; "12" in a daytime column is "12:00". If a cell says only a tick, an X, or "in", leave both times empty. "close", "late" and "til close" are an empty end time — do not invent one.
- If a cell is crossed out, leave it out and mention it in notes.`

/** The shape the SDK needs of a tool, rather than a union of the specific ones. */
interface VisionTool {
  name: string
  description: string
  input_schema: { type: 'object'; properties: Record<string, unknown>; required: string[] }
}

interface Call<T> {
  file: Blob
  signal?: AbortSignal
  system: string
  tool: VisionTool
  ask: string
  read: (input: Record<string, unknown>) => T
}

/**
 * One vision call, shared by both readers.
 *
 * Deliberately the same engine rules as the till roll: if scanning is off or
 * there is no key, this fails with the same codes the existing screens already
 * know how to explain, rather than inventing a second vocabulary for it.
 */
async function callVision<T>({ file, signal, system, tool, ask, read }: Call<T>): Promise<{ value: T; notes: string }> {
  const settings = loadSettings()
  const engine = effectiveEngine(settings)
  if (engine !== 'vision') {
    // The phone's own reader cannot do this. It can barely manage a till roll
    // printed in a fixed font; a chalkboard is hopeless, and pretending
    // otherwise would waste her evening.
    if (settings.engine === 'off') throw new Error('SCANNING_OFF')
    // Before the key check: the ordinary on-device setup has no key at all,
    // and telling that user to add one would be wrong advice — the roll scans
    // fine as they are, and this photograph needs a different engine, not a key.
    if (settings.engine === 'device') throw new Error('NEEDS_VISION')
    if (!settings.apiKey.trim()) throw new Error('NO_KEY')
    throw new Error('OFFLINE')
  }

  const { data, mediaType } = await prepareForVision(file)
  const { default: Anthropic } = await import('@anthropic-ai/sdk')
  const client = new Anthropic({ apiKey: settings.apiKey.trim(), dangerouslyAllowBrowser: true })

  const message = await client.messages.create(
    {
      model: settings.model,
      max_tokens: 4000,
      system,
      tools: [tool],
      tool_choice: { type: 'tool', name: tool.name },
      messages: [
        {
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: mediaType as 'image/jpeg', data } },
            { type: 'text', text: ask },
          ],
        },
      ],
    },
    { signal },
  )

  const block = message.content.find((c) => c.type === 'tool_use')
  if (!block || block.type !== 'tool_use') throw new Error('NO_ANSWER')
  const input = block.input as Record<string, unknown>
  return { value: read(input), notes: typeof input.notes === 'string' ? input.notes : '' }
}

/**
 * A price as written on a board, in pence.
 *
 * Deliberately stricter than the till-roll parser: a board is short, so a line
 * that does not read cleanly is better dropped than guessed at.
 */
export function parseBoardPrice(text: string): number | null {
  const cleaned = text.replace(/[£\s]/g, '')
  const m = /^(\d{1,3})(?:[.,](\d{1,2}))?$/.exec(cleaned)
  if (!m) return null
  const pounds = Number(m[1])
  const pence = m[2] ? Number(m[2].padEnd(2, '0')) : 0
  if (!Number.isFinite(pounds) || !Number.isFinite(pence)) return null
  const total = pounds * 100 + pence
  // A pub price. Anything outside this is a misread, not a bargain.
  return total > 0 && total <= 50000 ? total : null
}

export async function scanPriceBoard(file: Blob, signal?: AbortSignal): Promise<{ prices: ScannedPrice[]; notes: string }> {
  const { value, notes } = await callVision({
    file,
    ...(signal ? { signal } : {}),
    system: PRICE_SYSTEM,
    tool: PRICE_TOOL,
    ask: 'Read this price list.',
    read: (input) => {
      const rows = Array.isArray(input.prices) ? (input.prices as Array<Record<string, unknown>>) : []
      const out: ScannedPrice[] = []
      for (const row of rows) {
        const name = typeof row.name === 'string' ? row.name.trim() : ''
        const pence = parseBoardPrice(typeof row.price === 'string' ? row.price : '')
        if (!name || pence === null) continue
        const note = typeof row.note === 'string' ? row.note.trim() : ''
        out.push({ name, pence, ...(note ? { note } : {}) })
      }
      return out
    },
  })
  return { prices: value, notes }
}

export async function scanRota(file: Blob, signal?: AbortSignal): Promise<{ shifts: ScannedShift[]; notes: string }> {
  const { value, notes } = await callVision({
    file,
    ...(signal ? { signal } : {}),
    system: ROTA_SYSTEM,
    tool: ROTA_TOOL,
    ask: 'Read this rota.',
    read: (input) => {
      const rows = Array.isArray(input.shifts) ? (input.shifts as Array<Record<string, unknown>>) : []
      const out: ScannedShift[] = []
      for (const row of rows) {
        const name = typeof row.name === 'string' ? row.name.trim() : ''
        const day = typeof row.day === 'string' ? row.day.trim() : ''
        if (!name || !day) continue
        out.push({
          name,
          day,
          start: typeof row.start === 'string' ? row.start.trim() : '',
          end: typeof row.end === 'string' ? row.end.trim() : '',
        })
      }
      return out
    },
  })
  return { shifts: value, notes }
}


export async function scanDeliveryNote(file: Blob, signal?: AbortSignal): Promise<{ lines: ScannedDelivery[]; notes: string }> {
  const { value, notes } = await callVision({
    file,
    ...(signal ? { signal } : {}),
    system: DELIVERY_SYSTEM,
    tool: DELIVERY_TOOL,
    ask: 'Read this delivery note.',
    read: (input) => {
      const rows = Array.isArray(input.lines) ? (input.lines as Array<Record<string, unknown>>) : []
      const out: ScannedDelivery[] = []
      for (const row of rows) {
        const name = typeof row.name === 'string' ? row.name.trim() : ''
        const raw = typeof row.quantity === 'string' ? row.quantity.trim() : ''
        const quantity = Number(raw.replace(/[^0-9.]/g, ''))
        // A zero or a negative is a credit line or a misread; neither belongs
        // in a delivery, and adding one would overstate the cellar.
        if (!name || !Number.isFinite(quantity) || quantity <= 0) continue
        out.push({ name, quantity, unit: typeof row.unit === 'string' ? row.unit.trim() : '' })
      }
      return out
    },
  })
  return { lines: value, notes }
}
