import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  backupParts,
  base64ToBlob,
  blobToBase64,
  describeRestored,
  mergeStockConfig,
  prefersShareSheet,
  toCsv,
  toJson,
  parseBackup,
} from '../src/storage/export.ts'
import type { Pour, StockItem } from '../src/core/stock.ts'
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

const EMPTY = {
  days: [], prices: [], stock: { items: [], pours: [], mlPerShot: 30 },
  deliveries: [], stockCounts: [], people: [], shifts: [], weather: [], settings: {},
}

test('a backup round-trips the nights', () => {
  const restored = parseBackup(toJson({ ...EMPTY, days: [day(), day({ date: '2026-08-22' })] }))
  assert.equal(restored.days.length, 2)
  assert.equal(restored.days[0]?.till.pence, 421230)
})

test('a backup carries everything, not just the nights', () => {
  // The bug this replaced: a backup that saved only the nights, restored
  // without complaint, and silently lost the price list, the cellar with all
  // its costs, the rota and everyone on it. It was trusted, which made it
  // worse than having no backup at all.
  const full = toJson({
    ...EMPTY,
    days: [day()],
    prices: [{ code: '1', name: 'PINT TADDY LAGER', pence: 400 }],
    stock: {
      items: [{
        id: 'taddy', name: 'Taddy Lager', kind: 'liquid' as const,
        servingBaseUnits: 568, servingName: 'pint',
        container: { name: 'firkin', baseUnits: 72 * 568 },
        cost: { pence: 9500, baseUnits: 72 * 568 },
      }],
      pours: [{ itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: 568 }],
      mlPerShot: 30,
    },
    deliveries: [{ id: 'd1', date: '2026-08-20', lines: [{ stockItemId: 'taddy', baseUnits: 40896 }] }],
    stockCounts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 8236 }] }],
    people: [{ id: 'k', name: 'Kelly', slot: 1, ratePencePerHour: 1221 }],
    shifts: [{ id: '2026-08-23:k', date: '2026-08-23', personId: 'k', startMin: 1080, endMin: 1410 }],
    weather: [{ date: '2026-08-23', tempC: 21, rainMm: 0 }],
    settings: { weeklyHoursTarget: 45 },
  })

  const r = parseBackup(full)
  assert.equal(r.days.length, 1)
  assert.equal(r.prices.length, 1)
  assert.equal(r.stock?.items.length, 1)
  assert.equal(r.stock?.items[0]?.cost?.pence, 9500, 'the barrel cost survives')
  assert.equal(r.stock?.pours.length, 1)
  assert.equal(r.deliveries.length, 1)
  assert.equal(r.stockCounts.length, 1)
  assert.equal(r.people.length, 1)
  assert.equal(r.people[0]?.ratePencePerHour, 1221, 'and so does the hourly rate')
  assert.equal(r.shifts.length, 1)
  assert.equal(r.weather.length, 1)
  assert.equal(r.settings?.weeklyHoursTarget, 45)
  assert.equal(r.nightsOnly, false)
})

test('a backup never carries the API key', () => {
  // It gets emailed. A key in an inbox is a key in the wrong place.
  const text = toJson({ ...EMPTY, settings: { weeklyHoursTarget: 45 } })
  assert.equal(text.includes('apiKey'), false)
  assert.equal(text.includes('sk-ant'), false)
})

test('an old nights-only backup still restores, and says that is all it was', () => {
  const old = JSON.stringify({ app: 'tally', version: 1, days: [day()] })
  const r = parseBackup(old)
  assert.equal(r.days.length, 1)
  assert.equal(r.nightsOnly, true, 'so the interface can explain why the cellar is empty')
})

test('one bad section costs that section, not the whole restore', () => {
  const r = parseBackup(JSON.stringify({
    app: 'tally', version: 2,
    days: [day()],
    people: 'not an array',
    prices: [{ name: 'PINT', pence: 400 }, null, { name: 'no price' }],
  }))
  assert.equal(r.days.length, 1)
  assert.equal(r.people.length, 0)
  assert.equal(r.prices.length, 1, 'the one valid price is kept')
})

test('refuses a file that is not a backup', () => {
  assert.throws(() => parseBackup('{"hello":true}'), /not a Tally backup/i)
  assert.throws(() => parseBackup('null'), /not a Tally backup/i)
  assert.throws(() => parseBackup(JSON.stringify({ app: 'tally', version: 2, days: [] })), /nothing in it/i)
})

test('drops entries that are not days rather than importing rubbish', () => {
  const r = parseBackup(JSON.stringify({ app: 'tally', days: [{ date: 'nope' }, null, 42], people: [{ id: 'k', name: 'K' }] }))
  assert.equal(r.days.length, 0)
  assert.equal(r.people.length, 1)
})

test('a restore says what came back rather than succeeding silently', () => {
  const r = parseBackup(toJson({
    ...EMPTY,
    days: [day(), day({ date: '2026-08-22' })],
    people: [{ id: 'k', name: 'Kelly', slot: 1 }],
  }))
  const said = describeRestored(r)
  assert.match(said, /2 nights/)
  assert.match(said, /1 person/)
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

test('carries items sold into the spreadsheet, per department and in total', () => {
  const d = day()
  d.zRead = structuredClone(GARDENERS_ARMS)
  const csv = toCsv([d])
  const [header, row] = csv.split('\r\n')
  assert.ok(header?.includes('"Items sold"'))
  assert.ok(header?.includes('"Draught beers (sold)"'))
  assert.ok(row?.includes('"689"'), '689 items across the night')
  assert.ok(row?.includes('"406"'), '406 of them draught')
})

test('carries the void count as well as its value', () => {
  const d = day()
  d.zRead = structuredClone(GARDENERS_ARMS)
  const csv = toCsv([d])
  const [header, row] = csv.split('\r\n')
  assert.ok(header?.includes('"Voids"') && header?.includes('"Void value"'))
  assert.ok(row?.includes('"12.50"'), 'the value')
  assert.ok(row?.includes('"5"'), 'the no-sale count')
})

// --- getting a file off the phone --------------------------------------------

test('an iPhone gets the share sheet, because a download link can do nothing there', () => {
  const iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'
  assert.equal(prefersShareSheet(iphone, 5), true)
})

test('an iPad does too, even though it calls itself a Macintosh', () => {
  const ipad = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
  assert.equal(prefersShareSheet(ipad, 5), true, 'a Mac with a touch screen is an iPad')
  assert.equal(prefersShareSheet(ipad, 0), false, 'a Mac without one is a Mac')
})

test('everything else downloads, which is what a download is for', () => {
  const android = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36'
  const laptop = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36'
  assert.equal(prefersShareSheet(android, 5), false)
  assert.equal(prefersShareSheet(laptop, 0), false)
})

// --- a cellar restored onto a cellar ------------------------------------------

const beer: StockItem = {
  id: 'taddy-lager', name: 'Taddy Lager', kind: 'liquid', servingBaseUnits: 568, servingName: 'pint',
  container: { name: 'firkin', baseUnits: 72 * 568 },
  cost: { pence: 9500, baseUnits: 72 * 568 },
}
const gin: StockItem = { id: 'gin', name: 'Gin', kind: 'liquid', servingBaseUnits: 1, servingName: 'ml' }
const pour: Pour = { itemCode: 'P1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy-lager', baseUnits: 568 }

test('a file with a few lines in it does not take the rest of the cellar away', () => {
  const current = { items: [beer, gin], pours: [pour], mlPerShot: 25 }
  const cherry: StockItem = { id: 'cherry', name: 'Cherry', kind: 'count', servingBaseUnits: 1, servingName: 'bottle' }
  const merged = mergeStockConfig(current, { items: [cherry], pours: [], mlPerShot: 30 })
  assert.deepEqual(merged.items.map((i) => i.id).sort(), ['cherry', 'gin', 'taddy-lager'])
  assert.deepEqual(merged.pours, [pour], 'the till still knows what a pint takes off')
  assert.equal(merged.mlPerShot, 25, 'and the house measure is hers, not the file’s')
})

test('a line the file names keeps what the file says nothing about', () => {
  // The whole point: a stock take carries no costs, and must not wipe them.
  const current = { items: [beer], pours: [pour], mlPerShot: 30 }
  const weighed: StockItem = {
    ...beer, container: { name: 'large keg', baseUnits: 176 * 568, emptyKg: 22.4, fullKg: 123 },
  }
  const { cost: _cost, ...costless } = weighed
  const merged = mergeStockConfig(current, { items: [costless as StockItem], pours: [], mlPerShot: 30 })
  assert.deepEqual(merged.items[0]?.cost, beer.cost, 'the £95 firkin is still £95')
  assert.equal(merged.items[0]?.container?.name, 'large keg', 'and the new size took')
  assert.equal(merged.items[0]?.container?.emptyKg, 22.4)
})

test('a whole backup onto an empty app brings its own measure', () => {
  const merged = mergeStockConfig({ items: [], pours: [], mlPerShot: 30 }, { items: [gin], pours: [pour], mlPerShot: 35 })
  assert.equal(merged.mlPerShot, 35)
  assert.deepEqual(merged.items, [gin])
  assert.deepEqual(merged.pours, [pour])
})


// --- the receipts in the backup ------------------------------------------------
//
// The photographs used to be left out on the grounds that they were an audit
// trail rather than data. The night the receipt became the whole record that
// stopped being true: a backup of figures without the receipts they were read
// off is a backup of somebody's word for it.

/** Two bytes that are not valid UTF-8, so a text-only path would corrupt them. */
const BYTES = new Uint8Array([0xff, 0xd8, 0x00, 0x41, 0xfe, 0x10])

test('a photograph survives the trip out and back byte for byte', async () => {
  const data = await blobToBase64(new Blob([BYTES], { type: 'image/jpeg' }))
  const back = base64ToBlob(data, 'image/jpeg')
  assert.equal(back.type, 'image/jpeg')
  assert.deepEqual(new Uint8Array(await back.arrayBuffer()), BYTES)
})

test('the backup file is assembled in pieces and still parses as one', async () => {
  const data = await blobToBase64(new Blob([BYTES], { type: 'image/jpeg' }))
  const parts = backupParts({ ...EMPTY, days: [day()] }, [
    { id: 'a', savedAt: 111, type: 'image/jpeg', data },
    { id: 'b', savedAt: 222, type: 'image/jpeg', data },
  ])
  assert.ok(parts.length > 1, 'never one enormous string')

  const r = parseBackup(parts.join(''))
  assert.equal(r.days.length, 1, 'the nights are still there')
  assert.equal(r.photos.length, 2)
  assert.equal(r.photos[0]?.id, 'a', 'restored under the id the night points at')
  assert.equal(r.photos[1]?.savedAt, 222)
  assert.deepEqual(new Uint8Array(await r.photos[0]!.blob.arrayBuffer()), BYTES)
})

test('no photographs means the small file, unchanged', () => {
  const parts = backupParts({ ...EMPTY, days: [day()] }, [])
  assert.equal(parts.length, 1)
  assert.equal(parseBackup(String(parts[0])).photos.length, 0)
})

test('one unreadable photograph costs that photograph, not the restore', () => {
  const r = parseBackup(
    JSON.stringify({
      app: 'tally',
      days: [day()],
      photos: [
        { id: 'a', savedAt: 1, type: 'image/jpeg', data: 'not base64 at all !!!' },
        { id: 'b', savedAt: 2, type: 'image/jpeg', data: '' },
        { id: 'c' },
        null,
      ],
    }),
  )
  assert.equal(r.days.length, 1, 'the night is still restored')
  // 'b' decodes to an empty image, which is honest; 'c' and null are not
  // photographs at all and are dropped.
  assert.ok(r.photos.length <= 2)
  assert.ok(!r.photos.some((p) => p.id === 'c'))
})

test('a restore says how many receipts came back', () => {
  const r = parseBackup(
    JSON.stringify({ app: 'tally', days: [day()], photos: [{ id: 'a', savedAt: 1, type: 'image/jpeg', data: '' }] }),
  )
  assert.match(describeRestored(r), /1 receipt/)
})

test('an old backup with no photographs restores as before', () => {
  const r = parseBackup(toJson({ ...EMPTY, days: [day()] }))
  assert.deepEqual(r.photos, [])
  assert.doesNotMatch(describeRestored(r), /receipt/)
})
