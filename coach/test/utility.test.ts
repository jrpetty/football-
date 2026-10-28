// Grenade lineups in the Counter-Strike 2 pack: every map has them, Valve's
// own lineups sit where the game files put them, executes point at real
// lineups; and the coach answers lineup and grenade questions from them.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { builtInProfile } from '../src/profiles/index.ts'
import { validateProfile } from '../src/core/profile/schema.ts'
import { answerLocally, classify } from '../src/core/qa/ask.ts'
import type { SessionReport } from '../src/core/engine.ts'

const cs2 = builtInProfile('cs2')
const maps = cs2.knowledge.maps!
const report = { rounds: { status: { map: 'Mirage' } }, map: null } as unknown as SessionReport

test('the seven Active Duty maps, each with lineups, executes and retakes', () => {
  assert.deepEqual(maps.map((m) => m.id).sort(), ['ancient', 'anubis', 'cache', 'dust2', 'inferno', 'mirage', 'nuke'])
  for (const m of maps) {
    assert.equal(m.type, 'active duty')
    assert.ok((m.utility?.length ?? 0) >= 20, `${m.name}: ${m.utility?.length} lineups`)
    for (const side of ['T', 'CT'] as const) assert.ok(m.utility!.some((l) => l.side === side), `${m.name} ${side}`)
    for (const type of ['smoke', 'flash'] as const) assert.ok(m.utility!.some((l) => l.type === type), `${m.name} ${type}`)
    assert.ok(m.utility!.some((l) => l.official), `${m.name}: Valve's lineups`)
    assert.ok(m.utilityPlans!.some((p) => p.kind === 'execute') && m.utilityPlans!.some((p) => p.kind === 'retake'), m.name)
    // Every callout has a spot on the overview, so every lineup can be drawn.
    for (const c of m.callouts) assert.ok(m.radar?.calloutPx?.[c.name], `${m.name}: ${c.name}`)
  }
  const v = validateProfile(cs2)
  assert.ok(v.ok, v.ok ? '' : v.errors.slice(0, 5).join('\n'))
})

test("Valve's lineups land on the callout they name", () => {
  for (const m of maps)
    for (const l of m.utility!.filter((x) => x.official)) {
      assert.ok(l.standPx && l.standPx.every((v) => v >= 0 && v <= 1024), `${l.name} stands on the overview`)
      if (!l.landPx) continue
      const [x, y] = m.radar!.calloutPx![l.to]
      assert.ok(Math.hypot(l.landPx[0] - x, l.landPx[1] - y) < 130, `${m.name}: ${l.name} lands by ${l.to}`)
    }
})

test('no lineup carries practice positions from unvetted sources', () => {
  for (const m of maps) for (const l of m.utility!) assert.ok(!/set(?:pos|ang)/i.test(JSON.stringify(l)), `${m.name}: ${l.name}`)
})

test('questions about lineups, executes and grenade mechanics', () => {
  const ask = (q: string) => {
    const i = classify(q, cs2)
    return { kind: i.kind, a: answerLocally(i, cs2, report, []) ?? '' }
  }
  let r = ask('What smokes do I need for an A execute on Mirage?')
  assert.equal(r.kind, 'utility')
  assert.match(r.a, /execute/i)
  assert.match(r.a, /In order: 1,/)
  r = ask('Where do I throw the Xbox smoke on Dust II?')
  assert.equal(r.kind, 'utility')
  assert.match(r.a, /^Xbox smoke on Dust II/)
  assert.match(r.a, /Aim:/)
  r = ask('retake B on Nuke')
  assert.match(r.a, /^Retake B on Nuke/)
  // With no map named, the map being played.
  r = ask('what flashes for b site')
  assert.match(r.a, /on Mirage/)
  // How grenades work goes to the arsenal, with the detail asked about.
  r = ask('how long does a smoke grenade last')
  assert.equal(r.kind, 'arsenal')
  assert.match(r.a, /18 s/)
  r = ask('does a smoke put out a molotov')
  assert.match(r.a, /^Molotov:/)
  r = ask('is the incendiary grenade different from the molotov')
  assert.equal(r.kind, 'arsenal')
  r = ask('how does the smoke grenade work')
  assert.match(r.a, /^Smoke Grenade \(/)
})
