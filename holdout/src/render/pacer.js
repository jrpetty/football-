// Frame pacing. A browser calls us once per screen refresh, and on a fast
// screen (120, 144, 240 Hz) the game would draw every one of them and keep the
// graphics card flat out for frames that are hard to tell apart. "Smart" draws
// every 2nd, 3rd... refresh on such a screen so the rate stays at 60 or more
// (72 on a 144 Hz screen, 60 on 120 or 240 Hz), and evenly spaced, which a
// plain cap at some in-between number would not be. A card that cannot keep up
// loses nothing: when frames arrive late, every one is drawn. "Every refresh"
// turns it off. Kept on this device, apart from any save.
const KEY = 'holdout.pace'
let mode = 'smart'
try {
  const v = localStorage.getItem(KEY)
  if (v === 'full' || v === 'smart') mode = v
} catch {}

export const paceMode = () => mode
export function setPace(m) {
  mode = m === 'full' ? 'full' : 'smart'
  try {
    localStorage.setItem(KEY, mode)
  } catch {}
}

const win = []
let lastTick = 0
let lastDraw = -1e9
let iv = 1000 / 60 // the screen's refresh interval, measured
let n = 1 // refreshes per drawn frame
let count = 0

export const pacing = () => ({ every: n, refreshMs: iv })

// Call at the top of every animation frame: true when this one is to be drawn.
export function paceFrame(t) {
  const d = t - lastTick
  lastTick = t
  // the interval between callbacks, ignoring tab switches and stalls
  if (d > 2.5 && d < 60) {
    win.push(d)
    if (win.length > 120) win.shift()
  }
  if (++count % 60 === 0 && win.length >= 40) {
    // a low percentile: the screen's own interval, even if some frames ran late
    const s = [...win].sort((a, b) => a - b)
    iv = s[Math.floor(s.length * 0.2)]
    n = Math.max(1, Math.floor(1000 / iv / 58))
  }
  if (mode === 'full' || n === 1) {
    lastDraw = t
    return true
  }
  if (t - lastDraw >= (n - 0.5) * iv) {
    lastDraw = t
    return true
  }
  return false
}
