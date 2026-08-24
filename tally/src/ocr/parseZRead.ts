// ---------------------------------------------------------------------------
// Reading a whole Z read out of scanned text.
//
// This till prints the same layout every night, so the parser can be specific
// rather than defensive — and specific is what makes it trustworthy. The
// structure it walks:
//
//   header      #1233, date/time, GT1..GT3, the Z counter
//   DEPT./GROUP D01..D08 lines, GROUP01/02 subtotals, *DEPT TL
//   TRANSACTION NET, VOID, NO SALE, GUEST, ORDER/PAID TL, AVE, CASH, CARD, CID
//   ALL CLERK   the same shape again per clerk, then ***TOTAL
//   PLU         P00001.. item lines, then ***TOTAL
//
// The repetition is the trap. CASH, CREDIT CARD, PAID TL and CID all appear
// once in TRANSACTION, again under every clerk, and a third time under the
// clerk ***TOTAL. A parser that simply matched labels would end up reporting
// one clerk's figures as the pub's night. So this one tracks which section it
// is standing in, and only the TRANSACTION block and the clerk ***TOTAL — which
// state the same numbers — are allowed to write the day's summary.
// ---------------------------------------------------------------------------

import { parsePence } from '../core/money.ts'
import {
  emptyZRead,
  type ClerkLine,
  type DeptLine,
  type ZRead,
} from '../core/zread.ts'

type Section = 'head' | 'dept' | 'transaction' | 'clerk' | 'clerkTotal' | 'plu'

/** The running grand totals accumulate for the life of the till. £10m is plenty. */
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
  if (!Number.isFinite(n)) return undefined
  return Math.round(n * 1000)
}

function toBp(text: string): number | undefined {
  const n = Number(text.replace(/,/g, ''))
  if (!Number.isFinite(n)) return undefined
  return Math.round(n * 100)
}

/**
 * Split a printed line into its label and its figures.
 *
 * Order matters: the percentage is taken off the end first, then the starred
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

  // Amounts are printed with a leading star; a minus may sit before it.
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
  if (!label && pence === undefined && qtyMilli === undefined) return null
  return { label, qtyMilli, pence, percentBp }
}

/** Which section a line announces, if any. */
function sectionFor(label: string, line: string): Section | null {
  if (/^ALL CLERK/.test(label)) return 'clerk'
  if (/^TRANSACTION/.test(label)) return 'transaction'
  if (/^DEPT\.?\/GROUP/.test(label) || /^DEPT\/GROUP/.test(label)) return 'dept'
  if (/^PLU(\/EAN)?$/.test(label)) return 'plu'
  if (/^P\d{4,}/.test(line.trim())) return 'plu'
  return null
}

export function parseZRead(text: string): ZRead {
  const z = emptyZRead()
  let section: Section = 'head'
  let clerk: ClerkLine | null = null

  const closeClerk = () => {
    if (clerk) z.clerks.push(clerk)
    clerk = null
  }

  /** Write a tender/statistic to whichever block is currently being read. */
  const put = (f: Fields): void => {
    const target = section === 'clerk' && clerk ? clerk : null
    const t = z.transaction

    switch (f.label) {
      case 'NET1':
        if (!target) t.net1Pence = f.pence
        return
      case 'NET2':
        if (!target) t.net2Pence = f.pence
        return
      case 'VOID':
        if (target) {
          target.voidCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
          target.voidPence = f.pence
        } else {
          t.voidCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
          t.voidPence = f.pence
        }
        return
      case 'NO SALE':
        if (!target) t.noSaleCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
        return
      case 'GUEST':
        if (target) target.guestCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
        else t.guestCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
        return
      case 'ORDER TL':
        if (target) target.orderTotalPence = f.pence
        else t.orderTotalPence = f.pence
        return
      case 'NON COM':
        if (target) target.nonComPence = f.pence
        return
      case 'PAID TL':
        if (target) target.paidTotalPence = f.pence
        else t.paidTotalPence = f.pence
        return
      case 'AVE':
        if (target) target.avePence = f.pence
        else t.avePence = f.pence
        return
      case 'CASH':
        if (target) {
          target.cashCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
          target.cashPence = f.pence
        } else {
          t.cashCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
          t.cashPence = f.pence
        }
        return
      case 'CREDIT CARD':
        if (target) {
          target.cardCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
          target.cardPence = f.pence
        } else {
          t.cardCount = f.qtyMilli === undefined ? undefined : f.qtyMilli / 1000
          t.cardPence = f.pence
        }
        return
      case 'CID':
        if (target) target.cidPence = f.pence
        else t.cidPence = f.pence
        return
      case 'CA/CHK ID':
        if (!target) t.caChkIdPence = f.pence
        return
      default:
        return
    }
  }

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd()
    if (!line.trim()) continue

    // --- header oddities, which do not fit the label/figures shape ---------
    const gt = /^\s*GT([123])\s+(-?)\s*\*?\s*([0-9][0-9.,]*)/.exec(line)
    if (gt) {
      // A lifetime total, not a night's — GRAND_TOTAL_MAX rather than the
      // nightly sanity cap, which £140,111.26 would fail.
      const pence = parsePence(`${gt[2] === '-' ? '-' : ''}${gt[3] ?? ''}`, {
        loose: true,
        maxPence: GRAND_TOTAL_MAX,
      })
      if (pence !== null) {
        if (gt[1] === '1') z.header.gt1Pence = pence
        if (gt[1] === '2') z.header.gt2Pence = pence
        if (gt[1] === '3') z.header.gt3Pence = pence
      }
      // The Z counter rides on the GT1 line: "GT1 *0000140111.26  Z1  1685".
      const zc = /\bZ1\s+(\d+)/.exec(line)
      if (zc?.[1]) z.header.zNumber = Number(zc[1])
      continue
    }

    const stamp = /^\s*#(\d+)\s+(\d{2}\/\d{2}\/\d{4}\s+\d{2}:\d{2}(?::\d{2})?)/.exec(line)
    if (stamp) {
      z.header.receiptNo = stamp[1]
      z.header.printedAt = stamp[2]
      continue
    }

    // "0004 CLERK0004" in the header names whoever took the read. Matched only
    // in the header, so the ALL CLERK section cannot be dragged in by it.
    if (section === 'head' && !z.header.clerk) {
      const whose = /^\s*(\d{3,4})\s+(CLERK\d+)\s*$/.exec(line)
      if (whose) {
        z.header.clerk = `${whose[1]} ${whose[2]}`
        continue
      }
    }

    const clerkHead = /^\s*(CLK#\d+)\s*(\S+)?/.exec(line)
    if (clerkHead?.[1]) {
      closeClerk()
      section = 'clerk'
      clerk = { code: clerkHead[1], name: clerkHead[2] }
      continue
    }

    // The clerk section's own ***TOTAL restates the pub's night, so it closes
    // the last clerk and goes back to writing the day summary.
    if (/^\s*\*{2,}\s*TOTAL\s*$/.test(line)) {
      closeClerk()
      section = 'clerkTotal'
      continue
    }

    const fields = readLine(line)
    if (!fields) continue

    const announced = sectionFor(fields.label, line)
    if (announced) {
      if (announced !== 'clerk') closeClerk()
      section = announced
      if (announced === 'plu' && !/^P\d{4,}/.test(line.trim())) continue
      if (announced !== 'plu') continue
    }

    // --- department, group and total lines ---------------------------------
    const dept = /^(D\d{2})\s+(.*)$/.exec(fields.label)
    if (dept?.[1] && fields.pence !== undefined) {
      const entry: DeptLine = {
        code: dept[1],
        name: (dept[2] ?? '').trim(),
        qtyMilli: fields.qtyMilli ?? 0,
        pence: fields.pence,
      }
      if (fields.percentBp !== undefined) entry.percentBp = fields.percentBp
      z.departments.push(entry)
      section = 'dept'
      continue
    }

    const group = /^(GROUP\d+)$/.exec(fields.label)
    if (group?.[1] && fields.pence !== undefined) {
      z.groups.push({
        code: group[1],
        qtyMilli: fields.qtyMilli ?? 0,
        pence: fields.pence,
        ...(fields.percentBp !== undefined ? { percentBp: fields.percentBp } : {}),
      })
      // Departments read since the last group belong to this one.
      for (const d of z.departments) if (!d.group) d.group = group[1]
      continue
    }

    if (fields.label === 'DEPT TL' && fields.pence !== undefined) {
      z.deptTotal = {
        qtyMilli: fields.qtyMilli ?? 0,
        pence: fields.pence,
        ...(fields.percentBp !== undefined ? { percentBp: fields.percentBp } : {}),
      }
      continue
    }

    // --- item lines --------------------------------------------------------
    const plu = /^(P\d{4,})\s+(.*)$/.exec(fields.label)
    if (plu?.[1] && fields.pence !== undefined) {
      z.plus.push({
        code: plu[1],
        name: (plu[2] ?? '').trim(),
        qtyMilli: fields.qtyMilli ?? 0,
        pence: fields.pence,
      })
      section = 'plu'
      continue
    }

    if (section === 'plu' && fields.label === 'TOTAL' && fields.pence !== undefined) {
      z.pluTotal = { qtyMilli: fields.qtyMilli ?? 0, pence: fields.pence }
      continue
    }

    put(fields)
  }

  closeClerk()
  return z
}
