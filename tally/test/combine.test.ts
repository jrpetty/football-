// ---------------------------------------------------------------------------
// Counting things the way they are sold.
//
// The till and the cellar do not always count at the same grain: six flavours
// of crisp come in and go out through one button marked CRISPS. Counted apart,
// no sale can come off any of them without somebody guessing which.
// ---------------------------------------------------------------------------

import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  combineStockLines,
  linesNothingSells,
  ML_PER_PINT,
  type Delivery,
  type Pour,
  type StockCount,
  type StockItem,
} from '../src/core/stock.ts'

const packet = (id: string, name: string, container = false): StockItem => ({
  id, name, kind: 'count', servingBaseUnits: 1, servingName: 'unit',
  ...(container ? { container: { name: 'box', baseUnits: 25 }, cost: { pence: 750, baseUnits: 25 } } : {}),
})

const CRISPS = [
  packet('crisps-cheese', 'Crisps — cheese', true),
  packet('crisps-steak', 'Crisps — steak'),
  packet('crisps-vinegar', 'Crisps — vinegar'),
]
const IDS = CRISPS.map((i) => i.id)

const count = (date: string, lines: Array<[string, number]>): StockCount => ({
  date, lines: lines.map(([stockItemId, baseUnits]) => ({ stockItemId, baseUnits })),
})

function fold(over: Partial<Parameters<typeof combineStockLines>[0]> = {}) {
  return combineStockLines({
    items: CRISPS, pours: [], counts: [], deliveries: [],
    into: { id: 'crisps', name: 'Crisps' }, from: IDS,
    ...over,
  })
}

test('six lines become one, and the old ones are gone', () => {
  const out = fold()
  assert.deepEqual(out.items.map((i) => i.id), ['crisps'])
  assert.equal(out.items[0]?.name, 'Crisps')
  assert.deepEqual(out.absorbed.sort(), [...IDS].sort())
})

test('the total is the sum of what was counted', () => {
  const out = fold({ counts: [count('2026-09-16', [['crisps-cheese', 107], ['crisps-steak', 66], ['crisps-vinegar', 106]])] })
  assert.deepEqual(out.counts[0]?.lines, [{ stockItemId: 'crisps', baseUnits: 279 }])
})

test('a part-counted total is no total at all', () => {
  // Three counted flavours and three blank ones added together would turn a
  // partial count into a whole one, and the difference would read as stock
  // that walked.
  const out = fold({ counts: [count('2026-09-16', [['crisps-cheese', 107]])] })
  assert.deepEqual(out.counts[0]?.lines, [], 'uncounted, which is not none')
})

test('lines that are nothing to do with it are left alone', () => {
  const out = fold({ counts: [count('2026-09-16', [['taddy', 500], ['crisps-cheese', 107], ['crisps-steak', 66], ['crisps-vinegar', 106]])] })
  assert.deepEqual(
    out.counts[0]?.lines.sort((a, b) => a.stockItemId.localeCompare(b.stockItemId)),
    [{ stockItemId: 'crisps', baseUnits: 279 }, { stockItemId: 'taddy', baseUnits: 500 }],
  )
})

test('the container and the cost come across', () => {
  const out = fold()
  assert.equal(out.items[0]?.container?.name, 'box')
  assert.equal(out.items[0]?.container?.baseUnits, 25)
  assert.equal(out.items[0]?.cost?.pence, 750)
})

test('pours pointing at any of them point at the total instead', () => {
  const pours: Pour[] = [
    { itemCode: '1', itemName: 'CRISPS CHEESE', stockItemId: 'crisps-cheese', baseUnits: 1 },
    { itemCode: '2', itemName: 'PINT TADDY', stockItemId: 'taddy', baseUnits: ML_PER_PINT },
  ]
  const out = fold({ pours })
  assert.equal(out.pours.find((p) => p.itemCode === '1')?.stockItemId, 'crisps')
  assert.equal(out.pours.find((p) => p.itemCode === '2')?.stockItemId, 'taddy', 'untouched')
})

test('deliveries are added up onto the total too', () => {
  // A box booked in against one flavour must not vanish when the line does.
  const deliveries: Delivery[] = [
    { id: 'd1', date: '2026-09-14', lines: [{ stockItemId: 'crisps-cheese', baseUnits: 25 }, { stockItemId: 'crisps-steak', baseUnits: 25 }, { stockItemId: 'taddy', baseUnits: 100 }] },
  ]
  const out = fold({ deliveries })
  const lines = out.deliveries[0]!.lines
  assert.equal(lines.find((l) => l.stockItemId === 'crisps')?.baseUnits, 50)
  assert.equal(lines.find((l) => l.stockItemId === 'taddy')?.baseUnits, 100)
})

test('folding into a line that already exists absorbs it rather than duplicating it', () => {
  const items = [...CRISPS, packet('crisps', 'Crisps')]
  const out = combineStockLines({
    items, pours: [], counts: [count('2026-09-16', [['crisps', 10], ['crisps-cheese', 107], ['crisps-steak', 66], ['crisps-vinegar', 106]])],
    deliveries: [], into: { id: 'crisps', name: 'Crisps' }, from: [...IDS, 'crisps'],
  })
  assert.deepEqual(out.items.map((i) => i.id), ['crisps'])
  assert.equal(out.counts[0]?.lines[0]?.baseUnits, 289)
})

test('folding nothing changes nothing', () => {
  const out = fold({ from: ['nothing-like-it'] })
  assert.deepEqual(out.items.map((i) => i.id).sort(), [...IDS].sort())
  assert.deepEqual(out.absorbed, [])
})

// --- the mirror of an unmapped sale ------------------------------------------------

test('a cellar line nothing sells is named', () => {
  // It can never go down, so it sits at whatever it was last counted at
  // looking like stock that never moves.
  const pours: Pour[] = [{ itemCode: '1', itemName: 'CRISPS', stockItemId: 'crisps-cheese', baseUnits: 1 }]
  assert.deepEqual(linesNothingSells(CRISPS, pours).map((i) => i.id), ['crisps-steak', 'crisps-vinegar'])
})

test('and when everything is sold, nothing is named', () => {
  const pours: Pour[] = CRISPS.map((i, n) => ({ itemCode: String(n), itemName: i.name, stockItemId: i.id, baseUnits: 1 }))
  assert.deepEqual(linesNothingSells(CRISPS, pours), [])
})
