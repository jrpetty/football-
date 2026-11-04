// Headless simulation tests: the world must generate deterministically and a
// tribe run purely on instinct (no API key) must survive and develop.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { World, DEFAULT_CONFIG } from '../src/sim/world.ts'
import { DAY, YEAR } from '../src/sim/time.ts'
import { B } from '../src/sim/data.ts'
import { PRESETS } from '../src/sim/worldgen.ts'

test('a map seed reproduces the land; fixedHistory reproduces the people', () => {
  const a = new World({ ...DEFAULT_CONFIG, seed: 42, size: 96, fixedHistory: true })
  const b = new World({ ...DEFAULT_CONFIG, seed: 42, size: 96, fixedHistory: true })
  assert.deepEqual(Array.from(a.biome.slice(0, 2000)), Array.from(b.biome.slice(0, 2000)))
  assert.equal(a.agents[0].name, b.agents[0].name)
})

test('by default the same land gives rise to a different history every run', () => {
  const runs = [1, 2, 3].map(() => new World({ ...DEFAULT_CONFIG, seed: 42, size: 96 }))
  assert.deepEqual(Array.from(runs[0].biome.slice(0, 3000)), Array.from(runs[1].biome.slice(0, 3000)), 'same terrain')
  const casts = new Set(runs.map((w) => w.agents.map((x) => x.name).join(',')))
  assert.ok(casts.size > 1, 'different people')
  for (const w of runs) for (let d = 0; d < 24; d++) w.tick(DAY)
  const paths = new Set(runs.map((w) => w.tribes.map((t) => t.techs.join('>')).join('|')))
  assert.equal(paths.size, 3, 'different discoveries in a different order')
})

test('people discover things by doing them', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 3, size: 96 })
  const a = w.living[0]
  const t = w.tribeById(a.tribeId)!
  assert.ok(!w.hasTech(t.id, 'cordage'))
  for (let k = 0; k < 3000 && !w.hasTech(t.id, 'cordage'); k++) w.practice(a, 'gather:fiber', 1)
  assert.ok(w.hasTech(t.id, 'cordage'), 'twisting fibre long enough teaches cordage')
  assert.ok(w.chronicle.some((e) => /cordage/i.test(e.text)))
})

test('every preset makes land, water and rivers', () => {
  for (const p of PRESETS) {
    const w = new World({ ...DEFAULT_CONFIG, seed: 7, size: 112, preset: p.id })
    let land = 0, water = 0, river = 0
    for (const v of w.biome) { if (v === B.RIVER) river++; else if (v <= B.LAKE) water++; else land++ }
    assert.ok(land > 1500, `${p.id} has land (${land})`)
    assert.ok(water > 50 || river > 30, `${p.id} has water`)
    assert.ok(w.agents.length >= 6, `${p.id} spawned people`)
  }
})

test('an instinct-only civilisation survives and develops over several years', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 2024, size: 128 })
  const start = w.agents.length
  for (let d = 0; d < 4 * 12; d++) w.tick(DAY)
  const alive = w.living.length
  const built = w.buildings.filter((b) => b.done).length
  const techs = Math.max(...w.tribes.map((t) => t.techs.length))
  console.log(`after 4 years: pop ${start}→${alive}, buildings ${built}, max techs ${techs}, chronicle ${w.chronicle.length}`)
  console.log(w.tribes.map((t) => `${t.name}: ${t.techs.join(',')}`).join('\n'))
  const deaths = w.chronicle.filter((e) => e.kind === 'death').map((e) => e.text)
  console.log('deaths:', deaths.slice(0, 10))
  assert.ok(alive >= start * 0.6, 'most people survive')
  assert.ok(built >= 6, 'they build things')
  assert.ok(techs >= 5, 'they research')
  void YEAR
})
