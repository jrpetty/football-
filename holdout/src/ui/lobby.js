// The multiplayer card on the title screen: your name, hosting your
// multiplayer camp (a save of its own, apart from your single-player camp)
// and joining a friend's, by code or from the list of camps open here.
import { hasSave, MP_SAVE_KEY, MODES } from '../game/state.js'
import { me, saveMe, writeIntent, PROTO } from '../net/mp.js'
import { roomLobby, carrierKind, LOBBY_KEY, serverCall } from '../net/transport.js'
import { sfx } from '../core/audio.js'
import { h } from '../core/util.js'

const WHY = {
  'no-camp': 'No camp answered with that code. Check it, and that the host is still in their camp.',
  version: 'That camp runs a different version of Holdout. Both of you need the same build.',
  refused: 'The host turned the connection down.',
  offline: 'Could not reach the connection service. Check your internet connection.',
  timeout: 'The connection timed out. Try again in a moment.',
  self: 'That is your own camp. Open it with Host instead.',
  busy: 'The server is busy right now. Try again in a minute.',
  'slow-down': 'You have started a lot of camps in the last hour. Try again later.',
  full: 'The server holds as many camps as it can. Join one instead.',
  'code-taken': 'That code is already in use.',
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
  // the game mode, for a camp started from here
  const modeSelect = () => h('select.mini-sel.lb-mode', { 'data-tip': Object.values(MODES).map((m) => `<b>${m.name}</b>${m.desc}`).join('<br><br>') }, Object.entries(MODES).map(([k, m]) => h('option', { value: k }, `${m.name}: ${m.short.toLowerCase()}`)))
  const modeSel = modeSelect()
  const alwaysSel = modeSelect()
  const host = (fresh) => {
    const n = keep()
    if (!n) return
    sfx('click')
    writeIntent({ mode: 'host', fresh, name: n, gameMode: fresh ? modeSel.value : undefined })
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
    : h('div.lb-camp', h('div', h('b', 'No multiplayer camp yet'), h('small', 'Start one and invite friends. Your single-player camp is not touched.')), h('div.lb-row', modeSel, h('button.btn.go', { onclick: () => host(true) }, 'Start a camp')))
  const confirmFresh = () => {
    const row = hostBox.querySelector('.lb-row')
    row.replaceChildren(h('span.lb-warn', 'Start over? The old multiplayer camp is lost.'), modeSel, h('button.btn.danger', { onclick: () => host(true) }, 'Start over'), h('button.btn.ghost', { onclick: () => (row.replaceChildren(h('button.btn.go', { onclick: () => host(false) }, 'Host it'), h('button.btn.ghost', { onclick: () => confirmFresh() }, 'New camp'))) }, 'Keep it'))
  }

  const open = h('div.lb-open', h('small.dim', 'Looking for camps…'))
  const campRow = (c) => {
    const ok = c.v === PROTO
    const always = c.kind === 'server'
    return h(
      'div.lb-camp.small' + (always ? '.always' : ''),
      h('div', h('b', String(c.name || 'A camp').slice(0, 40), always ? h('em.lb-tag', 'always on') : null), h('small', `Day ${+c.day || 1} · ${+c.pop || 0} survivors · ${+c.on || 0} playing now${ok ? '' : ' · different version'}`)),
      h('button.btn' + (ok ? '.go' : ''), { disabled: !ok, onclick: () => join(String(c.code)) }, 'Join'),
    )
  }
  const how = h('p.fine')
  const campName = h('input.inp', { maxLength: 40, placeholder: 'Camp name, e.g. The Lumber Yard', spellcheck: false })
  const pub = h('input', { type: 'checkbox', checked: true })
  const makeAlways = async (btn) => {
    const n = keep()
    if (!n) return
    btn.disabled = true
    sfx('click')
    try {
      const r = await serverCall({ op: 'create', name: campName.value.trim() || `${n}'s camp`, pid: p.pid, pname: n, public: pub.checked, mode: modeSel.value })
      if (r.op !== 'created') throw new Error(r.why || 'busy')
      writeIntent({ mode: 'join', code: r.code, name: n })
      location.hash = ''
      location.reload()
    } catch (e) {
      btn.disabled = false
      err.textContent = joinError(e.message)
      err.hidden = false
      sfx('error')
    }
  }
  const always = h(
    'section.lb-sec',
    { hidden: true },
    h('h3', 'Always-on camp'),
    h('p.note', 'A camp that lives on the server: nobody has to host. You are its admin: you hand out survivors and set the pace. While nobody is on, the crew keeps working slowly and the clock waits.'),
    h('div.lb-row', campName, h('label.lb-check', pub, h('span', 'List it for everyone')), alwaysSel, h('button.btn.go', { onclick: (e) => makeAlways(e.currentTarget) }, 'Start it')),
  )
  const card = h(
    'div.tcard.lobby',
    h('div.logo.big', 'HOLDOUT'),
    h('h2', 'Multiplayer'),
    h('p.tag', 'One of you hosts the camp; the rest join with its code. Everyone builds and crafts for the same camp, each of you leads the survivors the host gives you, and you can be out on runs at the same time.'),
    h('label.lb-field', h('span', 'Your name'), name),
    err,
    h('section.lb-sec', h('h3', 'Host in your browser'), hostBox),
    always,
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
            return campRow({ ...c, on: +c.on || 1, v: ok ? PROTO : c.v })
          }),
        )
      }
      draw()
      stop = lobby.onPeers(draw, () => open.replaceChildren(h('small.dim', 'The camp list is not available here. Join with a code.')))
    } else if (kind === 'ws') {
      how.textContent = 'Camps on this server: hosted camps run in a player’s browser while they play; always-on camps live on the server, so friends can come and go whenever they like.'
      const draw = async () => {
        try {
          const r = await (await fetch('/api/camps', { cache: 'no-store' })).json()
          const camps = r.camps || []
          open.replaceChildren(...(camps.length ? camps.map(campRow) : [h('small.dim', 'No camps yet. Start one, or ask a friend for their code.')]))
        } catch {
          open.replaceChildren(h('small.dim', 'Could not reach the server.'))
        }
      }
      draw()
      const iv = setInterval(draw, 4000)
      stop = () => clearInterval(iv)
      always.hidden = false
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
