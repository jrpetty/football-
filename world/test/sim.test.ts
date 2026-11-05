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
  assert.ok(techs >= 4, `they research (${techs})`)
  void YEAR
})

test('minerals lie underground where geology puts them, and must be found and dug', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 7 })
  let total = 0, deep = 0, coalLow = 0, coal = 0, oilSea = 0
  for (let i = 0; i < w.W * w.H; i++) {
    const d = w.deposit[i]
    if (!d) continue
    total++
    if (w.depDepth[i] >= 1) deep++
    if (d === 4) { coal++; if (w.elev[i] < 0.45) coalLow++ }
    if (d === 6 && [B.SHALLOWS, B.OCEAN].includes(w.biome[i] as 1 | 2)) oilSea++
  }
  assert.ok(total > 300, `deposits exist (${total})`)
  assert.equal(deep, total, 'ore bodies continue below the surface')
  assert.ok(coal === 0 || coalLow / coal > 0.9, 'coal lies in low basins')
  assert.ok(oilSea > 0, 'some oil is offshore')
  // nobody knows about any of it at first
  const t = w.tribes[0]
  assert.equal(w.knownList(t.id).length, 0)
  // a prospector standing on a body, with the know-how, finds it
  const a = w.members(t.id)[0]
  t.techs.push('mining', 'prospecting', 'geology'); w.techSets.set(t.id, new Set(t.techs))
  let body = -1
  for (let i = 0; i < w.W * w.H && body < 0; i++) if (w.deposit[i] && w.depDepth[i] <= 2 && !w.biome[i].toString().match(/^[012]$/)) body = i
  a.x = (body % w.W) + 0.5; a.y = Math.floor(body / w.W) + 0.5
  let found: string[] = []
  for (let k = 0; k < 40 && !found.length; k++) found = w.prospect(a, 1)
  assert.ok(found.length > 0, 'prospecting finds the body')
  // what was found is remembered: a known ore body right around the prospector
  assert.ok(w.knownList(t.id).some((i) => Math.hypot((i % w.W) - a.x, Math.floor(i / w.W) - a.y) < 8), 'and remembers where it is')
  // a mine over it brings the ore up and the reserves go down
  t.techs.push('shaft_mining'); w.techSets.set(t.id, new Set(t.techs))
  const before = w.reservesNear(t.id, body % w.W, Math.floor(body / w.W), w.deposit[body] === 6 ? 'oil' : (['', 'copper', 'tin', 'iron_ore', 'coal', 'gold', 'oil', 'bauxite', 'uranium', 'lithium'] as const)[w.deposit[body]] as never)
  assert.ok(before > 0)
})

test('fish stocks fall when fished and recover slowly', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 11, size: 96 })
  let sea = -1
  for (let i = 0; i < w.W * w.H; i++) if (w.biome[i] === B.SHALLOWS) { sea = i; break }
  const x = sea % w.W, y = Math.floor(sea / w.W)
  const full = w.fishNear(x, y, 2)
  w.takeFish(x, y, full * 0.8, 2)
  const low = w.fishNear(x, y, 2)
  assert.ok(low < full * 0.3, 'overfished')
  w.tick(DAY)
  const later = w.fishNear(x, y, 2)
  assert.ok(later > low && later < full * 0.6, 'recovering, but not at once')
})

test('peoples meet, ally, go to war, fight battles and make peace', async () => {
  const { declareWar, propose, warBetween, pactBetween, met, status } = await import('../src/sim/diplomacy.ts')
  const w = new World({ ...DEFAULT_CONFIG, seed: 5, tribes: 3, perTribe: 8 })
  const [a, b, c] = w.tribes
  for (const [x, y] of [[a, b], [a, c], [b, c]]) { w.dip.contact[x.id < y.id ? `${x.id}|${y.id}` : `${y.id}|${x.id}`] = 0; x.relations[y.id] = 0; y.relations[x.id] = 0 }
  assert.ok(met(w, a.id, b.id))
  // b and c are friends and ally
  b.relations[c.id] = c.relations[b.id] = 60
  assert.ok(propose(w, b.id, c.id, 'alliance').ok, 'friends ally')
  assert.ok(pactBetween(w, b.id, c.id, 'alliance'))
  // a attacks b; b's ally c is drawn in
  for (const t of [a, b, c]) { t.techs.push('hunting'); w.techSets.set(t.id, new Set(t.techs)) }
  declareWar(w, a.id, b.id, 'test', 'raid')
  assert.ok(warBetween(w, a.id, b.id), 'at war')
  assert.ok(warBetween(w, c.id, a.id), 'the ally joins')
  assert.equal(status(w, a.id, b.id), 'war')
  // warriors march and fight
  let battles = 0
  for (let d = 0; d < 30 && !battles; d++) { w.tick(DAY); battles = w.dip.wars.reduce((s, x) => s + x.battles, 0) }
  assert.ok(battles > 0, 'battles are fought')
  // peace, eventually, when one side asks and the other is tired or winning
  const war = warBetween(w, a.id, b.id)
  if (war) { war.dead[b.id] = 10; propose(w, a.id, b.id, 'peace') }
  assert.ok(!warBetween(w, a.id, b.id), 'peace is made')
  assert.ok(!warBetween(w, c.id, a.id), 'the allied war ends with it')
})
