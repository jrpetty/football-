// The records: this camp's numbers, the dead put down by each weapon, who
// has done the most, the bests of every camp you've run, and achievements.
import { S, day } from '../game/state.js'
import { ITEMS } from '../game/data.js'
import { ACHIEVEMENTS, records } from '../game/achievements.js'
import { h, fmt } from '../core/util.js'
import { bar } from './common.js'

export function renderRecords(ui) {
  const st = S.stats
  const R = records()
  const got = ACHIEVEMENTS.filter((a) => S.ach?.[a.id])
  const stat = (label, v) => h('div.stat', h('span', label), h('b', fmt(v || 0)))
  const weapons = Object.entries(st.weaponKills || {}).sort((a, b) => b[1] - a[1])
  const top = weapons[0]?.[1] || 1
  // the living and the dead, by the dead they put down
  const people = [...S.survivors.map((s) => ({ name: s.name, kills: s.kills || 0, alive: true })), ...(st.memorial || []).map((m) => ({ name: m.name, kills: m.kills || 0, alive: false }))].sort((a, b) => b.kills - a.kills).slice(0, 6)
  return ui.frame(
    'Records',
    `Day ${day()} · ${got.length} of ${ACHIEVEMENTS.length} achievements`,
    [
      h('div.statgrid.records', stat('Days', day()), stat('Survivors', S.survivors.length), stat('Dead put down', st.kills), stat('Supply runs', st.runs), stat('Hordes held', (st.raids || 0) - (st.raidsLost || 0)), stat('Lost', st.deaths), stat('Taken in', st.recruited), stat('Crafted', st.crafted), stat('Silent kills', st.takedowns), stat('Feasts', st.feasts)),
      weapons.length
        ? h(
            'section.card',
            h('h3', 'By weapon'),
            ...weapons.slice(0, 10).map(([id, v]) => h('div.recrow', h('span', ITEMS[id]?.name || id), bar(v / top, id === 'pan' ? 'good' : ''), h('b', fmt(v)))),
          )
        : null,
      people.length && people[0].kills ? h('section.card', h('h3', 'Who did the most'), ...people.filter((p) => p.kills).map((p) => h('div.kv', h('span' + (p.alive ? '' : '.dim'), p.name, p.alive ? null : h('small', ' · remembered')), h('b', fmt(p.kills))))) : null,
      h('section.card', h('h3', 'Every camp you’ve run'), h('div.kv', h('span', 'Longest held'), h('b', `${R.bestDays || day()} days`)), h('div.kv', h('span', 'Most of the dead put down'), h('b', fmt(R.mostKills || st.kills || 0))), h('div.kv', h('span', 'Biggest camp'), h('b', `${R.mostPeople || S.survivors.length} people`)), h('div.kv', h('span', 'Achievements ever'), h('b', `${Object.keys(R.ach || {}).length} of ${ACHIEVEMENTS.length}`))),
      h(
        'section.card',
        h('h3', 'Achievements', h('small', `${got.length}/${ACHIEVEMENTS.length}`)),
        h(
          'div.achgrid',
          ...ACHIEVEMENTS.map((a) => {
            const when = S.ach?.[a.id]
            let p = [0, 1]
            try {
              p = a.prog()
            } catch {}
            const ever = !when && R.ach?.[a.id]
            return h('div.ach' + (when ? '.got' : ever ? '.ever' : ''), h('b', a.name), h('span', a.desc), when ? h('small.good', `Day ${when}`) : ever ? h('small', 'Earned in another camp') : h('div.achbar', bar(Math.min(1, p[0] / p[1])), h('small', `${fmt(Math.min(p[0], p[1]))}/${fmt(p[1])}`)))
          }),
        ),
      ),
    ],
    { icon: 'star' },
  )
}
