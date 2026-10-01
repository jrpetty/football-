// Full screen: a button in camp and on runs, a row in Settings, Alt+Enter.
// While full screen the game keeps the Esc key (hold Esc to leave), where the
// browser allows it.
import { h } from '../core/util.js'
import { icon } from './icons.js'

export const isFullscreen = () => !!document.fullscreenElement
export const canFullscreen = () => !!(document.fullscreenEnabled && document.documentElement.requestFullscreen)

export async function toggleFullscreen(ui) {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
      return true
    }
    await document.documentElement.requestFullscreen({ navigationUI: 'hide' })
    try {
      await navigator.keyboard?.lock?.(['Escape'])
    } catch {}
    return true
  } catch {
    ui?.toast?.('Full screen is not available here. Press F11 instead.', '')
    return false
  }
}

// A button that shows which way it goes.
export function fullscreenButton(ui, cls = 'button.navbtn.small') {
  const b = h(cls, { onclick: () => toggleFullscreen(ui).then(sync) })
  const sync = () => {
    const on = isFullscreen()
    b.innerHTML = ''
    b.append(h('i', { html: icon(on ? 'unfullscreen' : 'fullscreen') }))
    b.setAttribute('data-tip', `${on ? 'Leave full screen' : 'Full screen'} <kbd>Alt</kbd>+<kbd>Enter</kbd>${on ? '<br><em>or hold Esc</em>' : ''}`)
  }
  document.addEventListener('fullscreenchange', sync)
  sync()
  return b
}
