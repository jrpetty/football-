// Camp-wide panels: trade, goals, log, the wall, expansions, production and
// stockpiles, power, morale, settings, the gate visitor, reports and menus.
import { RES, STOCK_KEYS, ITEMS, QUALITY, RARITY, STATIONS, FENCE, EXPANSIONS, GOALS, HORDES, OCCUPATIONS, SKILLS, SKILL_KEYS, TRAITS, SEC_PER_DAY, AMMO_KEYS, MILESTONES } from '../game/data.js'
import {
  S, day, clockStr, gameDur, capOf, bedCount, canAfford, pay, gain, addItem, itemName, fenceMax, repairFenceCost, repairFence, fenceUpgradeCost, upgradeFence,
  expansionCost, startExpansion, expansionAvailable, claimGoal, survivorLevel, perimeter, save, wipeSave, countType, workersOf, survivorStats,
  unlockedBy, msDone, fenceUnlocked, listBackups, restoreBackup, exportSave, importSave,
} from '../game/state.js'
import { campFlow, stationFlow, power, powerNeed, isAutomated, boilerFuel, sourcePower, solarOutput, windOutput, moraleFactors, dailyNeeds, constructSpeed, raidIntel, threatLevel, isBloodMoonDay, sellMult, buyMult, resSellPrice, acceptRecruit, declineRecruit } from '../game/economy.js'
import { QUALITY as GFXQ } from '../render/pipeline.js'
import { sfx, setSound } from '../core/audio.js'
import { bus, h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { costList, resChip, bar, qualityTag, itemCard, skillRows, traitTags, seg, plural, resIcon } from './common.js'

// ---------------------------------------------------------------- trade
let tradeTab = 'buy'
export function renderMarket(ui) {
  const M = S.market
  const bm = buyMult()
  const sm = sellMult()
  const clerk = S.survivors.find((s) => s.status === 'ok' && OCCUPATIONS[s.occ].fx.market)
  const tabs = seg([['buy', 'Buy'], ['sell', 'Sell goods']], tradeTab, (v) => ((tradeTab = v), ui.refreshPanel()))
  let body
  if (tradeTab === 'buy') {
    const items = (M?.stock || []).filter((x) => x.kind === 'item')
    const res = (M?.stock || []).filter((x) => x.kind === 'res')
    body = [
      h('h3', 'Weapons & gear', h('small', 'New stock every morning')),
      items.length
        ? h(
            'div.igrid',
            items.map((x) => {
              const price = Math.round(x.price * bm)
              return itemCard({ id: x.id, q: x.q, cond: 100, mods: [] }, {
                owner: false,
                price,
                actions: h('button.mini', { disabled: S.res.cash < price, onclick: () => buyItem(ui, x, price) }, 'Buy'),
              })
            }),
          )
        : h('p.note', 'Sold out for today.'),
      h('h3', 'Supplies'),
      h(
        'div.tradelist',
        res.map((x) => {
          const price = Math.round(x.price * bm)
          return h('div.trow', h('span.tr-name', resChip(x.res, x.qty), ' ', RES[x.res].name), h('span.tr-price', resChip('cash', price)), h('button.mini', { disabled: S.res.cash < price, onclick: () => buyRes(ui, x, price) }, 'Buy'))
        }),
      ),
    ]
  } else {
    body = [
      h('p.note', 'Spare goods for cash. Sell items from the Items panel.'),
      h(
        'div.tradelist',
        STOCK_KEYS.filter((k) => S.res[k] >= 1).map((k) => {
          const amt = Math.floor(S.res[k])
          const qtys = [...new Set([1, 10, 50, Math.floor(amt / 2), amt].filter((q) => q >= 1 && q <= amt))].sort((a, b) => a - b)
          return h(
            'div.trow',
            h('span.tr-name', resChip(k, amt), ' ', RES[k].name),
            h('span.tr-price', h('small', `${(RES[k].sell * sm).toFixed(1)} each`)),
            h(
              'span.tr-btns',
              qtys.slice(0, 4).map((q) =>
                h(
                  'button.mini',
                  {
                    onclick: () => {
                      const p = resSellPrice(k, q)
                      S.res[k] -= q
                      gain({ cash: p })
                      sfx('coin')
                      ui.refreshPanel()
                    },
                  },
                  q === amt ? `All ${q}` : `${q}`,
                ),
              ),
            ),
          )
        }),
      ),
    ]
  }
  return ui.frame('Black market', h('span', resChip('cash', S.res.cash), clerk ? h('em.good', ` · ${clerk.first} haggles for you`) : ''), [tabs, ...body], { icon: 'market' })
}
function buyItem(ui, x, price) {
  if (!pay({ cash: price })) return sfx('error')
  addItem(x.id, { q: x.q })
  S.market.stock = S.market.stock.filter((y) => y !== x)
  sfx('coin')
  ui.toast(`Bought ${QUALITY[x.q].id !== 'standard' ? QUALITY[x.q].name + ' ' : ''}${ITEMS[x.id].name}`, 'good')
  ui.refreshPanel()
}
function buyRes(ui, x, price) {
  if (!pay({ cash: price })) return sfx('error')
  const lost = gain({ [x.res]: x.qty })
  S.market.stock = S.market.stock.filter((y) => y !== x)
  sfx('coin')
  if (lost[x.res]) ui.toast(`Storage full: ${Math.round(lost[x.res])} ${RES[x.res].name.toLowerCase()} wasted`, 'bad')
  ui.refreshPanel()
}

// ---------------------------------------------------------------- log
export function renderLog(ui) {
  return ui.frame('Camp log', `Day ${day()}`, h('div.log', S.log.slice(0, 80).map((e) => h('div.le.' + (e.kind || 'plain'), h('span.t', `D${Math.floor(e.t / 1440) + 1} ${clockStr(e.t)}`), h('span', e.text)))), { icon: 'log' })
}

// ---------------------------------------------------------------- wall & land
export function renderFence(ui) {
  const F = FENCE[S.fence.level]
  const mx = fenceMax()
  const hp = S.fence.hp
  const broken = hp.filter((x) => x <= 0).length
  const damaged = hp.filter((x) => x > 0 && x < mx).length
  const { missing, cost } = repairFenceCost()
  const next = FENCE[S.fence.level + 1]
  const body = [
    h('p.desc', 'The wall keeps hordes out while your defenders shoot over it. Every section has to be broken through on its own.'),
    h('section.card', h('h3', F.name, h('small', `${hp.length} m around`)), h('div.kv', h('span', 'Strength per section'), h('b', mx)), h('div.kv', h('span', 'Damaged / broken'), h('b' + (broken ? '.bad' : ''), `${damaged} / ${broken}`)), bar(hp.reduce((a, b) => a + b, 0) / (mx * hp.length || 1), 'hp')),
    h('section.card', h('h3', 'Repairs'), missing > 0 ? h('div.kv', costList(cost), h('button.btn.small', { disabled: !canAfford(cost), onclick: () => (repairFence() ? sfx('build') : sfx('error'), ui.refreshPanel()) }, 'Repair all')) : h('p.note', 'The wall is in perfect shape.')),
  ]
  if (next) {
    const c = fenceUpgradeCost()
    body.push(
      h(
        'section.card',
        h('h3', `Upgrade: ${next.name}`, h('small', `${next.hp} strength`)),
        S.fence.building
          ? [bar(1 - S.fence.building.left / S.fence.building.total, 'build'), h('p.note', 'Free survivors are raising the new wall.')]
          : !fenceUnlocked()
            ? [h('p.note.bad', `Needs the ${MILESTONES[unlockedBy('fence', S.fence.level + 1)].name} milestone (tier ${MILESTONES[unlockedBy('fence', S.fence.level + 1)].tier}). See Progress.`)]
            : [h('p.note', `Cost scales with the length of the wall (${perimeter()} m). Expand first and it costs more; upgrade first and expansions inherit the old wall level.`), h('div.kv', costList(c), h('button.btn.go.small', { disabled: !canAfford(c), onclick: () => (upgradeFence() ? (sfx('build'), ui.toast(`Building the ${next.name}`)) : sfx('error'), ui.refreshPanel()) }, 'Upgrade'))],
      ),
    )
  } else body.push(h('p.note', 'This is the strongest wall you can build.'))
  return ui.frame('The wall', F.name, body, { icon: 'shield' })
}
export function renderExpansion(ui, id) {
  const X = EXPANSIONS.find((e) => e.id === id)
  if (!X) return null
  const st = S.expansions[id]
  const cost = expansionCost(id)
  const sp = constructSpeed()
  const body = [h('p.desc', X.desc)]
  body.push(h('section.card', h('h3', 'The land'), h('div.kv', h('span', 'Adds'), h('b', `${X.side === 'n' || X.side === 's' ? 'a strip along the ' + { n: 'north', s: 'south' }[X.side] : 'a strip along the ' + { e: 'east', w: 'west' }[X.side]} side, 10 m deep`)), h('div.kv', h('span', 'Salvage when cleared'), costList(X.salvage, { have: false }))))
  if (st === 'done') body.push(h('p.note.good', 'Cleared and fenced. Build on it!'))
  else if (st === 'building') body.push(h('section.card', h('h3', 'Clearing'), bar(1 - S.expanding.left / S.expanding.total, 'build'), h('p.note', `About ${gameDur((S.expanding.left / Math.max(0.01, sp)) * 3)} at the current pace. Free survivors do the work.`)))
  else {
    const avail = expansionAvailable(id)
    body.push(
      h(
        'section.card',
        h('h3', 'Expand', h('small', `${X.time}s of work`)),
        !avail ? h('p.note.bad', unlockedBy('exp', id) && !msDone(unlockedBy('exp', id)) ? `Needs the ${MILESTONES[unlockedBy('exp', id)].name} milestone (tier ${MILESTONES[unlockedBy('exp', id)].tier}). See Progress.` : S.expanding ? 'Finish the current expansion first.' : 'Expand the inner strip on this side first.') : null,
        h('p.note', 'The wall moves out to enclose the new land, at its current level. Hordes get a little bigger as the camp grows.'),
        h('div.kv', costList(cost), h('button.btn.go.small', { disabled: !avail || !canAfford(cost) || !!S.expanding, onclick: () => (startExpansion(id) ? (sfx('build'), ui.toast(`Clearing ${X.name}`)) : sfx('error'), ui.refreshPanel()) }, 'Start')),
      ),
    )
  }
  return ui.frame(X.name, `${{ n: 'North', s: 'South', e: 'East', w: 'West' }[X.side]} · ring ${X.ring}`, body, { icon: 'expand' })
}

// ---------------------------------------------------------------- production overview
export function renderProduction(ui) {
  const flow = campFlow()
  const needs = dailyNeeds()
  const groups = [
    ['needs', 'Needs'],
    ['materials', 'Materials'],
    ['components', 'Components'],
    ['ammo', 'Ammunition'],
    ['supplies', 'Supplies'],
    ['project', 'Signal parts'],
    ['research', 'Research'],
  ]
  const producers = {}
  const pinfo = power()
  for (const st of S.stations) {
    const f = stationFlow(st, pinfo)
    for (const [k, v] of Object.entries(f)) if (Math.abs(v) > 0.05) (producers[k] ||= []).push(`${STATIONS[st.type].name} ${v > 0 ? '+' : ''}${v.toFixed(1)}`)
  }
  const tables = groups.map(([cat, name]) =>
    h(
      'section.card',
      h('h3', name),
      h(
        'div.stock',
        STOCK_KEYS.filter((k) => RES[k].cat === cat).map((k) => {
          const v = S.res[k]
          const cap = capOf(k)
          const r = flow[k] || 0
          return h(
            'div.srow',
            { 'data-tip': `<b>${RES[k].name}</b>${RES[k].desc}${producers[k] ? '<br><em>' + producers[k].join('<br>') + '</em>' : ''}` },
            h('span.s-name', { style: { '--c': RES[k].color } }, h('i', { html: resIcon(k) }), RES[k].name),
            spark(S.hist?.res?.[k], cap, RES[k].color),
            h('span.s-amt', fmt(v)),
            h('span.s-cap', bar(v / cap, '', null), h('small', `/ ${fmt(cap)}`)),
            h('span.s-rate' + (r > 0.05 ? '.up' : r < -0.05 ? '.down' : ''), Math.abs(r) < 0.05 ? '—' : `${r > 0 ? '+' : ''}${r.toFixed(Math.abs(r) < 10 ? 1 : 0)}/day`),
          )
        }),
      ),
    ),
  )
  const stations = h(
    'section.card',
    h('h3', 'Stations'),
    h(
      'div.stlist',
      S.stations
        .filter((st) => st.type !== 'campfire')
        .map((st) =>
          h(
            'button.strow' + (st.stalled ? '.stalled' : st.active ? '.active' : ''),
            { onclick: () => ui.openStation(st.id) },
            h('b', STATIONS[st.type].name, h('small', st.level ? ` L${st.level}` : ' (new)')),
            h('span', st.building ? 'Building…' : st.stalled || (st.active ? 'Working' : 'Idle')),
            h('span.w', `${workersOf(st).length}/${STATIONS[st.type].workers[Math.max(0, st.level - 1)] || 0}`, isAutomated(st, pinfo) ? h('i', { html: icon('module'), 'data-tip': 'Automated' }) : null),
          ),
        ),
    ),
  )
  return ui.frame('Camp overview', `Everyone eats ${needs.food.toFixed(1)} food and drinks ${needs.water.toFixed(1)} water a day`, [bottlenecks(ui), h('div.cols2', h('div', ...tables.slice(0, 3)), h('div', ...tables.slice(3), stations))], { icon: 'production' })
}
// The last four days of a stock as a little line, scaled to storage.
function spark(arr, cap, color) {
  if (!arr || arr.length < 2) return h('span.spark')
  const W = 64
  const H = 16
  const top = Math.max(cap === Infinity ? 0 : cap, ...arr, 1)
  const pts = arr.map((v, i) => `${((i / (arr.length - 1)) * W).toFixed(1)},${(H - 1 - (v / top) * (H - 2)).toFixed(1)}`)
  const span = Math.round(arr.length / 24)
  return h('span.spark', { 'data-tip': `The last ${span} day${span === 1 ? '' : 's'}: low ${fmt(Math.min(...arr))}, high ${fmt(Math.max(...arr))}`, html: `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"><polyline points="${pts.join(' ')}" fill="none" stroke="${color}" stroke-width="1.4" stroke-linejoin="round" vector-effect="non-scaling-stroke"/></svg>` })
}
// Stations that are stuck, and why, worst first.
function bottlenecks(ui) {
  const why = (x) => (x.includes('belt') ? 0 : x.startsWith('Missing') ? 1 : x.includes('full') ? 2 : x.includes('No work') || x.includes('Needs a') ? 3 : 4)
  const stuck = S.stations.filter((st) => st.stalled && !st.building && !/at target|stocked|No project|Everything/.test(st.stalled)).sort((a, b) => why(a.stalled) - why(b.stalled))
  const jams = (S.links || []).filter((l) => (l.jam || 0) > 1.5).length
  if (!stuck.length && !jams) return h('section.card.bneck.ok', h('h3', 'Bottlenecks', h('small', 'none')), h('p.note', 'Every station has what it needs.'))
  return h(
    'section.card.bneck',
    h('h3', 'Bottlenecks', h('small', `${stuck.length} station${stuck.length === 1 ? '' : 's'} stuck${jams ? ` · ${jams} belt${jams > 1 ? 's' : ''} backed up` : ''}`)),
    h('div.bnlist', stuck.slice(0, 8).map((st) => h('button.bn', { onclick: () => ui.openStation(st.id) }, h('b', STATIONS[st.type].name), h('span', st.stalled)))),
  )
}
export function renderPower(ui) {
  const p = power()
  const rows = []
  for (const st of S.stations) {
    if (st.level < 1 || st.building) continue
    const D = STATIONS[st.type]
    const name = `${D.name}${D.levels > 1 ? ` L${st.level}` : ''}`
    if (st.type === 'generator' || st.type === 'boiler') {
      const lit = p.srcs.some((x) => x.st === st)
      const k = st.type === 'generator' ? 'fuel' : boilerFuel(st)
      rows.push(h('div.kv', h('span', name, k && lit ? h('small.dim', ` · ${RES[k].name.toLowerCase()}`) : null), lit ? h('b.good', `${fmt(st.out || 0)} of ${fmt(sourcePower(st))}`) : h('b.bad', st.powerOn === false ? 'off' : 'no fuel')))
    }
    if (st.type === 'solar') rows.push(h('div.kv', h('span', name), h('b.good', `${(D.solar[st.level - 1] * solarOutput()).toFixed(1)} of ${D.solar[st.level - 1]}`)))
    if (st.type === 'wind') rows.push(h('div.kv', h('span', name), h('b.good', `${(D.wind[st.level - 1] * windOutput()).toFixed(1)} of ${D.wind[st.level - 1]}`)))
    if (st.type === 'battery') rows.push(h('div.kv', h('span', name), h('b', `${fmt(st.charge || 0)} / ${D.store[st.level - 1]} stored${(st.flow || 0) > 0.05 ? ' · charging' : (st.flow || 0) < -0.05 ? ' · supplying' : ''}`)), bar((st.charge || 0) / D.store[st.level - 1], 'prod'))
  }
  const users = S.stations.filter((st) => powerNeed(st) > 0)
  const mix = [['Sun and wind', p.fromRenew], ['Engines and generators', p.fromFuel], ['Batteries', p.fromBatt]].filter(([, v]) => v > 0.05)
  return ui.frame(
    'Power',
    `${fmt(p.used)} used of ${fmt(p.supply)}`,
    [
      h('section.card', h('h3', 'Sources'), rows.length ? rows : h('p.note', 'No power yet. A Steam Engine burns wood or coal (Steam Power milestone, tier 1). Diesel generators, solar, wind and batteries come later.')),
      mix.length ? h('section.card', h('h3', 'Where it comes from'), mix.map(([t, v]) => h('div.kv', h('span', t), h('b', fmt(v))))) : null,
      h('section.card', h('h3', 'Using power', h('small', `${fmt(p.demand)} wanted`)), users.length ? users.map((st) => h('div.kv', h('span', STATIONS[st.type].name), h('b' + (p.powered.has(st.id) ? '.good' : '.bad'), `${fmt(powerNeed(st))} ${p.powered.has(st.id) ? '✓' : STATIONS[st.type].machine ? 'by hand' : 'unpowered'}`))) : h('p.note', 'Nothing yet. Machines (Fabricator, Machine Shop, labs and benches), automated stations, turrets and floodlights use power.')),
      h('p.note', 'Turrets and floodlights get power first, then machines and automated stations in the order they were built. Sun and wind are used first and charge the batteries with what is left; engines only burn for the rest. A machine without power is hand-cranked at half speed.'),
    ],
    { icon: 'bolt' },
  )
}
export function renderMorale(ui) {
  const f = moraleFactors()
  const target = clamp(f.reduce((a, x) => a + x.v, 0), 0, 100)
  return ui.frame(
    'Morale',
    `${Math.round(S.morale)} now, heading for ${Math.round(target)}`,
    [
      h('section.card', f.map((x) => h('div.kv', h('span', x.text), h('b' + (x.v > 0 ? '.good' : x.v < 0 ? '.bad' : ''), `${x.v > 0 ? '+' : ''}${x.v}`)))),
      h('p.note', 'High morale makes everyone work up to 20% faster; low morale slows them down, and below 18 people start leaving in the night.'),
    ],
    { icon: 'morale' },
  )
}

// ---------------------------------------------------------------- horde intel
function nextBloodMoon() {
  let d = day()
  if (S.time > (d - 1) * 1440 + 22 * 60 + 120) d++
  while (!isBloodMoonDay(d)) d++
  return d
}
export function hordeInfo(ui) {
  const I = raidIntel()
  const defenders = S.survivors.filter((s) => s.status === 'ok')
  const guns = defenders.filter((s) => survivorStats(s).gun).length
  const towers = countType('watchtower')
  const turrets = countType('turret')
  const body = [
    h('section.card' + (I?.blood ? '.blood' : ''), h('h3', I?.blood ? 'Blood Moon' : 'Next horde'), I ? [h('div.kv', h('span', 'Arrives in'), h('b', gameDur(I.in))), h('div.kv', h('span', 'Size'), h('b', I.known ? `${I.name} · ${I.count} zombies` : 'Unknown')), I.blood ? h('p.note.bad', 'Every seventh night the dead come in force: a bigger horde, tougher and faster.') : null, I.known ? null : h('p.note', 'Build a Watchtower to see how big hordes are before they arrive.')] : h('p', 'No horde on the way.')),
    h('section.card', h('h3', 'Threat', h('small', `level ${threatLevel().toFixed(1)}`)), h('p.note', 'Hordes grow with what the camp has achieved: milestone tiers, Signal phases, how many live here and how far the walls reach. Time survived adds a little.'), h('div.kv', h('span', 'Next Blood Moon'), h('b', `Day ${nextBloodMoon()}`))),
    h('section.card', h('h3', 'Your defense'), h('div.kv', h('span', 'Wall'), h('b', `${FENCE[S.fence.level].name} · ${fenceMax()} per section`)), h('div.kv', h('span', 'Defenders in camp'), h('b', `${defenders.length} (${guns} with guns)`)), h('div.kv', h('span', 'Watchtowers / turrets'), h('b', `${towers} / ${turrets}`)), h('div.kv', h('span', 'Ammo'), h('span', AMMO_KEYS.map((k) => resChip(k, S.res[k]))))),
    h('p.note', 'When the horde comes, everyone in camp grabs a weapon. Squads out on a run miss the fight: the camp defends itself without them.'),
  ]
  return ui.frame('Horde', I ? (I.known ? I.name : 'Incoming') : '', body, { icon: 'horde' })
}

// ---------------------------------------------------------------- settings & menus
export function renderSettings(ui) {
  const st = S.settings
  const g = ui.game
  const row = (label, ctl) => h('div.kv', h('span', label), ctl)
  return h(
    'div',
    h('h2', 'Settings'),
    row('Graphics quality', seg(Object.entries(GFXQ).map(([k, q]) => [k, q.label]), st.quality || 'high', (v) => ((st.quality = v), g.applySettings(), ui.closeModal(), ui.openSettings()))),
    row('Tilt-shift focus', seg([[true, 'On'], [false, 'Off']], st.tilt !== false, (v) => ((st.tilt = v), g.applySettings(), ui.closeModal(), ui.openSettings()))),
    row('Edge scrolling', seg([[true, 'On'], [false, 'Off']], st.edgePan !== false, (v) => ((st.edgePan = v), g.applySettings(), ui.closeModal(), ui.openSettings()))),
    row('Sound', seg([[true, 'On'], [false, 'Off']], st.sound !== false, (v) => ((st.sound = v), setSound(v), ui.closeModal(), ui.openSettings()))),
    h('p.note', 'Controls: drag or WASD to move, scroll to zoom, right-drag or Q/E to rotate. Space pauses, 1–3 set the speed.'),
    S && !S.over && ui.game.running ? saveSection(ui) : null,
  )
}
// The viewer's download service when the game runs as a claude.ai artifact,
// else null. Asked once; a standalone file has no window.claude at all.
let dlHost = null
const savesHost = () => (dlHost ??= window.claude?.use ? window.claude.use('downloads').catch(() => null) : Promise.resolve(null))
// Export, import and the rolling daily backups.
function saveSection(ui) {
  const backups = listBackups()
  const download = async () => {
    sfx('click')
    const name = `holdout-day-${day()}.json`
    const data = exportSave()
    // Inside the claude.ai viewer a page cannot download by itself: the
    // viewer offers the file instead. A standalone copy uses a plain link.
    const dl = await savesHost()
    if (dl) {
      try {
        await dl.save({ filename: name, data })
        ui.toast('Save exported', 'good')
      } catch (e) {
        if (e?.code !== 'declined') ui.toast('This view cannot save files. Open the standalone game to export.', 'bad')
      }
      return
    }
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([data], { type: 'application/json' }))
    a.download = name
    document.body.append(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(a.href), 2000)
  }
  const file = h('input', { type: 'file', accept: '.json,application/json', style: { display: 'none' } })
  file.addEventListener('change', async () => {
    const f = file.files?.[0]
    if (!f) return
    const err = importSave(await f.text())
    if (err) return ui.toast(err, 'bad'), sfx('error')
    ui.confirm('Load this camp?', 'Your current camp is replaced by the one in the file.', 'Load', () => location.reload())
  })
  return h(
    'section.savebox',
    h('h3', 'Your camp', h('small', 'Saved in this browser')),
    h('div.kv', h('span', 'Take it with you, or keep a copy'), h('span.btns', h('button.btn.small', { onclick: download }, 'Export save'), h('button.btn.small.ghost', { onclick: () => file.click() }, 'Import'), file)),
    backups.length
      ? h(
          'div.backups',
          h('small', 'Daily backups'),
          backups.map((b, i) =>
            h('div.kv', h('span', `Day ${b.day} · ${b.pop ?? '?'} survivors · ${new Date(b.at).toLocaleString()}`), h('button.mini', { onclick: () => ui.confirm(`Go back to day ${b.day}?`, 'Your camp returns to this backup. Anything since then is lost.', 'Restore', () => (restoreBackup(i) ? location.reload() : ui.toast('Could not restore', 'bad')), { danger: true }) }, 'Restore')),
          ),
        )
      : h('p.note', 'A backup is kept each new day (the last three).'),
  )
}
export function menuModal(ui) {
  const g = ui.game
  return h(
    'div.menu',
    h('h2', 'Holdout'),
    h('p.note', `Day ${day()} · ${plural(S.survivors.length, 'survivor')}`),
    h('button.btn.big', { onclick: () => ui.closeModal() }, 'Resume'),
    h('button.btn', { onclick: () => (save(), ui.toast('Saved', 'good'), ui.closeModal()) }, 'Save now'),
    h('button.btn', { onclick: () => (ui.closeModal(), ui.openManual()) }, 'Field manual'),
    h('button.btn', { onclick: () => (ui.closeModal(), ui.openSettings()) }, 'Settings'),
    h('button.btn.ghost.danger', { onclick: () => ui.confirm('Start over?', 'This camp will be lost for good.', 'Start over', () => g.newGame(), { danger: true }) }, 'New camp'),
  )
}
export function recruitModal(ui) {
  const p = S.recruit.pending
  const s = p.s
  const beds = bedCount()
  const full = S.survivors.length >= beds
  return h(
    'div.recruit',
    h('h2', 'Someone at the gate'),
    h(
      'div.sheet-head',
      h('img.por.big', { src: ui.game.portrait(s) }),
      h('div.sh-info', h('h3.rname', s.name, h('small', ` · ${s.age}`)), traitTags(s), h('p.perk', h('b', OCCUPATIONS[s.occ].name + ': '), OCCUPATIONS[s.occ].perk), h('div.sh-meta', h('span', `Level ${survivorLevel(s)}`), h('span', `Waits until ${clockStr(p.expires)}`))),
    ),
    skillRows(s),
    full ? h('p.note.bad', `No free bed (${S.survivors.length}/${beds}). Build or upgrade a Bunkhouse to take them in.`) : h('p.note', 'Another mouth to feed, another pair of hands.'),
    h(
      'div.mactions',
      h('button.btn.ghost', { onclick: () => (declineRecruit(), ui.closeModal()) }, 'Turn away'),
      h('button.btn.go', { disabled: full, onclick: () => (acceptRecruit() ? (sfx('levelup'), ui.toast(`${s.first} joined the camp`, 'good')) : null, ui.closeModal()) }, 'Let them in'),
    ),
  )
}
export function raidReportModal(ui, r) {
  return h(
    'div',
    h('h2', r.won ? 'The wall held' : 'The horde broke through'),
    h('p', `${r.killed ?? r.count} zombies put down.`),
    r.injured.length ? h('p.bad', `Injured: ${r.injured.join(', ')}`) : h('p.good', 'Nobody was badly hurt.'),
    r.dead.length ? h('p.bad', `Lost: ${r.dead.join(', ')}`) : null,
    Object.keys(r.lost || {}).length ? h('p', 'Stolen: ', costList(r.lost, { have: false })) : null,
    h('div.mactions', h('button.btn.go', { onclick: () => ui.closeModal() }, 'Continue')),
  )
}
export function missionReportModal(ui, r) {
  return h(
    'div',
    h('h2', r.title || (r.result === 'extracted' ? 'Back home' : 'The run went wrong')),
    r.text ? h('p', r.text) : null,
    r.loot && Object.keys(r.loot).length ? h('section.card', h('h3', 'Brought back'), costList(r.loot, { have: false })) : null,
    r.items?.length ? h('section.card', h('h3', 'Items'), h('div.igrid', r.items.map((it) => itemCard(it, { owner: false })))) : null,
    r.lost?.length ? h('p.bad', `Left behind: ${r.lost.join(', ')}`) : null,
    r.injured?.length ? h('p.bad', `Injured: ${r.injured.join(', ')}`) : null,
    h('div.mactions', h('button.btn.go', { onclick: () => ui.closeModal() }, 'Continue')),
  )
}
// The coast answered: the end of the story (play can go on).
export function victoryModal(ui, held, close) {
  const alive = S.survivors.length
  return h(
    'div.gameover.victory',
    h('div.logo.big', 'THE COAST ANSWERED'),
    h('p', held ? `Dawn on day ${day()}. Headlights on the north road, then a column of armoured trucks from the coast. You held the last night, and the Signal brought them.` : `Dawn on day ${day()}. The camp is in ruins, but the trucks from the coast made it through. Everyone still standing is going home.`),
    h('div.statgrid', h('div.stat', h('span', 'Days'), h('b', day())), h('div.stat', h('span', 'Survivors'), h('b', alive)), h('div.stat', h('span', 'Zombies killed'), h('b', S.stats.kills)), h('div.stat', h('span', 'Supply runs'), h('b', S.stats.runs)), h('div.stat', h('span', 'Hordes held'), h('b', S.stats.raids)), h('div.stat', h('span', 'Recruited'), h('b', S.stats.recruited))),
    S.stats.memorial.length ? h('section.card', h('h3', 'They did not see it'), S.stats.memorial.slice(0, 10).map((m) => h('div.kv', h('span', `${m.name}, ${m.occ}`), h('small', `Day ${m.day} · ${m.cause}`)))) : null,
    h('p.note', 'You can stay: the camp keeps going, the dead keep coming, and there is always more to build.'),
    h('div.mactions', h('button.btn.go.big', { onclick: () => close() }, 'Stay with the camp'), h('button.btn.big.ghost', { onclick: () => ui.game.confirmNew() }, 'Start a new camp')),
  )
}
export function gameOverModal(ui) {
  return h(
    'div.gameover',
    h('h2', 'The camp has fallen'),
    h('p', `You held out for ${day()} days.`),
    h('div.statgrid', h('div.stat', h('span', 'Zombies killed'), h('b', S.stats.kills)), h('div.stat', h('span', 'Supply runs'), h('b', S.stats.runs)), h('div.stat', h('span', 'Hordes'), h('b', S.stats.raids)), h('div.stat', h('span', 'Recruited'), h('b', S.stats.recruited))),
    S.stats.memorial.length ? h('section.card', h('h3', 'In memory'), S.stats.memorial.slice(0, 12).map((m) => h('div.kv', h('span', `${m.name}, ${m.occ}`), h('small', `Day ${m.day} · ${m.cause}`)))) : null,
    h('div.mactions', h('button.btn.go.big', { onclick: () => ui.game.newGame() }, 'Start a new camp')),
  )
}
