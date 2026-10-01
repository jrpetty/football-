// The volume bar: a speaker that mutes and unmutes, and a slider. The same
// control sits in the camp's top bar, on runs and in Settings; moving one
// moves them all.
import { S } from '../game/state.js'
import { setSound, setVolume, sfx } from '../core/audio.js'
import { bus, h } from '../core/util.js'
import { icon } from './icons.js'

const DEFAULT = 0.8
const vol = () => S?.settings?.volume ?? DEFAULT
const muted = () => S?.settings?.sound === false

export function volumeControl(game, { wide = false } = {}) {
  const btn = h('button.vol-btn')
  const slider = h('input.vol-slider', { type: 'range', min: 0, max: 100, step: 1, 'aria-label': 'Volume' })
  const pct = h('b.vol-pct')
  const el = h('div.vol' + (wide ? '.wide' : ''), btn, h('div.vol-pop', slider, wide ? pct : null))
  let off = null
  const sync = () => {
    // a control that left the page (the top bar is redrawn now and then)
    // stops listening the next time the volume changes
    if (el.wasIn && !el.isConnected) return off?.()
    if (el.isConnected) el.wasIn = true
    const v = Math.round(vol() * 100)
    const silent = muted() || v === 0
    if (document.activeElement !== slider) slider.value = String(v)
    slider.style.setProperty('--v', `${silent ? 0 : v}%`)
    el.classList.toggle('off', silent)
    btn.innerHTML = icon(silent ? 'soundOff' : v < 45 ? 'soundLow' : 'sound')
    btn.setAttribute('data-tip', `${silent ? 'Sound off' : `Volume ${v}%`}<br><em>Click to ${silent ? 'unmute' : 'mute'} · scroll to change</em>`)
    pct.textContent = silent ? 'Off' : `${v}%`
  }
  // keep it in the save (and, for a guest, in their own settings)
  const keep = () => game.applySettings?.()
  const set = (v, done = false) => {
    if (!S?.settings) return
    S.settings.volume = Math.max(0, Math.min(1, v))
    if (S.settings.volume > 0 && muted()) {
      S.settings.sound = true
      setSound(true)
    }
    setVolume(S.settings.volume)
    bus.emit('volume')
    if (done) {
      keep()
      sfx('select')
    }
  }
  btn.onclick = () => {
    // a focused button would take the Space bar meant for pausing
    btn.blur()
    if (!S?.settings) return
    const on = muted() || vol() === 0
    S.settings.sound = on
    if (on && vol() === 0) S.settings.volume = DEFAULT
    setVolume(vol())
    setSound(on)
    bus.emit('volume')
    keep()
    if (on) sfx('select')
  }
  slider.addEventListener('input', () => set(+slider.value / 100))
  slider.addEventListener('change', () => {
    set(+slider.value / 100, true)
    // hand the keys back to the camera
    slider.blur()
  })
  el.addEventListener(
    'wheel',
    (e) => {
      e.preventDefault()
      e.stopPropagation()
      set(vol() + (e.deltaY < 0 ? 0.05 : -0.05))
      clearTimeout(el.saveT)
      el.saveT = setTimeout(keep, 600)
    },
    { passive: false },
  )
  off = bus.on('volume', sync)
  sync()
  return el
}
