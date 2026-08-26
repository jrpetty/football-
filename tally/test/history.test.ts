import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { at, marginMoves, record, type CostPoint, type PricePoint } from '../src/core/history.ts'
import { ML_PER_PINT, type Pour, type StockItem } from '../src/core/stock.ts'
import type { PriceBookEntry } from '../src/core/priceBook.ts'

// --- the log ------------------------------------------------------------------

test('a change is appended in date order however it arrives', () => {
  let log: PricePoint[] = []
  log = record(log, { date: '2026-03-01', pence: 400 })
  log = record(log, { date: '2026-01-01', pence: 380 })
  assert.deepEqual(log.map((p) => p.date), ['2026-01-01', '2026-03-01'])
})

test('correcting a typo the same day is one change, not two', () => {
  // Otherwise the history fills up with typing rather than with decisions.
  let log: PricePoint[] = [{ date: '2026-03-01', pence: 400 }]
  log = record(log, { date: '2026-03-01', pence: 420 })
  assert.equal(log.length, 1)
  assert.equal(log[0]!.pence, 420)
})

test('re-entering the same figure records nothing', () => {
  // Opening the screen and closing it must not write a point.
  const log: PricePoint[] = [{ date: '2026-01-01', pence: 400 }]
  assert.equal(record(log, { date: '2026-06-01', pence: 400 }).length, 1)
})

test('a cost at the same price but a different container size is a real change', () => {
  // £95 a firkin and £95 a kil are not the same cost.
  const log: CostPoint[] = [{ date: '2026-01-01', pence: 9500, baseUnits: 72 * ML_PER_PINT }]
  const next = record(log, { date: '2026-06-01', pence: 9500, baseUnits: 144 * ML_PER_PINT })
  assert.equal(next.length, 2)
})

test('what was in force on a date is the last change on or before it', () => {
  const log: PricePoint[] = [
    { date: '2026-01-01', pence: 380 },
    { date: '2026-06-01', pence: 400 },
  ]
  assert.equal(at(log, '2026-05-31')?.pence, 380)
  assert.equal(at(log, '2026-06-01')?.pence, 400)
  assert.equal(at(log, '2026-12-31')?.pence, 400)
  assert.equal(at(log, '2025-12-31'), null, 'before anything was recorded')
})

// --- did the board keep up? -----------------------------------------------------

const pour: Pour = { itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT }

function taddy(history: CostPoint[]): StockItem {
  return {
    id: 'taddy', name: 'Taddy Lager', kind: 'liquid',
    servingBaseUnits: ML_PER_PINT, servingName: 'pint',
    container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
    cost: history.at(-1) ? { pence: history.at(-1)!.pence, baseUnits: history.at(-1)!.baseUnits } : undefined,
    costHistory: history,
  }
}

function entry(history: PricePoint[]): PriceBookEntry {
  return { code: '1', name: 'PINT TADDY LAGER', pence: history.at(-1)?.pence ?? 400, history }
}

const firkin = 72 * ML_PER_PINT

test('a cost rise the board ignored reads as squeezed', () => {
  // The whole point: £95 to £108 a firkin, price left at £4.00. Nothing fails
  // to balance; the pint just makes eighteen pence less.
  const moves = marginMoves(
    [taddy([
      { date: '2026-01-01', pence: 9500, baseUnits: firkin },
      { date: '2026-06-01', pence: 10800, baseUnits: firkin },
    ])],
    [pour],
    [entry([{ date: '2026-01-01', pence: 400 }])],
  )
  assert.equal(moves.length, 1)
  const m = moves[0]!
  assert.equal(m.verdict, 'squeezed')
  assert.equal(m.costThenPence, 132)
  assert.equal(m.costNowPence, 150)
  assert.equal(m.priceNowPence, 400, 'the board never moved')
  assert.ok(m.gpChangeBp < -400, `GP fell ${m.gpChangeBp} basis points`)
})

test('a cost rise the board followed holds the margin', () => {
  const moves = marginMoves(
    [taddy([
      { date: '2026-01-01', pence: 9500, baseUnits: firkin },
      { date: '2026-06-01', pence: 10800, baseUnits: firkin },
    ])],
    [pour],
    [entry([
      { date: '2026-01-01', pence: 400 },
      { date: '2026-06-01', pence: 455 },
    ])],
  )
  assert.equal(moves[0]!.verdict, 'kept up')
  assert.ok(Math.abs(moves[0]!.gpChangeBp) < 100)
})

test('putting the price up without a cost rise improves the margin', () => {
  const moves = marginMoves(
    [taddy([{ date: '2026-01-01', pence: 9500, baseUnits: firkin }])],
    [pour],
    [entry([
      { date: '2026-01-01', pence: 400 },
      { date: '2026-06-01', pence: 440 },
    ])],
  )
  assert.equal(moves[0]!.verdict, 'improved')
  assert.ok(moves[0]!.gpChangeBp > 0)
})

test('a line nothing has happened to is not reported', () => {
  const moves = marginMoves(
    [taddy([{ date: '2026-01-01', pence: 9500, baseUnits: firkin }])],
    [pour],
    [entry([{ date: '2026-01-01', pence: 400 }])],
  )
  assert.equal(moves.length, 0, 'nothing has moved, so there is nothing to say')
})

test('a line with no price in the book is skipped rather than guessed at', () => {
  const moves = marginMoves(
    [taddy([
      { date: '2026-01-01', pence: 9500, baseUnits: firkin },
      { date: '2026-06-01', pence: 10800, baseUnits: firkin },
    ])],
    [pour],
    [],
  )
  assert.equal(moves.length, 0)
})

test('the worst squeeze is listed first', () => {
  const alpine: Pour = { itemCode: '3', itemName: 'PINT ALPINE', stockItemId: 'alpine', baseUnits: ML_PER_PINT }
  const alpineItem: StockItem = {
    ...taddy([
      { date: '2026-01-01', pence: 9500, baseUnits: firkin },
      { date: '2026-06-01', pence: 9900, baseUnits: firkin },
    ]),
    id: 'alpine', name: 'Alpine',
  }
  const moves = marginMoves(
    [
      taddy([
        { date: '2026-01-01', pence: 9500, baseUnits: firkin },
        { date: '2026-06-01', pence: 12000, baseUnits: firkin },
      ]),
      alpineItem,
    ],
    [pour, alpine],
    [entry([{ date: '2026-01-01', pence: 400 }]), { code: '3', name: 'PINT ALPINE', pence: 300, history: [{ date: '2026-01-01', pence: 300 }] }],
  )
  assert.equal(moves[0]!.code, '1', 'the biggest fall comes first')
  assert.ok(moves[0]!.gpChangeBp < moves[1]!.gpChangeBp)
})

test('a container size change is costed on the basis in force at the time', () => {
  // £95 a firkin then, £180 a kil now — that is a price cut per pint, not a rise.
  const moves = marginMoves(
    [taddy([
      { date: '2026-01-01', pence: 9500, baseUnits: firkin },
      { date: '2026-06-01', pence: 18000, baseUnits: 144 * ML_PER_PINT },
    ])],
    [pour],
    [entry([{ date: '2026-01-01', pence: 400 }])],
  )
  assert.equal(moves[0]!.costThenPence, 132)
  assert.equal(moves[0]!.costNowPence, 125, '£180 across 144 pints')
  assert.equal(moves[0]!.verdict, 'improved')
})
