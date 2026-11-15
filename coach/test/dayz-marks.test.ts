// DayZ map marks: what the player says they saw or own becomes a mark where they
// said; questions about marks; routes that mention recent sightings; sharing.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { aroundLine, draftIn, isMarkBook, liveMarks, makeMark, markAsk, markSaid, readShareCode, shareCode, warningLine, whereIn, type Mark } from '../src/core/dayz/marks.ts'
import { DAYZ_MAPS, findPlace } from '../src/core/dayz/places.ts'
import { localReply, runTool, type CompanionContext } from '../src/core/dayz/companion.ts'
import { parseSetSpot } from '../src/core/dayz/assistant.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { Storage } from '../electron/storage.ts'

const map = DAYZ_MAPS.find((m) => m.id === 'chernarus')!
const turovo = parseSetSpot("I'm at Turovo")!
const NOW = 1_800_000_000_000
const ctx = { spot: turovo, map: 'chernarus' as const, marks: [] as Mark[], now: NOW }
const dayz = builtInProfile('dayz')
const gorka = findPlace('Gorka', 'chernarus')!.place

test('sightings: who, how many, which way, and where (a town, "500 m south of", "here", a grid)', () => {
  assert.deepEqual(draftIn('Coach, saw two guys heading north near Gorka'), { kind: 'player', label: '2 players heading north', count: 2, heading: 'north' })
  assert.equal(draftIn('spotted a squad running east')!.count, 4)
  assert.equal(draftIn('there is a sniper on the hill north of Elektro')!.kind, 'danger')
  assert.equal(draftIn('heard shots east of Chernogorsk')!.kind, 'shots')
  assert.equal(draftIn('got shot at by someone near Stary Sobor')!.label, 'Shot at')
  const w = whereIn('500 m south of Zelenogorsk', map, turovo)!
  const zel = findPlace('Zelenogorsk', 'chernarus')!.place
  assert.equal(Math.round(zel.y - w.y), 500)
  assert.equal(w.where, '500 m south of Zelenogorsk')
  assert.equal(whereIn('right here', map, turovo)!.where, 'your spot (Turovo)')
  assert.equal(whereIn('at 123 045', map, turovo)!.where, 'grid 123 045')
  assert.equal(whereIn('1 km north-west of me', map, turovo)!.where, '1.0 km north-west of your spot')
  assert.equal(whereIn('somewhere over there', map, turovo), null)
})

test('what is yours: base, stash, car, meeting point, loot, notes', () => {
  assert.equal(draftIn('my base is 1 km north-west of Gorka')!.kind, 'base')
  assert.equal(draftIn('mark our base at grid 123 045')!.label, 'Our base')
  assert.equal(draftIn('I buried a barrel here')!.label, 'Stash (barrel)')
  assert.equal(draftIn('parked the olga at Berezino')!.label, 'Car (Olga)')
  assert.equal(draftIn('meet at Green Mountain')!.kind, 'rally')
  assert.equal(draftIn('remember the tower at 040 100 has a camper')!.label, 'The tower has a camper')
  // Questions and other talk are not marks.
  for (const q of ['where is my base', 'is there a sniper at NWAF?', 'where do I find a canteen', 'take me to Berezino', 'should we meet at Gorka?', 'I found a knife']) assert.equal(draftIn(q), null, q)
})

test('said: added where told, or click the map when no place was said; removing', () => {
  const add = markSaid('I saw two players heading north near Gorka', ctx)!
  assert.equal(add.type, 'add')
  assert.ok(add.type === 'add' && add.mark.x === gorka.x && add.mark.at === NOW)
  assert.match(add.text, /^Marked: 2 players heading north, Gorka\. [\d.]+ k?m \S+ of you\..*fades after half an hour/)
  const pick = markSaid('I saw a fresh spawn', ctx)!
  assert.equal(pick.type, 'pick')
  assert.match(pick.text, /Click the spot on the map/)
  const base = makeMark({ kind: 'base', label: 'Base' }, { x: 9000, y: 9000, where: 'grid 090 063' }, 'chernarus', NOW - 1000)
  const seen = makeMark({ kind: 'player', label: 'A player' }, { x: 1, y: 1, where: 'x' }, 'chernarus', NOW)
  const with2 = { ...ctx, marks: [base, seen] }
  assert.deepEqual(markSaid('clear sightings', with2), { type: 'remove', ids: [seen.id], text: 'Removed 1 sighting.' })
  assert.deepEqual(markSaid('remove my base', with2), { type: 'remove', ids: [base.id], text: 'Removed your base mark.' })
  assert.equal((markSaid('undo last mark', with2) as { ids: readonly string[] }).ids[0], seen.id)
  assert.equal(markAsk('take me back to my car')?.type, 'go')
  assert.deepEqual(markAsk('what have I seen near Gorka'), { type: 'seen', near: 'gorka' })
})

test('sightings age: kept for hours, owned marks stay; books from disk are checked', () => {
  const old = makeMark({ kind: 'player', label: 'A player' }, { x: 1, y: 1, where: 'x' }, 'chernarus', NOW - 7 * 3600_000)
  const base = makeMark({ kind: 'base', label: 'Base' }, { x: 1, y: 1, where: 'x' }, 'chernarus', NOW - 300 * 86400_000)
  assert.deepEqual(liveMarks([old, base], NOW), [base])
  assert.ok(isMarkBook({ version: 1, marks: [base] }))
  assert.ok(!isMarkBook({ version: 1, marks: [{ ...base, kind: 'enemy' }] }))
  assert.ok(!isMarkBook({ version: 2, marks: [] }))
})

test('routes mention recent sightings near the way, and setting your spot mentions ones close by', () => {
  const seen = makeMark({ kind: 'player', label: '2 players' }, { x: gorka.x, y: gorka.y + 200, where: 'Gorka' }, 'chernarus', NOW - 10 * 60_000)
  const route = [{ x: gorka.x - 3000, y: gorka.y }, { x: gorka.x + 3000, y: gorka.y }]
  assert.match(warningLine([seen], route, 'chernarus', NOW)!, /^Heads up: you saw 2 players near Gorka 10 min ago, 200 m from this way\.$/)
  assert.equal(warningLine([seen], route, 'chernarus', NOW + 3 * 3600_000), null)
  assert.equal(warningLine([seen], [{ x: 0, y: 0 }, { x: 100, y: 0 }], 'chernarus', NOW), null)
  assert.match(aroundLine([seen], { x: gorka.x, y: gorka.y + 500 }, 'chernarus', NOW)!, /^Heads up: 2 players marked 300 m south of here, 10 min ago\.$/)
})

test('squad codes carry marks both ways, with new ids; junk is refused', () => {
  const base = makeMark({ kind: 'base', label: 'Our base', note: 'code 1234' }, { x: 5000, y: 6000, where: 'grid 050 093' }, 'chernarus', NOW)
  const back = readShareCode(shareCode([base]), NOW + 1)!
  assert.equal(back.length, 1)
  assert.equal(back[0].label, 'Our base')
  assert.equal(back[0].note, 'code 1234')
  assert.notEqual(back[0].id, base.id)
  assert.equal(readShareCode('hello', NOW), null)
  assert.equal(readShareCode('SLMARKS1:!!!', NOW), null)
})

test('the companion: take me to my base (with the time), where is my car, what have I seen, my marks; warnings on routes', () => {
  const base = makeMark({ kind: 'base', label: 'Base' }, { x: gorka.x, y: gorka.y + 1000, where: '1.0 km north of Gorka' }, 'chernarus', NOW - 86400_000)
  const seen = makeMark({ kind: 'player', label: '2 players' }, { x: 12950, y: 10010, where: 'Berezino' }, 'chernarus', NOW - 5 * 60_000)
  const c: CompanionContext = { spot: turovo, kit: [], map: 'chernarus', marks: [base, seen], now: NOW }
  const go = localReply('take me to my base', c, dayz, null, null)!
  assert.match(go.text, /^Base: 1\.0 km north of Gorka, [\d.]+ km \S+ from Turovo, about \d+ min jogging/)
  assert.ok(go.card?.type === 'map' && go.card.route?.length === 2)
  const where = localReply('where is my base', c, dayz, null, null)!
  assert.ok(where.card?.type === 'map' && where.card.focus?.label === 'Base')
  assert.match(localReply('what have I seen near Berezino', c, dayz, null, null)!.text, /^Near Berezino: 2 players, Berezino, .* 5 min ago\. Only what you told me/)
  assert.match(localReply('show my marks', c, dayz, null, null)!.text, /^Your marks: Base/)
  const none = localReply('take me to my car', c, dayz, null, null)!
  assert.equal(none.card?.type, 'pick')
  // A route past Berezino says so.
  const r = localReply('take me to Berezino', c, dayz, null, 'Berezino: 4.1 km south from Turovo.')!
  assert.match(r.text, /Heads up: you saw 2 players near Berezino 5 min ago/)
  for (const x of [go, where, none, r]) assert.doesNotMatch(x.text, BANNED_CLAIMS)
})

test("Claude's tools: add_mark puts it where the player said, remove_marks and list_marks", () => {
  const added: Mark[] = []
  const removed: string[] = []
  const effects = { setSpot: () => {}, setKit: () => {}, addMark: (m: Mark) => added.push(m), removeMarks: (ids: readonly string[]) => removed.push(...ids) }
  const c: CompanionContext = { spot: turovo, kit: [], map: 'chernarus', marks: [], now: NOW }
  const r = runTool('add_mark', { kind: 'player', where: 'near Gorka', label: '3 players', count: 3, heading: 'north' }, c, dayz, effects)
  assert.equal(added.length, 1)
  assert.equal(added[0].count, 3)
  assert.ok(r.card?.type === 'map')
  assert.equal(runTool('add_mark', { kind: 'base', where: 'nowhere at all' }, c, dayz, effects).card?.type, 'pick')
  const list = runTool('list_marks', {}, { ...c, marks: added }, dayz, effects)
  assert.match(list.text, /1 sighting/)
  runTool('remove_marks', { what: 'sightings' }, { ...c, marks: added }, dayz, effects)
  assert.deepEqual(removed, [added[0].id])
})

test('storage keeps marks whenever the player makes them, and checks what it loads', async () => {
  const s = new Storage(await mkdtemp(join(tmpdir(), 'sightline-marks-')), null)
  await s.init()
  const base = makeMark({ kind: 'base', label: 'Base' }, { x: 1, y: 2, where: 'x' }, 'chernarus', NOW)
  assert.equal(await s.saveDoc('marks', 'dayz', { version: 1, marks: [base] }), true)
  assert.deepEqual(await s.loadDoc('marks', 'dayz'), { version: 1, marks: [base] })
  assert.equal(await s.saveDoc('marks', 'dayz', { version: 1, marks: [{ nope: 1 }] }), false)
})
