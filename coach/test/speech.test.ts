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

const u = (id: string, expiresAt: number | null = null, priority: Utterance['priority'] = 'normal'): Utterance => ({ id, text: id, category: 'reflective', priority, expiresAt })

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

test('a high-priority tip pre-empts lower-priority speech; the backlog drops the least important', () => {
  const { c, spoken, log } = setup([{ voiceURI: 'v', localService: true, default: true }])
  c.enqueue(u('note', null, 'low'))
  c.enqueue(u('tip', null, 'high'))
  assert.ok(log.includes('interrupted note'))
  assert.equal(spoken.at(-1)!.text, 'tip')
  c.enqueue(u('a', null, 'low'))
  c.enqueue(u('b', null, 'normal'))
  c.enqueue(u('c', null, 'normal'))
  assert.ok(log.includes('expired a'), 'the low-priority item was dropped first')
})

// --- Neural voice -----------------------------------------------------------------------

import { NeuralPlayer, sentences, type AudioOut } from '../src/speech/neural.ts'

function neuralSetup(fail = false) {
  const requests: string[] = []
  const playing: { text: string; end: () => void; stopped: boolean }[] = []
  const out: AudioOut = {
    play(samples, _sr, _vol, ended) {
      const p = { text: String(samples[0]), end: ended, stopped: false }
      playing.push(p)
      return { stop: () => void (p.stopped = true) }
    },
  }
  const texts: string[] = []
  const player = new NeuralPlayer(async ({ text }) => {
    requests.push(text)
    if (fail) throw new Error('not installed')
    texts.push(text)
    return { samples: new Float32Array([texts.length]), sampleRate: 22050 }
  }, out)
  return { player, requests, playing }
}

const tick = () => new Promise((r) => setTimeout(r, 0))

test('neural speech is split into sentences and the next is prepared while one plays', async () => {
  assert.deepEqual(sentences('Housed. Build a house now!  Then scout?'), ['Housed.', 'Build a house now!', 'Then scout?'])
  assert.ok(sentences('x'.repeat(900)).every((s) => s.length <= 400))
  const { player, requests, playing } = neuralSetup()
  let outcome = ''
  player.speak('One. Two. Three.', 'neural:jenny', 1, 1, (o) => (outcome = o))
  await tick()
  assert.equal(playing.length, 1)
  assert.deepEqual(requests, ['One.', 'Two.'], 'the second sentence is synthesised during the first')
  playing[0].end()
  await tick()
  playing[1].end()
  await tick()
  playing[2].end()
  await tick()
  assert.equal(outcome, 'finished')
})

test('the controller uses the neural voice, cancels it, and falls back to a system voice when it fails', async () => {
  const ok = neuralSetup()
  const spoken: unknown[] = []
  const synth: Synth = { speak: (x) => void spoken.push(x), cancel: () => {}, getVoices: () => [{ voiceURI: 'local', localService: true, default: true } as SpeechSynthesisVoice] }
  const make = (text: string) => ({ text, onend: null, onerror: null }) as unknown as SpeechSynthesisUtterance
  const log: string[] = []
  const c = new SpeechController(synth, make, { onEnd: (id, o) => log.push(`${o} ${id}`) }, ok.player)
  c.configure({ voiceURI: 'neural:jenny', rate: 1, volume: 1 })
  c.enqueue(u('a'))
  await tick()
  assert.equal(ok.playing.length, 1)
  assert.equal(spoken.length, 0, 'the system voice is not used')
  c.stopAll()
  assert.equal(ok.playing[0].stopped, true)
  assert.deepEqual(log, ['interrupted a'])

  const bad = neuralSetup(true)
  const c2 = new SpeechController(synth, make, {}, bad.player)
  c2.configure({ voiceURI: 'neural:kokoro:emma', rate: 1, volume: 1 })
  c2.enqueue(u('b'))
  await tick()
  assert.equal(spoken.length, 1, 'fell back to the local system voice')
})
