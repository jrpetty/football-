// One resource, start to finish: a flow diagram of where it is made (left),
// the camp's stock (middle) and where it goes (right), every band as wide as
// its rate per day. Click a station to open it.
import { RES, STATIONS, SEC_PER_DAY } from '../game/data.js'
import { S, gameDur } from '../game/state.js'
import { resourceFlow } from '../game/economy.js'
import { flowsNow } from '../game/rates.js'
import { h, fmt } from '../core/util.js'
import { icon } from './icons.js'
import { resIcon, bar } from './common.js'
import { isWatched, toggleWatch } from './watch.js'

const W = 600
const BOX = 150
const MID = 70
const GAP = 8
const MIN_H = 24
const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])
const perDay = (v) => (v < 10 ? v.toFixed(1) : Math.round(v).toString())

// Lay a column of boxes out: heights by value, scaled to fit.
function column(list, scale) {
  let y = 0
  return list.map((x) => {
    const hgt = Math.max(MIN_H, x.v * scale)
    const o = { ...x, y, h: hgt }
    y += hgt + GAP
    return o
  })
}
function band(x0, y0, h0, x1, y1, h1, color, op) {
  const c = (x0 + x1) / 2
  return `<path d="M${x0},${y0} C${c},${y0} ${c},${y1} ${x1},${y1} L${x1},${y1 + h1} C${c},${y1 + h1} ${c},${y0 + h0} ${x0},${y0 + h0} Z" fill="${color}" fill-opacity="${op}"/>`
}
function svgFor(F, color) {
  const big = Math.max(F.made, F.used, 0.1)
  const rows = Math.max(F.sources.length, F.sinks.length, 1)
  const H = Math.max(120, Math.min(360, 40 + rows * 34))
  const avail = H - 20 - GAP * (rows - 1)
  const scale = Math.max(0.5, avail / big)
  const L = column(F.sources, scale)
  const R = column(F.sinks, scale)
  const colH = (c) => (c.length ? c[c.length - 1].y + c[c.length - 1].h : 0)
  const hL = colH(L)
  const hR = colH(R)
  const H2 = Math.max(hL, hR, 60) + 20
  const offL = (H2 - hL) / 2
  const offR = (H2 - hR) / 2
  const midH = Math.max(40, Math.max(F.made, F.used) * scale)
  const midY = (H2 - midH) / 2
  const cx = W / 2 - MID / 2
  const parts = []
  // bands in: each source into the middle, stacked down its left edge
  let yIn = midY + (midH - F.made * scale) / 2
  for (const b of L) {
    const th = b.v * scale
    parts.push(band(BOX, offL + b.y, b.h, cx, yIn, th, color, 0.35))
    yIn += th
  }
  let yOut = midY + (midH - F.used * scale) / 2
  for (const b of R) {
    const th = b.v * scale
    parts.push(band(cx + MID, yOut, th, W - BOX, offR + b.y, b.h, '#d8805a', 0.32))
    yOut += th
  }
  const box = (b, x, off, side) => {
    const kind = b.st ? 'st' : b.kind || 'x'
    const v = perDay(b.v)
    // the name gets what the number leaves (about 7px a letter)
    const room = Math.floor((BOX - 22 - v.length * 8) / 7)
    const name = b.name.length > room ? b.name.slice(0, room - 1).trimEnd() + '…' : b.name
    const ty = off + b.y + Math.min(b.h, 30) / 2 + 4.5
    return `<g class="fbox ${side} ${kind}" ${b.st ? `data-st="${b.st.id}"` : ''}><title>${esc(b.name)}: ${v} a day</title><rect x="${x}" y="${off + b.y}" width="${BOX}" height="${b.h}" rx="5"/><text x="${x + 8}" y="${ty}">${esc(name)}</text><text class="v" x="${x + BOX - 8}" y="${ty}" text-anchor="end">${v}</text></g>`
  }
  for (const b of L) parts.push(box(b, 0, offL, 'in'))
  for (const b of R) parts.push(box(b, W - BOX, offR, 'out'))
  parts.push(`<g class="fmid"><rect x="${cx}" y="${midY}" width="${MID}" height="${midH}" rx="6" fill="${color}"/><text x="${W / 2}" y="${midY + midH / 2 - 4}" text-anchor="middle">${esc(fmt(F.stock))}</text><text class="s" x="${W / 2}" y="${midY + midH / 2 + 12}" text-anchor="middle">of ${esc(fmt(F.cap))}</text></g>`)
  if (!L.length) parts.push(`<text class="none" x="${BOX / 2}" y="${H2 / 2}" text-anchor="middle">Nothing makes it</text>`)
  if (!R.length) parts.push(`<text class="none" x="${W - BOX / 2}" y="${H2 / 2}" text-anchor="middle">Nothing uses it</text>`)
  return `<svg class="flowsvg" viewBox="0 0 ${W} ${H2}" preserveAspectRatio="xMidYMid meet">${parts.join('')}</svg>`
}
// A little line of the last few days.
function spark(hist, cap, color) {
  if (!hist?.length || hist.length < 2) return null
  const w = 220
  const hh = 40
  const max = Math.max(cap, ...hist, 1)
  const pts = hist.map((v, i) => `${((i / (hist.length - 1)) * w).toFixed(1)},${(hh - (v / max) * hh).toFixed(1)}`).join(' ')
  return h('div.fspark', { html: `<svg viewBox="0 0 ${w} ${hh}" preserveAspectRatio="none"><polyline points="${pts}" fill="none" stroke="${color}" stroke-width="2"/></svg>` }, h('small', 'last 4 days'))
}

export function renderFlow(ui, k) {
  const R = RES[k]
  if (!R) return null
  const F = resourceFlow(k)
  const color = R.color
  const net = F.net
  const days = net < -0.05 ? F.stock / -net : net > 0.05 ? (F.cap - F.stock) / net : null
  const head = h(
    'section.card.fhead',
    h('div.fh-top', h('span.fh-ic', { style: { color }, html: resIcon(k) }), h('div', h('b', `${fmt(F.stock)} / ${fmt(F.cap)}`), bar(F.stock / F.cap, '')), h('div.fh-net' + (net > 0.05 ? '.up' : net < -0.05 ? '.down' : ''), h('b', `${net > 0 ? '+' : ''}${perDay(net)}`), h('small', 'a day'))),
    h('p.note', net < -0.05 ? `Runs out in about ${days < 1 ? gameDur(days * 1440) : `${days.toFixed(1)} days`} at this rate.` : net > 0.05 ? (F.stock >= F.cap - 0.5 ? 'Storage is full: more is being made than can be kept.' : `Full in about ${days < 1 ? gameDur(days * 1440) : `${days.toFixed(1)} days`}.`) : 'Holding steady.'),
    h('div.fh-acts', h('button.btn.small.ghost' + (isWatched('res', k) ? '.on' : ''), { onclick: () => (toggleWatch('res', k), ui.refreshPanel()) }, h('i', { html: icon('pin') }), isWatched('res', k) ? ' Pinned' : ' Pin to watch list')),
    spark(S.hist?.res?.[k], F.cap, color),
  )
  // meals come from eggs and milk first: say so on the food chart
  let fresh = null
  if (k === 'food') {
    const ate = ['eggs', 'milk'].reduce((a, f) => a + (resourceFlow(f).sinks.find((x) => x.key === 'eat')?.v || 0), 0)
    if (ate > 0.05) fresh = h('p.note', `The camp eats eggs and milk before the stores: ${perDay(ate)} a day of its meals come from them right now${F.sinks.some((x) => x.key === 'eat') ? '' : ', so no tinned food is eaten'}.`)
  }
  const diagram = h('section.card.fdiag', h('h3', 'Each day', h('small', `${perDay(F.made)} made · ${perDay(F.used)} used`)), h('div.fwrap', { html: svgFor(F, color) }), fresh)
  diagram.addEventListener('click', (e) => {
    const g = e.target.closest?.('[data-st]')
    if (g) ui.openStation(g.dataset.st)
  })
  const row = (b, sign) => h('div.frow', b.st ? h('button.plink', { onclick: () => ui.openStation(b.st.id) }, b.name) : h('span', b.name), h('b.' + (sign > 0 ? 'good' : 'bad'), `${sign > 0 ? '+' : '−'}${perDay(b.v)}`))
  const lists = h('div.cols2', h('section.card', h('h3', 'Made by'), F.sources.length ? F.sources.map((b) => row(b, 1)) : h('p.note', 'Nothing in camp makes it. Runs, trade and outposts bring it in.')), h('section.card', h('h3', 'Used by'), F.sinks.length ? F.sinks.map((b) => row(b, -1)) : h('p.note', 'Nothing uses it up.')))
  // the belts carrying it
  const fl = flowsNow().links
  const belts = (S.links || []).filter((l) => l.res === k)
  const beltCard = belts.length
    ? h(
        'section.card',
        h('h3', 'On belts', h('small', `${belts.length}`)),
        belts.map((l) => {
          const a = S.stations.find((x) => x.id === l.from)
          const b = S.stations.find((x) => x.id === l.to)
          const r = fl.get(l.id)
          return h('div.frow', h('button.plink', { onclick: () => ui.openBelt(l) }, `${a ? STATIONS[a.type].name : '?'} → ${b ? STATIONS[b.type].name : '?'}`), h('b', `${perDay(r?.flow ?? (l.flow || 0) * SEC_PER_DAY)}/day`))
        }),
      )
    : null
  return ui.frame(R.name, h('span', R.desc), [head, diagram, lists, beltCard], { icon: 'flow' })
}
