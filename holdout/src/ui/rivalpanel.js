// Rival camps: the other always-on camps on this server. Trade with them,
// send a gift, make (or break) an alliance, or send a raiding party. The
// rules are in game/rivals.js; this camp's game on the server settles them.
import { S, NET, day, canFight, survivorStats } from '../game/state.js'
import { RES } from '../game/data.js'
import { rivals, raidable, partyPower, RIVAL } from '../game/rivals.js'
import { h, fmt } from '../core/util.js'
import { sfx } from '../core/audio.js'
import { costList } from './common.js'

let camps = null
let loading = false
let fetchedAt = 0
async function loadCamps(ui) {
  if (loading) return
  loading = true
  try {
    const r = await fetch('/api/camps', { cache: 'no-store' })
    const j = r.ok ? await r.json() : null
    camps = (j?.camps || []).filter((c) => c.kind === 'server' && c.code !== S.mp?.code)
  } catch {
    camps = []
  }
  fetchedAt = performance.now()
  loading = false
  ui.refreshPanel?.()
}
const TRADE_RES = ['food', 'water', 'wood', 'scrap', 'metal', 'cloth', 'parts', 'meds', 'fuel', 'pammo', 'rammo', 'shells', 'electronics', 'chemicals', 'cash']
const send = (ui, m) => {
  const why = ui.game.net?.rival(m)
  if (why) ui.toast(why, 'bad')
  else sfx('click')
  setTimeout(() => ui.refreshPanel?.(), 400)
}
function resPicker(label, state) {
  const sel = h('select.mini-sel', { onchange: (e) => (state.k = e.target.value) }, TRADE_RES.filter((k) => RES[k]).map((k) => h('option', { value: k, selected: k === state.k }, RES[k].name)))
  const num = h('input.inp.num', { type: 'number', min: 0, max: 999, value: state.n, oninput: (e) => (state.n = Math.max(0, Math.min(999, +e.target.value || 0))) })
  return h('div.kv', h('span', label), h('span.rv-pick', num, sel))
}
function tradeModal(ui, c, gift) {
  const give = { k: 'food', n: 10 }
  const want = { k: 'scrap', n: 10 }
  let close
  close = ui.modal(
    h(
      'div',
      h('h2', gift ? `A gift for ${c.name}` : `Trade with ${c.name}`),
      h('p.note', gift ? 'It goes straight to them.' : 'What you give is held back until they answer; if they turn it down it comes home.'),
      resPicker('You give', give),
      gift ? null : resPicker('You want', want),
    ),
    {
      actions: [
        h('button.btn.ghost', { onclick: () => close() }, 'Cancel'),
        h(
          'button.btn.go',
          {
            onclick: () => {
              const g = give.n > 0 ? { [give.k]: give.n } : {}
              const w = !gift && want.n > 0 ? { [want.k]: want.n } : {}
              send(ui, { op: gift ? 'gift' : 'offer', to: c.code, toName: c.name, give: g, want: w })
              close()
            },
          },
          gift ? 'Send it' : 'Make the offer',
        ),
      ],
    },
  )
}
function raidModal(ui, c) {
  const pick = new Set()
  const able = S.survivors.filter((s) => s.status === 'ok' && canFight(s) && (!S.mp?.owner?.[s.id] || S.mp.owner[s.id] === NET.pid))
  let close
  const power = h('b')
  const upd = () => (power.textContent = `${Math.round(partyPower([...pick].map((id) => S.survivors.find((s) => s.id === id))))}`)
  close = ui.modal(
    h(
      'div',
      h('h2', `Raid ${c.name}`),
      h('p.note', `Up to ${RIVAL.maxParty} go. If they beat the wall (its level, towers, turrets and whoever is home to fight), they carry off a share of the stores, ${RIVAL.carry} a head at most; if not, most come back hurt. A camp under day ${RIVAL.safeDays} is left alone, and so are your allies.`),
      h(
        'div.picklist',
        able.map((s) => {
          const st = survivorStats(s)
          return h(
            'button.pickrow',
            {
              onclick: (e) => {
                if (pick.has(s.id)) pick.delete(s.id)
                else if (pick.size < RIVAL.maxParty) pick.add(s.id)
                e.currentTarget.classList.toggle('on', pick.has(s.id))
                upd()
              },
            },
            h('div.pr-main', h('b', s.name), h('span', `${st.weapon.name} · ${Math.round(s.hp ?? 100)} hp`)),
          )
        }),
      ),
      h('div.kv', h('span', 'Party strength'), power),
    ),
    {
      actions: [
        h('button.btn.ghost', { onclick: () => close() }, 'Cancel'),
        h('button.btn.danger', { onclick: () => (pick.size ? (send(ui, { op: 'raid', to: c.code, toName: c.name, ids: [...pick] }), close()) : ui.toast('Pick who goes', 'bad')) }, 'Send them'),
      ],
    },
  )
  upd()
}

export function renderRivals(ui) {
  const body = []
  if (!S.mp?.server) {
    body.push(h('section.card', h('h3', 'Other camps'), h('p', 'Rival camps live on the Holdout server: start or join an always-on camp from the multiplayer lobby, and every other always-on camp there is a neighbour to trade with, ally with, or raid.')))
    return ui.frame('Rival camps', 'Only in always-on camps', body, { icon: 'people' })
  }
  if (!camps || performance.now() - fetchedAt > 30000) loadCamps(ui)
  const R = rivals()
  const pend = R.inbox.filter((e) => !e.done)
  if (pend.length)
    body.push(
      h(
        'section.card',
        h('h3', 'Waiting on you'),
        ...pend.map((e) =>
          h(
            'div.rv-row',
            h('div', h('b', e.from.name), e.kind === 'ally' ? h('span', ' asks for an alliance') : h('span', ' offers ', costList(e.give, { have: false, small: true }), ' for ', costList(e.want, { small: true }))),
            h('div.rv-btns', h('button.mini', { onclick: () => send(ui, { op: 'answer', to: e.from.code, id: e.id, yes: false }) }, 'No'), h('button.mini.go', { onclick: () => send(ui, { op: 'answer', to: e.from.code, id: e.id, yes: true }) }, 'Yes')),
          ),
        ),
      ),
    )
  body.push(
    h(
      'section.card',
      h('h3', 'Neighbours', h('button.mini', { onclick: () => loadCamps(ui) }, loading ? '…' : 'Refresh')),
      !camps ? h('p.note', 'Looking for other camps…') : !camps.length ? h('p.note', 'No other always-on camps on this server yet.') : null,
      ...(camps || []).map((c) => {
        const ally = R.allies[c.code]
        const why = raidable(c.code) || (day() < RIVAL.safeDays ? `Your camp is too new (day ${RIVAL.safeDays})` : (c.day || 0) < RIVAL.safeDays ? 'They’re too new to raid' : null)
        return h(
          'div.rv-row',
          h('div', h('b', c.name || c.code), ally ? h('span.trait.good', 'Ally') : null, h('small.dim', ` · day ${c.day || '?'} · ${c.pop || '?'} people${c.on ? ` · ${c.on} playing` : ''}`)),
          h(
            'div.rv-btns',
            h('button.mini', { onclick: () => tradeModal(ui, c, false) }, 'Trade'),
            h('button.mini', { onclick: () => tradeModal(ui, c, true) }, 'Gift'),
            ally ? h('button.mini', { onclick: () => send(ui, { op: 'unally', to: c.code, toName: c.name }) }, 'End alliance') : h('button.mini', { onclick: () => send(ui, { op: 'ally', to: c.code, toName: c.name }) }, 'Ally'),
            h('button.mini.danger', { disabled: !!why, 'data-tip': why || 'Send a raiding party', onclick: () => raidModal(ui, c) }, 'Raid'),
          ),
        )
      }),
    ),
  )
  const out = R.out.filter((o) => o.kind === 'offer')
  if (out.length) body.push(h('section.card', h('h3', 'Offers out'), ...out.map((o) => h('div.kv', h('span', (camps || []).find((c) => c.code === o.to)?.name || o.to), h('small', 'waiting')))))
  if (R.log.length) body.push(h('section.card', h('h3', 'What happened'), ...R.log.slice(0, 12).map((e) => h('div.kv', h('span' + (e.kind === 'bad' ? '.bad' : e.kind === 'good' ? '.good' : ''), e.text), h('small', `day ${e.day}`)))))
  return ui.frame('Rival camps', `${Object.keys(R.allies).length} allies · ${fmt((camps || []).length)} neighbours`, body, { icon: 'people' })
}
