// The city map's interface: a top bar (back to camp, the danger legend, the
// clock and horde timer) and the planner: what a location holds and how
// dangerous it is, the drive there, who goes and what they carry.
import { LOCATIONS, ROOMS, CONTAINERS, RES, ITEMS, ZOMBIES, zombieMix, LEVEL_COLORS, OCCUPATIONS, STATIONS, GAME_MIN_PER_SEC, INFECTION, OUTPOST } from '../game/data.js'
import { S, day, clockStr, gameDur, survivorStats, getS, hasFlag, outpostAt, outpostProblem, claimOutpost, outpostYield, outpostUpgradeCost, upgradeOutpost, abandonOutpost, canAfford } from '../game/state.js'
import { raidIntel } from '../game/economy.js'
import { sfx } from '../core/audio.js'
import { h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { resIcon, hpBar, plural, costList, bar } from './common.js'

const MAX_SQUAD = 4
const DISTRICT = { residential: 'Residential streets', commercial: 'Shopping district', downtown: 'Downtown', industrial: 'Industrial zone', park: 'Parkland', military: 'Highway checkpoint' }

// What a location tends to hold, weighted by its rooms and their containers.
export function lootHints(type) {
  const L = LOCATIONS[type]
  const resW = {}
  const itemW = {}
  for (const room of L.rooms || []) {
    const RM = ROOMS[room]
    if (!RM) continue
    const conts = { ...RM.containers }
    if (RM.aisles) conts[RM.aisles] = (conts[RM.aisles] || 0) + 3
    for (const [k, w] of Object.entries(conts)) {
      const C = CONTAINERS[k]
      if (!C) continue
      const tot = C.pool.reduce((a, e) => a + e.w, 0) || 1
      for (const e of C.pool) {
        if (e.r) resW[e.r] = (resW[e.r] || 0) + (w * e.w) / tot
        else if (e.i) itemW[e.i] = (itemW[e.i] || 0) + (w * e.w) / tot
      }
    }
  }
  const res = Object.entries(resW).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([k]) => k)
  const items = Object.entries(itemW).sort((a, b) => b[1] - a[1]).filter(([, w]) => w > 0.05).slice(0, 6).map(([k]) => k)
  return { res, items }
}
export function infectedRange(loc) {
  const L = LOCATIONS[loc.type]
  const area = (L.size[0] * L.size[1]) / 300
  const base = Math.round(4 + loc.level * 3 + area * 0.6)
  return [base, base + 2 + loc.level]
}
// Drive time (game minutes) and fuel for a round trip of `dist` metres.
export function travelInfo(dist) {
  const km = dist / 1000
  const fuel = Math.max(1, Math.round(km * 1.6))
  const drive = Math.round(8 + dist / 55)
  const van = (S.res.fuel || 0) >= fuel
  return { km, fuel, drive, walk: drive * 3, van, min: van ? drive : drive * 3 }
}
// Rough fighting strength for the odds estimate.
export function squadPower(s) {
  const st = survivorStats(s)
  const dps = st.dmg / st.rate
  const ammoOk = !st.gun || (S.res[st.ammoType] || 0) > 12
  return (dps * (st.gun ? (ammoOk ? 1.4 : 0.35) : 1) + st.maxHp * 0.15) * (s.hp / st.maxHp) * (s.status === 'ok' ? 1 : 0)
}

export class MapPanel {
  constructor(game, map) {
    this.game = game
    this.map = map
    this.squad = map.squad || (map.squad = new Set())
    this.t = 0
    this.root = document.getElementById('hud')
    this.top = h('div.maptop')
    this.side = h('aside.panel.mplan')
    this.root.append(this.top, this.side)
    this.renderTop()
    this.render()
  }
  destroy() {
    this.top.remove()
    this.side.remove()
  }
  update(dt) {
    this.t += dt
    if (this.t > 1) {
      this.t = 0
      this.renderTop()
    }
  }
  renderTop() {
    const intel = raidIntel()
    this.top.innerHTML = ''
    this.top.append(
      h('button.btn.ghost', { onclick: () => this.game.closeMap(), 'data-tip': 'Back to camp <kbd>Esc</kbd>' }, h('span', { html: icon('gate') }), 'Camp'),
      h('div.mt-title', h('b', 'Ashford'), h('small', 'Danger rises the further you go from camp')),
      h('div.mt-legend', LEVEL_COLORS.map((c, i) => h('span', { style: { '--c': c }, 'data-tip': ['Level 1: houses, flats and corner stores', 'Level 2: offices, diners, gas stations, hardware', 'Level 3: pharmacies, supermarkets, garages, the school', 'Level 4: hospital, fire station, gun stores, warehouses', 'Level 5: police station and the military checkpoint'][i] }, `L${i + 1}`))),
      h('div.mt-clock', h('span', { html: icon(clockStr().slice(0, 2) >= 20 || clockStr().slice(0, 2) < 6 ? 'moon' : 'sun') }), `Day ${day()}`, h('b', clockStr())),
      h('div.mt-res', { 'data-tip': 'Fuel for the van. A round trip costs fuel by distance; without enough the squad walks.' }, h('i', { html: resIcon('fuel') }), h('b', fmt(S.res.fuel || 0)), h('small', 'fuel')),
      intel ? h('div.mt-horde' + (intel.in < 120 ? '.soon' : ''), h('span', { html: icon('horde') }), h('small', intel.known ? intel.name : 'Horde'), h('b', gameDur(intel.in))) : null,
    )
  }
  render() {
    const P = this.side
    P.innerHTML = ''
    const loc = this.map.sel
    if (!loc) return this.renderIndex(P)
    this.renderLoc(P, loc)
  }
  // ---------------------------------------------------------------- index
  renderIndex(P) {
    const evs = (S.events || []).filter((e) => e.expires > S.time)
    const locs = [...this.map.locs.values()].map((L) => L.loc)
    const byLevel = [1, 2, 3, 4, 5].map((lv) => locs.filter((l) => l.level === lv))
    P.append(
      h('div.phead', h('div.pic', { html: icon('map') }), h('div.ptitle', h('h2', 'Supply runs'), h('div.psub', 'Pick a location on the map or from the list'))),
      h(
        'div.pbody',
        h('p.note', 'Level 1 places are near camp and hold food, water and cloth. Level 5 holds guns, armor and radios, and far more of the dead. Every run takes survivors away from their jobs and the wall.'),
        evs.length
          ? h(
              'div.card.evcard',
              h('h3', 'Radio traffic'),
              evs.map((e) => {
                const l = locs.find((x) => x.id === e.locId)
                if (!l) return null
                return h('button.evrow', { onclick: () => this.map.select(l) }, h('span.evk.' + e.kind, e.kind === 'distress' ? 'SOS' : 'DROP'), h('div', h('b', l.name), h('small', e.kind === 'distress' ? `${e.npc?.name || 'Someone'} is trapped inside` : 'Supply crate in the yard')), h('span.evt', gameDur(e.expires - S.time)))
              }),
            )
          : null,
        byLevel.map((list, i) =>
          list.length
            ? h(
                'div.lvgroup',
                h('h3', h('span.lvlbadge', { style: { '--c': LEVEL_COLORS[i] } }, `Level ${i + 1}`), h('small', plural(list.length, 'place'))),
                list
                  .sort((a, b) => a.dCamp - b.dCamp)
                  .map((l) =>
                    h(
                      'button.locrow' + (S.looted[l.id] ? '.looted' : ''),
                      { onclick: () => this.map.select(l), onmouseenter: () => ((this.map.hover = l), this.map.refreshMarkers()), onmouseleave: () => ((this.map.hover = null), this.map.refreshMarkers()) },
                      h('b', l.name),
                      h('small', LOCATIONS[l.type].name),
                      S.looted[l.id] ? h('span.tag', 'looted') : (S.events || []).some((e) => e.locId === l.id) ? h('span.tag.ev', 'signal') : null,
                    ),
                  ),
              )
            : null,
        ),
      ),
    )
  }
  // ---------------------------------------------------------------- one location
  renderLoc(P, loc) {
    const L = LOCATIONS[loc.type]
    const col = LEVEL_COLORS[loc.level - 1]
    const looted = S.looted[loc.id]
    const ev = (S.events || []).find((e) => e.locId === loc.id && e.expires > S.time)
    const hints = lootHints(loc.type)
    const [z0, z1] = infectedRange(loc)
    const mix = zombieMix(loc.level, L.zombieTheme)
      .filter((m) => m.w > 0.6)
      .map((m) => ZOMBIES[m.t].name + 's')
    const T = travelInfo(this.map.routeLen || 1000)
    // squad
    const av = S.survivors.filter((s) => s.status !== 'mission' && s.status !== 'outpost')
    const sick = (s) => s.infection >= INFECTION.sick
    for (const id of [...this.squad]) if (!av.find((s) => s.id === id && s.status === 'ok' && !sick(s))) this.squad.delete(id)
    if (!this.squad.size && !this.suggested) {
      this.suggested = true
      const pickable = av.filter((s) => s.status === 'ok' && !sick(s)).sort((a, b) => !!a.job - !!b.job || squadPower(b) - squadPower(a))
      for (const s of pickable.slice(0, Math.min(3, Math.max(1, pickable.length - 1)))) this.squad.add(s.id)
    }
    const squad = [...this.squad].map((id) => getS(id)).filter(Boolean)
    const pw = squad.reduce((a, s) => a + squadPower(s), 0)
    const threat = ((z0 + z1) / 2) * (6 + loc.level * 4)
    const ratio = pw / threat
    const verdict = !squad.length ? null : ratio > 1.6 ? ['Comfortable', '#8ccf72'] : ratio > 1 ? ['Fair fight', '#c8c64a'] : ratio > 0.6 ? ['Risky', '#eaa53c'] : ['Suicidal', '#ef6a52']
    const left = S.survivors.filter((s) => s.status === 'ok' && !this.squad.has(s.id)).length
    const intel = raidIntel()
    const away = T.min * 2 + 150 + loc.level * 25
    const lowAmmo = squad.some((s) => {
      const st = survivorStats(s)
      return st.gun && (S.res[st.ammoType] || 0) < 15
    })
    const rows = av.map((s) => {
      const st = survivorStats(s)
      const on = this.squad.has(s.id)
      const hurt = s.status === 'injured' || sick(s)
      const job = s.job ? S.stations.find((x) => x.id === s.job) : null
      const util = s.util && st.utilSlots ? Math.min(st.utilSlots, S.res[s.util] || 0) : 0
      return h(
        'button.sqrow' + (on ? '.on' : '') + (hurt ? '.hurt' : ''),
        {
          disabled: hurt,
          onclick: () => {
            if (on) this.squad.delete(s.id)
            else if (this.squad.size < MAX_SQUAD) this.squad.add(s.id)
            else return sfx('error')
            sfx('click')
            this.render()
          },
        },
        h('img.por', { src: this.game.portrait(s), alt: '' }),
        h(
          'div.sq-main',
          h('div.sq-name', h('b', s.name), h('small', OCCUPATIONS[s.occ].name)),
          h('div.sq-gear', h('span', st.weapon.name + (st.weaponBroken ? ' (broken)' : '')), st.armorItem ? h('span', ITEMS[st.armorItem.id].name) : null, util ? h('span.util', h('i', { html: resIcon(s.util) }), `${RES[s.util].name} ×${util}`) : null),
          h('div.sq-sk', h('span', `MEL ${s.skills.melee}`), h('span', `RNG ${s.skills.ranged}`), h('span', `SCV ${s.skills.scavenge}`), h('span.eye', { 'data-tip': `Sight ${Math.round(st.sight)} m · hearing ${Math.round(st.hearing)} m${st.wallSense ? ` · sees through a wall (${st.wallSense} m)` : ''}` }, h('i', { html: icon('eye') }), `${Math.round(st.sight)} m${st.wallSense ? ' +wall' : ''}`), job ? h('span.job', `leaves ${STATIONS[job.type]?.name || job.type}`) : null),
          hpBar(s),
        ),
        h('span.sq-tick', sick(s) ? 'Too sick' : hurt ? 'Injured' : on ? h('i', { html: icon('check') }) : ''),
      )
    })
    P.append(
      h(
        'div.phead',
        h('span.lvlbadge.big', { style: { '--c': col } }, loc.level),
        h('div.ptitle', h('h2', loc.name), h('div.psub', `${L.name} · ${DISTRICT[loc.lot.district] || ''}`)),
        h('button.x', { onclick: () => this.map.select(null), 'aria-label': 'Close', html: icon('close') }),
      ),
      h(
        'div.pbody',
        h('p.blurb', L.blurb),
        ev ? h('div.card.evbig.' + ev.kind, h('b', ev.kind === 'distress' ? `Distress call: ${ev.npc?.name || 'a survivor'} is trapped inside.` : 'A supply drop came down in the yard.'), h('small', ev.kind === 'distress' ? 'Reach them and get them out: they will join the camp.' : 'Military crates: ammo, meds and gear.'), h('span', `Signal fades in ${gameDur(ev.expires - S.time)}`)) : null,
        looted ? h('div.card.warn', `Picked clean. Worth another look on day ${looted}.`) : null,
        h(
          'div.mfacts',
          h('div', h('small', 'Infected'), h('b', `${z0}–${z1}`), h('em', mix.join(', '))),
          h('div', h('small', 'Drive'), h('b', `${T.km.toFixed(1)} km`), h('em', T.van ? `${gameDur(T.drive)} each way` : `On foot: ${gameDur(T.walk)}`)),
          h('div', h('small', 'Fuel'), h('b' + (T.van ? '' : '.bad'), `${T.fuel}`), h('em', T.van ? `${fmt(S.res.fuel)} in store` : 'Not enough: walking')),
        ),
        h('h3', 'Likely finds'),
        h(
          'div.lootchips',
          hints.res.map((k) => h('span.ci', { style: { '--c': RES[k].color }, 'data-tip': RES[k].desc || RES[k].name }, h('i.ic', { html: resIcon(k) }), RES[k].name)),
        ),
        hints.items.length ? h('p.note', 'Chance of ', hints.items.map((i) => ITEMS[i].name).join(', ')) : null,
        this.outpostCard(loc, squad),
        h('h3', 'Squad', h('small', `${this.squad.size}/${MAX_SQUAD} · click to add or remove`)),
        h('div.sqlist', rows),
      ),
      h(
        'div.pfoot',
        verdict ? h('div.verdict', { style: { '--c': verdict[1] } }, h('small', 'Odds'), h('b', verdict[0])) : null,
        h(
          'div.pf-notes',
          h('span', `${left} stay${left === 1 ? 's' : ''} behind.`),
          intel && intel.in < away ? h('span.bad', `Horde due in ${gameDur(intel.in)}: the camp may fight without them.`) : null,
          lowAmmo ? h('span.bad', 'Low on ammo for their guns.') : null,
          !T.van ? h('span.bad', 'No fuel for the van: no stash, slow trip.') : null,
        ),
        outpostAt(loc.id)
          ? h('button.btn.big', { disabled: true }, 'Your outpost')
          : h('button.btn.go.big', { disabled: !squad.length || !!looted || this.map.launching, onclick: () => this.deploy(loc, squad, T) }, looted ? 'Already looted' : h('span', { html: icon('truck') }), looted ? null : T.van ? ' Roll out' : ' Head out on foot'),
      ),
    )
  }
  // Hold a place you have run: claim it with the selected squad as the
  // garrison, or run the outpost you already have there.
  outpostCard(loc, squad) {
    const o = outpostAt(loc.id)
    const yieldChips = (y) => Object.entries(y).filter(([, v]) => v >= 0.05).map(([k, v]) => h('span.ci', { style: { '--c': RES[k].color }, 'data-tip': RES[k].name }, h('i.ic', { html: resIcon(k) }), v < 1 ? `${Math.round(v * 100)}%` : fmt(v)))
    if (o) {
      const crew = o.crew.map(getS).filter(Boolean)
      const up = outpostUpgradeCost(o)
      return h(
        'div.card.outpost',
        h('h3', `Outpost · level ${o.level}`, h('small', `held since day ${o.since}`)),
        h('div.kv', h('span', 'Garrison'), h('b', crew.map((s) => s.first).join(', ') || 'nobody')),
        h('div.kv', h('span', 'Defences'), bar(o.hp / 100, o.hp < 40 ? 'hp.low' : 'hp', `${Math.round(o.hp)}%`)),
        h('div.kv', h('span', 'Convoy each morning'), h('span.flows', yieldChips(outpostYield(o)))),
        o.last ? h('p.note', `Day ${o.last.day}: brought ${Object.entries(o.last.got).map(([k, v]) => `${v} ${RES[k].name.toLowerCase()}`).join(', ') || 'nothing (storage full)'}.`) : null,
        h(
          'div.kv',
          up ? h('button.btn.small', { disabled: !canAfford(up), 'data-tip': `More goods and stronger walls.`, onclick: () => (upgradeOutpost(o) ? sfx('build') : sfx('error'), this.render()) }, `Fortify (${Object.entries(up).map(([k, v]) => `${v} ${RES[k].short || RES[k].name.toLowerCase()}`).join(', ')})`) : h('small', 'Fully fortified'),
          h('button.btn.small.ghost', { onclick: () => (abandonOutpost(o), sfx('click'), this.render(), this.map.refreshMarkers?.()) }, 'Bring them home'),
        ),
      )
    }
    if (!hasFlag('outposts')) return S.explored?.[loc.id] ? h('p.note', 'Later (tier 8, Convoys) you can hold places like this as outposts that send goods home every day.') : null
    const why = outpostProblem(loc, squad)
    const y = outpostYield({ type: loc.type, level: 1, hp: 100, n: Math.min(3, squad.length || 2) })
    return h(
      'div.card.outpost.claim',
      h('h3', 'Hold this place', h('small', `${(S.outposts || []).length}/${OUTPOST.max} outposts`)),
      h('p.note', 'The squad you pick below stays here as a garrison. A convoy brings the goods home every morning, and sometimes the dead come for it.'),
      h('div.kv', h('span', squad.length ? `With ${squad.length} holding it, per day` : 'Per day with two holding it'), h('span.flows', yieldChips(y))),
      h('div.kv', costList(OUTPOST.cost[0], { small: true }), h('button.btn.small.go', { disabled: !!why, 'data-tip': why || 'Claim it with the selected squad', onclick: () => {
        if (claimOutpost(loc, squad)) {
          sfx('complete')
          this.squad.clear()
          this.map.refreshMarkers?.()
          this.render()
        } else sfx('error')
      } }, 'Claim outpost')),
      why && squad.length ? h('p.note.bad', why) : null,
    )
  }
  deploy(loc, squad, T) {
    if (!squad.length) return
    sfx('truck')
    const ids = squad.map((s) => s.id)
    if (T.van) S.res.fuel -= T.fuel
    this.squad.clear()
    this.suggested = false
    this.map.launch(loc, ids, { van: T.van, travel: T.min, dist: this.map.routeLen, fuel: T.van ? T.fuel : 0 })
  }
}
export const minutesToSec = (m) => m / GAME_MIN_PER_SEC
