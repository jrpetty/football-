import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toCsv, toJson, parseBackup } from '../src/storage/export.ts'
import { emptyDay } from '../src/core/types.ts'
import type { DayRecord } from '../src/core/types.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'

function day(over: Partial<DayRecord> = {}): DayRecord {
  const d = emptyDay('2026-08-21', 0)
  d.till = { pence: 421230, source: 'vision', edited: false }
  d.card = { pence: 232175, source: 'vision', edited: true }
  d.cashPence = 189055
  return { ...d, ...over }
}

test('writes a row a spreadsheet can add up', () => {
  const csv = toCsv([day()])
  const [header, row] = csv.split('\r\n')
  assert.ok(header?.startsWith('"Date","Weekday"'))
  assert.ok(row?.includes('"4212.30"'))
  assert.ok(row?.includes('"2321.75"'))
  assert.ok(row?.includes('"1890.55"'))
  assert.ok(row?.includes('"4212.30"'), 'counted total')
  assert.ok(row?.includes('"0.00"'), 'variance')
  assert.ok(row?.includes('"Balanced"'))
})

test('records where each figure came from', () => {
  const row = toCsv([day()]).split('\r\n')[1] ?? ''
  assert.ok(row.includes('"Claude"'), 'accepted as read')
  assert.ok(row.includes('"Claude, corrected"'), 'she changed this one')
})

test('leaves an unfinished night blank rather than half-computed', () => {
  const d = day()
  d.cashPence = null
  const row = toCsv([d]).split('\r\n')[1] ?? ''
  assert.ok(row.includes('"Not finished"'))
  assert.ok(!row.includes('"0.00"'), 'no variance invented from a missing figure')
})

test('a note cannot become a spreadsheet formula', () => {
  // "-5 in the till" is a note a person would really write, and unguarded it
  // opens in Excel as a broken calculation rather than as what she typed.
  const row = toCsv([day({ note: '=1+1' })]).split('\r\n')[1] ?? ''
  assert.ok(row.includes(`"'=1+1"`))
  const row2 = toCsv([day({ note: '-5 short in the till' })]).split('\r\n')[1] ?? ''
  assert.ok(row2.includes(`"'-5 short in the till"`))
})

test('a quotation mark in a note does not break the row', () => {
  const row = toCsv([day({ note: 'said "it balanced"' })]).split('\r\n')[1] ?? ''
  assert.ok(row.includes('""it balanced""'))
})

test('a backup round-trips', () => {
  const days = [day(), day({ date: '2026-08-22' })]
  const restored = parseBackup(toJson(days))
  assert.equal(restored.length, 2)
  assert.equal(restored[0]?.till.pence, 421230)
})

test('refuses a file that is not a backup', () => {
  assert.throws(() => parseBackup('{"hello":true}'), /no days/i)
  assert.throws(() => parseBackup('null'), /not a Tally backup/i)
})

test('drops entries that are not days rather than importing rubbish', () => {
  const restored = parseBackup(JSON.stringify({ days: [{ date: 'nope' }, null, 42] }))
  assert.equal(restored.length, 0)
})

test('carries the till’s own figures and every department into the spreadsheet', () => {
  const d = day()
  d.zRead = structuredClone(GARDENERS_ARMS)
  const csv = toCsv([d])
  const [header, row] = csv.split('\r\n')
  assert.ok(header?.includes('"Draught beers"'))
  assert.ok(header?.includes('"Cash in drawer"'))
  assert.ok(header?.includes('"Z number"'))
  assert.ok(row?.includes('"1685"'), 'the Z counter')
  assert.ok(row?.includes('"1492.25"'), 'draught beers')
  assert.ok(row?.includes('"351.80"'), 'cash in drawer')
  assert.ok(row?.includes('"267"'), 'the sales count')
})

test('keeps a fixed department column order even when a department sold nothing', () => {
  const quiet = day()
  quiet.zRead = structuredClone(GARDENERS_ARMS)
  quiet.zRead.departments = quiet.zRead.departments.filter((x) => x.code === 'D01')
  const rows = toCsv([day(), quiet]).split('\r\n')
  assert.equal(rows[1]?.split(',').length, rows[2]?.split(',').length, 'columns must line up')
})
