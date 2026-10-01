// Procedural sound effects with WebAudio. Nothing is loaded from disk.

let ctx = null
let master = null
let noiseBuf = null
let ambience = null
let enabled = true
let lastPlay = {}

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
  master.gain.value = enabled ? 0.55 : 0
  master.connect(ctx.destination)
  noiseBuf = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate)
  const d = noiseBuf.getChannelData(0)
  for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1
  startAmbience()
}

export function setSound(on) {
  enabled = on
  if (master) master.gain.setTargetAtTime(on ? 0.55 : 0, ctx.currentTime, 0.05)
}
export const soundOn = () => enabled

function noise(dur, { type = 'lowpass', freq = 1000, q = 1, gain = 0.5, attack = 0.002, decay = dur, freqEnd = null, pan = 0 } = {}) {
  const t = ctx.currentTime
  const src = ctx.createBufferSource()
  src.buffer = noiseBuf
  src.playbackRate.value = 0.8 + Math.random() * 0.4
  const f = ctx.createBiquadFilter()
  f.type = type
  f.frequency.setValueAtTime(freq, t)
  if (freqEnd) f.frequency.exponentialRampToValueAtTime(freqEnd, t + dur)
  f.Q.value = q
  const g = ctx.createGain()
  g.gain.setValueAtTime(0.0001, t)
  g.gain.exponentialRampToValueAtTime(gain, t + attack)
  g.gain.exponentialRampToValueAtTime(0.0001, t + decay)
  const p = ctx.createStereoPanner ? ctx.createStereoPanner() : null
  src.connect(f).connect(g)
  if (p) {
    p.pan.value = pan
    g.connect(p).connect(master)
  } else g.connect(master)
  src.start(t, Math.random())
  src.stop(t + dur + 0.05)
}

function tone(freq, dur, { type = 'sine', gain = 0.2, attack = 0.005, freqEnd = null, delay = 0 } = {}) {
  const t = ctx.currentTime + delay
  const o = ctx.createOscillator()
  o.type = type
  o.frequency.setValueAtTime(freq, t)
  if (freqEnd) o.frequency.exponentialRampToValueAtTime(freqEnd, t + dur)
  const g = ctx.createGain()
  g.gain.setValueAtTime(0.0001, t)
  g.gain.exponentialRampToValueAtTime(gain, t + attack)
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur)
  o.connect(g).connect(master)
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
}

export function sfx(id, throttleMs = 40) {
  if (!ctx || !enabled || !SFX[id]) return
  if (!gate(id, throttleMs)) return
  try {
    SFX[id]()
  } catch {}
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
