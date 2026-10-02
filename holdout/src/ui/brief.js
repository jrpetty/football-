// The camp brief, top right: what needs you now (problems, each with the fix
// one click away) and what to do next (the guided first day for a new camp,
// then a starter task, the next milestone and a place to clear).
import { S, day, canControl, campTier, tierMilestones, tierLock, msDone, claimGoal, liberation, isCleared, capOf, gameDur } from '../game/state.js'
import { GOALS, MILESTONES, INFECTION, RES, STATIONS, LOCATIONS } from '../game/data.js'
import { dailyNeeds, upkeepNeeds, campFlow, raidIntel, power, powerNeed } from '../game/economy.js'
import { h, fmt } from '../core/util.js'
import { sfx } from '../core/audio.js'
import { plural } from './common.js'
import { showProgressTab } from './progresspanel.js'

// ---------------------------------------------------------------- the first day
// Four steps, each one a starter task, each pointing at what to press. (The
// camp starts with someone on the farm, so feeding it is already done.)
const bench = (ui) => {
  const st = S.stations.find((x) => x.type === 'workbench' && !x.building)
  if (st) ui.openStation(st.id)
}
export const GUIDE = [
  { goal: 'buildFilter', title: 'Clean water', text: 'Everyone drinks every day and the rain barrel will not keep up. Press Build and put down a Water Filter.', nav: 'build' },
  { goal: 'firstRun', title: 'Your first run', text: 'Open the map and send two or three people to a house nearby. Search what you can, carry it to the van and get out before dark.', nav: 'map' },
  { goal: 'craft', title: 'Arm them', text: 'Click the Workbench and queue a weapon. Better gear makes every fight shorter.', show: bench },
  { goal: 'surviveHorde', title: 'Hold the wall', text: 'The horde comes at night. Everyone in camp takes the wall: select a defender and right-click to move them to a gap.', nav: null },
]
export const guideOn = () => !!S && !S.guideOff && !S.mp && day() <= 4 && GUIDE.some((g) => !S.goals?.[g.goal])
const guideStep = () => GUIDE.findIndex((g) => !S.goals?.[g.goal])

// ---------------------------------------------------------------- needs you
export function alerts(ui) {
  if (!S) return []
  const out = []
  const g = ui.game
  const need = dailyNeeds()
  const flow = campFlow()
  for (const k of ['food', 'water']) {
    const net = (flow[k] || 0) - need[k]
    if (net >= 0) continue
    const days = (S.res[k] || 0) / -net
    if (days < 3) out.push({ sev: days < 1.2 ? 'bad' : 'warn', key: k, text: `${RES[k].name} runs out in ${days < 1 ? gameDur(days * 1440) : `${days.toFixed(1)} days`}`, sub: `${fmt(-net)} more used than made each day`, act: 'Production', fn: () => ui.openProduction() })
  }
  const up = upkeepNeeds()
  const short = Object.entries(up).filter(([k, v]) => (S.res[k] || 0) < v).map(([k]) => RES[k]?.name.toLowerCase())
  if (short.length) out.push({ sev: 'warn', key: 'upkeep', text: `Upkeep short: ${short.slice(0, 3).join(', ')}`, sub: 'Stations work slower until it is paid', act: 'Production', fn: () => ui.openProduction() })
  for (const s of S.survivors) {
    if ((s.infection || 0) >= INFECTION.sick && canControl(s)) out.push({ sev: 'bad', key: 'inf' + s.id, text: `${s.first} is infected (${Math.round(s.infection)}%)`, sub: 'An antiviral cures it below 60', act: 'Sheet', fn: () => ui.openSurvivor(s.id) })
  }
  const I = raidIntel()
  if (I && I.in < 240) {
    const defenders = S.survivors.filter((s) => s.status === 'ok').length
    out.push({ sev: I.in < 90 ? 'bad' : 'warn', key: 'horde', text: `${I.known ? I.name : 'A horde'} in ${gameDur(I.in)}`, sub: `${plural(defenders, 'defender')}${I.count ? ` against ${I.count}` : ''}`, act: 'Intel', fn: () => ui.openHorde() })
  }
  const hp = S.fence?.hp || []
  if (hp.length) {
    const max = Math.max(1, ...hp, 1)
    const avg = hp.reduce((a, b) => a + b, 0) / hp.length / max
    if (avg < 0.6 && S.fence.level > 0) out.push({ sev: avg < 0.35 ? 'bad' : 'warn', key: 'wall', text: `The wall is at ${Math.round(avg * 100)}%`, sub: 'Repair it before the next horde', act: 'Wall', fn: () => ui.openFence() })
  }
  const P = power()
  const dark = S.stations.filter((st) => powerNeed(st) > 0 && !P.powered.has(st.id))
  if (dark.length) out.push({ sev: 'warn', key: 'power', text: `${plural(dark.length, 'station')} without power`, sub: dark.slice(0, 2).map((st) => STATIONS[st.type].name).join(', '), act: 'Power', fn: () => ui.openPower() })
  const stopped = (S.links || []).filter((l) => !P.powered.has(l.id))
  if (stopped.length) out.push({ sev: 'bad', key: 'beltpower', text: `${plural(stopped.length, 'belt')} stopped: no power`, sub: `They need ${(P.beltNeed || 0).toFixed(1)} power between them`, act: 'Power', fn: () => ui.openPower() })
  if ((S.morale ?? 50) < 30) out.push({ sev: S.morale < 18 ? 'bad' : 'warn', key: 'morale', text: `Morale is low (${Math.round(S.morale)})`, sub: S.morale < 18 ? 'People will start leaving' : 'Beds, hot food and the fire help', act: 'Morale', fn: () => ui.openMorale() })
  if (S.recruit?.pending) out.push({ sev: 'info', key: 'gate', text: 'Someone is at the gate', sub: S.recruit.pending.s?.name || 'They want to join', act: 'Meet', fn: () => ui.showRecruit() })
  const done = GOALS.filter((x) => S.goals?.[x.id] === 'done')
  if (done.length) out.push({ sev: 'good', key: 'claim', text: `${plural(done.length, 'task')} done`, sub: 'Rewards waiting', act: 'Claim', fn: () => (done.forEach((x) => claimGoal(x.id)), sfx('coin'), ui.toast(`Claimed ${plural(done.length, 'reward')}`, 'good')) })
  const idle = S.survivors.filter((s) => s.status === 'ok' && !s.job && canControl(s))
  if (idle.length >= 2 && !S.stations.some((st) => st.building)) out.push({ sev: 'info', key: 'idle', text: `${idle.length} ${idle.length === 1 ? 'person' : 'people'} with no job`, sub: 'They forage the yard; a job pays more', act: 'Crew', fn: () => ui.openCrew() })
  const full = ['food', 'water', 'wood', 'scrap', 'metal'].filter((k) => (S.res[k] || 0) >= capOf(k) - 0.5)
  if (full.length) out.push({ sev: 'info', key: 'full', text: `Storage full: ${full.map((k) => RES[k].name.toLowerCase()).join(', ')}`, sub: 'Build more storage or put it to use', act: 'Build', fn: () => ui.toggleBuild(true) })
  const ev = (S.events || []).find((e) => e.expires > S.time)
  if (ev) {
    const loc = S.cityLocs?.find((l) => l.id === ev.locId)
    out.push({ sev: 'info', key: 'ev', text: ev.kind === 'distress' ? `Distress call at ${loc?.name || 'a place in the city'}` : `Supply drop at ${loc?.name || 'a place in the city'}`, sub: `Fades in ${gameDur(ev.expires - S.time)}`, act: 'Map', fn: () => g.openMap?.() })
  }
  const order = { bad: 0, warn: 1, good: 2, info: 3 }
  return out.sort((a, b) => order[a.sev] - order[b.sev])
}

// ---------------------------------------------------------------- next up
export function nextUp(ui) {
  if (!S) return []
  const out = []
  const g = ui.game
  const goal = GOALS.find((x) => !S.goals?.[x.id])
  if (goal) out.push({ kind: 'Starter task', text: goal.text, fn: () => (showProgressTab('tasks'), ui.openProgress()) })
  // the next milestone in reach
  for (let n = 1; n <= Math.min(8, campTier() + 1); n++) {
    if (tierLock(n)) continue
    const id = tierMilestones(n).find((m) => !msDone(m))
    if (!id) continue
    const M = MILESTONES[id]
    const have = Object.entries(M.cost).reduce((a, [k, v]) => a + Math.min(1, (S.res[k] || 0) / v), 0) / Object.keys(M.cost).length
    out.push({ kind: `Tier ${n} milestone`, text: M.name, sub: `${Math.round(have * 100)}% of the cost in store`, fn: () => (showProgressTab('board'), ui.openProgress()) })
    break
  }
  // a place to clear, nearest first, at a level the camp can handle
  const L = liberation()
  if (L.total && S.stats?.runs) {
    const cand = (S.cityLocs || []).filter((l) => l.type !== 'military' && !isCleared(l.id) && l.level <= Math.min(5, 1 + campTier()))
    const known = cand.filter((l) => S.places?.[l.id]?.left != null).sort((a, b) => S.places[a.id].left - S.places[b.id].left)
    const pick = known[0] || cand.sort((a, b) => a.level - b.level)[0]
    if (pick) out.push({ kind: `Liberation · ${Math.round(L.pct * 100)}%`, text: `Clear ${pick.name}`, sub: S.places?.[pick.id]?.left != null ? `${S.places[pick.id].left} infected left inside` : `${LOCATIONS[pick.type].name}, level ${pick.level}`, fn: () => g.openMap?.(pick.id) })
  }
  return out.slice(0, 3)
}

// ---------------------------------------------------------------- the widget
export function renderBrief(ui) {
  const el = ui.briefEl
  if (!el || !S || S.over) return
  const A = alerts(ui)
  const N = nextUp(ui)
  const gi = guideOn() ? guideStep() : -1
  const open = S.briefOpen !== false
  const sig = JSON.stringify([open, gi, A.map((a) => [a.key, a.sev, a.text, a.sub]), N.map((n) => [n.text, n.sub])])
  // the button the guide points at glows
  for (const b of ui.nav?.querySelectorAll('[data-nav]') || []) b.classList.toggle('coach', gi >= 0 && b.dataset.nav === GUIDE[gi].nav)
  if (sig === ui.briefSig) return
  ui.briefSig = sig
  const bad = A.filter((a) => a.sev === 'bad').length
  el.innerHTML = ''
  el.append(
    h(
      'button.br-head',
      { onclick: () => ((S.briefOpen = !open), sfx('click'), renderBrief(ui)) },
      h('b', gi >= 0 ? 'First day' : 'Camp brief'),
      A.length ? h('span.br-count' + (bad ? '.bad' : ''), A.length) : null,
      h('i', open ? '▾' : '▸'),
    ),
  )
  if (!open) return
  if (gi >= 0) {
    const G = GUIDE[gi]
    el.append(
      h(
        'div.br-guide',
        h('div.bg-steps', GUIDE.map((_, k) => h('i' + (k < gi ? '.done' : k === gi ? '.on' : '')))),
        h('small', `Step ${gi + 1} of ${GUIDE.length}`),
        h('b', G.title),
        h('p', G.text),
        h('div.bg-acts', G.nav || G.show ? h('button.btn.small.go', { onclick: () => (G.show ? (sfx('click'), G.show(ui)) : ui.navClick(G.nav)) }, 'Show me') : null, h('button.btn.small.ghost', { onclick: () => ((S.guideOff = true), sfx('click'), renderBrief(ui)) }, 'Skip the guide')),
      ),
    )
  }
  if (A.length) {
    el.append(
      h('div.br-sec', h('small', 'Needs you')),
      ...A.slice(0, 5).map((a) => h('button.br-row.' + a.sev, { onclick: () => (sfx('click'), a.fn()) }, h('i.dot'), h('span', h('b', a.text), a.sub ? h('small', a.sub) : null), h('em', a.act))),
    )
    if (A.length > 5) el.append(h('small.br-more', `and ${A.length - 5} more`))
  }
  if (gi < 0 && N.length)
    el.append(
      h('div.br-sec', h('small', 'Next up')),
      ...N.map((n) => h('button.br-row.next', { onclick: () => (sfx('click'), n.fn()) }, h('i.dot'), h('span', h('small.k', n.kind), h('b', n.text), n.sub ? h('small', n.sub) : null))),
    )
}
