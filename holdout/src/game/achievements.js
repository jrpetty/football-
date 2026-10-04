// Achievements, and the records kept across every camp you have run. A
// camp's own are in S.ach (the day each was earned); the best of all time
// live in this browser (localStorage), so a fallen camp still counts.
import { S, day, ageGroup, liberation } from './state.js'
import { bus } from '../core/util.js'

const wk = (id) => S.stats.weaponKills?.[id] || 0
const n = (k) => S.stats[k] || 0
const has = (type) => S.stations.some((s) => s.type === type && s.level > 0)

// test() says whether it is earned; prog() how far along, as [have, need]
export const ACHIEVEMENTS = [
  { id: 'week', name: 'A Week Behind the Wall', desc: 'Survive 7 days.', prog: () => [day(), 7] },
  { id: 'month', name: 'Thirty Sunrises', desc: 'Survive 30 days.', prog: () => [day(), 30] },
  { id: 'season', name: 'All Four Seasons', desc: 'Survive a whole year: 24 days.', prog: () => [day(), 24] },
  { id: 'hundred', name: 'The Long Haul', desc: 'Survive 100 days.', prog: () => [day(), 100] },
  { id: 'kills100', name: 'Hundred Down', desc: 'Put down 100 of the dead.', prog: () => [n('kills'), 100] },
  { id: 'kills1000', name: 'Grim Tally', desc: 'Put down 1,000 of the dead.', prog: () => [n('kills'), 1000] },
  { id: 'pan', name: 'Kitchen Justice', desc: 'Put down 10 of the dead with a frying pan.', prog: () => [wk('pan'), 10] },
  { id: 'fists', name: 'Bare Knuckles', desc: 'Put down 10 of the dead with bare hands.', prog: () => [wk('fists'), 10] },
  { id: 'crossbow', name: 'Quiet as the Grave', desc: 'Put down 25 with a crossbow.', prog: () => [wk('crossbow'), 25] },
  { id: 'brutes', name: 'Giant Killer', desc: 'Put down 10 brutes.', prog: () => [n('brutes'), 10] },
  { id: 'ghost', name: 'Ghost', desc: 'Take 10 of the dead from behind without a sound.', prog: () => [n('takedowns'), 10] },
  { id: 'door', name: 'Hold the Door', desc: 'Lean on a shut door while the dead bash at it.', prog: () => [n('braced'), 1] },
  { id: 'runs10', name: 'Regular Runner', desc: 'Come home from 10 supply runs.', prog: () => [n('runs'), 10] },
  { id: 'runs50', name: 'Knows Every Street', desc: 'Come home from 50 supply runs.', prog: () => [n('runs'), 50] },
  { id: 'raid', name: 'The Wall Held', desc: 'Survive a horde.', prog: () => [n('raids') - n('raidsLost'), 1] },
  { id: 'raids10', name: 'Siege Veterans', desc: 'Hold the wall against 10 hordes.', prog: () => [n('raids') - n('raidsLost'), 10] },
  { id: 'blood', name: 'Under the Blood Moon', desc: 'Hold the wall through a Blood Moon.', prog: () => [n('bloodMoons'), 1] },
  { id: 'pop10', name: 'A Real Camp', desc: '10 survivors at once.', prog: () => [S.survivors.length, 10] },
  { id: 'pop25', name: 'A Village', desc: '25 survivors at once.', prog: () => [S.survivors.length, 25] },
  { id: 'child', name: 'The Next Generation', desc: 'Take in a child.', prog: () => [S.survivors.some((s) => ageGroup(s) === 'child') ? 1 : 0, 1] },
  { id: 'elder', name: 'Old Hands', desc: 'Take in someone over 65.', prog: () => [S.survivors.some((s) => ageGroup(s) === 'elder') ? 1 : 0, 1] },
  { id: 'unscathed', name: 'Nobody Left Behind', desc: 'Reach day 20 without losing anyone.', prog: () => [n('deaths') ? 0 : Math.min(day(), 20), 20] },
  { id: 'feast', name: 'Like Old Times', desc: 'Hold a feast after a big haul.', prog: () => [n('feasts'), 1] },
  { id: 'memorial', name: 'We Remember', desc: 'Raise the memorial wall.', prog: () => [has('memorial') ? 1 : 0, 1] },
  { id: 'crafted', name: 'The Workshop Never Sleeps', desc: 'Craft 50 things.', prog: () => [n('crafted'), 50] },
  { id: 'cleared', name: 'Taking It Back', desc: 'Clear a place in the city of the dead.', prog: () => [liberation().cleared, 1] },
  { id: 'mast', name: 'Someone Out There', desc: 'Raise the Signal Mast.', prog: () => [has('mast') ? 1 : 0, 1] },
]
export const achById = (id) => ACHIEVEMENTS.find((a) => a.id === id)

const KEY = 'holdout.records'
export function records() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '{}') || {}
  } catch {
    return {}
  }
}
function saveRecords(r) {
  try {
    localStorage.setItem(KEY, JSON.stringify(r))
  } catch {}
}

// Every few seconds: anything newly earned, and the all-time bests.
export function checkAchievements() {
  if (!S?.stats) return []
  S.ach ||= {}
  const got = []
  for (const a of ACHIEVEMENTS) {
    if (S.ach[a.id]) continue
    let ok = false
    try {
      const [have, need] = a.prog()
      ok = have >= need
    } catch {}
    if (!ok) continue
    S.ach[a.id] = day()
    got.push(a)
  }
  const r = records()
  const was = JSON.stringify(r)
  r.bestDays = Math.max(r.bestDays || 0, day())
  r.mostKills = Math.max(r.mostKills || 0, S.stats.kills || 0)
  r.mostPeople = Math.max(r.mostPeople || 0, S.survivors.length)
  r.ach ||= {}
  for (const id of Object.keys(S.ach)) r.ach[id] ||= Date.now()
  if (JSON.stringify(r) !== was) saveRecords(r)
  for (const a of got) bus.emit('achievement', a)
  return got
}
