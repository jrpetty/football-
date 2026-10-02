// A small frame-rate readout in the bottom-right corner, over every screen.
// It shows frames a second, how long a frame takes, and the worst frame of
// the last moment (a stutter shows there long before the average moves).
// F3 or Settings turns it off and on; the choice is kept on this device.
import { h } from '../core/util.js'

const KEY = 'holdout.fps'
let el = null
let on = true
let frames = 0
let span = 0
let worst = 0
let shownAt = 0

try {
  on = localStorage.getItem(KEY) !== '0'
} catch {}

export const fpsOn = () => on
export function setFps(v) {
  on = !!v
  try {
    localStorage.setItem(KEY, on ? '1' : '0')
  } catch {}
  if (el) el.hidden = !on
  frames = span = worst = 0
}

export function initFps() {
  if (el) return
  el = h('div.fps', { hidden: !on }, h('b.fps-n', '--'), h('span', 'FPS'), h('i.fps-ms', ''), h('i.fps-low', ''))
  document.body.appendChild(el)
  addEventListener('keydown', (e) => {
    if (e.key !== 'F3' || e.ctrlKey || e.metaKey || e.altKey) return
    // F3 is the browser's find: the game keeps it
    e.preventDefault()
    setFps(!on)
  })
}

// Call once per drawn frame with how long it took since the last, in ms.
export function fpsFrame(ms) {
  if (!el || !on || !(ms > 0) || ms > 1000) return
  frames++
  span += ms
  if (ms > worst) worst = ms
  // refresh twice a second: steady enough to read
  if (span < 500) return
  const fps = (frames * 1000) / span
  el.className = 'fps ' + (fps >= 55 ? 'good' : fps >= 30 ? 'mid' : 'bad')
  el.children[0].textContent = Math.round(fps)
  el.children[2].textContent = `${(span / frames).toFixed(1)} ms`
  el.children[3].textContent = `low ${Math.round(1000 / worst)}`
  frames = span = worst = 0
}
