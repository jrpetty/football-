// The multiplayer card on the title screen: your name, hosting your
// multiplayer camp (a save of its own, apart from your single-player camp)
// and joining a friend's, by code or from the list of camps open here.
import { hasSave, MP_SAVE_KEY } from '../game/state.js'
import { me, saveMe, writeIntent, PROTO } from '../net/mp.js'
import { roomLobby, carrierKind, LOBBY_KEY } from '../net/transport.js'
import { sfx } from '../core/audio.js'
import { h } from '../core/util.js'

const WHY = {
  'no-camp': 'No camp answered with that code. Check it, and that the host is still in their camp.',
  version: 'That camp runs a different version of Holdout. Both of you need the same build.',
  refused: 'The host turned the connection down.',
  offline: 'Could not reach the connection service. Check your internet connection.',
  timeout: 'The connection timed out. Try again in a moment.',
  self: 'That is your own camp. Open it with Host instead.',
}
export const joinError = (code) => WHY[code] || `Could not join (${code}).`

export function lobbyCard(game, { error, code = '', back }) {
  const p = me()
  const name = h('input.inp', { value: p.name || '', maxLength: 20, placeholder: 'Your name', spellcheck: false })
  const codeIn = h('input.inp.code', { value: code, maxLength: 5, placeholder: 'CODE', spellcheck: false })
  const err = h('p.lb-err', error || '')
  err.hidden = !error
  const keep = () => {
    const n = name.value.trim().slice(0, 20)
    if (!n) {
      name.focus()
      name.classList.add('bad')
      err.textContent = 'Pick a name first: it is what your friends see.'
      err.hidden = false
      sfx('error')
      return null
    }
    saveMe({ pid: p.pid, name: n })
    return n
  }
  const host = (fresh) => {
    const n = keep()
    if (!n) return
    sfx('click')
    writeIntent({ mode: 'host', fresh, name: n })
    location.hash = ''
    location.reload()
  }
  const join = (c) => {
    const n = keep()
    if (!n) return
    c = (c || codeIn.value).trim().toUpperCase()
    if (!/^[A-Z0-9]{5}$/.test(c)) {
      codeIn.focus()
      codeIn.classList.add('bad')
      err.textContent = 'A camp code is five letters and numbers.'
      err.hidden = false
      return sfx('error')
    }
    sfx('click')
    writeIntent({ mode: 'join', code: c, name: n })
    location.hash = ''
    location.reload()
  }
  codeIn.addEventListener('input', () => (codeIn.value = codeIn.value.toUpperCase().replace(/[^A-Z0-9]/g, '')))
  codeIn.addEventListener('keydown', (e) => e.key === 'Enter' && join())

  const mine = hasSave(MP_SAVE_KEY)
  const hostBox = mine
    ? h(
        'div.lb-camp',
        h('div', h('b', 'Your multiplayer camp'), h('small', `Day ${Math.floor(mine.time / 1440) + 1} · ${mine.survivors.length} survivors · ${Object.keys(mine.mp?.players || {}).length} players · code ${mine.mp?.code || '?'}`)),
        h('div.lb-row', h('button.btn.go', { onclick: () => host(false) }, 'Host it'), h('button.btn.ghost', { onclick: () => confirmFresh() }, 'New camp')),
      )
    : h('div.lb-camp', h('div', h('b', 'No multiplayer camp yet'), h('small', 'Start one and invite friends. Your single-player camp is not touched.')), h('div.lb-row', h('button.btn.go', { onclick: () => host(true) }, 'Start a camp')))
  const confirmFresh = () => {
    const row = hostBox.querySelector('.lb-row')
    row.replaceChildren(h('span.lb-warn', 'Start over? The old multiplayer camp is lost.'), h('button.btn.danger', { onclick: () => host(true) }, 'Start over'), h('button.btn.ghost', { onclick: () => (row.replaceChildren(h('button.btn.go', { onclick: () => host(false) }, 'Host it'), h('button.btn.ghost', { onclick: () => confirmFresh() }, 'New camp'))) }, 'Keep it'))
  }

  const open = h('div.lb-open', h('small.dim', 'Looking for camps…'))
  const how = h('p.fine')
  const card = h(
    'div.tcard.lobby',
    h('div.logo.big', 'HOLDOUT'),
    h('h2', 'Multiplayer'),
    h('p.tag', 'One of you hosts the camp; the rest join with its code. Everyone builds and crafts for the same camp, each of you leads the survivors the host gives you, and you can be out on runs at the same time.'),
    h('label.lb-field', h('span', 'Your name'), name),
    err,
    h('section.lb-sec', h('h3', 'Host'), hostBox),
    h('section.lb-sec', h('h3', 'Join a friend'), open, h('div.lb-row', codeIn, h('button.btn.go', { onclick: () => join() }, 'Join'))),
    how,
    h('div.tbtns', h('button.btn.ghost', { onclick: () => (sfx('click'), stop?.(), back()) }, 'Back')),
  )
  let stop = null
  carrierKind().then(async (kind) => {
    if (kind === 'room') {
      how.textContent = 'Inside Claude, everyone with this artifact open can see the camps being hosted here. Invite friends to the artifact from its share menu; they need Contributor access or above for the smoothest connection.'
      const lobby = await roomLobby()
      const draw = () => {
        const camps = lobby.peers().filter((x) => !x.sameTab && x.presence?.[LOBBY_KEY]?.code)
        if (!camps.length) return open.replaceChildren(h('small.dim', 'No camps open here right now. Ask a friend for their code, or host one.'))
        open.replaceChildren(
          ...camps.map((x) => {
            const c = x.presence[LOBBY_KEY]
            const ok = c.v === PROTO
            return h('div.lb-camp.small', h('div', h('b', String(c.name || 'A camp').slice(0, 40)), h('small', `Day ${+c.day || 1} · ${+c.pop || 0} survivors · ${+c.on || 1} playing${ok ? '' : ' · different version'}`)), h('button.btn' + (ok ? '.go' : ''), { disabled: !ok, onclick: () => join(String(c.code)) }, 'Join'))
          }),
        )
      }
      draw()
      stop = lobby.onPeers(draw, () => open.replaceChildren(h('small.dim', 'The camp list is not available here. Join with a code.')))
    } else {
      open.replaceChildren()
      how.textContent =
        kind === 'tabs'
          ? 'Test mode: camps connect between tabs of this browser.'
          : 'Players connect browser to browser (WebRTC, through the free PeerJS service), so you all need an internet connection and this same game file. The host shares the five-letter code.'
    }
  })
  setTimeout(() => (name.value ? codeIn : name).focus(), 50)
  return card
}
