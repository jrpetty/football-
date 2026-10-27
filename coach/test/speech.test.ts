// The speech controller: local voices only, one at a time, interruptible, expiring.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SpeechController, type Synth } from '../src/speech/speech.ts'
import type { Utterance } from '../src/core/types.ts'

type FakeUtterance = { text: string; voice: unknown; rate: number; volume: number; onend: (() => void) | null; onerror: (() => void) | null }

function setup(voices: Partial<SpeechSynthesisVoice>[]) {
  const spoken: FakeUtterance[] = []
  let cancels = 0
  const synth: Synth = {
    speak: (u) => void spoken.push(u as unknown as FakeUtterance),
    cancel: () => void cancels++,
    getVoices: () => voices as SpeechSynthesisVoice[],
  }
  const log: string[] = []
  let now = 0
  const c = new SpeechController(
    synth,
    (text) => ({ text, voice: null, rate: 1, volume: 1, onend: null, onerror: null }) as unknown as SpeechSynthesisUtterance,
    { onStart: (id) => log.push(`start ${id}`), onEnd: (id, o) => log.push(`${o} ${id}`) },
  )
  c.setClock(() => now)
  return { c, spoken, log, cancels: () => cancels, setNow: (t: number) => (now = t) }
}

const u = (id: string, expiresAt: number | null = null): Utterance => ({ id, text: id, kind: 'round-review', expiresAt })

test('only local voices are offered or used', () => {
  const { c, spoken } = setup([
    { voiceURI: 'cloud', name: 'Cloud', localService: false, default: true },
    { voiceURI: 'local', name: 'Local', localService: true, default: false },
  ])
  assert.deepEqual(c.localVoices().map((v) => v.voiceURI), ['local'])
  c.configure({ voiceURI: 'cloud', rate: 1.2, volume: 0.5 })
  c.enqueue(u('a'))
  assert.equal((spoken[0].voice as SpeechSynthesisVoice).voiceURI, 'local')
  assert.equal(spoken[0].rate, 1.2)
})

test('with no local voice nothing is spoken', () => {
  const { c, spoken, log } = setup([{ voiceURI: 'cloud', localService: false, default: true }])
  assert.equal(c.available, false)
  c.enqueue(u('a'))
  assert.equal(spoken.length, 0)
  assert.deepEqual(log, ['error a'])
})

test('one at a time; expired utterances are dropped, not delayed', () => {
  const { c, spoken, log, setNow } = setup([{ voiceURI: 'v', localService: true, default: true }])
  c.enqueue(u('a'))
  c.enqueue(u('b', 1000))
  assert.equal(spoken.length, 1)
  setNow(2000)
  spoken[0].onend!()
  assert.equal(spoken.length, 1, 'b expired while a was speaking')
  assert.deepEqual(log, ['start a', 'finished a', 'expired b'])
})

test('cancel and stopAll interrupt immediately', () => {
  const { c, spoken, log, cancels } = setup([{ voiceURI: 'v', localService: true, default: true }])
  c.enqueue(u('a'))
  c.enqueue(u('b'))
  c.cancel(['a'])
  assert.equal(cancels(), 1)
  assert.equal(spoken.at(-1)!.text, 'b')
  c.stopAll()
  assert.equal(c.speakingId, null)
  assert.ok(log.includes('interrupted a') && log.includes('interrupted b'))
})

test('the waiting backlog is bounded', () => {
  const { c, log } = setup([{ voiceURI: 'v', localService: true, default: true }])
  for (const id of ['a', 'b', 'c', 'd']) c.enqueue(u(id))
  assert.ok(log.includes('expired b'))
})
