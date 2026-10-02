// The locker in the Items panel, trade offers and the trade composer.
import { S, NET, itemName, ownerOf } from '../game/state.js'
import { RES } from '../game/data.js'
import { lockerRes, lockerItems, tradesFor, describe, MAX_OFFERS } from '../net/lockers.js'
import { sfx } from '../core/audio.js'
import { h, fmt } from '../core/util.js'
import { itemCard, resChip, resIcon } from './common.js'

const nameOf = (pid) => (pid === NET.pid ? 'You' : S.mp?.players?.[pid]?.name || 'Someone')
const colorOf = (pid) => S.mp?.players?.[pid]?.color || '#aaa894'
const others = () => Object.keys(S.mp?.players || {}).filter((p) => p !== NET.pid)
// send an op; the change arrives with the host's next update
export function lockerOp(ui, m, done = null) {
  return op(ui, m, done)
}
function op(ui, m, done = null) {
  const why = ui.game.net?.locker(m)
  if (why) {
    sfx('error')
    return ui.toast(`Locker: ${why}`, 'bad')
  }
  sfx('click')
  if (done) ui.toast(done, 'good')
}

// ---------------------------------------------------------------- the locker
const put = { res: null, n: 10 }
export function lockerView(ui, equipFn) {
  const items = lockerItems()
  const res = Object.entries(lockerRes()).filter(([, n]) => n > 0)
  const out = []
  // offers first: they want an answer
  const offers = tradesFor()
  if (offers.length) out.push(h('section.card.trades', h('h3', 'Trade offers', h('small', `${offers.filter((t) => t.to === NET.pid).length} for you`)), offers.map((t) => offerRow(ui, t))))
  // things
  const giveSel = (it) => {
    const sel = h('select.mini-sel', { onchange: (e) => e.target.value && op(ui, { op: 'give', to: e.target.value, items: [it.uid] }, `${itemName(it)} given to ${nameOf(e.target.value)}.`) }, h('option', { value: '' }, 'Give to…'), others().map((p) => h('option', { value: p }, nameOf(p))))
    return sel
  }
  out.push(
    h(
      'section.card',
      h('h3', 'Your things', h('small', `${items.length}`)),
      items.length
        ? h(
            'div.igrid',
            items.map((it) =>
              itemCard(it, {
                actions: h(
                  'span.ic-acts',
                  h('button.mini', { onclick: () => equipFn(ui, it) }, ownerOf(it.uid) ? 'Swap' : 'Equip'),
                  h('button.mini', { 'data-tip': 'Back to camp storage, for everyone', onclick: () => op(ui, { op: 'shareItem', item: it.uid }) }, 'To camp'),
                  others().length ? giveSel(it) : null,
                ),
              }),
            ),
          )
        : h('p.note', 'Nothing yet. Keep things from camp storage with the Keep button on the Camp storage tab. Only your survivors can wear them, and they stay yours.'),
    ),
  )
  // resources
  const pick = h('select.mini-sel', { onchange: (e) => (put.res = e.target.value) }, Object.keys(RES).filter((k) => (S.res[k] || 0) >= 1).map((k) => h('option', { value: k, selected: k === put.res }, `${RES[k].name} (${fmt(S.res[k])})`)))
  const amt = h('input.inp.num', { type: 'number', min: 1, value: put.n, step: 1, oninput: (e) => (put.n = Math.max(1, Math.floor(+e.target.value || 1))) })
  amt.addEventListener('keydown', (e) => e.stopPropagation())
  out.push(
    h(
      'section.card',
      h('h3', 'Your stores'),
      res.length
        ? h(
            'div.lk-res',
            res.map(([k, n]) =>
              h(
                'div.kv',
                h('span', resChip(k, n), ' ', RES[k].name),
                h('span.ic-acts', h('button.mini', { onclick: () => op(ui, { op: 'shareRes', res: k, n: Math.min(n, 10) }) }, `${Math.min(n, 10)} to camp`), h('button.mini', { onclick: () => op(ui, { op: 'shareRes', res: k, n }) }, 'All to camp')),
              ),
            ),
          )
        : h('p.note', 'Nothing put away yet.'),
      h('div.lb-row.lk-put', h('span.dim', 'Put away from camp:'), pick, amt, h('button.btn.small', { onclick: () => pick.value && op(ui, { op: 'keepRes', res: pick.value, n: +amt.value }) }, 'Keep')),
    ),
  )
  // trading
  if (others().length) {
    const who = h('select.mini-sel', others().map((p) => h('option', { value: p }, nameOf(p))))
    out.push(h('section.card', h('h3', 'Trade with a friend'), h('p.note', 'Offer things from your locker for things in theirs. They accept or turn it down; nothing moves until they accept.'), h('div.lb-row', who, h('button.btn.small', { onclick: () => tradeModal(ui, who.value) }, 'Make an offer'))))
  }
  return out
}

function offerRow(ui, t) {
  const mineOut = t.from === NET.pid
  const other = mineOut ? t.to : t.from
  return h(
    'div.offer' + (mineOut ? '.out' : '.in'),
    { style: { '--c': colorOf(other) } },
    h('div.of-main', h('b', mineOut ? `To ${nameOf(other)}` : `From ${nameOf(other)}`), h('small', mineOut ? `You give ${describe(t.give)} for ${describe(t.want)}` : `They give ${describe(t.give)} for ${describe(t.want)}`), itemsLine(t)),
    mineOut
      ? h('button.btn.small.ghost', { onclick: () => op(ui, { op: 'cancel', id: t.id }) }, 'Withdraw')
      : h('span.ic-acts', h('button.btn.small.go', { onclick: () => op(ui, { op: 'accept', id: t.id }, 'Trade done.') }, 'Accept'), h('button.btn.small.ghost', { onclick: () => op(ui, { op: 'decline', id: t.id }) }, 'No thanks')),
  )
}
function itemsLine(t) {
  const names = (list) => (list || []).map((u) => S.items.find((i) => i.uid === u)).filter(Boolean).map(itemName)
  const a = names(t.give.items)
  const b = names(t.want.items)
  if (!a.length && !b.length) return null
  return h('small.of-items', [a.length ? `Gives: ${a.join(', ')}` : null, b.length ? `Wants: ${b.join(', ')}` : null].filter(Boolean).join(' · '))
}

// ---------------------------------------------------------------- composer
export function tradeModal(ui, to) {
  if (!to) return
  const sel = { give: new Set(), want: new Set() }
  const amounts = { give: {}, want: {} }
  const side = (key, pid) => {
    const items = lockerItems(pid)
    const res = Object.entries(lockerRes(pid)).filter(([, n]) => n > 0)
    return h(
      'div.tr-side',
      h('h3', key === 'give' ? 'You give' : `You ask ${nameOf(pid)} for`),
      items.length
        ? h(
            'div.igrid.small',
            items.map((it) => {
              const card = itemCard(it, { owner: false, onclick: () => (sel[key].has(it.uid) ? sel[key].delete(it.uid) : sel[key].add(it.uid), card.classList.toggle('sel'), sfx('select')) })
              return card
            }),
          )
        : h('p.note', key === 'give' ? 'No items in your locker.' : 'No items in their locker.'),
      res.length
        ? h(
            'div.tr-res',
            res.map(([k, n]) => {
              const inp = h('input.inp.num', { type: 'number', min: 0, max: n, value: 0, step: 1, oninput: (e) => (amounts[key][k] = Math.max(0, Math.min(n, Math.floor(+e.target.value || 0)))) })
              inp.addEventListener('keydown', (e) => e.stopPropagation())
              return h('label.tr-row', h('i.ic', { html: resIcon(k), style: { color: RES[k].color } }), h('span', `${RES[k].name}`, h('small', ` of ${fmt(n)}`)), inp)
            }),
          )
        : null,
    )
  }
  const pending = tradesFor().filter((t) => t.from === NET.pid).length
  let close
  close = ui.modal(
    h('div.trade', h('h2', `Trade with ${nameOf(to)}`), h('p.note', 'Pick from both lockers. They see your offer and accept or turn it down.'), h('div.tr-cols', side('give', NET.pid), side('want', to)), pending >= MAX_OFFERS ? h('p.lb-err', `You have ${MAX_OFFERS} offers waiting already.`) : null),
    {
      actions: [
        h('button.btn.ghost', { onclick: () => close() }, 'Cancel'),
        h(
          'button.btn.go',
          {
            disabled: pending >= MAX_OFFERS,
            onclick: () => {
              const pack = (k) => ({ items: [...sel[k]], res: amounts[k] })
              const give = pack('give')
              const want = pack('want')
              if (!give.items.length && !want.items.length && !Object.values(give.res).some(Boolean) && !Object.values(want.res).some(Boolean)) return sfx('error')
              close()
              op(ui, { op: 'offer', to, give, want }, `Offer sent to ${nameOf(to)}.`)
            },
          },
          'Send offer',
        ),
      ],
    },
  )
}

// ---------------------------------------------------------------- news
// a new offer for this player: say so once
const seen = new Set()
export function watchTrades(ui) {
  if (NET.role === 'solo' || !S.mp) return
  const mine = (S.mp.trades || []).filter((t) => t.to === NET.pid)
  for (const t of mine) {
    if (seen.has(t.id)) continue
    seen.add(t.id)
    ui.toast(`${nameOf(t.from)} offers you ${describe(t.give)} for ${describe(t.want)}. See Items → My locker.`, 'good')
    sfx('radio')
  }
  ui.nav?.querySelector('[data-nav=items]')?.classList.toggle('badge', mine.length > 0)
  // the Items panel follows the lockers as the host's updates come in
  if (ui.panelKey === 'items') {
    const sig = JSON.stringify([S.mp.lockers || null, (S.mp.trades || []).map((t) => t.id), S.items.length, S.items.filter((i) => i.locker).map((i) => i.uid + i.locker)])
    if (sig !== lastSig && lastSig !== null) ui.refreshPanel()
    lastSig = sig
  } else lastSig = null
}
let lastSig = null
