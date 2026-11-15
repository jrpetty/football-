// Server restarts: the player types their server's restart times (most
// community servers restart on a fixed schedule) and the coach warns before
// each one, so nobody is mid-loot or mid-drive when the server goes down.
// Times are the player's local clock. Nothing is read from the game.

/** Minutes past midnight of each restart, sorted, from "00:00 06:00 12:00 18:00" or "every 4 hours from 02:00". */
export function parseRestarts(text: string): number[] {
  const t = text.toLowerCase().trim()
  if (!t) return []
  const every = t.match(/every\s+(\d{1,2})\s*(?:h|hours?|hrs?)\b(?:\s*(?:from|starting|at)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?)?/)
  if (every) {
    const step = Number(every[1])
    if (step < 1 || step > 24 || 24 % step !== 0) return []
    const start = every[2] ? toMinutes(Number(every[2]), Number(every[3] ?? 0), every[4]) : 0
    if (start === null) return []
    const out: number[] = []
    for (let m = start % (step * 60); m < 1440; m += step * 60) out.push(m)
    return out
  }
  const out = new Set<number>()
  for (const m of t.matchAll(/(\d{1,2})(?::|\.|h)?(\d{2})?\s*(am|pm)?/g)) {
    if (!m[2] && !m[3] && m[1].length < 2 && !/\d/.test(t.replace(m[0], ''))) continue
    const v = toMinutes(Number(m[1]), Number(m[2] ?? 0), m[3])
    if (v !== null) out.add(v)
  }
  return [...out].sort((a, b) => a - b)
}

function toMinutes(h: number, min: number, ampm?: string): number | null {
  let hh = h
  if (ampm === 'pm' && hh < 12) hh += 12
  if (ampm === 'am' && hh === 12) hh = 0
  if (hh > 24 || min > 59) return null
  return (hh % 24) * 60 + min
}

/** The next restart after now (local time): when, and minutes to go. */
export function nextRestart(restarts: readonly number[], now: Date): { at: Date; inMin: number } | null {
  if (!restarts.length) return null
  const mins = now.getHours() * 60 + now.getMinutes() + now.getSeconds() / 60
  const next = restarts.find((r) => r > mins) ?? restarts[0] + 1440
  const inMin = next - mins
  return { at: new Date(now.getTime() + inMin * 60_000), inMin }
}

/** Warn this many minutes before. */
export const WARN_AT: readonly number[] = [15, 5, 1]

/** The warning due between two checks (the threshold just crossed), or null. */
export function dueWarning(restarts: readonly number[], prev: Date, now: Date): number | null {
  const a = nextRestart(restarts, prev)
  const b = nextRestart(restarts, now)
  if (!a || !b || Math.abs(a.at.getTime() - b.at.getTime()) > 90_000) return null
  return WARN_AT.find((w) => a.inMin > w && b.inMin <= w) ?? null
}

/** "Server restart in 5 minutes (at 18:00): get somewhere safe and log out in cover." */
export function warningText(minutes: number, at: Date): string {
  const when = clock(at)
  if (minutes <= 1) return `Server restart in about a minute (${when}). Find cover now; log out in a safe spot.`
  if (minutes <= 5) return `Server restart in ${minutes} minutes (${when}). Stop looting and get somewhere safe to log out.`
  return `Server restart in ${minutes} minutes (${when}). Time to head somewhere safe; do not start a long drive.`
}

/** "Next restart at 18:00, in 42 minutes." */
export function restartLine(restarts: readonly number[], now: Date): string {
  const n = nextRestart(restarts, now)
  if (!n) return 'No restart times set. Add your server\'s restart times in Coach settings (e.g. "00:00 06:00 12:00 18:00" or "every 4 hours from 02:00"), and I will warn you 15, 5 and 1 minutes before.'
  const m = Math.round(n.inMin)
  return `Next restart at ${clock(n.at)}, in ${m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`}. Restarts: ${restarts.map((r) => clock(minutesToDate(r))).join(', ')}.`
}

export function isRestartQuestion(q: string): boolean {
  return /\b(restart|reboot|server (?:reset|wipe)|when does the server)\b/i.test(q)
}

const clock = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
const minutesToDate = (m: number) => new Date(2000, 0, 1, Math.floor(m / 60), m % 60)
