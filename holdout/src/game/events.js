// Camp life between the fighting: birthdays, a feast after a big haul, a
// funeral for someone lost, and the camp's say on a stranger at the gate.
// The ones that need a decision wait in S.happen until the player makes it
// (from the brief) or the moment passes.
import { S, day, addMoraleEvent, log, pay, canAfford } from './state.js'
import { SEASON_DAYS, OCCUPATIONS, DAY_MIN } from './data.js'
import { bus, uid, pick } from '../core/util.js'
import { rivalStale } from './rivals.js'

const YEAR = SEASON_DAYS * 4
const hash = (s) => {
  let x = 2166136261
  for (let i = 0; i < s.length; i++) x = Math.imul(x ^ s.charCodeAt(i), 16777619)
  return x >>> 0
}
// what each one costs
export const FEAST = { food: 18, water: 10, wood: 6 }
export const FUNERAL = { wood: 12, cloth: 3 }
const CAKE = { food: 3 }

export function birthdayOf(s) {
  return 1 + (hash(s.id) % YEAR)
}
const dayOfYear = (d = day()) => ((d - 1) % YEAR) + 1

function addHappening(e) {
  S.happen ||= []
  S.happen.unshift({ id: uid('hp'), at: S.time, expires: S.time + DAY_MIN * 0.75, ...e })
  S.happen = S.happen.slice(0, 8)
  bus.emit('happening', S.happen[0])
}
export const pendingHappenings = () => (S.happen || []).filter((e) => !e.done && e.expires > S.time)

// ---------------------------------------------------------------- new day
// Called when a day begins (game/economy.js).
export function campDay() {
  const d = dayOfYear()
  for (const s of S.survivors) {
    if (birthdayOf(s) !== d || s.bdayDone === day() || s.status === 'mission') continue
    s.bdayDone = day()
    s.age = (s.age || 30) + 1
    // the cook finds something sweet if there's food to spare
    const kitchen = S.stations.some((st) => st.type === 'kitchen' && st.active)
    const cake = kitchen && canAfford(CAKE) && S.res.food > S.survivors.length * 4 && pay(CAKE)
    addMoraleEvent(`${s.first}'s birthday`, cake ? 4 : 2, 1)
    log(`It's ${s.first}'s birthday: ${s.age} today.${cake ? ' The cook made something that was nearly a cake.' : ' Somebody found a candle.'}`, 'good')
    if (s.age === 16) log(`${s.first} is sixteen: old enough to go out on runs and stand at the wall now.`, 'story')
    if (s.age === 65) log(`${s.first} is sixty-five. The others won't let them on the runs any more.`, 'story')
    bus.emit('birthday', s)
  }
  rivalStale()
  // let go the decisions nobody made
  for (const e of S.happen || []) if (!e.done && e.expires <= S.time) e.done = 'passed'
}

// ---------------------------------------------------------------- feast
// A run came home heavy: offer to eat well tonight.
export function offerFeast(loot) {
  const food = (loot.food || 0) + (loot.water || 0) * 0.5
  const total = Object.values(loot || {}).reduce((a, b) => a + (b || 0), 0)
  if (food < 25 && total < 140) return
  if (pendingHappenings().some((e) => e.kind === 'feast')) return
  if ((S.lastFeast || -99) > day() - 3) return
  addHappening({ kind: 'feast', text: 'A big haul came home', sub: 'Throw a feast tonight?' })
}
export function holdFeast(e) {
  if (!pay(FEAST)) return false
  e.done = 'held'
  S.lastFeast = day()
  S.stats.feasts = (S.stats.feasts || 0) + 1
  addMoraleEvent('A feast after the haul', 9, 2)
  log('The camp ate like it used to. Someone found a guitar with four strings.', 'good')
  bus.emit('feast')
  return true
}

// ---------------------------------------------------------------- funeral
// Someone died: offer a proper goodbye.
export function offerFuneral(s, cause) {
  addHappening({ kind: 'funeral', sid: s.id, name: s.first, full: s.name, cause, text: `${s.first} is gone`, sub: 'Hold a funeral?', expires: S.time + DAY_MIN * 1.5 })
}
export function holdFuneral(e) {
  if (!pay(FUNERAL)) return false
  e.done = 'held'
  S.stats.funerals = (S.stats.funerals || 0) + 1
  // the grief is still there, but shared
  const ev = S.moraleEvents.find((x) => x.text === `${e.name} died` || x.text === `${e.name} turned`)
  if (ev) ev.amount = Math.round(ev.amount * 0.5)
  addMoraleEvent(`Said goodbye to ${e.name}`, 3, 1.5)
  log(`The camp buried ${e.full} at sundown. ${pick(['Nobody said much. Nobody needed to.', 'Someone read something from a book with no cover.', 'They told the story about the dog. Everyone laughed, then nobody did.'])}`, 'story')
  bus.emit('funeral', e)
  return true
}

// ---------------------------------------------------------------- the vote
// Everyone in camp says yes or no to the stranger, and why. Fixed for that
// stranger (asking twice gets the same answer).
export function campVote(p) {
  if (p.vote) return p.vote
  const s = p.s
  const food = (S.res.food || 0) / Math.max(1, S.survivors.length)
  const grief = S.moraleEvents.some((e) => / (died|turned)$/.test(e.text) && e.until > S.time)
  const need = !S.survivors.some((x) => x.occ === s.occ)
  const lines = []
  for (const v of S.survivors) {
    if (v.status === 'mission') continue
    let k = (hash(v.id + s.id) % 1000) / 1000 - 0.5
    const why = []
    if (food < 3) {
      k -= 0.55
      why.push(['no', 'We can’t feed who we’ve got.'])
    } else if (food > 8) {
      k += 0.2
      why.push(['yes', 'There’s food enough.'])
    }
    if (need) {
      k += 0.35
      why.push(['yes', `We could use a ${OCCUPATIONS[s.occ].name.toLowerCase()}.`])
    }
    if (grief) {
      k -= 0.25
      why.push(['no', 'Not after what happened. Not yet.'])
    }
    if ((v.traits || []).includes('coward')) {
      k -= 0.3
      why.push(['no', 'I don’t like the look of them.'])
    }
    k += ((S.morale ?? 50) - 50) / 120
    const yes = k > 0
    const own = why.filter(([w]) => (w === 'yes') === yes)
    const generic = yes ? ['Everyone deserves a chance.', 'Another pair of hands.', 'Another gun on the wall.', 'Someone let me in once.'] : ['Too many strangers lately.', 'Who knows where they’ve been.', 'Check them for bites first.', 'We don’t know them.']
    const text = pick([...own.map((w) => w[1]), ...own.map((w) => w[1]), ...generic])
    lines.push({ id: v.id, name: v.first, yes, text })
  }
  const yes = lines.filter((l) => l.yes).length
  p.vote = { yes, no: lines.length - yes, lines }
  return p.vote
}
// The gate decided: going with the camp lifts it; overruling it stings.
export function afterVote(p, letIn) {
  const v = p?.vote
  if (!v || v.yes === v.no) return
  const majority = v.yes > v.no
  if (majority === letIn) addMoraleEvent('The camp had its say', 3, 1)
  else addMoraleEvent('Overruled at the gate', -4, 1.5)
}

// For the brief: what's waiting on a decision.
export function happeningLabel(e) {
  if (e.kind === 'feast') return { text: e.text, sub: `${e.sub} (${costText(FEAST)})` }
  if (e.kind === 'funeral') return { text: e.text, sub: `${e.sub} (${costText(FUNERAL)})` }
  return { text: e.text, sub: e.sub }
}
const costText = (c) =>
  Object.entries(c)
    .map(([k, v]) => `${v} ${k}`)
    .join(', ')

// Once, at start-up: birthdays with the new day, a funeral offered for the dead.
let wired = false
export function wireCampEvents() {
  if (wired) return
  wired = true
  bus.on('newDay', () => S && !S.over && campDay())
  bus.on('death', (s) => {
    if (!S || S.over) return
    offerFuneral(s, S.stats.memorial?.[0]?.cause || '')
  })
}
