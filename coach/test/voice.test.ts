// Talking to the coach: the talk key (tap, or hold while speaking, read from
// the OS's repeated key reports), and always listening (finding speech in the
// microphone's audio, and telling questions for the coach from other talk).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { FIRST_REPEAT_MS, RELEASE_GAP_MS, SinglePress, TalkKeyGesture, type TalkKeyAction } from '../src/speech/talkKey.ts'
import { heardCommand, VAD_FRAME, VAD_RATE, VoiceActivity } from '../src/speech/vad.ts'
import { sanitizeSettings, DEFAULT_SETTINGS } from '../src/core/settings.ts'
import { plural } from '../src/core/analyses/intel.ts'

/** Drive a gesture: key reports at the given times, ticks every 40 ms; the actions with their times. */
function drive(presses: number[], until: number, g = new TalkKeyGesture()): [number, TalkKeyAction][] {
  const out: [number, TalkKeyAction][] = []
  const events = [...presses].sort((a, b) => a - b)
  for (let t = 0; t <= until; t += 10) {
    while (events.length && events[0] <= t) {
      const a = g.press(events.shift()!)
      if (a) out.push([t, a])
    }
    if (t % 40 === 0) {
      const a = g.tick(t)
      if (a) out.push([t, a])
    }
  }
  return out
}

/** A key held from `from` to `to`: Windows reports it, waits `delay`, then repeats every `rate` ms. */
const held = (from: number, to: number, delay = 500, rate = 33) => {
  const out = [from]
  for (let t = from + delay; t <= to; t += rate) out.push(t)
  return out
}

test('talk key: holding it records until it is let go (the repeats are not presses)', () => {
  const acts = drive(held(1000, 4000), 6000)
  assert.deepEqual(
    acts.map(([, a]) => a),
    ['start', 'stop'],
  )
  assert.equal(acts[0][0], 1000)
  assert.ok(acts[1][0] > 4000 && acts[1][0] <= 4000 + RELEASE_GAP_MS + 50, `stopped at ${acts[1][0]}`)
  // The slowest repeat settings (1 s delay, about 400 ms between repeats) still read as a hold.
  assert.deepEqual(drive(held(0, 3000, 1000, 400), 5000).map(([, a]) => a), ['start', 'stop'])
})

test('talk key: a tap starts, the next tap stops, and holding the second press does not start again', () => {
  const acts = drive([1000, 5000, ...held(9000, 10_500)], 12_000)
  assert.deepEqual(acts.slice(0, 3), [
    [1000, 'start'],
    [5000, 'stop'],
    [9000, 'start'],
  ])
  assert.equal(acts[3][1], 'stop')
  assert.ok(acts[3][0] > 10_500 && acts[3][0] <= 10_500 + RELEASE_GAP_MS + 50)
  assert.equal(acts.length, 4)
  // A tap, then a held second press: one stop, its repeats swallowed, then idle again.
  const g = new TalkKeyGesture()
  const second = drive([0, ...held(3000, 4500)], 6000, g)
  assert.deepEqual(second.map(([, a]) => a), ['start', 'stop'])
  assert.deepEqual(drive([7000], 7500, g).map(([, a]) => a), ['start'])
})

test('talk key: recording ended by the time limit or the button, so the next press starts again', () => {
  const g = new TalkKeyGesture()
  assert.equal(g.press(0), 'start')
  for (let t = 40; t < FIRST_REPEAT_MS + 200; t += 40) g.tick(t)
  g.reset()
  assert.equal(g.press(20_000), 'start')
  // Reset while the key is still held: its repeats do not start a new recording.
  const h = new TalkKeyGesture()
  h.press(0)
  h.press(500)
  h.reset()
  assert.equal(h.press(533), null)
  assert.equal(h.tick(533 + RELEASE_GAP_MS + 10), null)
  assert.equal(h.press(5000), 'start')
})

test('the other keys act once per press, however long they are held', () => {
  const p = new SinglePress()
  const fired = held(0, 3000).filter((t) => p.press(t))
  assert.deepEqual(fired, [0])
  assert.equal(p.press(3000 + FIRST_REPEAT_MS + 100), true)
})

// --- Always listening -------------------------------------------------------------------

/** Generated audio: `ms` of noise at `level`, or a voiced sound (harmonics with a wobble) when `voice`. */
function sound(ms: number, level: number, voice = false, seed = 1): Float32Array {
  const n = Math.round((ms / 1000) * VAD_RATE)
  const out = new Float32Array(n)
  let s = seed
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647) * 2 - 1
  for (let i = 0; i < n; i++) {
    const t = i / VAD_RATE
    out[i] = voice ? level * (0.6 * Math.sin(2 * Math.PI * 140 * t) + 0.3 * Math.sin(2 * Math.PI * 280 * t) + 0.1 * rnd()) * (0.75 + 0.25 * Math.sin(2 * Math.PI * 4 * t)) : level * rnd()
  }
  return out
}

function run(vad: VoiceActivity, ...parts: Float32Array[]): Float32Array[] {
  const all = new Float32Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    all.set(p, at)
    at += p.length
  }
  const got: Float32Array[] = []
  for (let i = 0; i + VAD_FRAME <= all.length; i += VAD_FRAME) {
    const seg = vad.push(all.subarray(i, i + VAD_FRAME))
    if (seg) got.push(seg)
  }
  return got
}

test('always listening: a spoken question in a quiet room is one stretch, with the start kept', () => {
  const got = run(new VoiceActivity(), sound(2000, 0.003), sound(1800, 0.2, true), sound(1500, 0.003))
  assert.equal(got.length, 1)
  const sec = got[0].length / VAD_RATE
  // The speech plus a little before it and the pause after it.
  assert.ok(sec > 1.8 && sec < 3.2, `${sec.toFixed(2)} s`)
})

test('always listening: clicks and short noises are not speech; steady game noise raises the bar', () => {
  assert.equal(run(new VoiceActivity(), sound(2000, 0.003), sound(130, 0.3, true), sound(2000, 0.003)).length, 0, 'a click')
  // Loud, steady noise (game audio through speakers) for 10 s: not taken for speech.
  const vad = new VoiceActivity()
  assert.equal(run(vad, sound(10_000, 0.03, false, 7)).length, 0)
  assert.ok(vad.threshold > 0.05, `threshold ${vad.threshold.toFixed(3)}`)
  // A voice well above that noise still is.
  assert.equal(run(vad, sound(1500, 0.3, true), sound(1500, 0.03, false, 9)).length, 1)
})

test('always listening: a long stretch is cut at the limit; a reset drops what was in progress', () => {
  const long = run(new VoiceActivity({ maxMs: 4000 }), sound(1000, 0.003), sound(9000, 0.2, true))
  assert.ok(long.length >= 2 && long[0].length / VAD_RATE <= 4.5)
  const vad = new VoiceActivity()
  run(vad, sound(1000, 0.003), sound(800, 0.2, true))
  vad.reset()
  assert.equal(run(vad, sound(1500, 0.003)).length, 0)
})

test('always listening: only questions for the coach are asked', () => {
  // With the wake word.
  assert.deepEqual(heardCommand('Coach, how many villagers should I have?', true), { kind: 'question', text: 'How many villagers should I have?' })
  assert.deepEqual(heardCommand('hey coach what counters longbows', true), { kind: 'question', text: 'What counters longbows' })
  assert.deepEqual(heardCommand('Couch. What age are they in?', true), { kind: 'question', text: 'What age are they in?' })
  assert.deepEqual(heardCommand('Coach.', true), { kind: 'wake' })
  assert.deepEqual(heardCommand('push mid with me', true), { kind: 'ignore' })
  // After "coach" on its own, the next words are the question.
  assert.deepEqual(heardCommand('how do I beat knights', true, true), { kind: 'question', text: 'How do I beat knights' })
  // Without the wake word: anything of two words or more, except the recogniser's noise.
  assert.deepEqual(heardCommand('what beats knights', false), { kind: 'question', text: 'What beats knights' })
  for (const noise of ['Thank you.', 'you', 'Um.', '', '  ', 'Okay.', 'Yeah']) assert.deepEqual(heardCommand(noise, false), { kind: 'ignore' }, noise)
  assert.deepEqual(heardCommand('Coach, thank you.', true), { kind: 'wake' })
})

test('settings: the listening choice is kept and checked', () => {
  assert.equal(DEFAULT_SETTINGS.listen, 'key')
  assert.equal(DEFAULT_SETTINGS.wakeWord, true)
  const s = sanitizeSettings({ ...DEFAULT_SETTINGS, listen: 'always', wakeWord: false })
  assert.equal(s.listen, 'always')
  assert.equal(s.wakeWord, false)
  assert.equal(sanitizeSettings({ ...DEFAULT_SETTINGS, listen: 'shout' }).listen, 'key')
})

test('unit names with a note keep the note as it is', () => {
  assert.equal(plural('Man-at-Arms (from the Castle Age)'), 'Men-at-Arms (from the Castle Age)')
  assert.equal(plural('Horseman'), 'Horsemen')
  assert.equal(plural('Knight'), 'Knights')
})

test('asked out loud: "how many villagers should I have at ten minutes?" gets the number', async () => {
  const { answerLocally, classify, clockIn } = await import('../src/core/qa/ask.ts')
  const { builtInProfile } = await import('../src/profiles/index.ts')
  const aoe4 = builtInProfile('aoe4')
  assert.equal(clockIn('how many villagers should i have at ten minutes'), 600)
  assert.equal(clockIn('villagers by 12:30'), 750)
  assert.equal(clockIn('at 8 mins'), 480)
  assert.equal(clockIn('what about villagers'), null)
  const intent = classify('How many villagers should I have at ten minutes?', aoe4)
  assert.deepEqual(intent, { kind: 'benchmark', target: true, atSec: 600 })
  // Nothing read yet: the curve's numbers still answer it.
  const report = { benchmarks: [] } as unknown as Parameters<typeof answerLocally>[2]
  assert.equal(
    answerLocally(intent, aoe4, report, []),
    'One Town Center that never stops has 36 villagers at 10:00. That is 6 to start and 3 a minute, up to 120.',
  )
  assert.equal(
    answerLocally(classify('how many villagers should I have?', aoe4), aoe4, report, []),
    'One Town Center that never stops has 21 villagers at 5:00, 36 at 10:00, 51 at 15:00 and 66 at 20:00. That is 6 to start and 3 a minute, up to 120.',
  )
})

test('a talk-key recording is trimmed to the speech (the recogniser hears nothing after a second of quiet)', async () => {
  const { trimToSpeech } = await import('../src/speech/vad.ts')
  const rec = new Float32Array([...sound(3000, 0.002), ...sound(2500, 0.2, true), ...sound(6000, 0.002)])
  const t = trimToSpeech(rec)
  const sec = t.length / VAD_RATE
  assert.ok(sec > 2.5 && sec < 3.8, `${sec.toFixed(2)} s`)
  // It starts a little before the speech (about 3 s in), not seconds before.
  const startSec = t.byteOffset / Float32Array.BYTES_PER_ELEMENT / VAD_RATE
  assert.ok(startSec > 2.3 && startSec < 3, `starts at ${startSec.toFixed(2)} s`)
  // Nothing stands out: left as it is for the recogniser to judge.
  const flat = sound(3000, 0.002)
  assert.equal(trimToSpeech(flat).length, flat.length)
  assert.deepEqual(heardCommand('Hooch, how many villagers should I have?', true), { kind: 'question', text: 'How many villagers should I have?' })
  const { withoutWakeWord } = await import('../src/speech/vad.ts')
  assert.equal(withoutWakeWord('Coach, what beats knights?'), 'What beats knights?')
  assert.equal(withoutWakeWord('what beats knights?'), 'What beats knights?')
  assert.equal(withoutWakeWord(''), '')
})

test('"Coach" as the recogniser mishears it, before a pause, is still the coach; other first words are not', () => {
  for (const t of ['Ouch, how did that fight go?', 'Poch, what is the enemy building?', 'Hoech, how is my economy?']) assert.equal(heardCommand(t, true).kind, 'question', t)
  for (const t of ['Catch them at the gold.', 'Each of you take a side.', 'Ouch that hurt', 'Much better, well played.']) assert.equal(heardCommand(t, true).kind, 'ignore', t)
})

test('the voice detector keeps only the speech, and finds none in game sound', async () => {
  const { speechPart, MIN_SPEECH_SEC } = await import('../electron/stt/recognizer.ts')
  const pcm = new Float32Array(16000 * 6)
  assert.equal(speechPart(pcm, []), null)
  assert.equal(speechPart(pcm, [[16000, 16000 + MIN_SPEECH_SEC * 16000 - 100]]), null, 'too little speech')
  const part = speechPart(pcm, [
    [32000, 48000],
    [52000, 64000],
  ])!
  assert.equal(part.byteOffset / 4, 32000 - 0.35 * 16000)
  assert.equal(part.length, 64000 + 0.4 * 16000 - (32000 - 0.35 * 16000))
})
