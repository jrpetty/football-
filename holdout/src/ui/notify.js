// Desktop notifications for when the game is in a background tab: the horde
// is coming, the horde is here, someone is at the gate. Opt-in from
// Settings; the browser asks once.
import { S } from '../game/state.js'

export const canNotify = () => typeof Notification !== 'undefined'
export const notifyOn = () => canNotify() && !!S?.settings?.notify && Notification.permission === 'granted'

export async function askNotify() {
  if (!canNotify()) return false
  if (Notification.permission === 'granted') return true
  if (Notification.permission === 'denied') return false
  try {
    return (await Notification.requestPermission()) === 'granted'
  } catch {
    return false
  }
}

let last = {}
export function notify(title, body, tag = body) {
  if (!notifyOn() || !document.hidden) return
  const now = Date.now()
  if (last[tag] && now - last[tag] < 60000) return
  last[tag] = now
  try {
    const n = new Notification(title, { body, tag, silent: false })
    n.onclick = () => {
      window.focus()
      n.close()
    }
  } catch {
    // some browsers only allow notifications from a service worker
  }
}
