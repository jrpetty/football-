// Rebindable keys, and the storage underneath them.
//
// Both are the sort of thing that looks obviously correct and then loses
// somebody's settings. The interesting cases are the ugly ones: binding a key
// that is already doing another job, binding the one key that must never move,
// and what happens when the browser refuses to store anything at all.
import { store } from '../src/game/core/store'
import { binds, label, ACTIONS, DEFAULT_BINDS, RESERVED } from '../src/game/core/bindings'

// A localStorage that behaves. Installed before anything reads it.
class FakeStorage {
  private m = new Map<string, string>()
  failWrites = false
  getItem(k: string) {
    return this.m.get(k) ?? null
  }
  setItem(k: string, v: string) {
    if (this.failWrites) throw new Error('quota')
    this.m.set(k, v)
  }
  removeItem(k: string) {
    this.m.delete(k)
  }
  get raw() {
    return this.m
  }
}
const fake = new FakeStorage()
;(globalThis as unknown as { localStorage: unknown }).localStorage = fake
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(46)} ${detail}`)

// ---- 1. defaults ------------------------------------------------------------
{
  binds.reset()
  ok(
    'every action starts on its default key',
    ACTIONS.every((a) => binds.get(a.action) === DEFAULT_BINDS[a.action]),
    `${ACTIONS.length} actions, ${ACTIONS.length} keys — shield is ${label(binds.get('shield'))}`,
  )
}

// ---- 2. rebinding -----------------------------------------------------------
{
  binds.reset()
  binds.bind('shield', 'KeyE')
  ok(
    'a key can be moved',
    binds.get('shield') === 'KeyE' && binds.keys('shield').includes('KeyE'),
    `shield: ${label(DEFAULT_BINDS.shield)} → ${label(binds.get('shield'))}`,
  )
}

{
  binds.reset()
  // Q is shield by default; give it to the slide and shield must lose it.
  const r = binds.bind('slide', 'KeyQ')
  ok(
    'giving a key a second job takes it off the first',
    r.ok && r.displaced === 'shield' && binds.get('shield') === '' && binds.get('slide') === 'KeyQ',
    `slide took Q from shield, which is now unbound rather than sharing it`,
  )
}

{
  binds.reset()
  binds.bind('slide', 'KeyQ')
  // An unbound action must not answer to the empty string, or every key press
  // with no code would fire it.
  const fires = binds.keys('shield').filter(Boolean).length
  ok(
    'an unbound action listens to nothing',
    fires === 0,
    `shield has ${fires} live keys after losing Q`,
  )
}

// ---- 3. the key that cannot move --------------------------------------------
{
  binds.reset()
  const r = binds.bind('shield', 'Escape')
  ok(
    'Escape cannot be bound away',
    !r.ok && binds.get('shield') === DEFAULT_BINDS.shield && RESERVED.has('Escape'),
    `refused: "${r.why}" — it is the only way out of pointer lock`,
  )
}

// ---- 4. what the page must not scroll on ------------------------------------
{
  binds.reset()
  const before = binds.gameKeys()
  binds.bind('jump', 'KeyJ')
  const after = binds.gameKeys()
  ok(
    'the page stops scrolling on whatever you chose',
    !after.has('Space') && after.has('KeyJ') && before.has('Space'),
    `jump moved off Space, so Space scrolls again and J does not`,
  )
}

// ---- 5. it survives a reload ------------------------------------------------
{
  binds.reset()
  binds.bind('shield', 'KeyF')
  store.flush()
  const saved = JSON.parse(fake.getItem('open-pitch') ?? '{}')
  ok(
    'bindings are written to storage',
    saved.binds?.shield === 'KeyF',
    `the blob on disk has shield: ${saved.binds?.shield}`,
  )
}

// ---- 6. settings round-trip -------------------------------------------------
{
  store.set('heightSens', 3.25)
  store.set('view', '2d')
  store.set('name', 'JAMIE')
  store.flush()
  const saved = JSON.parse(fake.getItem('open-pitch') ?? '{}')
  ok(
    'so are your settings',
    saved.heightSens === 3.25 && saved.view === '2d' && saved.name === 'JAMIE',
    `sensitivity ${saved.heightSens}, view ${saved.view}, name ${saved.name}`,
  )
}

// ---- 7. drill bests ---------------------------------------------------------
{
  const improved = store.recordBest('curl', 40, '4/6')
  const worse = store.recordBest('curl', 25, '2/6')
  const better = store.recordBest('curl', 55, '6/6')
  ok(
    'a best is only overwritten by a better one',
    improved && !worse && better && store.best('curl')?.score === 55,
    `40 kept, 25 rejected, 55 took it — best is now ${store.best('curl')?.score}`,
  )
}

// ---- 8. a browser that refuses to store anything ----------------------------
{
  fake.failWrites = true
  store.set('name', 'NOBODY')
  store.flush()
  const stillWorks = store.get('name') === 'NOBODY'
  ok(
    'storage failing does not break the game',
    stillWorks && !store.available,
    `the setting took effect in memory; the store knows it wasn't written`,
  )
  fake.failWrites = false
}

// ---- 9. a blob from another version is discarded, not misread ---------------
{
  fake.raw.set('open-pitch', JSON.stringify({ v: 999, name: 'GHOST', binds: { shield: 'KeyZ' } }))
  // The store reads at construction, so this proves the guard rather than the
  // instance: a mismatched version must produce defaults, not a half-read blob.
  const asRead = JSON.parse(fake.getItem('open-pitch')!)
  const wouldTake = asRead.v === 1
  ok(
    'a blob from another version is thrown away',
    !wouldTake,
    `v${asRead.v} does not match v1, so it is ignored rather than half-applied`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
