import { test } from 'node:test'
import assert from 'node:assert/strict'
import { shortBy, soldFrom, soldIsEmpty } from '../src/core/sold.ts'
import { emptyZRead, type ZRead } from '../src/core/zread.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'

function roll(): ZRead {
  return structuredClone(GARDENERS_ARMS)
}

/** A roll torn below the departments: the categories are there, the buttons are not. */
function categoriesOnly(): ZRead {
  const z = roll()
  z.plus = []
  z.pluTotal = undefined
  return z
}

/** A photograph that missed the top of the roll: the buttons, and nothing else. */
function itemsOnly(): ZRead {
  const z = roll()
  z.departments = []
  z.groups = []
  z.deptTotal = undefined
  return z
}

test('one receipt: the categories are the till’s own department block', () => {
  const sold = soldFrom([roll()])
  const draught = sold.categories.lines.find((l) => l.code === 'D01')
  assert.ok(draught)
  assert.equal(draught.name, 'Draught beers')
  assert.equal(draught.qtyMilli, 406000)
  assert.equal(draught.pence, 149225)
  // £1,492.25 over 406 sold is £3.68 each.
  assert.equal(draught.eachPence, 368)
})

test('the category total is the money the till says it took', () => {
  const sold = soldFrom([roll()])
  assert.equal(sold.categories.pence, 219280)
  assert.equal(sold.categories.qtyMilli, 689000)
})

test('biggest earner first, not the busiest button', () => {
  const sold = soldFrom([roll()])
  assert.equal(sold.categories.lines[0]?.code, 'D01')
  // Sundries go out of the door twice as often as wine (86 against 43) and take
  // two thirds as much (£146.20 against £234.80). By money, wine is above.
  const order = sold.categories.lines.map((l) => l.code)
  assert.ok(order.indexOf('D03') < order.indexOf('D07'), order.join(','))
})

test('sorted by quantity instead, the busiest button comes up', () => {
  const sold = soldFrom([roll()], 'quantity')
  const order = sold.categories.lines.map((l) => l.code)
  assert.ok(order.indexOf('D07') < order.indexOf('D03'), order.join(','))
})

test('shares add up to a whole, near enough to the till’s own percentages', () => {
  const sold = soldFrom([roll()])
  const sum = sold.categories.lines.reduce((a, l) => a + l.shareBp, 0)
  assert.ok(Math.abs(sum - 10000) <= 5, String(sum))
  // The till printed 68.05% against draught. So do we.
  assert.equal(sold.categories.lines.find((l) => l.code === 'D01')?.shareBp, 6805)
})

test('two of the same night double the units and the money', () => {
  const one = soldFrom([roll()])
  const two = soldFrom([roll(), roll()])
  assert.equal(two.categories.pence, one.categories.pence * 2)
  assert.equal(two.categories.qtyMilli, one.categories.qtyMilli * 2)
  assert.equal(two.items.pence, one.items.pence * 2)
  // What one went for does not double, which is the point of having it.
  const a = one.categories.lines.find((l) => l.code === 'D01')?.eachPence
  const b = two.categories.lines.find((l) => l.code === 'D01')?.eachPence
  assert.equal(a, b)
})

test('a button read on two nights is one line, not two', () => {
  const sold = soldFrom([roll(), roll()])
  const codes = sold.items.lines.map((l) => l.code)
  assert.equal(new Set(codes).size, codes.length)
})

test('the same button named slightly differently still totals as one', () => {
  const a = roll()
  const b = roll()
  const first = b.plus[0]
  assert.ok(first)
  first.name = `${first.name.slice(0, -1)}1` // a misread last character
  const sold = soldFrom([a, b])
  const line = sold.items.lines.find((l) => l.code === a.plus[0]?.code.toUpperCase())
  assert.ok(line)
  assert.equal(line.qtyMilli, (a.plus[0]?.qtyMilli ?? 0) * 2)
  // The name shown is the one read first — the one on the receipt in her hand.
  assert.equal(line.name, a.plus[0]?.name)
})

test('a night is never invented: nothing read is nothing counted', () => {
  const sold = soldFrom([undefined, null, emptyZRead()])
  assert.equal(sold.offered, 3)
  assert.equal(sold.reads, 0)
  assert.equal(sold.categories.reads, 0)
  assert.equal(sold.items.reads, 0)
  assert.equal(sold.categories.pence, 0)
  assert.ok(soldIsEmpty(sold))
})

test('a torn roll gives categories without items, and says which nights', () => {
  const sold = soldFrom([roll(), categoriesOnly()])
  assert.equal(sold.reads, 2)
  assert.equal(sold.categories.reads, 2)
  assert.equal(sold.items.reads, 1)
  assert.equal(shortBy(sold, sold.categories), 0)
  assert.equal(shortBy(sold, sold.items), 1)
})

test('a photograph that missed the top gives items without categories', () => {
  const sold = soldFrom([roll(), itemsOnly()])
  assert.equal(sold.categories.reads, 1)
  assert.equal(sold.items.reads, 2)
  assert.equal(shortBy(sold, sold.categories), 1)
})

test('a missing item line is not made up out of the department total', () => {
  // The two blocks are separate statements by the same till. Drop one button off
  // the PLU list — a line the photograph missed — and the item side must fall by
  // exactly that line while the department side does not move at all. Anything
  // that balanced the two would be turning a gap in the paper into a figure.
  const whole = roll()
  const dropped = whole.plus[3]
  assert.ok(dropped)
  const torn = roll()
  torn.plus = torn.plus.filter((p) => p.code !== dropped.code)

  const before = soldFrom([whole])
  const after = soldFrom([torn])
  assert.equal(after.categories.pence, before.categories.pence)
  assert.equal(after.categories.qtyMilli, before.categories.qtyMilli)
  assert.equal(after.items.pence, before.items.pence - dropped.pence)
  assert.equal(after.items.qtyMilli, before.items.qtyMilli - dropped.qtyMilli)
})

test('a line nothing sold has no price, rather than a price of nothing', () => {
  const z = emptyZRead()
  z.departments = [{ code: 'D09', name: 'FOOD', qtyMilli: 0, pence: 0 }]
  const sold = soldFrom([z])
  assert.equal(sold.categories.lines[0]?.eachPence, null)
})

test('a department the till has never printed keeps the name off the roll', () => {
  const z = emptyZRead()
  z.departments = [{ code: 'D11', name: 'COFFEE', qtyMilli: 12000, pence: 3600 }]
  const sold = soldFrom([z])
  assert.equal(sold.categories.lines[0]?.name, 'Coffee')
  assert.equal(sold.categories.lines[0]?.eachPence, 300)
})
