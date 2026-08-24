// ---------------------------------------------------------------------------
// A Z read, in full.
//
// Modelled from a real one: the Gardeners Arms, 23/08/2026, Z counter 1685.
// That receipt turned out to carry far more than a session total — it has the
// department breakdown with its own percentages, the transaction statistics,
// the clerk split, the PLU list, and, crucially, the till's own view of how the
// money was taken: CASH £351.80 and CREDIT CARD £1,841.00, with CID stating
// what the drawer should contain.
//
// That last part reshapes the reconciliation. The card slip is no longer the
// only source for the card figure — it is a second opinion on a number the till
// has already stated, which is a far more useful thing to check.
//
// Units follow the rule set in money.ts: everything is an integer.
//   - money      -> pence            (£1,492.25 -> 149225)
//   - quantity   -> thousandths      (406.000 Q -> 406000, as the till prints)
//   - percentage -> basis points     (68.05%    -> 6805)
// ---------------------------------------------------------------------------

/** Header block: who, when, and the running lifetime totals. */
export interface ZReadHeader {
  /** The printed receipt number, e.g. "1233". */
  receiptNo?: string
  /**
   * The Z counter — 1685 on the reference receipt.
   *
   * It increments once per Z read, so a gap in it across saved days means a
   * night was never read off, and a repeat means the same read was entered
   * twice. It is the only field here that can detect a *missing* night.
   */
  zNumber?: number
  clerk?: string
  /** Exactly as printed, e.g. "23/08/2026 21:39:16". Not reformatted. */
  printedAt?: string
  /** Running grand totals. Their day-on-day change re-derives the day's net. */
  gt1Pence?: number
  gt2Pence?: number
  gt3Pence?: number
}

export interface DeptLine {
  /** "D01" */
  code: string
  /** "DRAUGHT BEERS" */
  name: string
  qtyMilli: number
  pence: number
  percentBp?: number
  /** "GROUP01" — which group subtotal this department rolls into. */
  group?: string
}

export interface GroupLine {
  code: string
  qtyMilli: number
  pence: number
  percentBp?: number
}

export interface TotalLine {
  qtyMilli: number
  pence: number
  percentBp?: number
}

/** The TRANSACTION block. Every field optional — a roll may be torn or unlit. */
export interface TransactionSummary {
  net1Pence?: number
  net2Pence?: number
  voidCount?: number
  voidPence?: number
  noSaleCount?: number
  guestCount?: number
  orderTotalPence?: number
  paidTotalPence?: number
  avePence?: number
  cashCount?: number
  cashPence?: number
  cardCount?: number
  cardPence?: number
  /** Cash In Drawer — what the till says should be there. The cash benchmark. */
  cidPence?: number
  caChkIdPence?: number
}

export interface ClerkLine {
  /** "CLK#0004" */
  code: string
  name?: string
  orderTotalPence?: number
  nonComPence?: number
  paidTotalPence?: number
  avePence?: number
  voidCount?: number
  voidPence?: number
  guestCount?: number
  cashCount?: number
  cashPence?: number
  cardCount?: number
  cardPence?: number
  cidPence?: number
}

export interface PluLine {
  /** "P00014" */
  code: string
  name: string
  qtyMilli: number
  pence: number
}

export interface ZRead {
  header: ZReadHeader
  departments: DeptLine[]
  groups: GroupLine[]
  deptTotal?: TotalLine
  transaction: TransactionSummary
  clerks: ClerkLine[]
  plus: PluLine[]
  pluTotal?: TotalLine
}

export function emptyZRead(): ZRead {
  return { header: {}, departments: [], groups: [], transaction: {}, clerks: [], plus: [] }
}

/** True when nothing at all was captured, so the interface can stay out of the way. */
export function isZReadEmpty(z: ZRead | undefined): boolean {
  if (!z) return true
  return (
    z.departments.length === 0 &&
    z.groups.length === 0 &&
    z.clerks.length === 0 &&
    z.plus.length === 0 &&
    !z.deptTotal &&
    Object.keys(z.transaction).length === 0 &&
    Object.keys(z.header).length === 0
  )
}

/** Quantity as printed: 406000 -> "406". */
export function formatQty(qtyMilli: number): string {
  const whole = qtyMilli / 1000
  if (Number.isInteger(whole)) return String(whole)
  // Trailing zeroes go, and so does a decimal point left stranded by them.
  return whole.toFixed(3).replace(/0+$/, '').replace(/\.$/, '')
}

/** 6805 -> "68.05%". */
export function formatPercent(bp: number): string {
  return `${(bp / 100).toFixed(2)}%`
}

/** Share of a total, in basis points. Guards the empty-night divide-by-zero. */
export function shareBp(part: number, total: number): number {
  if (total === 0) return 0
  return Math.round((part / total) * 10000)
}

/** The parts of a roll, which arrive on different photographs. */
export type ZReadSection = 'departments' | 'totals' | 'clerks' | 'items'

const SECTION_LABELS: Record<ZReadSection, string> = {
  departments: 'Departments',
  totals: 'Totals and payments',
  clerks: 'By clerk',
  items: 'Items',
}

export function sectionLabel(section: ZReadSection): string {
  return SECTION_LABELS[section]
}

/**
 * Which sections a parsed read actually contains.
 *
 * The roll is longer than a phone camera's frame, so it arrives as several
 * photographs and nobody should have to remember which is which. This is how
 * each one announces itself — "Items", "Departments" — so a missing part of the
 * roll is visible rather than silently absent.
 */
export function sectionsIn(z: ZRead): ZReadSection[] {
  const found: ZReadSection[] = []
  if (z.departments.length > 0 || z.groups.length > 0 || z.deptTotal) found.push('departments')
  if (Object.keys(z.transaction).length > 0) found.push('totals')
  if (z.clerks.length > 0) found.push('clerks')
  if (z.plus.length > 0) found.push('items')
  return found
}

/**
 * Merge a freshly-scanned section into what has been captured so far.
 *
 * A Z read is a long roll and comes in as several photographs, each showing
 * different sections. Later readings win on scalar fields they actually carry,
 * and lists are merged by code so re-photographing one section does not wipe
 * another — the common case being the summary photographed after the PLU list.
 */
export function mergeZRead(base: ZRead, incoming: ZRead): ZRead {
  const mergeList = <T extends { code: string }>(a: T[], b: T[]): T[] => {
    const out = new Map<string, T>()
    for (const item of a) out.set(item.code, item)
    for (const item of b) out.set(item.code, { ...out.get(item.code), ...item })
    return [...out.values()]
  }
  const defined = <T extends object>(o: T): Partial<T> =>
    Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== '')) as Partial<T>

  return {
    header: { ...base.header, ...defined(incoming.header) },
    departments: mergeList(base.departments, incoming.departments),
    groups: mergeList(base.groups, incoming.groups),
    deptTotal: incoming.deptTotal ?? base.deptTotal,
    transaction: { ...base.transaction, ...defined(incoming.transaction) },
    clerks: mergeList(base.clerks, incoming.clerks),
    plus: mergeList(base.plus, incoming.plus),
    pluTotal: incoming.pluTotal ?? base.pluTotal,
  }
}
