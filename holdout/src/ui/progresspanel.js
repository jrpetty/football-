// The Progress panel: the milestone board (tiers 1-8), the Signal and its
// five phases, and the starter tasks that used to be the goals list.
import { RES, STATIONS, TIERS, MILESTONES, SIGNAL, GOALS, BELTS, FENCE, EXPANSIONS } from '../game/data.js'
import { S, canAfford, completeMilestone, msDone, tierLock, tierMilestones, campTier, mastOf, signalNeed, deliverSignal, claimGoal, signalCost, liberation, LIBERATION, DISTRICTS, isCleared, modeOf, MODES, placeLeft } from '../game/state.js'
import { signalLock } from '../game/story.js'
import { sfx } from '../core/audio.js'
import { h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { costList, bar, resIcon } from './common.js'

let tab = 'board'
export const showProgressTab = (t) => (tab = t)
const FLAG_TEXT = {
  autoBoost: 'Automated stations +50% speed',
  rations: 'The camp eats 15% less',
  cores: 'Power cores overclock stations',
  outposts: 'Claim outposts in the city',
  arsenal: 'Better quality from every bench',
  beacon: 'More and better newcomers',
}
// Short chips describing what a milestone unlocks.
function unlockChips(m) {
  const u = m.unlocks
  const out = []
  for (const t of u.stations || []) out.push(h('span.unl', { 'data-tip': `<b>${STATIONS[t].name}</b>${STATIONS[t].desc}` }, h('i', { html: icon('build') }), STATIONS[t].name))
  if (u.belt) out.push(h('span.unl', { 'data-tip': `<b>${BELTS[u.belt].name}</b>${BELTS[u.belt].desc}` }, h('i', { html: icon('belt') }), BELTS[u.belt].name))
  if (u.fence) out.push(h('span.unl', { 'data-tip': `<b>${FENCE[u.fence].name}</b>${FENCE[u.fence].hp} strength per section` }, h('i', { html: icon('shield') }), FENCE[u.fence].name))
  if (u.exp?.length) out.push(h('span.unl', { 'data-tip': u.exp.map((e) => EXPANSIONS.find((x) => x.id === e)?.name).join(', ') }, h('i', { html: icon('expand') }), `${u.exp.length} expansion${u.exp.length > 1 ? 's' : ''}`))
  for (const [st, r] of u.recipes || []) {
    const out1 = Object.keys(STATIONS[st].recipes[r].out)[0]
    out.push(h('span.unl', { 'data-tip': `${RES[out1].name} at the ${STATIONS[st].name}` }, h('i', { html: resIcon(out1) }), RES[out1].name))
  }
  for (const f of u.flags || []) out.push(h('span.unl', h('i', { html: icon('star') }), FLAG_TEXT[f] || f))
  return out
}

export function renderProgress(ui) {
  const tier = campTier()
  const tabs = h(
    'div.tabs',
    [['board', 'Milestones'], ['signal', 'The Signal'], ['city', 'The city'], ['tasks', 'Starter tasks']].map(([id, label]) => h('button' + (tab === id ? '.on' : ''), { onclick: () => ((tab = id), sfx('click'), ui.refreshPanel()) }, label)),
  )
  const body = tab === 'board' ? board(ui) : tab === 'signal' ? signal(ui) : tab === 'city' ? city(ui) : tasks(ui)
  const ph = S.signal.phase
  return ui.frame('Progress', h('span', `Tier ${tier} of ${TIERS.length - 1} · Signal phase ${ph} of ${SIGNAL.length}`), body, { icon: 'goals', tabs })
}

// Liberating the city: every place cleared of the infected, district by district.
function city(ui) {
  const L = liberation()
  const pct = (f) => `${Math.round(f * 100)}%`
  const next = LIBERATION.find((m) => !(S.libDone || []).includes(m.at))
  const M = MODES[modeOf()]
  const locs = (S.cityLocs || []).filter((l) => l.type !== 'military')
  return [
    h(
      'section.card.libhead',
      h('div.lib-top', h('div', h('b', 'Liberate the city'), h('small', 'Clear a place by leaving nothing alive inside when the squad gets out. An outpost keeps it clear; left alone, the infected may drift back.')), h('div.lib-pct', pct(L.pct))),
      h('div.libbar', h('i', { style: { width: pct(L.pct) } })),
      h('small.dim', `${L.cleared} of ${L.total} places clear${next ? ` · next: ${next.name} at ${pct(next.at)}` : ' · the city is yours'}`),
    ),
    h(
      'section.card',
      h('h3', 'Districts'),
      h(
        'div.libdist',
        Object.entries(L.by).map(([d, v]) =>
          h(
            'div.ld' + (v.cleared === v.total && v.total ? '.done' : ''),
            h('div.ld-top', h('b', DISTRICTS[d] || d), h('span', `${v.cleared} / ${v.total}`)),
            h('div.libbar.small', h('i', { style: { width: pct(v.total ? v.cleared / v.total : 0) } })),
            h('small', locs.filter((l) => l.district === d && !isCleared(l.id)).slice(0, 4).map((l) => l.name).join(', ') || 'All clear. The people hiding here came out with supplies.'),
          ),
        ),
      ),
    ),
    h(
      'section.card',
      h('h3', 'Rewards'),
      LIBERATION.map((m) => h('div.libm' + ((S.libDone || []).includes(m.at) ? '.have' : ''), h('span.lm-at', pct(m.at)), h('div', h('b', m.name), h('small', m.desc)))),
    ),
    h('section.card.modecard', h('h3', 'Game mode', h('small', M.name)), h('p.note', M.desc), modeOf() === 'once' ? h('small.dim', `${locs.filter((l) => placeLeft(l.id) != null && placeLeft(l.id) < 0.03).length} places emptied so far`) : null),
  ]
}

function board(ui) {
  const out = []
  for (let n = 1; n < TIERS.length; n++) {
    const T = TIERS[n]
    const lock = tierLock(n)
    const ids = tierMilestones(n)
    const done = ids.filter(msDone).length
    const all = done === ids.length
    out.push(
      h(
        'section.tier' + (lock ? '.locked' : all ? '.done' : '.open'),
        h('div.t-head', h('span.t-n', n), h('div', h('b', T.name), h('small', T.blurb)), h('span.t-st', lock ? h('span', h('i.inl', { html: icon('lock') }), lock) : `${done} / ${ids.length}`)),
        lock && n > campTier() + 2
          ? null
          : h(
              'div.t-ms',
              ids.map((id) => {
                const m = MILESTONES[id]
                const have = msDone(id)
                const ok = !lock && !have && canAfford(m.cost)
                return h(
                  'div.ms' + (have ? '.have' : ok ? '.ready' : ''),
                  h('div.ms-top', h('b', m.name), have ? h('span.ms-done', h('i', { html: icon('check') }), typeof S.milestones[id] === 'number' && S.milestones[id] > 0 ? `Day ${S.milestones[id]}` : 'Done') : null),
                  h('p', m.desc),
                  h('div.ms-unl', unlockChips(m)),
                  have
                    ? null
                    : h(
                        'div.ms-pay',
                        costList(m.cost, { small: true }),
                        h(
                          'button.btn.small' + (ok ? '.go' : ''),
                          {
                            disabled: !ok,
                            onclick: () => {
                              if (completeMilestone(id)) {
                                sfx('complete')
                                ui.toast(`Milestone: ${m.name}`, 'good')
                                ui.refreshPanel()
                                if (ui.dock && !ui.dock.hidden) ui.renderBuild()
                              } else sfx('error')
                            },
                          },
                          'Deliver',
                        ),
                      ),
                )
              }),
            ),
      ),
    )
  }
  return out
}

function signal(ui) {
  const ph = S.signal.phase
  const mast = mastOf()
  const out = []
  out.push(
    h(
      'section.card.sig-intro',
      h('p.desc', 'Old maps show a broadcast mast on the hill above the yard. Rebuild it in five phases, and when it can reach the coast, call for the evacuation. Each phase also opens new tiers on the milestone board.'),
      !mast
        ? h('p.note.bad', msDone('signal') ? 'Build the Signal Mast (Build menu, Camp tab) to start delivering.' : 'Deliver the Signal milestone (tier 2) to unlock the mast.')
        : h('p.note', 'Deliver by hand from storage, or run belts into the mast: every belted part counts the moment it lands.'),
    ),
  )
  SIGNAL.forEach((P, i) => {
    const done = i < ph
    const cur = i === ph
    const need = cur ? signalNeed() : null
    const lock = !done ? signalLock(i) : null
    const rows = Object.entries(cur ? signalCost(P) : P.cost).map(([k, v]) => {
      const paid = done ? v : cur ? v - need[k] : 0
      return h('div.sig-row', h('span.ci', { style: { '--c': RES[k].color } }, h('i.ic', { html: resIcon(k) }), RES[k].short || RES[k].name), bar(paid / v, done ? 'hp' : 'prod'), h('small', `${fmt(paid)} / ${fmt(v)}`), cur ? h('small.dim', `${fmt(S.res[k] || 0)} in store`) : null)
    })
    const opens = TIERS.map((T, n) => (T && T.phase === i + 1 ? n : 0)).filter(Boolean)
    out.push(
      h(
        'section.card.sig' + (done ? '.done' : cur ? '.cur' : '.later'),
        h('h3', h('span', `Phase ${i + 1}: ${P.name}`), h('small', done ? `Done${S.signal.at?.[i] ? ` · day ${S.signal.at[i]}` : ''}` : cur ? 'In progress' : 'Later')),
        h('p.note', P.desc),
        rows,
        lock ? h('p.note.bad', h('b', `${lock.short}. `), lock.text, ' ', h('a.link', { onclick: () => ui.openJournal() }, 'Open the journal')) : null,
        opens.length ? h('p.note', `Opens tier${opens.length > 1 ? 's' : ''} ${opens.join(' and ')}.`) : i === SIGNAL.length - 1 ? h('p.note', 'The final call. Hold on until the coast answers.') : null,
        cur && mast
          ? h(
              'div.kv',
              h('span', 'Hand over what storage has'),
              h('button.btn.small.go', { onclick: () => (deliverSignal() ? (sfx('build'), ui.toast('Delivered to the mast', 'good')) : (sfx('error'), ui.toast('Nothing in storage the mast still needs', 'bad')), ui.refreshPanel()) }, 'Deliver'),
            )
          : null,
      ),
    )
  })
  return out
}

function tasks(ui) {
  const rows = GOALS.map((g) => {
    const st = S.goals[g.id]
    return h('div.goal' + (st === 'claimed' ? '.claimed' : st === 'done' ? '.done' : ''), h('i', { html: icon(st ? 'check' : 'goals') }), h('div.g-main', h('b', g.text), costList(g.reward, { small: true, have: false })), st === 'done' ? h('button.btn.go.small', { onclick: () => (claimGoal(g.id), sfx('coin'), ui.refreshPanel()) }, 'Claim') : null)
  })
  const done = GOALS.filter((g) => S.goals[g.id]).length
  return [h('p.note', `Small jobs to learn the ropes, with a reward each. ${done} of ${GOALS.length} done.`), ...rows]
}
