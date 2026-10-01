// The multiplayer interface: who leads which survivor, the Players panel
// (code, who is here, what they are doing, handing survivors out), chat in
// the feed, the connection chip and rings where friends are looking.
import * as THREE from 'three'
import { S, NET, playerOf, canControl, survivorStats } from '../game/state.js'
import { OCCUPATIONS, STATIONS } from '../game/data.js'
import { view } from '../render/view.js'
import { sfx } from '../core/audio.js'
import { h } from '../core/util.js'
import { icon } from './icons.js'
import { plural } from './common.js'

const esc = (t) => String(t).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const nameOf = (pid) => playerOf(pid)?.name || 'Someone'
const colorOf = (pid) => playerOf(pid)?.color || '#aaa894'

// ---------------------------------------------------------------- leaders
export function leaderChip(s, dot = false) {
  if (NET.role === 'solo' || !s) return null
  const pid = S.mp?.owner?.[s.id]
  const P = pid && playerOf(pid)
  if (!P) return dot ? h('i.lead-dot.free', { 'data-tip': 'Takes orders from anyone' }) : h('span.lead-chip.free', { 'data-tip': 'Nobody leads them: anyone can give them orders.' }, 'Anyone')
  const you = pid === NET.pid
  if (dot) return h('i.lead-dot', { style: { background: P.color }, 'data-tip': you ? 'Yours' : `Led by ${esc(P.name)}` })
  return h('span.lead-chip', { style: { '--c': P.color } }, you ? 'Yours' : P.name)
}
export function leaderBanner(s) {
  if (canControl(s)) return null
  const pid = S.mp.owner[s.id]
  return h('div.lead-banner', { style: { '--c': colorOf(pid) } }, h('i', { html: icon('lock') }), h('span', h('b', nameOf(pid)), ` leads ${s.first}. Only they can change ${s.female ? 'her' : 'his'} job and gear or take ${s.female ? 'her' : 'him'} on runs.${NET.role === 'host' ? ' You can hand survivors around in the Players panel.' : ''}`))
}

// ---------------------------------------------------------------- players panel
export function renderPlayers(ui) {
  const net = ui.game.net
  const mp = S.mp
  if (!mp || !net) return ui.frame('Players', '', [h('p.note', 'Not in a multiplayer camp.')], { icon: 'people' })
  const host = net.isAdmin()
  const online = net.online || new Set()
  const cams = net.cams || {}
  const players = Object.entries(mp.players).sort((a, b) => (b[0] === mp.host) - (a[0] === mp.host) || online.has(b[0]) - online.has(a[0]))
  const out = []
  // the code
  const copy = () => {
    const txt = mp.code
    const ok = () => (sfx('click'), ui.toast('Code copied', 'good'))
    navigator.clipboard?.writeText(txt).then(ok, () => ui.toast(`The code is ${txt}`))
  }
  const st = { live: ['Connected', 'good'], connecting: ['Connecting…', ''], reconnecting: ['Reconnecting…', 'bad'], lost: ['Lost the host… retrying', 'bad'], offline: ['Not reachable', 'bad'], gone: ['Closed', 'bad'] }[net.status] || [net.status, '']
  out.push(
    h(
      'section.card.mp-code',
      h('div.mpc-main', h('small', 'Camp code'), h('b.code', mp.code), h('button.btn.small', { onclick: copy }, h('i', { html: icon('log') }), 'Copy')),
      h('div.mpc-side', h('span.netdot.' + st[1]), h('span', st[0]), h('small.dim', net.c?.kind === 'room' ? 'via Claude' : net.c?.kind === 'peer' ? 'browser to browser' : net.c?.kind === 'tabs' ? 'between tabs' : '')),
      mp.server
        ? h('p.note', 'An always-on camp: it lives on the server, so anyone with the code can come and go. While nobody is here the crew keeps working at a slow pace and the clock waits, as in single player. When a horde comes, a player in camp fights it live for everyone.')
        : host
          ? h('p.note', net.c?.kind === 'room' ? 'Friends open this same artifact (invite them from the share menu), press Multiplayer on the title screen and pick your camp, or type the code.' : net.c?.kind === 'ws' ? 'Friends open this website, press Multiplayer and pick your camp from the list, or type the code.' : 'Friends open Holdout, press Multiplayer on the title screen and type this code. Everyone needs the same game file and an internet connection.')
          : null,
    ),
  )
  // who is here
  out.push(
    h(
      'section.card',
      h('h3', 'Players', h('small', `${online.size} here · ${players.length} in this camp`)),
      players.map(([pid, P]) => {
        const here = online.has(pid)
        const mine = S.survivors.filter((s) => mp.owner[s.id] === pid)
        const doing = here ? cams[pid]?.w || (pid === NET.pid ? 'In camp' : 'Here') : `Away · joined day ${P.since || 1}`
        return h(
          'div.mp-player' + (here ? '' : '.away'),
          h('i.swatch', { style: { background: P.color } }),
          h('div.mpp-main', h('b', P.name, pid === mp.host ? h('em.tag', 'host') : pid === mp.admin ? h('em.tag', 'admin') : null, pid === NET.pid ? h('em.tag.you', 'you') : null), h('small', doing)),
          h('span.mpp-crew', plural(mine.length, 'survivor')),
          host && pid !== NET.pid
            ? here
              ? h('button.mini', { onclick: () => ui.confirm(`Send ${P.name} away?`, 'They are disconnected. Their survivors stay in camp and they can join again.', 'Disconnect', () => (net.kick(pid), ui.refreshPanel())) }, 'Kick')
              : h('button.mini', { onclick: () => ui.confirm(`Forget ${P.name}?`, 'Their survivors take orders from anyone again.', 'Forget', () => (net.forget(pid), ui.refreshPanel())) }, 'Forget')
            : null,
        )
      }),
    ),
  )
  // who leads whom
  const rows = S.survivors.map((s) => {
    const pid = mp.owner[s.id] || ''
    const where = s.status === 'mission' ? 'On a run' : s.status === 'outpost' ? 'Outpost' : s.status === 'injured' ? 'Injured' : s.job ? STATIONS[S.stations.find((x) => x.id === s.job)?.type]?.name || 'Job' : 'No job'
    const pick = host
      ? h(
          'select.mp-sel',
          { style: { '--c': pid ? colorOf(pid) : 'transparent' }, onchange: (e) => (net.assign(s.id, e.target.value || null), sfx('click'), ui.refreshPanel()) },
          h('option', { value: '', selected: !pid }, 'Anyone'),
          players.map(([p, P]) => h('option', { value: p, selected: p === pid }, P.name)),
        )
      : leaderChip(s)
    return h('div.mp-surv', h('img.por.sm', { src: ui.game.portrait(s), onclick: () => ui.openSurvivor(s.id) }), h('div.mps-main', h('b', s.name), h('small', `${OCCUPATIONS[s.occ].name} · ${where} · ${Math.round(survivorStats(s).maxHp)} hp`)), pick)
  })
  const free = S.survivors.filter((s) => !mp.owner[s.id])
  const share = () => {
    const ps = players.filter(([p]) => online.has(p)).map(([p]) => p)
    if (!ps.length) return
    // hand out the unled ones, evening out the counts
    const count = (p) => S.survivors.filter((s) => mp.owner[s.id] === p).length
    for (const s of free) {
      const p = ps.slice().sort((a, b) => count(a) - count(b))[0]
      net.assign(s.id, p)
    }
    sfx('click')
    ui.refreshPanel()
  }
  out.push(
    h(
      'section.card',
      h('h3', 'Who leads whom', h('small', `${free.length} take orders from anyone`)),
      h('p.note', host ? 'Each player gives orders to the survivors they lead: jobs, gear and runs. Survivors set to Anyone take orders from everyone. Whoever rescues or recruits someone leads them.' : 'The host hands survivors out. You lead the ones marked Yours, and anyone can direct the ones marked Anyone.'),
      host && free.length ? h('div.kv', h('span', 'Hand out the unled survivors evenly'), h('button.btn.small', { onclick: share }, 'Share out')) : null,
      h('div.mp-list', rows),
    ),
  )
  // chat history
  const input = h('input.inp.mp-chatin', { placeholder: 'Say something to the camp…', maxLength: 240 })
  const send = () => {
    const t = input.value.trim()
    if (!t) return
    net.say(NET.pid, t)
    input.value = ''
    ui.refreshPanel(true)
  }
  input.addEventListener('keydown', (e) => {
    e.stopPropagation()
    if (e.key === 'Enter') send()
  })
  out.push(h('section.card', h('h3', 'Chat', h('small', 'Enter to talk from anywhere in camp')), h('div.mp-chat', net.chat.slice(-30).map(chatLine)), h('div.lb-row', input, h('button.btn.small', { onclick: send }, 'Send'))))
  const closes = NET.role === 'host'
  out.push(h('div.pactions', h('button.btn.small.ghost.danger', { onclick: () => ui.confirm(closes ? 'Close the camp?' : 'Leave the camp?', closes ? 'Everyone is disconnected. The camp is saved and you can host it again from the title screen.' : mp.server ? 'You go back to the title screen. The camp carries on; come back any time with the code.' : 'You go back to the title screen. You can join again with the code.', closes ? 'Close camp' : 'Leave', () => ui.game.leaveNet(), { danger: true }) }, closes ? 'Close the camp' : 'Leave the camp')))
  return ui.frame('Players', `${mp.code} · ${online.size} here`, out, { icon: 'people' })
}
export function chatLine(m) {
  if (!m.pid) return h('div.chl.sys', m.text)
  return h('div.chl', h('b', { style: { color: colorOf(m.pid) } }, nameOf(m.pid)), h('span', m.text))
}
// A chat line in the feed.
export function feedChat(feed, m) {
  const el = h('div.fe.chat' + (m.pid ? '' : '.sys'), { style: { '--c': m.pid ? colorOf(m.pid) : 'var(--info)' } }, m.pid ? h('b.who', nameOf(m.pid)) : h('i.sysi', { html: icon('people') }), h('span', m.text))
  feed.prepend(el)
  while (feed.children.length > 7) feed.lastChild.remove()
  setTimeout(() => el.classList.add('old'), 14000)
  setTimeout(() => el.remove(), 30000)
}
// The chip in the top bar: who is here and how the line is.
export function netChip(ui) {
  const el = h('button.rchip.netchip', { onclick: () => ui.openPlayers() }, h('span.netdot'), h('b'), h('small'))
  return el
}
export function updateNetChip(el, net) {
  if (!el || !net) return
  const ok = net.status === 'live'
  el.querySelector('.netdot').className = 'netdot ' + (ok ? 'good' : net.status === 'connecting' ? '' : 'bad')
  el.querySelector('b').textContent = `${net.online.size}`
  el.querySelector('small').textContent = S.mp?.code || ''
  const who = [...net.online].map((p) => `<span style="color:${colorOf(p)}">●</span> ${esc(nameOf(p))}${p === NET.pid ? ' (you)' : ''}: ${esc(net.cams[p]?.w || 'In camp')}`).join('<br>')
  el.setAttribute('data-tip', `<b>${NET.role === 'host' ? 'Hosting' : 'Playing in'} camp ${esc(S.mp?.code || '')}</b>${who}<br><em>Players panel <kbd>O</kbd> · chat <kbd>Enter</kbd></em>`)
}

// ---------------------------------------------------------------- markers
// A soft ring with a name where each friend's camera points.
const ringGeo = new THREE.RingGeometry(0.85, 1.1, 40).rotateX(-Math.PI / 2)
export function netMarkers(base, dt) {
  const net = base.game.net
  const M = (base.netMarks ??= new Map())
  const want = new Set()
  if (net && base.active) {
    for (const [pid, c] of Object.entries(net.cams || {})) {
      if (pid === NET.pid || !net.online.has(pid) || !c || !/^In camp/.test(c.w || 'In camp')) continue
      want.add(pid)
      let m = M.get(pid)
      if (!m) {
        const color = colorOf(pid)
        const mesh = new THREE.Mesh(ringGeo, new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.75, depthWrite: false }))
        mesh.renderOrder = 3
        mesh.position.set(c.x, 0.06, c.z)
        base.scene.add(mesh)
        const pos = new THREE.Vector3(c.x, 0, c.z)
        const label = view.labels.add(h('div.netmark', { style: { '--c': color } }, nameOf(pid)), () => pos, { offsetY: 0.5, scene: base.scene })
        m = { mesh, label, pos, t: Math.random() * 6 }
        M.set(pid, m)
      }
      const k = 1 - Math.exp(-dt * 4)
      m.pos.x += (c.x - m.pos.x) * k
      m.pos.z += (c.z - m.pos.z) * k
      m.t += dt
      m.mesh.position.set(m.pos.x, 0.06, m.pos.z)
      m.mesh.scale.setScalar(1 + Math.sin(m.t * 2.4) * 0.06)
    }
  }
  for (const [pid, m] of M) {
    if (want.has(pid)) continue
    base.scene.remove(m.mesh)
    m.mesh.material.dispose()
    m.label.remove()
    M.delete(pid)
  }
}
