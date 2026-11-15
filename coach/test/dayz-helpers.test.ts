// DayZ helpers the player sets up: server restart warnings, beginner mode
// (first hour, glossary, plainer answers), traders and places of a community
// server, and learning the player's own pace on foot.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dueWarning, nextRestart, parseRestarts, restartLine, warningText } from '../src/core/dayz/server.ts'
import { FIRST_HOUR, glossaryAnswer, isFirstHourQuestion, plainer } from '../src/core/dayz/beginner.ts'
import { draftIn, isMarkBook, learnedPace, makeMark, namedMark, paceSample, paceWords, parsePlaceList } from '../src/core/dayz/marks.ts'
import { DAYZ_MAPS, findPlace } from '../src/core/dayz/places.ts'
import { localReply, type CompanionContext } from '../src/core/dayz/companion.ts'
import { parseSetSpot } from '../src/core/dayz/assistant.ts'
import { getFootPace, setFootPace, straightSeconds } from '../src/core/dayz/travel.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const dayz = builtInProfile('dayz')
const turovo = parseSetSpot("I'm at Turovo")!
const NOW = new Date(2026, 10, 15, 17, 50, 0).getTime()

test('restart times: a list or "every N hours", the next one, and warnings 15, 5 and 1 minutes before', () => {
  assert.deepEqual(parseRestarts('00:00 06:00 12:00 18:00'), [0, 360, 720, 1080])
  assert.deepEqual(parseRestarts('every 4 hours from 02:00'), [120, 360, 600, 840, 1080, 1320])
  assert.deepEqual(parseRestarts('3am, 3pm'), [180, 900])
  assert.deepEqual(parseRestarts(''), [])
  const r = parseRestarts('06:00 18:00')
  assert.equal(Math.round(nextRestart(r, new Date(NOW))!.inMin), 10)
  // 17:44 → 17:46 crosses 15 minutes before 18:00; 17:50 → 17:51 crosses nothing.
  assert.equal(dueWarning(r, new Date(2026, 10, 15, 17, 44, 50), new Date(2026, 10, 15, 17, 45, 10)), 15)
  assert.equal(dueWarning(r, new Date(2026, 10, 15, 17, 50, 0), new Date(2026, 10, 15, 17, 50, 20)), null)
  assert.equal(dueWarning(r, new Date(2026, 10, 15, 17, 58, 50), new Date(2026, 10, 15, 17, 59, 10)), 1)
  assert.match(warningText(5, new Date(2026, 10, 15, 18, 0)), /^Server restart in 5 minutes \(18:00\)/)
  assert.match(restartLine(r, new Date(NOW)), /^Next restart at 18:00, in 10 min\. Restarts: 06:00, 18:00\.$/)
  assert.match(restartLine([], new Date(NOW)), /^No restart times set/)
})

test('beginner mode: the first hour, what words mean, and jargon explained in answers', () => {
  assert.ok(FIRST_HOUR.length >= 8)
  assert.ok(isFirstHourQuestion("I'm new, where do I start?"))
  assert.ok(!isFirstHourQuestion('where do I find a new backpack'))
  assert.match(glossaryAnswer('what does KOS mean?')!, /^KOS: Kill on sight/)
  assert.match(glossaryAnswer("what's a freshie")!, /^Freshie:/)
  assert.equal(glossaryAnswer('take me to Berezino'), null)
  assert.match(plainer('Medical in Gorka (loot tier 2).'), /loot tier 2 \(how good the loot is/)
  // Only once.
  assert.equal((plainer('loot tier 2 and loot tier 3').match(/how good/g) ?? []).length, 1)
  for (const s of FIRST_HOUR) assert.doesNotMatch(s.line, BANNED_CLAIMS)
})

test('traders and places: said, pasted as a list, and found by name', () => {
  assert.deepEqual(draftIn('mark the trader at Green Mountain'), { kind: 'trader', label: 'Trader' })
  assert.equal(draftIn('the black market is at Kumyrna')!.kind, 'trader')
  const map = DAYZ_MAPS[0]
  const { marks, skipped } = parsePlaceList('Green Mountain Trader: 037 050\nBlack market, Kumyrna\nSafe zone - 500 m north of Stary Sobor\nnonsense line', map, 1)
  assert.deepEqual(marks.map((m) => [m.kind, m.label]), [['trader', 'Green Mountain Trader'], ['trader', 'Black market'], ['poi', 'Safe zone']])
  assert.deepEqual(skipped, ['nonsense line'])
  const stary = findPlace('Stary Sobor', 'chernarus')!.place
  assert.equal(Math.round(marks[2].y - stary.y), 500)
  assert.equal(namedMark('take me to the green mountain trader', marks)?.label, 'Green Mountain Trader')
  assert.equal(namedMark('take me to Berezino', marks), null)
  const c: CompanionContext = { spot: turovo, kit: [], map: 'chernarus', marks, now: NOW }
  const r = localReply('take me to the black market', c, dayz, null, null)!
  assert.match(r.text, /^Black market: Kumyrna, [\d.]+ km \S+ from Turovo, about \d+ min jogging/)
})

test('your pace: fair trips only, the median of the last eight once there are three, and times follow it', () => {
  assert.equal(paceSample(600, 500, 2000), 1.2)
  assert.equal(paceSample(600, 2000, 2000), null, 'a long stop on the way')
  assert.equal(paceSample(600, 100, 2000), null, 'a drive')
  assert.equal(paceSample(60, 50, 200), null, 'too short')
  assert.equal(learnedPace([1.1, 1.2]), null)
  assert.equal(learnedPace([1.1, 1.2, 1.0, 1.3, 0.9]), 1.1)
  assert.equal(paceWords([1.1, 1.2, 1.0, 1.3, 0.9]), '10% faster than the estimate (5 trips)')
  assert.ok(isMarkBook({ version: 1, marks: [], pace: [1.1, 0.9] }))
  assert.ok(!isMarkBook({ version: 1, marks: [], pace: ['x'] }))
  const before = straightSeconds(3000, 'foot')
  setFootPace(1.25)
  assert.equal(getFootPace(), 1.25)
  assert.ok(Math.abs(straightSeconds(3000, 'foot') - before / 1.25) < 1)
  setFootPace(1)
})

test('the companion: first-hour guide card, glossary, restarts, and plainer answers in beginner mode', () => {
  const c: CompanionContext = { spot: turovo, kit: [], map: 'chernarus', marks: [], now: NOW, restarts: [360, 1080] }
  const guide = localReply("I'm new to DayZ, how do I start?", c, dayz, null, null)!
  assert.equal(guide.card?.type, 'guide')
  assert.match(localReply('what does NWAF mean', c, dayz, null, null)!.text, /^NWAF: North-West Airfield/)
  assert.match(localReply('when is the next restart?', c, dayz, null, null)!.text, /^Next restart at 18:00, in 10 min/)
  const plain = localReply('where do I find a canteen', { ...c, beginner: true }, dayz, null, 'Canteen: spawns in military buildings inland (loot tier 2).')!
  assert.match(plain.text, /loot tier 2 \(how good the loot is/)
  const expert = localReply('where do I find a canteen', c, dayz, null, 'Canteen: spawns in military buildings inland (loot tier 2).')!
  assert.doesNotMatch(expert.text, /how good the loot is/)
  void makeMark
})
