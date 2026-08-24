// ---------------------------------------------------------------------------
// Money.
//
// Everything is an integer number of pence. Floating point has no business
// anywhere near a till reconciliation: 0.1 + 0.2 is famously not 0.3, and the
// whole point of this app is that a number either balances or it does not.
// Pounds exist only at the edges — when text is read in, and when a figure is
// printed out.
// ---------------------------------------------------------------------------

/** Anything above this is a typo, not a night's trade. Guards a slipped key. */
export const MAX_REASONABLE_PENCE = 100_000_00

const GBP = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' })

/** `123456` -> `"£1,234.56"`. */
export function formatMoney(pence: number): string {
  return GBP.format(pence / 100)
}

/**
 * `-500` -> `"−£5.00"`, with a real minus sign rather than a hyphen.
 *
 * Used for the variance, where the sign is the entire message and needs to
 * survive being read at a glance, upside down, at midnight.
 */
export function formatSigned(pence: number): string {
  if (pence === 0) return formatMoney(0)
  const sign = pence < 0 ? '−' : '+'
  return `${sign}${formatMoney(Math.abs(pence))}`
}

/** `123456` -> `"1234.56"`, for prefilling an editable field. */
export function penceToInput(pence: number | null): string {
  if (pence === null) return ''
  return (pence / 100).toFixed(2)
}

/**
 * Characters OCR reliably confuses on thermal receipt paper, mapped to the
 * digit that was almost certainly printed.
 *
 * Applied only to a token that already looks like an amount — never to prose,
 * where turning every O into a zero would be vandalism.
 */
const CONFUSIONS: Record<string, string> = {
  O: '0', o: '0', Q: '0', D: '0',
  l: '1', I: '1', i: '1', '|': '1', '!': '1', L: '1',
  Z: '2', z: '2',
  S: '5', s: '5',
  G: '6', b: '6',
  T: '7', '?': '7',
  B: '8',
  g: '9', q: '9',
}

/**
 * Only repair a token that is already mostly digits.
 *
 * Without this guard the repair is worse than useless: "TOTAL" maps cleanly to
 * "70741" and "SALE" to "5413", so a line of ordinary receipt wording would
 * parse as a confident, entirely fictional amount. Requiring that the token
 * arrive at least half numeric means the repair only ever finishes a number
 * that was already there.
 */
function looksNumericEnough(token: string): boolean {
  let digits = 0
  let letters = 0
  for (const ch of token) {
    if (ch >= '0' && ch <= '9') digits++
    else if (/[a-z]/i.test(ch)) letters++
  }
  return digits > 0 && digits >= letters
}

function undoConfusions(token: string): string {
  if (!looksNumericEnough(token)) return token
  let out = ''
  for (const ch of token) out += CONFUSIONS[ch] ?? ch
  return out
}

export interface ParseOptions {
  /**
   * Repair characters a scanner commonly misreads. Only ever set for text that
   * came out of an OCR engine; a human typing "S" meant to type "S".
   */
  loose?: boolean
}

/**
 * Read a monetary amount, returning whole pence, or null if the text is not an
 * amount.
 *
 * Deliberately strict about the things that would silently produce a wrong
 * number: more than two decimal places is rejected rather than rounded,
 * because on a till roll that means the reading is wrong, not that the pub
 * took a third of a penny.
 */
export function parsePence(input: string, opts: ParseOptions = {}): number | null {
  let text = input.trim()
  if (!text) return null

  // A leading or trailing minus, or accounting-style brackets, all mean the
  // same thing: a refund total, or a card slip showing net of refunds.
  let negative = false
  const bracketed = /^\((.*)\)$/.exec(text)
  if (bracketed) {
    negative = true
    text = bracketed[1] ?? ''
  }
  text = text.replace(/^[-−]\s*/, () => ((negative = true), ''))
  text = text.replace(/\s*[-−]$/, () => ((negative = true), ''))

  // Currency marks and spacing, wherever they sit.
  text = text.replace(/[£$€]/g, '').replace(/[\s\u00a0\u202f]/g, '').trim()
  if (!text) return null

  if (opts.loose) text = undoConfusions(text)

  // What remains must be digits and separators only.
  if (!/^[0-9.,]+$/.test(text)) return null
  if (!/[0-9]/.test(text)) return null

  // Work out which separator is the decimal point. A UK till prints
  // "1,234.56"; a European card terminal may print "1.234,56"; and plenty
  // print no thousands separator at all.
  const lastDot = text.lastIndexOf('.')
  const lastComma = text.lastIndexOf(',')
  let decimalAt = -1
  if (lastDot >= 0 && lastComma >= 0) {
    decimalAt = Math.max(lastDot, lastComma)
  } else if (lastDot >= 0) {
    decimalAt = lastDot
  } else if (lastComma >= 0) {
    decimalAt = lastComma
  }

  let whole: string
  let frac = ''
  if (decimalAt === -1) {
    whole = text
  } else {
    const tail = text.slice(decimalAt + 1)
    // A group of exactly three digits after the final separator is a thousands
    // group, not pence: "1,234" is twelve hundred pounds, not £1.23.
    if (/^[0-9]{3}$/.test(tail)) {
      whole = text
    } else {
      whole = text.slice(0, decimalAt)
      frac = tail
    }
  }

  // Any separators left in the whole part must genuinely be thousands
  // grouping. Without this check "12.34.56" quietly becomes £1,234.56 — a
  // clearly broken reading accepted as a confident number, which is the worst
  // thing this function could do.
  if (/[.,]/.test(whole) && !/^[0-9]{1,3}([.,][0-9]{3})+$/.test(whole)) return null

  whole = whole.replace(/[.,]/g, '')
  if (whole === '') whole = '0'

  if (!/^[0-9]+$/.test(whole)) return null
  if (frac !== '' && !/^[0-9]{1,2}$/.test(frac)) return null

  const pounds = Number(whole)
  const pence = frac === '' ? 0 : Number(frac.padEnd(2, '0'))
  if (!Number.isFinite(pounds)) return null

  const total = pounds * 100 + pence
  if (total > MAX_REASONABLE_PENCE) return null
  return negative ? -total : total
}

/** Whether a figure is plausible for one night, used to warn rather than block. */
export function isPlausibleTakings(pence: number): boolean {
  return pence >= 0 && pence <= 20_000_00
}
