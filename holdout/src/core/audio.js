// Procedural sound effects with WebAudio. Nothing is loaded from disk.

let ctx = null
let master = null
let noiseBuf = null
let ambience = null
let enabled = true
let volume = 0.8 // the player's volume, 0 to 1
let lastPlay = {}
// a sound placed in the world plays through its own chain (see sfxAt)
let out = null
let pitchMul = 1
// loudness follows the square of the slider, which is how ears hear it;
// 80% is the level the game always had
const level = () => (enabled ? 0.86 * volume * volume : 0)

export function initAudio() {
  if (ctx) {
    if (ctx.state === 'suspended') ctx.resume()
    return
  }
  try {
    ctx = new (window.AudioContext || window.webkitAudioContext)()
  } catch {
    return
  }
  master = ctx.createGain()
  master.gain.value = level()
  master.connect(ctx.destination)
  noiseBuf = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate)
  const d = noiseBuf.getChannelData(0)
  for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1
  startAmbience()
}

export function setSound(on) {
  enabled = on
  if (master) master.gain.setTargetAtTime(level(), ctx.currentTime, 0.05)
}
export function setVolume(v) {
  volume = Math.max(0, Math.min(1, +v || 0))
  if (master) master.gain.setTargetAtTime(level(), ctx.currentTime, 0.05)
}
export const soundOn = () => enabled
export const getVolume = () => volume

function noise(dur, { type = 'lowpass', freq = 1000, q = 1, gain = 0.5, attack = 0.002, decay = dur, freqEnd = null, pan = 0 } = {}) {
  const t = ctx.currentTime
  const src = ctx.createBufferSource()
  src.buffer = noiseBuf
  src.playbackRate.value = 0.8 + Math.random() * 0.4
  const f = ctx.createBiquadFilter()
  f.type = type
  f.frequency.setValueAtTime(freq * pitchMul, t)
  if (freqEnd) f.frequency.exponentialRampToValueAtTime(freqEnd * pitchMul, t + dur)
  f.Q.value = q
  const g = ctx.createGain()
  g.gain.setValueAtTime(0.0001, t)
  g.gain.exponentialRampToValueAtTime(gain, t + attack)
  g.gain.exponentialRampToValueAtTime(0.0001, t + decay)
  const p = ctx.createStereoPanner ? ctx.createStereoPanner() : null
  src.connect(f).connect(g)
  if (p && !out) {
    p.pan.value = pan
    g.connect(p).connect(master)
  } else g.connect(out || master)
  src.start(t, Math.random())
  src.stop(t + dur + 0.05)
}

function tone(freq, dur, { type = 'sine', gain = 0.2, attack = 0.005, freqEnd = null, delay = 0 } = {}) {
  const t = ctx.currentTime + delay
  const o = ctx.createOscillator()
  o.type = type
  o.frequency.setValueAtTime(freq * pitchMul, t)
  if (freqEnd) o.frequency.exponentialRampToValueAtTime(freqEnd * pitchMul, t + dur)
  const g = ctx.createGain()
  g.gain.setValueAtTime(0.0001, t)
  g.gain.exponentialRampToValueAtTime(gain, t + attack)
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur)
  o.connect(g).connect(out || master)
  o.start(t)
  o.stop(t + dur + 0.05)
}

// Throttle each sound id so twenty zombies don't groan in the same frame.
function gate(id, ms) {
  const now = performance.now()
  if (lastPlay[id] && now - lastPlay[id] < ms) return false
  lastPlay[id] = now
  return true
}

const SFX = {
  click: () => tone(880, 0.06, { type: 'triangle', gain: 0.08 }),
  select: () => {
    tone(520, 0.07, { type: 'triangle', gain: 0.08 })
    tone(780, 0.08, { type: 'triangle', gain: 0.06, delay: 0.05 })
  },
  move: () => tone(340, 0.08, { type: 'triangle', gain: 0.06, freqEnd: 420 }),
  error: () => tone(160, 0.18, { type: 'square', gain: 0.05, freqEnd: 110 }),
  pistol: () => {
    noise(0.22, { freq: 2600, freqEnd: 300, gain: 0.5 })
    tone(140, 0.12, { type: 'sine', gain: 0.35, freqEnd: 50 })
  },
  shotgun: () => {
    noise(0.45, { freq: 1800, freqEnd: 150, gain: 0.7 })
    tone(90, 0.25, { type: 'sine', gain: 0.5, freqEnd: 35 })
  },
  rifle: () => {
    noise(0.35, { freq: 3200, freqEnd: 200, gain: 0.6 })
    tone(110, 0.2, { type: 'sine', gain: 0.4, freqEnd: 40 })
  },
  smg: () => noise(0.12, { freq: 3000, freqEnd: 500, gain: 0.35 }),
  crossbow: () => {
    noise(0.08, { type: 'highpass', freq: 2500, gain: 0.2 })
    tone(220, 0.1, { type: 'triangle', gain: 0.1, freqEnd: 120 })
  },
  swing: () => noise(0.18, { type: 'bandpass', freq: 900, freqEnd: 2400, q: 2, gain: 0.18, attack: 0.05 }),
  hit: () => {
    noise(0.1, { freq: 500, gain: 0.35 })
    tone(90, 0.1, { type: 'sine', gain: 0.25, freqEnd: 60 })
  },
  hurt: () => tone(300, 0.2, { type: 'sawtooth', gain: 0.07, freqEnd: 180 }),
  groan: () => {
    const base = 70 + Math.random() * 50
    tone(base, 0.9, { type: 'sawtooth', gain: 0.045, attack: 0.2, freqEnd: base * 0.7 })
    noise(0.8, { type: 'bandpass', freq: 500, q: 4, gain: 0.05, attack: 0.25 })
  },
  zdie: () => {
    noise(0.35, { freq: 400, freqEnd: 120, gain: 0.3 })
    tone(90, 0.4, { type: 'sawtooth', gain: 0.05, freqEnd: 45 })
  },
  search: () => noise(0.25, { type: 'bandpass', freq: 1400, q: 1.5, gain: 0.08, attack: 0.05 }),
  dismantle: () => {
    noise(0.12, { freq: 1200, gain: 0.25 })
    tone(200 + Math.random() * 80, 0.08, { type: 'square', gain: 0.05 })
  },
  loot: () => {
    tone(660, 0.1, { type: 'triangle', gain: 0.1 })
    tone(990, 0.16, { type: 'triangle', gain: 0.09, delay: 0.08 })
  },
  rare: () => {
    tone(660, 0.12, { type: 'triangle', gain: 0.1 })
    tone(880, 0.12, { type: 'triangle', gain: 0.1, delay: 0.09 })
    tone(1320, 0.3, { type: 'triangle', gain: 0.1, delay: 0.18 })
  },
  build: () => {
    noise(0.07, { freq: 900, gain: 0.3 })
    tone(180, 0.07, { type: 'square', gain: 0.05, delay: 0.01 })
  },
  complete: () => {
    tone(392, 0.15, { type: 'triangle', gain: 0.1 })
    tone(523, 0.15, { type: 'triangle', gain: 0.1, delay: 0.12 })
    tone(784, 0.35, { type: 'triangle', gain: 0.1, delay: 0.24 })
  },
  alarm: () => {
    for (let i = 0; i < 3; i++) {
      tone(440, 0.28, { type: 'square', gain: 0.06, delay: i * 0.4, freqEnd: 660 })
    }
  },
  radio: () => {
    noise(0.5, { type: 'bandpass', freq: 1800, q: 3, gain: 0.08 })
    tone(1200, 0.08, { type: 'sine', gain: 0.05, delay: 0.5 })
  },
  ping: () => {
    tone(1480, 0.09, { type: 'sine', gain: 0.07 })
    tone(1980, 0.16, { type: 'sine', gain: 0.05, delay: 0.09 })
  },
  coin: () => {
    tone(1200, 0.07, { type: 'square', gain: 0.04 })
    tone(1600, 0.12, { type: 'square', gain: 0.04, delay: 0.06 })
  },
  levelup: () => {
    ;[523, 659, 784, 1046].forEach((f, i) => tone(f, 0.2, { type: 'triangle', gain: 0.08, delay: i * 0.08 }))
  },
  down: () => tone(220, 0.6, { type: 'sawtooth', gain: 0.07, freqEnd: 90 }),
  truck: () => noise(1.4, { freq: 160, gain: 0.2, attack: 0.3 }),
  boom: () => {
    noise(1.6, { freq: 900, freqEnd: 60, gain: 0.9, attack: 0.002 })
    tone(70, 0.9, { type: 'sine', gain: 0.5, freqEnd: 30 })
  },
  fire: () => {
    noise(0.5, { type: 'bandpass', freq: 600, q: 0.7, gain: 0.25, attack: 0.01 })
    noise(1.4, { freq: 300, gain: 0.12, attack: 0.2 })
  },
  glass: () => {
    for (let k = 0; k < 4; k++) tone(2400 + Math.random() * 2400, 0.12, { type: 'triangle', gain: 0.05, delay: k * 0.03 })
    noise(0.2, { type: 'highpass', freq: 3000, gain: 0.2 })
  },
  beep: () => tone(1320, 0.09, { type: 'square', gain: 0.05 }),
  throw: () => noise(0.25, { type: 'bandpass', freq: 700, freqEnd: 1800, q: 1.5, gain: 0.12, attack: 0.03 }),
  unlock: () => {
    tone(520, 0.07, { type: 'square', gain: 0.05 })
    tone(780, 0.09, { type: 'square', gain: 0.05, delay: 0.08 })
  },
  // a zombie comes into view: a low tense sting
  spot: () => {
    tone(196, 0.32, { type: 'sawtooth', gain: 0.035, freqEnd: 185 })
    tone(233, 0.28, { type: 'triangle', gain: 0.03, delay: 0.04 })
  },
  // a new room opens up
  reveal: () => noise(0.6, { type: 'bandpass', freq: 500, freqEnd: 900, q: 0.8, gain: 0.05, attack: 0.15 }),
  // a trap spotted / tripped
  trapSpot: () => {
    tone(980, 0.06, { type: 'triangle', gain: 0.05 })
    tone(1240, 0.08, { type: 'triangle', gain: 0.05, delay: 0.07 })
  },
  snap: () => {
    noise(0.12, { freq: 4200, freqEnd: 900, gain: 0.4 })
    tone(140, 0.25, { type: 'square', gain: 0.08, freqEnd: 60 })
  },
  scream: () => {
    tone(880, 0.9, { type: 'sawtooth', gain: 0.06, freqEnd: 1320 })
    tone(1100, 0.8, { type: 'sawtooth', gain: 0.04, freqEnd: 1500, delay: 0.05 })
    noise(0.9, { type: 'bandpass', freq: 2200, q: 2, gain: 0.08, attack: 0.05 })
  },
  burst: () => {
    noise(0.5, { freq: 600, freqEnd: 120, gain: 0.45 })
    tone(90, 0.4, { type: 'sine', gain: 0.12, freqEnd: 40 })
  },
}

// Co-op runs listen in on the leader's sounds to replay them for friends.
export const sfxTap = { fn: null }
export function sfx(id, throttleMs = 40) {
  sfxTap.fn?.(id)
  if (!ctx || !enabled || volume <= 0 || !SFX[id]) return
  if (!gate(id, throttleMs)) return
  try {
    SFX[id]()
  } catch {}
}

// A sound somewhere in the world: panned left or right, quieter with
// distance, and muffled (a low-pass) when a wall is in the way. `pitch`
// gives each zombie its own voice.
export function sfxAt(id, { pan = 0, gain = 1, muffle = 0, pitch = 1 } = {}, throttleMs = 40) {
  sfxTap.fn?.(id)
  if (!ctx || !enabled || volume <= 0 || !SFX[id] || gain <= 0.01) return
  if (!gate(id, throttleMs)) return
  const g = ctx.createGain()
  g.gain.value = gain
  const f = ctx.createBiquadFilter()
  f.type = 'lowpass'
  f.frequency.value = 18000 * Math.pow(420 / 18000, Math.max(0, Math.min(1, muffle)))
  f.Q.value = 0.6
  g.connect(f)
  let tail = f
  if (ctx.createStereoPanner) {
    const p = ctx.createStereoPanner()
    p.pan.value = Math.max(-1, Math.min(1, pan))
    f.connect(p)
    tail = p
  }
  tail.connect(master)
  out = g
  pitchMul = pitch
  try {
    SFX[id]()
  } catch {
  } finally {
    out = null
    pitchMul = 1
  }
  // the longest sounds are done within a couple of seconds
  setTimeout(() => g.disconnect(), 4000)
}

// Low wind bed that keeps the world from feeling silent.
function startAmbience() {
  const src = ctx.createBufferSource()
  src.buffer = noiseBuf
  src.loop = true
  const f = ctx.createBiquadFilter()
  f.type = 'lowpass'
  f.frequency.value = 380
  const g = ctx.createGain()
  g.gain.value = 0.035
  const lfo = ctx.createOscillator()
  lfo.frequency.value = 0.07
  const lg = ctx.createGain()
  lg.gain.value = 160
  lfo.connect(lg).connect(f.frequency)
  src.connect(f).connect(g).connect(master)
  src.start()
  lfo.start()
  ambience = { g }
}
export function setAmbience(level) {
  if (ambience) ambience.g.gain.setTargetAtTime(level, ctx.currentTime, 0.5)
}
