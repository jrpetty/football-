import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { deadStock, ML_PER_PINT, type StockItem, type StockLine } from '../src/core/stock.ts'
import { costOf } from '../src/core/margin.ts'

const pint = (id: string, name: string): StockItem => ({
  id, name, kind: 'liquid',
  servingBaseUnits: ML_PER_PINT, servingName: 'pint',
  container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
  cost: { pence: 9500, baseUnits: 72 * ML_PER_PINT },
})

const line = (item: StockItem, onHandServings: number): StockLine => ({
  item,
  countedBaseUnits: 0,
  deliveredBaseUnits: 0,
  pouredBaseUnits: 0,
  expectedBaseUnits: onHandServings * ML_PER_PINT,
})

const taddy = pint('taddy', 'Taddy Lager')
const obscure = pint('obscure', 'Obscure Porter')

/** 28 days of trade — four weeks, so a week's rate is the total over four. */
const FOUR_WEEKS = 28

test('a line selling twice a week with stock on hand is flagged as not selling', () => {
  const used = new Map([['obscure', 6 * ML_PER_PINT]]) // 1.5 a week
  const rows = deadStock([line(obscure, 60)], used, FOUR_WEEKS, costOf)
  assert.equal(rows.length, 1)
  assert.equal(rows[0]!.reason, 'not selling')
  assert.equal(rows[0]!.perWeek, 1.5)
})

test('a line selling well but with three months downstairs is overstocked, not dead', () => {
  // 40 a week is a healthy line; 500 pints of it is an ordering problem.
  const used = new Map([['taddy', 160 * ML_PER_PINT]])
  const rows = deadStock([line(taddy, 500)], used, FOUR_WEEKS, costOf)
  assert.equal(rows[0]!.reason, 'overstocked', 'the beer is fine, the order was too big')
  assert.equal(rows[0]!.weeksOfCover, 12.5)
})

test('a healthy line with a sensible amount on hand is not reported at all', () => {
  const used = new Map([['taddy', 160 * ML_PER_PINT]])
  assert.equal(deadStock([line(taddy, 80)], used, FOUR_WEEKS, costOf).length, 0)
})

test('a slow line with nothing on hand is not a problem', () => {
  // It sells badly, but it is not taking up any space, so it is not on the list.
  assert.equal(deadStock([line(obscure, 0)], new Map(), FOUR_WEEKS, costOf).length, 0)
})

test('a line that never sells has no weeks of cover rather than infinity', () => {
  const rows = deadStock([line(obscure, 40)], new Map(), FOUR_WEEKS, costOf)
  assert.equal(rows[0]!.weeksOfCover, null)
  assert.equal(rows[0]!.perWeek, 0)
})

test('the money tied up comes off the cost', () => {
  const rows = deadStock([line(obscure, 72)], new Map(), FOUR_WEEKS, costOf)
  assert.equal(rows[0]!.tiedUpPence, 9500, 'a full firkin at £95')
})

test('the biggest sum standing still is listed first', () => {
  const used = new Map<string, number>()
  const rows = deadStock([line(obscure, 10), line(taddy, 72)], used, FOUR_WEEKS, costOf)
  assert.equal(rows[0]!.item.id, 'taddy')
})

test('a short window still gives a weekly rate rather than dividing by nothing', () => {
  const used = new Map([['obscure', 3 * ML_PER_PINT]])
  const rows = deadStock([line(obscure, 40)], used, 0, costOf)
  assert.equal(Number.isFinite(rows[0]!.perWeek), true)
})
