// ---------------------------------------------------------------------------
// Finding the total in a wall of scanned receipt text.
//
// This is pure text work, deliberately kept away from any engine, because it
// is the part most likely to be wrong and the part easiest to test. Whatever
// read the paper — a model, a WASM scanner — hands its text here.
//
// The governing rule is that a wrong number confidently presented is far worse
// than no number at all. She is standing at the bar at midnight; if the app
// says £4,212.30 she will believe it. So a candidate has to be *justified* by
// something printed next to it, and the fallbacks that are merely plausible
// are marked as such rather than dressed up.
// ---------------------------------------------------------------------------

import { parsePence, MAX_REASONABLE_PENCE } from '../core/money.ts'

export type ReceiptKind = 'till' | 'card'

export interface TotalCandidate {
  pence: number
  /** The printed wording that justified this figure. */
  label: string
  /** The line it was read from, for showing her what was matched. */
  line: string
  score: number
  /** True when nothing labelled it and it was chosen on shape alone. */
  guessed: boolean
}

/**
 * Wording that means "this is the figure we want", strongest first.
 *
 * Matched against the line with spaces removed, so a scanner that prints
 * "T O T A L" or "GRANDTOTAL" is still understood.
 */
const POSITIVE: Record<ReceiptKind, Array<[string, number]>> = {
  till: [
    ['GRANDTOTAL', 100],
    ['TOTALTAKINGS', 100],
    ['DAILYTOTAL', 95],
    ['GROSSTOTAL', 90],
    ['NETTOTAL', 85],
    ['ZTOTAL', 85],
    ['ZREADTOTAL', 85],
    ['TOTALSALES', 80],
    ['SALESTOTAL', 80],
    ['TURNOVER', 70],
    ['TOTAL', 60],
  ],
  card: [
    ['GRANDTOTAL', 100],
    ['BATCHTOTAL', 95],
    ['TOTALSALES', 90],
    ['SALESTOTAL', 90],
    ['GROSSTOTAL', 85],
    ['TOTALAMOUNT', 80],
    ['NETTOTAL', 75],
    ['TOTAL', 60],
    ['AMOUNT', 40],
  ],
}

/**
 * Wording that vetoes a line outright.
 *
 * These are the breakdown figures. Every one of them sits next to a perfectly
 * well-formed amount, which is exactly why they need naming: "TOTAL CASH" on a
 * till roll is a real total of a real thing, and not the one being asked for.
 */
const VETO = [
  'SUBTOTAL', 'SUBTOT',
  'VAT', 'TAX',
  'CHANGE', 'TENDER', 'TENDERED',
  'CASH', 'CARD', 'CHEQUE', 'CHECK', 'CONTACTLESS',
  'VOID', 'REFUND', 'CANCEL', 'NOSALE', 'ERROR',
  'DISCOUNT', 'TIP', 'GRATUITY', 'SERVICE',
  'ROUNDING', 'FLOAT', 'PAIDOUT', 'PAYOUT', 'PICKUP',
  'CUSTOMER', 'COVERS', 'QTY', 'QUANTITY', 'COUNT',
  'TID', 'MID', 'AID', 'AUTHCODE', 'MERCHANT', 'TERMINAL',
  'OPENING', 'CLOSING', 'BALANCE',
  'PERHOUR', 'AVERAGE', 'AVG',
]

/** Lines that are terminal housekeeping and never carry the figure. */
const NOISE = /^(?:\*+|-+|=+|_+)$/

function despace(s: string): string {
  return s.replace(/[\s .·]/g, '').toUpperCase()
}

/**
 * Pull the monetary amounts out of one line.
 *
 * Totals are right-aligned on receipt paper, so the last amount on a line is
 * the one that belongs to its label. Amounts carrying a currency mark or a
 * decimal point are trusted more than a bare run of digits, which on a card
 * slip is as likely to be a terminal ID as money.
 */
/**
 * Close up a space used as a thousands separator, and only that.
 *
 * Whitespace cannot be allowed inside an amount token generally: receipt
 * columns are spaced out, and "DEPT 1    2104.50" would merge into
 * £12,104.50 — a wrong number that looks entirely reasonable. Requiring
 * complete groups of three digits keeps the one legitimate case working
 * without opening that door.
 */
function joinSpacedThousands(line: string): string {
  return line.replace(
    /([0-9]{1,3})((?:[ \u00a0][0-9]{3})+)([.,][0-9]{1,2})?/g,
    (_m, head: string, groups: string, tail: string | undefined) =>
      head + groups.replace(/[ \u00a0]/g, '') + (tail ?? ''),
  )
}

export function amountsInLine(rawLine: string): Array<{ pence: number; emphatic: boolean }> {
  const line = joinSpacedThousands(rawLine)
  const out: Array<{ pence: number; emphatic: boolean }> = []
  // A token is a currency mark and/or a run of digits, separators and the
  // letters a scanner substitutes for digits. No whitespace — see above. Times,
  // dates and masked card numbers carry ':' '/' or '*' and so never match.
  const re = /[£$€]?[0-9OoIlSBGZTqg|][0-9OoIlSBGZTqg|.,]{0,14}/g
  for (const raw of line.match(re) ?? []) {
    const token = raw.trim()
    if (!token) continue
    const emphatic = /[£$€]/.test(token) || /[.,][0-9]{2}\b/.test(token)
    const pence = parsePence(token, { loose: true })
    if (pence === null) continue
    if (pence < 0 || pence > MAX_REASONABLE_PENCE) continue
    out.push({ pence, emphatic })
  }
  return out
}

/**
 * Rank every figure the text could plausibly be offering as the total.
 *
 * Returns them best-first. The caller shows the top one and — this is the
 * point — lets her overrule it.
 */
export function extractTotals(text: string, kind: ReceiptKind): TotalCandidate[] {
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !NOISE.test(l))

  const positives = POSITIVE[kind]
  const candidates: TotalCandidate[] = []
  const seen = new Set<string>()

  const push = (c: TotalCandidate) => {
    const key = `${c.pence}:${c.label}`
    if (seen.has(key)) return
    seen.add(key)
    candidates.push(c)
  }

  lines.forEach((line, i) => {
    const flat = despace(line)
    if (VETO.some((v) => flat.includes(v))) return

    const hit = positives.find(([word]) => flat.includes(word))
    if (!hit) return
    const [word, weight] = hit

    // The figure is usually on the label's own line; some rolls print it on
    // the next one, under the wording.
    let amounts = amountsInLine(line)
    let source = line
    if (amounts.length === 0 && i + 1 < lines.length) {
      const next = lines[i + 1] ?? ''
      if (!VETO.some((v) => despace(next).includes(v))) {
        amounts = amountsInLine(next)
        source = next
      }
    }
    if (amounts.length === 0) return

    const chosen = amounts[amounts.length - 1]
    if (!chosen) return

    // Later in the roll is more likely to be the summary; a figure printed
    // with a currency mark or real pence is more likely to be money.
    const positionBonus = Math.round((i / Math.max(1, lines.length - 1)) * 15)
    const emphasisBonus = chosen.emphatic ? 10 : 0

    push({
      pence: chosen.pence,
      label: word,
      line: source,
      score: weight + positionBonus + emphasisBonus,
      guessed: false,
    })
  })

  if (candidates.length > 0) {
    candidates.sort((a, b) => b.score - a.score || b.pence - a.pence)
    return candidates
  }

  // Nothing was labelled. The largest properly-formed amount on the receipt is
  // usually the total — but that is a guess, and it says so, so the interface
  // can present it as something to check rather than something to accept.
  let best: { pence: number; line: string } | null = null
  for (const line of lines) {
    if (VETO.some((v) => despace(line).includes(v))) continue
    for (const a of amountsInLine(line)) {
      if (!a.emphatic) continue
      if (!best || a.pence > best.pence) best = { pence: a.pence, line }
    }
  }
  if (best) {
    push({ pence: best.pence, label: 'largest amount', line: best.line, score: 1, guessed: true })
  }
  return candidates
}

/** The single figure to put in front of her, or null if the scan gave nothing. */
export function bestTotal(text: string, kind: ReceiptKind): TotalCandidate | null {
  return extractTotals(text, kind)[0] ?? null
}
