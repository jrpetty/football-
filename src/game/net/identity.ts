import { store } from '../core/store'

// Who you are, across sessions.
//
// Two separate things that both used to be the literal string 'player':
//
//   The name is what other people see over your head. It is yours to choose and
//     it is saved, because typing it every time you join is the sort of friction
//     that stops people joining.
//
//   The token is not shown to anybody. It is a random per-browser string that
//     lets a host recognise you when you come back after a dropped connection
//     and hand you the same shirt instead of a new one. It never leaves the
//     match you are in, and it identifies a browser rather than a person.

export function playerName(): string {
  const saved = store.get('name').trim()
  if (saved) return saved.slice(0, 14)
  // Not everyone will set one. A shirtless "PLAYER 2" is a worse label than
  // something with a bit of character, and the host uniquifies by seat anyway.
  return 'PLAYER'
}

export function setPlayerName(n: string) {
  store.set('name', n.trim().slice(0, 14))
}

let cached = ''

export function clientToken(): string {
  if (cached) return cached
  // Not persisted deliberately: it should survive a dropped socket, not a
  // deliberate reload. Coming back after a refresh is a new join, and taking
  // over a shirt the host is holding for a tab that no longer exists would be
  // the wrong behaviour if two tabs were ever open at once.
  cached = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
  return cached
}
