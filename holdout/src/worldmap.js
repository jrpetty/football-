// The city map: a top-down tactical map of streets and buildings. Pick a
// location, pick a squad, deploy.
import { LOCATIONS, CONTAINERS, ITEMS, RES, RARITY, LEVEL_COLORS, ZOMBIES, zombieMix, HORDES } from './data.js'
import { S, day, survivorStats, raidIntel, gameDur, available } from './state.js'
import { icon } from './icons.js'
import { sfx } from './audio.js'
import { h, mulberry32, clamp } from './util.js'

const BY_LEVEL = [[], ['house', 'apartment', 'store'], ['diner', 'gas', 'hardware'], ['pharmacy', 'supermarket', 'garage'], ['hospital', 'gunstore', 'warehouse'], ['police', 'military']]

export function genCity(seed) {
  const R = mulberry32(seed)
  const W = 1600
  const H = 1100
  const xs = [0]
  while (xs[xs.length - 1] < W - 120) xs.push(xs[xs.length - 1] + 140 + R() * 90)
  xs.push(W)
  const zs = [0]
  while (zs[zs.length - 1] < H - 110) zs.push(zs[zs.length - 1] + 120 + R() * 80)
  zs.push(H)
  const blocks = []
  const buildings = []
  const parks = []
  const lots = []
  const SW = 13
  // camp block near the bottom-left
  const campI = 1
  const campJ = zs.length - 3
  let camp = null
  for (let i = 0; i < xs.length - 1; i++) {
    for (let j = 0; j < zs.length - 1; j++) {
      const b = { x: xs[i] + SW, y: zs[j] + SW, w: xs[i + 1] - xs[i] - SW * 2, h: zs[j + 1] - zs[j] - SW * 2 }
      blocks.push(b)
      if (i === campI && j === campJ) {
        camp = { x: b.x + b.w / 2, y: b.y + b.h / 2, b }
        continue
      }
      const r = R()
      if (r < 0.09) {
        parks.push(b)
        continue
      }
      if (r < 0.15) {
        lots.push(b)
        continue
      }
      // subdivide into building lots along the long side
      const long = b.w > b.h
      const n = 2 + Math.floor(R() * 3)
      let acc = 0
      for (let k = 0; k < n; k++) {
        const frac = k === n - 1 ? 1 - acc : (1 / n) * (0.7 + R() * 0.6)
        const start = acc
        acc += frac
        const bx = long ? b.x + start * b.w : b.x
        const by = long ? b.y : b.y + start * b.h
        const bw = long ? frac * b.w : b.w
        const bh = long ? b.h : frac * b.h
        const inset = 5 + R() * 6
        // sometimes two rows of buildings
        if (!long && b.w > 150 && R() < 0.5) {
          buildings.push({ x: bx + inset, y: by + inset, w: bw / 2 - inset * 1.5, h: bh - inset * 2 })
          buildings.push({ x: bx + bw / 2 + inset / 2, y: by + inset, w: bw / 2 - inset * 1.5, h: bh - inset * 2 })
        } else buildings.push({ x: bx + inset, y: by + inset, w: bw - inset * 2, h: bh - inset * 2 })
      }
    }
  }
  // Locations: danger grows with distance from camp.
  const maxD = Math.hypot(W, H) * 0.85
  const cands = buildings.filter((b) => b.w > 26 && b.h > 22)
  const locs = []
  const used = {}
  const order = cands.map((b) => ({ b, r: R() })).sort((a, b) => a.r - b.r)
  for (const { b } of order) {
    if (locs.length >= 34) break
    const cx = b.x + b.w / 2
    const cy = b.y + b.h / 2
    if (locs.some((l) => Math.hypot(l.x - cx, l.y - cy) < 70)) continue
    const d = Math.hypot(cx - camp.x, cy - camp.y) / maxD
    if (d < 0.07) continue
    const level = clamp(Math.floor(1 + d * 5.2 + (R() - 0.5) * 1.1), 1, 5)
    const pool = BY_LEVEL[level]
    const type = pool[Math.floor(R() * pool.length)]
    const names = LOCATIONS[type].names
    used[type] = (used[type] || 0) + 1
    const base = names[(used[type] - 1) % names.length]
    const name = used[type] > names.length ? `${base} ${Math.ceil(used[type] / names.length)}` : base
    b.loc = true
    locs.push({ id: 'loc' + locs.length, type, level, name, x: cx, y: cy, b })
  }
  // make sure every level exists
  for (let L = 1; L <= 5; L++) {
    if (!locs.some((l) => l.level === L) && locs.length) {
      const far = locs.slice().sort((a, b) => Math.abs(a.level - L) - Math.abs(b.level - L))[0]
      far.level = L
      far.type = BY_LEVEL[L][0]
      far.name = LOCATIONS[far.type].names[0]
    }
  }
  return { W, H, xs, zs, SW, blocks, buildings, parks, lots, camp, locs }
}

export class WorldMap {
  constructor(game) {
    this.game = game
    this.city = null
    this.el = null
    this.sel = null
    this.squad = new Set()
    this.view = { s: 1, x: 0, y: 0 }
    this.hover = null
  }
  open() {
    if (!this.city || this.city.seed !== S.seed) {
      this.city = genCity(S.seed)
      this.city.seed = S.seed
    }
    this.build()
    this.fit()
    this.draw()
    this.running = true
    const loop = () => {
      if (!this.running) return
      this.draw()
      requestAnimationFrame(loop)
    }
    requestAnimationFrame(loop)
  }
  close() {
    this.running = false
    this.el?.remove()
    this.el = null
  }
  get isOpen() {
    return !!this.el
  }
  build() {
    this.el?.remove()
    const canvas = h('canvas.mapc')
    this.canvas = canvas
    this.ctx = canvas.getContext('2d')
    this.plan = h('aside.planner')
    const intel = raidIntel()
    this.el = h(
      'div.worldmap',
      canvas,
      h(
        'div.maptop',
        h('button.btn.ghost', { onclick: () => this.game.closeMap() }, '← Back to camp'),
        h('div.maptitle', h('b', 'The City'), h('small', 'Danger rises the further you go from camp')),
        h('div.legend', LEVEL_COLORS.map((c, i) => h('span', { style: { '--c': c } }, `L${i + 1}`))),
        intel ? h('div.mhorde', h('span', { html: icon('horde') }), `${intel.name} in ${gameDur(intel.in)}`) : null,
      ),
      this.plan,
    )
    document.getElementById('hud').append(this.el)
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    const resize = () => {
      canvas.width = window.innerWidth * dpr
      canvas.height = window.innerHeight * dpr
      this.dpr = dpr
    }
    resize()
    this.onResize = resize
    window.addEventListener('resize', resize)
    // pan / zoom
    const ptrs = new Map()
    let drag = null
    let pinch = null
    canvas.addEventListener('pointerdown', (e) => {
      canvas.setPointerCapture(e.pointerId)
      ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY })
      if (ptrs.size === 1) drag = { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, moved: false }
      if (ptrs.size === 2) {
        const [a, b] = [...ptrs.values()]
        pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), s: this.view.s }
        drag = null
      }
    })
    canvas.addEventListener('pointermove', (e) => {
      const p = ptrs.get(e.pointerId)
      if (!p) {
        this.hover = this.hitLoc(e.clientX, e.clientY)
        canvas.style.cursor = this.hover ? 'pointer' : 'grab'
        return
      }
      p.x = e.clientX
      p.y = e.clientY
      if (pinch && ptrs.size >= 2) {
        const [a, b] = [...ptrs.values()]
        const d = Math.hypot(a.x - b.x, a.y - b.y)
        this.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, (pinch.s * d) / pinch.d / this.view.s)
        return
      }
      if (drag) {
        if (Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) > 6) drag.moved = true
        if (drag.moved) {
          this.view.x += e.clientX - drag.x
          this.view.y += e.clientY - drag.y
          drag.x = e.clientX
          drag.y = e.clientY
        }
      }
    })
    const up = (e) => {
      ptrs.delete(e.pointerId)
      if (ptrs.size < 2) pinch = null
      if (drag && !drag.moved) {
        const l = this.hitLoc(e.clientX, e.clientY)
        if (l) this.select(l)
      }
      drag = null
    }
    canvas.addEventListener('pointerup', up)
    canvas.addEventListener('pointercancel', up)
    canvas.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault()
        this.zoomAt(e.clientX, e.clientY, Math.pow(0.9985, e.deltaY))
      },
      { passive: false },
    )
    this.renderPlan()
  }
  fit() {
    const c = this.city
    const wide = window.innerWidth > 820
    const pw = wide ? 412 : 0
    const top = wide ? 70 : 56
    const bottom = wide ? 16 : window.innerHeight * 0.5 + 12
    const aw = window.innerWidth - pw - 24
    const ah = window.innerHeight - top - bottom
    const s = Math.min(aw / c.W, ah / c.H)
    this.view = { s, x: 12 + (aw - c.W * s) / 2, y: top + (ah - c.H * s) / 2 }
  }
  zoomAt(sx, sy, f) {
    const v = this.view
    const ns = clamp(v.s * f, 0.3, 3)
    const k = ns / v.s
    v.x = sx - (sx - v.x) * k
    v.y = sy - (sy - v.y) * k
    v.s = ns
  }
  toScreen(x, y) {
    return [this.view.x + x * this.view.s, this.view.y + y * this.view.s]
  }
  hitLoc(sx, sy) {
    let best = null
    let bd = 22
    for (const l of this.city.locs) {
      const [x, y] = this.toScreen(l.x, l.y)
      const d = Math.hypot(x - sx, y - sy)
      if (d < bd) {
        bd = d
        best = l
      }
    }
    return best
  }
  select(l) {
    this.sel = l
    sfx('click')
    if (!this.squad.size) {
      // Suggest the healthiest fighters.
      // Prefer people without a job so the camp keeps running.
      const av = available()
        .slice()
        .sort((a, b) => !!a.job - !!b.job || power(b) - power(a))
      for (const s of av.slice(0, Math.min(3, Math.max(1, av.length - 1)))) this.squad.add(s.id)
    }
    this.renderPlan()
  }
  // ---------------------------------------------------------------- drawing
  draw() {
    const ctx = this.ctx
    const c = this.city
    const dpr = this.dpr
    const v = this.view
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.fillStyle = '#161915'
    ctx.fillRect(0, 0, window.innerWidth, window.innerHeight)
    ctx.save()
    ctx.translate(v.x, v.y)
    ctx.scale(v.s, v.s)
    // streets
    ctx.fillStyle = '#2a2e27'
    ctx.fillRect(0, 0, c.W, c.H)
    // blocks
    for (const b of c.blocks) {
      ctx.fillStyle = '#1e221c'
      ctx.fillRect(b.x, b.y, b.w, b.h)
    }
    for (const b of c.parks) {
      ctx.fillStyle = '#233021'
      ctx.fillRect(b.x, b.y, b.w, b.h)
      ctx.fillStyle = '#2e4029'
      for (let i = 0; i < 18; i++) {
        const tx = b.x + ((i * 53) % b.w)
        const ty = b.y + ((i * 97) % b.h)
        ctx.beginPath()
        ctx.arc(tx, ty, 6 + (i % 3) * 2, 0, Math.PI * 2)
        ctx.fill()
      }
    }
    for (const b of c.lots) {
      ctx.strokeStyle = '#31362d'
      ctx.lineWidth = 1
      for (let x = b.x + 8; x < b.x + b.w; x += 12) {
        ctx.beginPath()
        ctx.moveTo(x, b.y + 6)
        ctx.lineTo(x, b.y + 22)
        ctx.moveTo(x, b.y + b.h - 22)
        ctx.lineTo(x, b.y + b.h - 6)
        ctx.stroke()
      }
    }
    // buildings
    for (const b of c.buildings) {
      ctx.fillStyle = b.loc ? '#3a4035' : '#2c3129'
      ctx.fillRect(b.x, b.y, b.w, b.h)
      ctx.fillStyle = b.loc ? '#454c3f' : '#343a31'
      ctx.fillRect(b.x + 3, b.y + 3, b.w - 6, b.h - 6)
    }
    // street centre dashes
    ctx.strokeStyle = 'rgba(200,190,140,0.12)'
    ctx.lineWidth = 1.5
    ctx.setLineDash([8, 10])
    for (const x of c.xs) {
      ctx.beginPath()
      ctx.moveTo(x, 0)
      ctx.lineTo(x, c.H)
      ctx.stroke()
    }
    for (const y of c.zs) {
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(c.W, y)
      ctx.stroke()
    }
    ctx.setLineDash([])
    // danger haze rings from camp
    const g = ctx.createRadialGradient(c.camp.x, c.camp.y, 100, c.camp.x, c.camp.y, 1500)
    g.addColorStop(0, 'rgba(124,195,106,0.05)')
    g.addColorStop(0.5, 'rgba(232,163,61,0.04)')
    g.addColorStop(1, 'rgba(216,56,74,0.09)')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, c.W, c.H)
    // camp
    const cb = c.camp.b
    ctx.fillStyle = '#3b3322'
    ctx.fillRect(cb.x + 12, cb.y + 12, cb.w - 24, cb.h - 24)
    ctx.strokeStyle = '#e8a33d'
    ctx.lineWidth = 3
    ctx.setLineDash([6, 4])
    ctx.strokeRect(cb.x + 12, cb.y + 12, cb.w - 24, cb.h - 24)
    ctx.setLineDash([])
    // route
    if (this.sel) {
      const t = performance.now() / 1000
      ctx.strokeStyle = 'rgba(232,163,61,0.8)'
      ctx.lineWidth = 3 / v.s
      ctx.setLineDash([10 / v.s, 8 / v.s])
      ctx.lineDashOffset = -t * 30
      ctx.beginPath()
      // follow the street grid: horizontal then vertical
      const sx = c.camp.x
      const sy = c.camp.y
      const nearestX = c.xs.reduce((a, b) => (Math.abs(b - this.sel.x) < Math.abs(a - this.sel.x) ? b : a))
      const nearestY = c.zs.reduce((a, b) => (Math.abs(b - sy) < Math.abs(a - sy) ? b : a))
      ctx.moveTo(sx, sy)
      ctx.lineTo(sx, nearestY)
      ctx.lineTo(nearestX, nearestY)
      ctx.lineTo(nearestX, this.sel.y)
      ctx.lineTo(this.sel.x, this.sel.y)
      ctx.stroke()
      ctx.setLineDash([])
      ctx.lineDashOffset = 0
    }
    ctx.restore()
    // markers in screen space so they stay crisp
    ctx.font = '600 11px "Saira Condensed", "Arial Narrow", sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    const [cx, cy] = this.toScreen(c.camp.x, c.camp.y)
    ctx.fillStyle = '#e8a33d'
    ctx.font = '700 13px "Saira Stencil One", "Saira Condensed", sans-serif'
    ctx.fillText('CAMP', cx, cy)
    const t = performance.now() / 1000
    for (const l of c.locs) {
      const [x, y] = this.toScreen(l.x, l.y)
      const looted = S.looted[l.id]
      const col = LEVEL_COLORS[l.level - 1]
      const sel = this.sel === l
      const hov = this.hover === l
      const r = sel ? 13 : hov ? 12 : 10
      if (sel) {
        ctx.strokeStyle = col
        ctx.globalAlpha = 0.5 + Math.sin(t * 4) * 0.3
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.arc(x, y, r + 6 + Math.sin(t * 4) * 2, 0, Math.PI * 2)
        ctx.stroke()
        ctx.globalAlpha = 1
      }
      ctx.fillStyle = looted ? '#3a3d38' : '#12140f'
      ctx.beginPath()
      ctx.arc(x, y, r, 0, Math.PI * 2)
      ctx.fill()
      ctx.lineWidth = 2.5
      ctx.strokeStyle = looted ? '#5a5d56' : col
      ctx.stroke()
      ctx.fillStyle = looted ? '#7a7d76' : col
      ctx.font = '700 12px "Saira Condensed", "Arial Narrow", sans-serif'
      ctx.fillText(String(l.level), x, y + 0.5)
      if (this.view.s > 0.75 || sel || hov) {
        ctx.font = '600 11px "Saira Condensed", "Arial Narrow", sans-serif'
        ctx.fillStyle = looted ? 'rgba(160,160,150,0.7)' : 'rgba(232,228,214,0.92)'
        ctx.fillText(l.name, x, y + r + 10)
        if (looted) {
          ctx.fillStyle = 'rgba(160,160,150,0.6)'
          ctx.fillText(`looted · ${Math.max(1, looted - day())}d`, x, y + r + 22)
        }
      }
    }
  }
  // ---------------------------------------------------------------- planner
  renderPlan() {
    const P = this.plan
    P.innerHTML = ''
    const l = this.sel
    if (!l) {
      P.append(
        h('div.pl-empty', h('h2', 'Choose a location'), h('p', 'Tap a marker on the map. Level 1 places are safe-ish and full of food and cloth. Level 5 police stations and military checkpoints hold the best guns and armor, and far more of the dead.'), h('p.note', 'Every run takes survivors out of camp. Leave enough people behind to defend it and keep the stations running.')),
      )
      return
    }
    const L = LOCATIONS[l.type]
    const looted = S.looted[l.id]
    const col = LEVEL_COLORS[l.level - 1]
    // loot hints
    const resW = {}
    const items = new Set()
    for (const [k, w] of Object.entries(L.containers)) {
      for (const e of CONTAINERS[k].pool) {
        if (e.r) resW[e.r] = (resW[e.r] || 0) + e.w * w
        else if (e.w * w > 0.3) items.add(e.i)
      }
    }
    if (L.outside) for (const k of Object.keys(L.outside)) for (const e of CONTAINERS[k].pool) if (e.r) resW[e.r] = (resW[e.r] || 0) + e.w
    const topRes = Object.entries(resW).sort((a, b) => b[1] - a[1]).slice(0, 5)
    const zmin = 3 + l.level * 3
    const mix = zombieMix(l.level).filter((m) => m.w > 0.5).map((m) => ZOMBIES[m.t].name + 's')
    P.append(
      h('div.pl-head', h('span.lvlbadge', { style: { '--c': col } }, `Level ${l.level}`), h('h2', l.name), h('p.sub', L.name), h('button.x', { onclick: () => ((this.sel = null), this.renderPlan()), 'aria-label': 'Close', html: icon('close') })),
      h('p', L.blurb),
      h('div.pl-facts', h('div', h('small', 'Infected'), h('b', `${zmin}–${zmin + 2}`), h('small', mix.join(', '))), h('div', h('small', 'Likely loot'), h('div.chips', topRes.map(([k]) => h('span.chip', { style: { '--c': RES[k].color }, title: RES[k].name, html: icon(k) }, h('b', RES[k].name)))))),
      items.size ? h('p.note', `Chance of ${[...items].slice(0, 5).map((i) => ITEMS[i].name).join(', ')}`) : null,
    )
    if (looted) {
      P.append(h('p.bad', `Picked clean. Worth another look on day ${looted}.`))
    }
    // squad picker
    const av = S.survivors.filter((s) => s.status !== 'mission')
    const list = h('div.plist.squadpick')
    for (const s of av) {
      const st = survivorStats(s)
      const on = this.squad.has(s.id)
      const hurt = s.status === 'injured'
      list.append(
        h(
          'button.prow.pick' + (on ? '.on' : ''),
          {
            disabled: hurt,
            onclick: () => {
              if (on) this.squad.delete(s.id)
              else if (this.squad.size < 4) this.squad.add(s.id)
              sfx('click')
              this.renderPlan()
            },
          },
          h('img', { src: this.game.portrait(s), alt: '' }),
          h('div.pinfo', h('b', s.name), h('small', `${st.weapon.name}${st.gun ? ' · gun' : ''} · MEL ${s.skills.melee} · RNG ${s.skills.ranged} · SCV ${s.skills.scavenge}`), h('div.hpbar', h('i', { style: { width: `${clamp(s.hp / st.maxHp, 0, 1) * 100}%` } }))),
          h('span.tick', hurt ? 'Injured' : on ? '✓' : ''),
        ),
      )
    }
    for (const id of [...this.squad]) if (!av.find((s) => s.id === id && s.status === 'ok')) this.squad.delete(id)
    const squad = [...this.squad].map((id) => S.survivors.find((s) => s.id === id))
    const pw = squad.reduce((a, s) => a + power(s), 0)
    const threat = zmin * (6 + l.level * 4)
    const ratio = pw / threat
    const verdict = !squad.length ? '' : ratio > 1.6 ? 'Comfortable' : ratio > 1 ? 'Fair fight' : ratio > 0.6 ? 'Risky' : 'Suicidal'
    const vcol = ratio > 1.6 ? '#7cc36a' : ratio > 1 ? '#c8c64a' : ratio > 0.6 ? '#e8a33d' : '#d8384a'
    const left = S.survivors.filter((s) => s.status === 'ok' && !this.squad.has(s.id)).length
    const intel = raidIntel()
    P.append(h('h3', 'Squad', h('small', `${this.squad.size}/4 · tap to add or remove`)), list)
    P.append(
      h(
        'div.pl-foot',
        squad.length ? h('div.verdict', { style: { '--c': vcol } }, h('small', 'Odds'), h('b', verdict)) : null,
        h('small.note', `${left} stay behind to defend and work.${intel && intel.in < 8 * 60 ? ` Horde due in ${gameDur(intel.in)}.` : ''}${S.res.ammo < 20 && squad.some((s) => survivorStats(s).gun) ? ' Low on ammo.' : ''}`),
        h('button.btn.go.big', { disabled: !squad.length || !!looted, onclick: () => this.deploy(l, squad) }, looted ? 'Already looted' : 'Deploy squad'),
      ),
    )
  }
  deploy(l, squad) {
    sfx('truck')
    const ids = squad.map((s) => s.id)
    this.squad.clear()
    this.game.startMission(l, ids)
  }
}

// Rough fighting strength of a survivor for the odds estimate.
function power(s) {
  const st = survivorStats(s)
  const dps = st.dmg / st.rate
  return (dps * (st.gun ? (S.res.ammo > 10 ? 1.4 : 0.4) : 1) + st.maxHp * 0.15) * (s.hp / st.maxHp) * (s.status === 'ok' ? 1 : 0)
}
