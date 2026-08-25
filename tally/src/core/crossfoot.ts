// ---------------------------------------------------------------------------
// Making the receipt check its own reading.
//
// A Z read is heavily redundant. The departments sum to the department total.
// Cash plus card equals the paid total. The transaction counts sum to the guest
// count. The paid total divided by the guests is the printed average. Each
// department's percentage recomputes from its own value. The PLU list sums to
// the same figure as the departments.
//
// None of that is decoration — it is a set of simultaneous equations, and a
// misread digit almost always breaks at least one of them. That makes this a
// far better guide to whether a scan is right than any confidence score an OCR
// engine can offer: instead of "80% sure", it says "the departments add up to
// £2,192.40 but the total says £2,192.80 — one of these six lines is wrong".
//
// The author of this file was himself caught by it. Transcribing the reference
// receipt by eye, the PLU list came out £25.35 over the department total; the
// spirits lines had been read off by a column. The check found it immediately.
// ---------------------------------------------------------------------------

import { formatMoney } from './money.ts'
import { formatPercent, shareBp, type ZRead } from './zread.ts'

export type CheckSeverity = 'error' | 'warning'

export interface CrossfootCheck {
  id: string
  /** What was compared, in words she would use. */
  label: string
  ok: boolean
  /** Present when the check ran and could be quantified. */
  expected?: string
  actual?: string
  severity: CheckSeverity
  /** Which fields to look at when it fails. */
  fields: string[]
}

/** The till rounds its own average, so allow the last penny either way. */
const PENNY = 1
/** Percentages are printed to two places; allow one basis point of rounding. */
const BP = 1

function sum(values: number[]): number {
  return values.reduce((a, b) => a + b, 0)
}

function moneyCheck(
  id: string,
  label: string,
  expected: number | undefined,
  actual: number | undefined,
  fields: string[],
  severity: CheckSeverity = 'error',
  tolerance = 0,
): CrossfootCheck | null {
  if (expected === undefined || actual === undefined) return null
  return {
    id,
    label,
    ok: Math.abs(expected - actual) <= tolerance,
    expected: formatMoney(expected),
    actual: formatMoney(actual),
    severity,
    fields,
  }
}

function countCheck(
  id: string,
  label: string,
  expected: number | undefined,
  actual: number | undefined,
  fields: string[],
  severity: CheckSeverity = 'error',
): CrossfootCheck | null {
  if (expected === undefined || actual === undefined) return null
  return {
    id,
    label,
    ok: expected === actual,
    expected: String(expected),
    actual: String(actual),
    severity,
    fields,
  }
}

/**
 * Run every equation the receipt states about itself.
 *
 * Only checks whose inputs were all captured are returned — a torn roll that
 * never showed the PLU list should not be accused of failing a PLU check.
 */
export function crossfoot(z: ZRead): CrossfootCheck[] {
  const out: (CrossfootCheck | null)[] = []
  const t = z.transaction
  const deptTotalPence = z.deptTotal?.pence

  if (z.departments.length > 0) {
    out.push(
      moneyCheck(
        'departments-sum',
        'The departments add up to the department total',
        deptTotalPence,
        sum(z.departments.map((d) => d.pence)),
        ['departments', 'deptTotal'],
      ),
    )
    if (z.deptTotal?.qtyMilli !== undefined) {
      out.push(
        countCheck(
          'departments-qty',
          'The department quantities add up',
          z.deptTotal.qtyMilli,
          sum(z.departments.map((d) => d.qtyMilli)),
          ['departments', 'deptTotal'],
        ),
      )
    }
  }

  // Each group subtotal against the departments filed under it.
  for (const group of z.groups) {
    const members = z.departments.filter((d) => d.group === group.code)
    if (members.length === 0) continue
    out.push(
      moneyCheck(
        `group-${group.code}`,
        `${group.code} matches the departments inside it`,
        group.pence,
        sum(members.map((m) => m.pence)),
        ['groups', 'departments'],
      ),
    )
  }

  if (z.groups.length > 0 && deptTotalPence !== undefined) {
    out.push(
      moneyCheck(
        'groups-sum',
        'The groups add up to the department total',
        deptTotalPence,
        sum(z.groups.map((g) => g.pence)),
        ['groups', 'deptTotal'],
      ),
    )
  }

  // How the money was taken must equal how much was taken.
  if (t.cashPence !== undefined && t.cardPence !== undefined) {
    out.push(
      moneyCheck(
        'tenders-sum',
        'Cash plus card equals the paid total',
        t.paidTotalPence,
        t.cashPence + t.cardPence,
        ['cashPence', 'cardPence', 'paidTotalPence'],
      ),
    )
  }

  if (t.cashCount !== undefined && t.cardCount !== undefined) {
    out.push(
      countCheck(
        'tender-counts',
        'The cash and card transactions add up to the guest count',
        t.guestCount,
        t.cashCount + t.cardCount,
        ['cashCount', 'cardCount', 'guestCount'],
      ),
    )
  }

  if (t.paidTotalPence !== undefined && t.guestCount !== undefined && t.guestCount > 0 && t.avePence !== undefined) {
    out.push(
      moneyCheck(
        'average',
        'The average spend matches the total divided by the guests',
        Math.round(t.paidTotalPence / t.guestCount),
        t.avePence,
        ['avePence', 'paidTotalPence', 'guestCount'],
        'warning',
        PENNY,
      ),
    )
  }

  out.push(
    moneyCheck(
      'order-equals-paid',
      'The order total and the paid total agree',
      t.orderTotalPence,
      t.paidTotalPence,
      ['orderTotalPence', 'paidTotalPence'],
    ),
  )

  if (deptTotalPence !== undefined) {
    out.push(
      moneyCheck(
        'net-equals-departments',
        'The net total matches the departments',
        deptTotalPence,
        t.net1Pence,
        ['net1Pence', 'deptTotal'],
      ),
    )
    out.push(
      moneyCheck(
        'paid-equals-departments',
        'The paid total matches the departments',
        deptTotalPence,
        t.paidTotalPence,
        ['paidTotalPence', 'deptTotal'],
      ),
    )
  }

  // A float or a payout legitimately separates these, so it is a warning that
  // asks a question rather than an error that says something is wrong.
  out.push(
    moneyCheck(
      'cid-matches-cash',
      'Cash in drawer matches the cash taken',
      t.cashPence,
      t.cidPence,
      ['cidPence', 'cashPence'],
      'warning',
    ),
  )

  if (z.plus.length > 0) {
    out.push(
      moneyCheck(
        'plu-sum',
        'The item list adds up to its own total',
        z.pluTotal?.pence,
        sum(z.plus.map((p) => p.pence)),
        ['plus', 'pluTotal'],
      ),
    )
  }

  if (z.pluTotal && deptTotalPence !== undefined) {
    out.push(
      moneyCheck(
        'plu-matches-departments',
        'The item list agrees with the departments',
        deptTotalPence,
        z.pluTotal.pence,
        ['pluTotal', 'deptTotal'],
      ),
    )
  }

  if (z.clerks.length > 0 && t.paidTotalPence !== undefined) {
    const clerkTotals = z.clerks
      .map((c) => c.paidTotalPence)
      .filter((v): v is number => v !== undefined)
    if (clerkTotals.length === z.clerks.length) {
      out.push(
        moneyCheck(
          'clerks-sum',
          'The clerks add up to the paid total',
          t.paidTotalPence,
          sum(clerkTotals),
          ['clerks', 'paidTotalPence'],
        ),
      )
    }
  }

  // Each printed percentage against the value it claims to describe.
  if (deptTotalPence !== undefined && deptTotalPence > 0) {
    for (const dept of z.departments) {
      if (dept.percentBp === undefined) continue
      const expected = shareBp(dept.pence, deptTotalPence)
      out.push({
        id: `percent-${dept.code}`,
        label: `${dept.name} percentage matches its value`,
        ok: Math.abs(expected - dept.percentBp) <= BP,
        expected: formatPercent(expected),
        actual: formatPercent(dept.percentBp),
        severity: 'warning',
        fields: ['departments'],
      })
    }
  }

  // The three running grand totals describe each other: the gross is the net
  // plus what was voided out of it. Inferred from one receipt rather than from
  // a manual, so it is a warning — but it holds to the penny there, and it
  // caught a digit misread out of "-00000021185.57", where the padding zeros
  // hide where the figure starts.
  if (z.header.gt1Pence !== undefined && z.header.gt2Pence !== undefined && z.header.gt3Pence !== undefined) {
    out.push(
      moneyCheck(
        'grand-totals',
        'The running grand totals agree with each other',
        z.header.gt2Pence,
        z.header.gt1Pence + Math.abs(z.header.gt3Pence),
        ['header.gt1Pence', 'header.gt2Pence', 'header.gt3Pence'],
        'warning',
      ),
    )
  }

  return out.filter((c): c is CrossfootCheck => c !== null)
}

export interface CrossfootVerdict {
  checks: CrossfootCheck[]
  passed: number
  /** Failures that mean a figure is wrong. */
  errors: CrossfootCheck[]
  /** Failures with an innocent explanation, worth a glance. */
  warnings: CrossfootCheck[]
  /** Nothing disagrees. Not proof, but a strong signal the reading is right. */
  clean: boolean
}

export function crossfootVerdict(z: ZRead): CrossfootVerdict {
  const checks = crossfoot(z)
  const failed = checks.filter((c) => !c.ok)
  const errors = failed.filter((c) => c.severity === 'error')
  const warnings = failed.filter((c) => c.severity === 'warning')
  return {
    checks,
    passed: checks.length - failed.length,
    errors,
    warnings,
    clean: failed.length === 0 && checks.length > 0,
  }
}

/**
 * Checks that need last night as well as tonight.
 *
 * The Z counter is the only field on the whole receipt that can reveal a night
 * that was never entered at all — the figures for a missing day are not wrong,
 * they are simply absent, and nothing inside a single receipt can notice that.
 */
export function checkContinuity(previous: ZRead, current: ZRead): CrossfootCheck[] {
  const out: (CrossfootCheck | null)[] = []

  if (previous.header.zNumber !== undefined && current.header.zNumber !== undefined) {
    out.push(
      countCheck(
        'z-sequence',
        'This is the next Z read after the last one saved',
        previous.header.zNumber + 1,
        current.header.zNumber,
        ['header.zNumber'],
        'warning',
      ),
    )
  }

  // The running grand total should have moved by exactly tonight's net.
  if (
    previous.header.gt1Pence !== undefined &&
    current.header.gt1Pence !== undefined &&
    current.deptTotal?.pence !== undefined
  ) {
    out.push(
      moneyCheck(
        'gt1-delta',
        "The running grand total moved by tonight's takings",
        current.deptTotal.pence,
        current.header.gt1Pence - previous.header.gt1Pence,
        ['header.gt1Pence', 'deptTotal'],
        'warning',
      ),
    )
  }

  return out.filter((c): c is CrossfootCheck => c !== null)
}
