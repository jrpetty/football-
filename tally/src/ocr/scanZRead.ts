// ---------------------------------------------------------------------------
// Reading a whole till roll from photographs.
//
// The division of labour here is the important decision. Claude is asked only
// to *transcribe* — to copy what is printed, line by line, character for
// character. It is not asked to identify the total, sum a column, or decide
// which figure matters. All of that is done afterwards by parseZRead, which is
// pinned by tests to the real Gardeners Arms roll, and then checked by
// crossfoot against the receipt's own arithmetic.
//
// The reason is that transcription is the task a vision model is reliably good
// at, and interpretation is the task tested code is reliably good at. Asking
// the model to do both means a wrong figure can arrive looking exactly like a
// right one. Split this way, a misread digit almost always breaks one of the
// receipt's internal equations, and the interface can point at the line.
//
// It also means the on-device scanner and Claude go down an identical path:
// text in, parsed, cross-footed. Only the quality of the text differs.
// ---------------------------------------------------------------------------

import { parseZRead } from './parseZRead.ts'
import { crossfootVerdict, type CrossfootVerdict } from '../core/crossfoot.ts'
import { emptyZRead, mergeZRead, sectionsIn, type ZRead, type ZReadSection } from '../core/zread.ts'
import { prepareForVision } from './image.ts'
import { transcribeOnDevice } from './device.ts'
import { loadSettings, effectiveEngine, supportsEffort } from '../storage/settings.ts'
import type { EngineId } from './types.ts'

const SYSTEM = `You transcribe photographs of a British pub's end-of-day till roll (a "Z read").

Your only job is to copy out what is printed. You are not reading for meaning.

Rules, in order of importance:
- Transcribe verbatim, line by line, top to bottom. Same words, same numbers, same order.
- Never calculate. Never add a column up, never work out a missing figure, never correct a
  figure that looks wrong to you. If the paper says something that cannot be right, transcribe
  what the paper says — a disagreement is information, and something downstream is checking for
  exactly that.
- Never omit a line because it looks unimportant, and never invent a line that is not there.
- Keep each printed line on its own line. Keep the label and its figures on that same line,
  separated by spaces, in the order printed: label, then quantity, then amount, then percentage.
- Keep the till's own punctuation exactly: the leading * on amounts, the trailing Q on
  quantities, the % on percentages, the leading D on department codes, the # on numbers.
- This roll prints amounts like *1492.25 and quantities like 406.000 Q. Do not reformat either.
  Do not add thousands separators the till did not print. Do not drop trailing zeroes.
- The same labels (CASH, CREDIT CARD, PAID TL, CID) appear several times on one roll: once for
  the whole day, then again for each clerk. Transcribe every occurrence, in place, including
  the section headings (DEPT./GROUP, TRANSACTION, ALL CLERK, CLK#0001, ***TOTAL, PLU) that say
  which is which. Those headings are what makes the repeats tellable apart.
- If a character is genuinely ambiguous, transcribe your best reading and say so in notes,
  naming the line. Do not silently pick the likelier one, and do not use placeholders like ? in
  the transcription itself.
- If the photograph is unusable, or shows something that is not a till roll, say so in notes and
  return an empty transcription. An honest blank beats a plausible invention.`

const TOOL = {
  name: 'report_till_roll',
  description: 'Return the till roll transcribed verbatim.',
  input_schema: {
    type: 'object' as const,
    properties: {
      transcription: {
        type: 'string',
        description: 'The roll copied out line by line, exactly as printed. Empty if unreadable.',
      },
      confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
      notes: {
        type: 'string',
        description: 'Ambiguous characters (naming the line), glare, a torn roll, a cut-off edge. May be empty.',
      },
    },
    required: ['transcription', 'confidence', 'notes'],
  },
}

export interface ZReadScan {
  engine: EngineId
  zRead: ZRead
  verdict: CrossfootVerdict
  rawText: string
  confidence: 'high' | 'medium' | 'low'
  notes: string
}

interface ScanZReadRequest {
  file: Blob
  signal?: AbortSignal
  /** What has already been captured, so a second photograph adds rather than replaces. */
  existing?: ZRead
}

async function transcribeWithVision(file: Blob, signal?: AbortSignal): Promise<{ text: string; confidence: 'high' | 'medium' | 'low'; notes: string }> {
  const settings = loadSettings()
  const apiKey = settings.apiKey.trim()
  if (!apiKey) throw new Error('NO_KEY')

  const { data, mediaType } = await prepareForVision(file)
  const { default: Anthropic } = await import('@anthropic-ai/sdk')
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true })

  const message = await client.messages.create(
    {
      model: settings.model,
      // A full roll with a PLU list runs long; truncating it mid-transcription
      // would silently lose the sections that come last.
      max_tokens: 8000,
      // Copying, not reasoning: low effort is both cheaper and quicker, and
      // thinking stays on, which keeps the model from writing a tool call into
      // its visible text instead of calling the tool.
      ...(supportsEffort(settings.model) ? { output_config: { effort: 'low' as const } } : {}),
      system: SYSTEM,
      tools: [TOOL],
      tool_choice: { type: 'tool', name: 'report_till_roll' },
      messages: [
        {
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: mediaType as 'image/jpeg', data } },
            { type: 'text', text: 'Transcribe this till roll.' },
          ],
        },
      ],
    },
    { signal },
  )

  const block = message.content.find((c) => c.type === 'tool_use')
  if (!block || block.type !== 'tool_use') throw new Error('NO_ANSWER')
  const input = block.input as { transcription: string; confidence: 'high' | 'medium' | 'low'; notes: string }
  return { text: input.transcription ?? '', confidence: input.confidence ?? 'medium', notes: input.notes ?? '' }
}

/**
 * Scan one photograph of the roll and fold it into what is already captured.
 *
 * Returns the merged read together with the cross-foot verdict, so the caller
 * can say "these six lines agree with each other" or "the departments come to
 * £2,192.40 but the total says £2,192.80" rather than quoting a percentage.
 */
export async function scanZRead(req: ScanZReadRequest): Promise<ZReadScan> {
  const settings = loadSettings()
  const engine = effectiveEngine(settings)
  if (engine === 'off') {
    if (settings.engine === 'off') throw new Error('SCANNING_OFF')
    if (!settings.apiKey.trim()) throw new Error('NO_KEY')
    throw new Error('OFFLINE')
  }

  let text: string
  let confidence: 'high' | 'medium' | 'low'
  let notes: string
  let used: EngineId

  if (engine === 'device') {
    text = await transcribeOnDevice(req.file)
    confidence = 'low'
    notes = 'Read on the phone. This roll is dense, so check the figures carefully.'
    used = 'device'
  } else {
    const result = await transcribeWithVision(req.file, req.signal)
    text = result.text
    confidence = result.confidence
    notes = result.notes
    used = 'vision'
  }

  const scanned = parseZRead(text)
  const zRead = req.existing ? mergeZRead(req.existing, scanned) : scanned

  return { engine: used, zRead, verdict: crossfootVerdict(zRead), rawText: text, confidence, notes }
}

/** Failures phrased for someone standing at a bar rather than a developer. */
export function describeZReadError(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err)
  if (msg === 'SCANNING_OFF') return 'Scanning is switched off in Settings.'
  if (msg === 'NO_KEY') return 'Add an Anthropic API key in Settings to scan the roll.'
  if (msg === 'OFFLINE') return 'No connection. Switch to on-device scanning, or type the figures in.'
  if (msg === 'NO_ANSWER') return 'Nothing came back from that photograph. Try again, or type the figures in.'
  if (/abort/i.test(msg)) return 'Cancelled.'
  if (/401|authentication|invalid x-api-key/i.test(msg)) return 'That API key was rejected. Check it in Settings.'
  if (/429|rate.?limit/i.test(msg)) return 'Rate limited — wait a moment and try again.'
  if (/credit|billing|quota/i.test(msg)) return 'There is a billing problem on that Anthropic account.'
  if (/CORS|Failed to fetch|NetworkError/i.test(msg)) return 'Could not reach the internet. Type the figures in, or switch to on-device scanning.'
  return `Could not read that photograph: ${msg}`
}


// ---------------------------------------------------------------------------
// Several photographs at once.
//
// The roll does not fit in one frame, so it arrives in pieces — and nobody
// standing at a bar should have to remember that the item list goes second.
// Each photograph is read on its own, announces which sections it turned out to
// contain, and is then folded into one read. Order does not matter, a photograph
// that fails does not take the others with it, and what is still missing is
// visible rather than silently absent.
// ---------------------------------------------------------------------------

export interface PhotoOutcome {
  index: number
  /** Sections this photograph turned out to contain. Empty if it read nothing. */
  sections: ZReadSection[]
  error?: string
  rawText?: string
  confidence?: 'high' | 'medium' | 'low'
  notes?: string
}

export interface BatchResult {
  zRead: ZRead
  verdict: CrossfootVerdict
  photos: PhotoOutcome[]
}

interface BatchRequest {
  files: Blob[]
  signal?: AbortSignal
  existing?: ZRead
  /** Called as each photograph lands, so the interface can show progress. */
  onProgress?: (done: number, total: number) => void
}

/** One photograph, reduced to its own parsed read and never merged in place. */
async function readOne(file: Blob, index: number, signal?: AbortSignal): Promise<{ z: ZRead; outcome: PhotoOutcome }> {
  try {
    const result = await scanZRead({ file, ...(signal ? { signal } : {}) })
    return {
      z: result.zRead,
      outcome: {
        index,
        sections: sectionsIn(result.zRead),
        rawText: result.rawText,
        confidence: result.confidence,
        notes: result.notes,
      },
    }
  } catch (err) {
    return { z: emptyZRead(), outcome: { index, sections: [], error: describeZReadError(err) } }
  }
}

export async function scanZReadBatch(req: BatchRequest): Promise<BatchResult> {
  const total = req.files.length
  let done = 0
  const tick = () => req.onProgress?.(++done, total)

  // Claude handles the photographs concurrently, which turns a fifteen-second
  // wait into a five-second one. The on-device scanner shares a single worker
  // and has to go one at a time.
  const concurrent = effectiveEngine(loadSettings()) === 'vision'

  let results: Array<{ z: ZRead; outcome: PhotoOutcome }>
  if (concurrent) {
    results = await Promise.all(
      req.files.map((file, i) => readOne(file, i, req.signal).then((r) => (tick(), r))),
    )
  } else {
    results = []
    for (const [i, file] of req.files.entries()) {
      results.push(await readOne(file, i, req.signal))
      tick()
    }
  }

  // Folded in photograph order, so a figure appearing on two of them settles
  // the same way every time.
  let zRead = req.existing ?? emptyZRead()
  for (const r of results) zRead = mergeZRead(zRead, r.z)

  return { zRead, verdict: crossfootVerdict(zRead), photos: results.map((r) => r.outcome) }
}
