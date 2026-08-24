// ---------------------------------------------------------------------------
// Reading a whole Z read out of scanned text.
//
// The thing that makes this hard is not the characters — it is that the till
// spreads one record across several printed lines. A department is three:
//
//     D01                    406.000 Q     <- code and quantity
//     DRAUGHT BEERS           *1492.25     <- name and value
//                               68.05%     <- percentage, on its own
//
// and CASH, CREDIT CARD, VOID, the group subtotals and the department total are
// all split the same way. A parser that assumes one line per record — as an
// earlier version of this one did — reads precisely nothing from the real
// receipt while passing every test against an invented layout.
//
// So this reads records rather than lines. A starter line opens a record, the
// lines that follow fill in whatever it is still missing, and the record closes
// when the next starter arrives. That handles the printed layout and also a
// tidied one-line-per-record transcription, which matters because a vision
// model may straighten the columns despite being asked not to. Both must give
// the identical answer, and a test holds them to it.
//
// The other trap is repetition. CASH, CREDIT CARD, PAID TL and CID appear once
// in TRANSACTION, again under every clerk, and a third time under the clerk
// ***TOTAL. Matching on labels alone would report one clerk's takings as the
// pub's night, so the reader tracks which section it is standing in.
// ---------------------------------------------------------------------------

import { parsePence } from '../core/money.ts'
import {
  emptyZRead,
  type ClerkLine,
  type DeptLine,
  type ZRead,
} from '../core/zread.ts'

type Section = 'head' | 'dept' | 'transaction' | 'clerk' | 'clerkTotal' | 'plu'

/** The running grand totals accumulate for the life of the till. */
const GRAND_TOTAL_MAX = 1_000_000_000

/** Figures pulled off one printed line. */
interface Fields {
  label: string
  qtyMilli?: number
  pence?: number
  percentBp?: number
}

/** Strip the till's decoration: leading stars, hashes, trailing dots. */
function normaliseLabel(raw: string): string {
  return raw
    .replace(/^[*#\s]+/, '')
    .replace(/[.\s]+$/, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toUpperCase()
}

function toMilli(text: string): number | undefined {
  const n = Number(text.replace(/,/g, ''))
  return Number.isFinite(n) ? Math.round(n * 1000) : undefined
}

function toBp(text: string): number | undefined {
  const n = Number(text.replace(/,/g, ''))
  return Number.isFinite(n) ? Math.round(n * 100) : undefined
}

/**
 * Split a printed line into its label and its figures.
 *
 * Order matters: the percentage comes off the end first, then the starred
 * amount, then the quantity, so a line carrying all three cannot mistake one
 * for another.
 */
export function readLine(line: string): Fields | null {
  let rest = line.trimEnd()
  if (!rest.trim()) return null

  let percentBp: number | undefined
  const pct = /([0-9][0-9.,]*)\s*%\s*$/.exec(rest)
  if (pct?.[1]) {
    percentBp = toBp(pct[1])
    rest = rest.slice(0, pct.index)
  }

  let pence: number | undefined
  const amounts = [...rest.matchAll(/(-?)\s*\*\s*(-?[0-9][0-9.,]*)/g)]
  const lastAmount = amounts[amounts.length - 1]
  if (lastAmount) {
    const sign = lastAmount[1] === '-' ? '-' : ''
    pence = parsePence(`${sign}${lastAmount[2] ?? ''}`, { loose: true }) ?? undefined
    rest = rest.slice(0, lastAmount.index)
  }

  let qtyMilli: number | undefined
  const qty = /([0-9][0-9.,]*)\s*Q\b/.exec(rest)
  if (qty?.[1]) {
    qtyMilli = toMilli(qty[1])
    rest = rest.slice(0, qty.index)
  }

  const label = normaliseLabel(rest)
  if (!label && pence === undefined && qtyMilli === undefined && percentBp === undefined) return null
  return { label, qtyMilli, pence, percentBp }
}

// --- records ----------------------------------------------------------------

/** Statistics printed as a plain label with a count and/or an amount. */
const STAT_LABELS = new Set([
  'NET1', 'NET2', 'VOID', 'NO SALE', 'GUEST', 'ORDER TL', 'PAID TL', 'NON COM',
  'AVE', 'CASH', 'CREDIT CARD', 'CID', 'CA/CHK ID',
])

type Pending =
  | { kind: 'dept'; code: string; name: string; qtyMilli?: number; pence?: number; percentBp?: number }
  | { kind: 'group'; code: string; qtyMilli?: number; pence?: number; percentBp?: number }
  | { kind: 'deptTotal'; qtyMilli?: number; pence?: number; percentBp?: number }
  | { kind: 'plu'; code: string; name: string; qtyMilli?: number; pence?: number }
  | { kind: 'stat'; label: string; count?: number; pence?: number }

/**
 * Does this line open a new record?
 *
 * Anything that does not is a continuation of the one before it — which is how
 * "DRAUGHT BEERS  *1492.25" is understood to belong to the "D01" above it
 * rather than to be a record of its own.
 */
function starterFor(label: string): Pending | null {
  // One to three digits, normalised to the till's own two. A transcription may
  // tidy "D01" to "D1"; requiring exactly two digits would drop the whole
  // department on the floor over a leading zero.
  const dept = /^D\s?(\d{1,3})\b\s*(.*)$/.exec(label)
  if (dept?.[1]) {
    const n = Number(dept[1])
    const code = Number.isFinite(n) && n < 100 ? `D${String(n).padStart(2, '0')}` : `D${dept[1]}`
    return { kind: 'dept', code, name: (dept[2] ?? '').trim() }
  }

  const group = /^GROUP\s?(\d{1,3})\b/.exec(label)
  if (group?.[1]) {
    const n = Number(group[1])
    const code = Number.isFinite(n) && n < 100 ? `GROUP${String(n).padStart(2, '0')}` : `GROUP${group[1]}`
    return { kind: 'group', code }
  }

  if (label === 'DEPT TL') return { kind: 'deptTotal' }

  const plu = /^(P\d{4,})\b\s*(.*)$/.exec(label)
  if (plu?.[1]) return { kind: 'plu', code: plu[1], name: (plu[2] ?? '').trim() }

  if (STAT_LABELS.has(label)) return { kind: 'stat', label }

  return null
}

/** Counts print as quantities; the till uses the same column for both. */
const asCount = (qtyMilli: number | undefined): number | undefined =>
  qtyMilli === undefined ? undefined : qtyMilli / 1000

export function parseZRead(text: string): ZRead {
  const z = emptyZRead()
  let section: Section = 'head'
  let clerk: ClerkLine | null = null
  let pending: Pending | null = null
  /** Departments read since the last group line, awaiting one to belong to. */
  let ungrouped: DeptLine[] = []

  const closeClerk = () => {
    if (clerk) z.clerks.push(clerk)
    clerk = null
  }

  /** Write a finished statistic into whichever block is being read. */
  const putStat = (label: string, count?: number, pence?: number): void => {
    // Only the day's own TRANSACTION block and the clerk ***TOTAL — which
    // restate the same figures — may write the day summary.
    const target = section === 'clerk' && clerk ? clerk : null
    const t = z.transaction

    switch (label) {
      case 'NET1': if (!target) t.net1Pence = pence; return
      case 'NET2': if (!target) t.net2Pence = pence; return
      case 'VOID':
        if (target) { target.voidCount = count; target.voidPence = pence }
        else { t.voidCount = count; t.voidPence = pence }
        return
      case 'NO SALE': if (!target) t.noSaleCount = count; return
      case 'GUEST':
        if (target) target.guestCount = count
        else t.guestCount = count
        return
      case 'ORDER TL':
        if (target) target.orderTotalPence = pence
        else t.orderTotalPence = pence
        return
      case 'NON COM': if (target) target.nonComPence = pence; return
      case 'PAID TL':
        if (target) target.paidTotalPence = pence
        else t.paidTotalPence = pence
        return
      case 'AVE':
        if (target) target.avePence = pence
        else t.avePence = pence
        return
      case 'CASH':
        if (target) { target.cashCount = count; target.cashPence = pence }
        else { t.cashCount = count; t.cashPence = pence }
        return
      case 'CREDIT CARD':
        if (target) { target.cardCount = count; target.cardPence = pence }
        else { t.cardCount = count; t.cardPence = pence }
        return
      case 'CID':
        if (target) target.cidPence = pence
        else t.cidPence = pence
        return
      case 'CA/CHK ID': if (!target) t.caChkIdPence = pence; return
      default: return
    }
  }

  /** Commit whatever record was being accumulated. */
  const flush = (): void => {
    const p = pending
    pending = null
    if (!p) return

    switch (p.kind) {
      case 'dept': {
        if (p.pence === undefined) return
        const entry: DeptLine = {
          code: p.code,
          name: p.name,
          qtyMilli: p.qtyMilli ?? 0,
          pence: p.pence,
        }
        if (p.percentBp !== undefined) entry.percentBp = p.percentBp
        z.departments.push(entry)
        ungrouped.push(entry)
        return
      }
      case 'group': {
        if (p.pence === undefined) return
        z.groups.push({
          code: p.code,
          qtyMilli: p.qtyMilli ?? 0,
          pence: p.pence,
          ...(p.percentBp !== undefined ? { percentBp: p.percentBp } : {}),
        })
        // Everything read since the last group belongs to this one.
        for (const d of ungrouped) d.group = p.code
        ungrouped = []
        return
      }
      case 'deptTotal': {
        if (p.pence === undefined) return
        z.deptTotal = {
          qtyMilli: p.qtyMilli ?? 0,
          pence: p.pence,
          ...(p.percentBp !== undefined ? { percentBp: p.percentBp } : {}),
        }
        return
      }
      case 'plu': {
        if (p.pence === undefined) return
        z.plus.push({ code: p.code, name: p.name, qtyMilli: p.qtyMilli ?? 0, pence: p.pence })
        return
      }
      case 'stat':
        putStat(p.label, p.count, p.pence)
        return
    }
  }

  /**
   * Fold a line into the record being built.
   *
   * `isStarter` marks the line that opened the record. Its label is the code
   * ("D01"), so it must not be taken as the name — the name arrives on the
   * continuation line below it, or inline on a flattened transcription, where
   * the starter has already split it out.
   */
  const absorb = (p: Pending, f: Fields, isStarter = false): void => {
    if (f.qtyMilli !== undefined) {
      if (p.kind === 'stat') {
        if (p.count === undefined) p.count = asCount(f.qtyMilli)
      } else if (p.qtyMilli === undefined) {
        p.qtyMilli = f.qtyMilli
      }
    }
    if (f.pence !== undefined && p.pence === undefined) p.pence = f.pence
    if (f.percentBp !== undefined && p.kind !== 'stat' && p.kind !== 'plu' && p.percentBp === undefined) {
      p.percentBp = f.percentBp
    }
    // A department or item whose code arrived alone takes its name from the
    // next labelled line.
    if (!isStarter && (p.kind === 'dept' || p.kind === 'plu') && !p.name && f.label) p.name = f.label
  }

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd()
    // A blank line does NOT close a record. A transcription may put one between
    // a value and its percentage, and treating that as the end of the record
    // silently drops the percentage. Records are closed by the next starter or
    // section heading, both of which flush explicitly.
    if (!line.trim()) continue

    // --- header oddities, which do not fit the label/figures shape ---------
    const gt = /^\s*GT([123])\s+(-?)\s*\*?\s*([0-9][0-9.,]*)/.exec(line)
    if (gt) {
      flush()
      const pence = parsePence(`${gt[2] === '-' ? '-' : ''}${gt[3] ?? ''}`, {
        loose: true,
        maxPence: GRAND_TOTAL_MAX,
      })
      if (pence !== null) {
        if (gt[1] === '1') z.header.gt1Pence = pence
        if (gt[1] === '2') z.header.gt2Pence = pence
        if (gt[1] === '3') z.header.gt3Pence = pence
      }
      const zc = /\bZ1\s+(\d+)/.exec(line)
      if (zc?.[1]) z.header.zNumber = Number(zc[1])
      continue
    }

    const stamp = /^\s*#(\d+)\s+(\d{2}\/\d{2}\/\d{4}\s+\d{2}:\d{2}(?::\d{2})?)/.exec(line)
    if (stamp) {
      flush()
      z.header.receiptNo = stamp[1]
      z.header.printedAt = stamp[2]
      continue
    }

    if (section === 'head' && !z.header.clerk) {
      const whose = /^\s*(\d{3,4})\s+(CLERK\d+)\s*$/.exec(line)
      if (whose) {
        flush()
        z.header.clerk = `${whose[1]} ${whose[2]}`
        continue
      }
    }

    const clerkHead = /^\s*(CLK#\d+)\s*(\S+)?/.exec(line)
    if (clerkHead?.[1]) {
      flush()
      closeClerk()
      section = 'clerk'
      clerk = { code: clerkHead[1], ...(clerkHead[2] ? { name: clerkHead[2] } : {}) }
      continue
    }

    // The clerk section's own ***TOTAL restates the pub's night, so it closes
    // the last clerk and goes back to writing the day summary.
    if (/^\s*\*{2,}\s*TOTAL\s*$/.test(line)) {
      flush()
      closeClerk()
      section = 'clerkTotal'
      continue
    }

    const fields = readLine(line)
    if (!fields) continue

    // --- section headings ---------------------------------------------------
    if (/^ALL CLERK/.test(fields.label)) {
      flush()
      section = 'clerk'
      continue
    }
    if (/^TRANSACTION/.test(fields.label)) {
      flush()
      closeClerk()
      section = 'transaction'
      continue
    }
    if (/^DEPT\.?\/?GROUP/.test(fields.label)) {
      flush()
      section = 'dept'
      continue
    }
    if (/^PLU(\/EAN)?$/.test(fields.label) || /^EAN$/.test(fields.label) || /^SET (PLU|EAN)$/.test(fields.label)) {
      flush()
      section = 'plu'
      continue
    }

    // The item list's own total, which is a starter nowhere else.
    if (section === 'plu' && fields.label === 'TOTAL' && fields.pence !== undefined) {
      flush()
      z.pluTotal = { qtyMilli: fields.qtyMilli ?? 0, pence: fields.pence }
      continue
    }

    // --- records ------------------------------------------------------------
    const starter = starterFor(fields.label)
    if (starter) {
      flush()
      pending = starter
      absorb(pending, fields, true)
      if (starter.kind === 'dept' || starter.kind === 'group' || starter.kind === 'deptTotal') section = 'dept'
      if (starter.kind === 'plu') section = 'plu'
      continue
    }

    // Not a starter, so it continues the record above it.
    if (pending) absorb(pending, fields)
  }

  flush()
  closeClerk()
  return z
}
