// The story layer: threads that open as the camp grows, clues that narrow
// down where someone or something is, people to rescue and key items to
// recover, lore notes found on runs, and the two Signal phases that wait on
// the story. Nothing here tells the player exactly where to go: a thread
// starts with a district or a rumour, and runs, notes and the radio narrow
// it down.
import { THREADS, NOTES, STORY_PEOPLE, DISTRICT_NAMES } from './storydata.js'
import { LOCATIONS, RESEARCH } from './data.js'
import { S, day, log, makeSurvivor, randomLook, addMoraleEvent, vehicleOf, addVehicle, gain, countType, msDone, getS } from './state.js'
import { bus, clamp } from '../core/util.js'

const KESSLER_NEEDED = 3
export function storyState() {
  if (!S.story) S.story = { threads: {}, notes: [], unread: [], keys: {}, flags: {}, personal: [], nextPersonal: 9 }
  return S.story
}
const locs = () => (S.cityLocs || []).filter((l) => !l.minor)
const locOf = (id) => locs().find((l) => l.id === id) || null
export const locName = (id) => locOf(id)?.name || 'somewhere in the city'
const districtOf = (id) => locOf(id)?.district || 'residential'
// Seeded choices so a camp's story lands in the same places every time.
function rng(key) {
  let h = 2166136261 ^ ((S.seed || 1) >>> 0)
  for (const c of key) h = Math.imul(h ^ c.charCodeAt(0), 16777619)
  let a = h >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const pickFrom = (r, list) => list[Math.floor(r() * list.length)]
const fill = (text) => text.replace('{police}', locName(policeLoc())).replace('{hospital}', locName(hospitalLoc()))
const policeLoc = () => (locs().find((l) => l.type === 'police') || locs().find((l) => l.type === 'firestation') || locs().find((l) => l.level >= 4))?.id
const hospitalLoc = () => (locs().find((l) => l.type === 'hospital') || locs().find((l) => l.type === 'pharmacy'))?.id
const militaryLoc = () => (locs().find((l) => l.type === 'military') || locs().find((l) => l.level === 5))?.id

export const thread = (id) => storyState().threads[id] || null
export const threadActive = (id) => !!thread(id) && !thread(id).done
export function startThread(id, extra = {}) {
  const st = storyState()
  if (st.threads[id]) return st.threads[id]
  const t = (st.threads[id] = { stage: 0, started: day(), clues: [], cands: null, checked: [], ...extra })
  log(`New in the journal: ${THREADS[id].name}.`, 'story')
  bus.emit('story', id)
  return t
}
function addClue(t, text, cands) {
  if (!t.clues.includes(text)) {
    t.clues.push(text)
    log(text, 'story')
  }
  if (cands) t.cands = cands.filter((id) => !t.checked.includes(id) || id === t.target)
  bus.emit('story')
}
function finish(id, text) {
  const t = thread(id)
  if (!t || t.done) return
  t.done = day()
  t.cands = null
  log(text || `Story complete: ${THREADS[id].name}.`, 'good')
  addMoraleEvent(THREADS[id].name, 6, 1.5)
  bus.emit('story', id)
}

// ---------------------------------------------------------------- the threads
// Checked every in-game hour: threads open, timed clues arrive, finished
// goals close.
export function tickStory() {
  if (!S || !S.cityLocs?.length) return
  const st = storyState()
  const radio = countType('radio') > 0
  const d = day()
  // Wheels: the dead van
  if (!st.threads.wheels) startThread('wheels')
  if (threadActive('wheels') && vehicleOf('van') && !vehicleOf('van').broken) finish('wheels', 'The van runs. Further places are within reach now, on a lot less food and water.')
  // The Voice on the Radio: Dana Okoye
  if (!st.threads.dana && (d >= 4 || radio || msDone('signal'))) {
    const r = rng('dana')
    const kitchens = locs().filter((l) => ['diner', 'supermarket', 'school'].includes(l.type))
    const target = pickFrom(r, kitchens.length ? kitchens : locs().filter((l) => l.level <= 3)).id
    const t = startThread('dana', { target, npc: storyPerson('dana') })
    const dist = districtOf(target)
    addClue(t, `Dana says she can see the mast from where she is hiding. That puts her somewhere in ${DISTRICT_NAMES[dist] || 'the city'}.`, locs().filter((l) => l.district === dist && l.type !== 'military').map((l) => l.id))
  }
  const dana = thread('dana')
  if (dana && !dana.done && dana.stage === 0 && !dana.kitchen && d >= dana.started + (radio ? 3 : 5)) danaKitchen(radio ? 'The radio again, fainter: "...the walk-in is keeping the food cold. The generator won\'t last..." A walk-in fridge: a diner, a school kitchen or a supermarket.' : 'A newcomer at the gate heard Dana\'s broadcast too: "She said something about a walk-in fridge." A diner, a school kitchen or a supermarket.')
  // Kessler's Secret
  const kNotes = st.notes.filter((n) => NOTES[n]?.thread === 'kessler').length
  if (!st.threads.kessler && (kNotes > 0 || d >= 18)) startThread('kessler')
  const ks = thread('kessler')
  if (ks && !ks.done && ks.stage === 0 && (kNotes >= KESSLER_NEEDED || st.notes.includes('kessler5'))) {
    ks.stage = 1
    ks.target = hospitalLoc()
    addClue(ks, `The Kessler files agree: Dr. Imre moved the KX-9 reference samples to ${locName(ks.target)}, sub-level B, cold room 12.`, [ks.target])
  }
  // Harbor Light: the codebook
  if (!st.threads.codebook && ((S.signal?.phase || 0) >= 2 || st.notes.includes('evac2'))) {
    const t = startThread('codebook', { target: policeLoc() })
    addClue(t, 'Colonel Hart was last heard on the police band, falling back somewhere he could hold.', locs().filter((l) => ['police', 'firestation', 'hospital'].includes(l.type)).map((l) => l.id))
  }
  const cb = thread('codebook')
  if (cb && !cb.done && cb.stage === 0 && !cb.hartKnown && d >= cb.started + (radio ? 2 : 4)) hartAt(radio ? 'An old police repeater still loops its last traffic. Hart fell back to {police}, keycard and all.' : 'A survivor at the gate saw soldiers barricade themselves into {police} on the last night.')
  // The Lost Convoy
  if (!st.threads.convoy && d >= 25 && (vehicleOf('van') && !vehicleOf('van').broken || (S.vehicles || []).some((v) => v.kind === 'car'))) {
    const r = rng('convoy')
    const docks = locs().filter((l) => ['warehouse', 'garage', 'hardware'].includes(l.type))
    const target = pickFrom(r, docks.length ? docks : locs().filter((l) => l.level >= 2)).id
    const t = startThread('convoy', { target })
    const dist = districtOf(target)
    addClue(t, `The beacon is loudest from ${DISTRICT_NAMES[dist] || 'the city'}, somewhere a truck could pull in off the road: a warehouse, a garage or a builders' yard.`, locs().filter((l) => l.district === dist && ['warehouse', 'garage', 'hardware', 'gas'].includes(l.type)).map((l) => l.id))
    if (!t.cands?.length) t.cands = [target]
  }
  tickPersonal()
}
function danaKitchen(text) {
  const t = thread('dana')
  if (!t || t.done || t.kitchen) return
  t.kitchen = true
  const cands = (t.cands || locs().map((l) => l.id)).filter((id) => ['diner', 'supermarket', 'school'].includes(locOf(id)?.type))
  addClue(t, text, cands.length ? cands : [t.target])
}
function hartAt(text) {
  const t = thread('codebook')
  if (!t || t.done || t.hartKnown) return
  t.hartKnown = true
  addClue(t, fill(text), [t.target])
}
function storyPerson(key) {
  const P = STORY_PEOPLE[key]
  const s = makeSurvivor({ occ: P.occ, first: P.first, last: P.last, quality: P.quality })
  s.look = randomLook(P.female)
  s.age = P.age
  s.story = key
  return s
}

// ---------------------------------------------------------------- missing people
// Now and then someone in camp asks after a relative who never turned up.
// A rough idea of where they were headed; a week before the trail goes cold.
function tickPersonal() {
  const st = storyState()
  const d = day()
  for (const p of st.personal) {
    if (!p.done && !p.failed && d > p.until) {
      p.failed = d
      const who = getS(p.asker)
      log(`The trail has gone cold. Nobody has seen ${p.npc.first} ${p.npc.name.split(' ')[1] || ''}.`.replace(' .', '.'), 'bad')
      if (who) addMoraleEvent(`${who.first} lost hope`, -4, 1.5)
      p.npc = { first: p.npc.first, name: p.npc.name }
      bus.emit('story')
    }
  }
  if (d < st.nextPersonal || st.personal.some((p) => !p.done && !p.failed)) return
  st.nextPersonal = d + 10 + Math.floor(Math.random() * 7)
  const askers = S.survivors.filter((s) => s.status === 'ok' && !s.story)
  if (askers.length < 4) return
  const asker = askers[Math.floor(Math.random() * askers.length)]
  const options = locs().filter((l) => l.level <= Math.min(4, 2 + Math.floor(d / 20)) && l.type !== 'military')
  if (!options.length) return
  const target = options[Math.floor(Math.random() * options.length)]
  const rel = ['sister', 'brother', 'daughter', 'son', 'husband', 'wife', 'cousin', 'best friend'][Math.floor(Math.random() * 8)]
  const npc = makeSurvivor({ last: asker.name.split(' ').slice(-1)[0], quality: 2 })
  const kind = LOCATIONS[target.type].name.toLowerCase()
  const cands = locs().filter((l) => l.district === target.district && l.type === target.type).map((l) => l.id)
  const p = { id: 'p' + d + '-' + Math.floor(Math.random() * 1000), asker: asker.id, askerName: asker.first, rel, npc, target: target.id, cands, checked: [], since: d, until: d + 7 }
  p.clue = `${asker.first}'s ${rel} ${npc.first} was headed for a ${kind} in ${DISTRICT_NAMES[target.district] || 'the city'} when it all went wrong.`
  st.personal.push(p)
  log(`${asker.first} asks for help: ${p.clue}`, 'story')
  bus.emit('story')
}

// ---------------------------------------------------------------- what a run finds
// Who is waiting at a location, and which story items are hidden there.
export function storyAt(locId) {
  const st = storyState()
  const out = { npc: null, items: [] }
  const dana = thread('dana')
  if (dana && !dana.done && dana.target === locId && dana.npc) out.npc = { s: dana.npc, thread: 'dana' }
  for (const p of st.personal) if (!p.done && !p.failed && p.target === locId && p.npc?.id) out.npc = out.npc || { s: p.npc, personal: p.id }
  const ks = thread('kessler')
  if (ks && !ks.done && ks.stage === 1 && ks.target === locId) out.items.push({ key: 'kxcase', name: 'Cold room 12', rooms: ['lab', 'ward', 'storeroom', 'pharmacy'], kinds: ['fridge', 'medcab', 'chemshelf', 'locker', 'safe'] })
  const cb = thread('codebook')
  if (cb && !cb.done && cb.stage === 0 && cb.target === locId) out.items.push({ key: 'keycard', name: 'Colonel Hart\'s kit bag', rooms: ['armory', 'office', 'lockers', 'cells'], kinds: ['locker', 'gunlocker', 'desk', 'milcrate'] })
  if (cb && !cb.done && cb.stage === 1 && militaryLoc() === locId) out.items.push({ key: 'codebook', name: 'Comms safe', rooms: ['office', 'armory', 'barracks'], kinds: ['safe', 'desk', 'filing', 'locker'], needsKey: 'keycard' })
  const cv = thread('convoy')
  if (cv && !cv.done && cv.target === locId && !st.keys.truckKeys) out.items.push({ key: 'truckKeys', name: 'A soldier\'s body, still in uniform', rooms: ['garage', 'warehouse', 'storeroom'], kinds: ['crate', 'pallet', 'toolchest', 'car', 'locker'] })
  return out
}
// A key story item was found on a run. Returns the line to show.
export function foundStoryItem(key) {
  const st = storyState()
  if (st.keys[key]) return null
  st.keys[key] = day()
  bus.emit('story')
  if (key === 'keycard') {
    const t = thread('codebook')
    t.stage = 1
    t.target = militaryLoc()
    addClue(t, `Hart's keycard is in camp. The comms safe is at ${locName(t.target)}.`, [t.target])
    st.notes.includes('hart') || addNote('hart')
    return 'Colonel Hart\'s keycard, still clipped to his vest.'
  }
  if (key === 'codebook') {
    finish('codebook', THREADS.codebook.done)
    return 'The evacuation codebook: call signs and one-time codes for Coastal Command.'
  }
  if (key === 'kxcase') {
    finish('kessler', THREADS.kessler.done)
    S.research = S.research || { done: {}, alts: [], pick: null }
    for (const r of ['antiviral', 'immunity']) if (!S.research.done[r]) S.research.done[r] = day()
    gain({ specimen: 4 })
    st.flags.kxcase = day()
    return 'The KX-9 sample case, still cold, Dr. Imre\'s notes taped to the lid.'
  }
  if (key === 'truckKeys') return 'Keys to Harbor Light Seven, on a tag around the driver\'s neck. The truck should start.'
  return null
}
// The run ended at a location: rescues and recoveries close their threads,
// and a lead that turned out empty is crossed off.
export function storyRunEnd(locId, result, report) {
  const st = storyState()
  const rescued = report?.rescuedStory
  if (result === 'extracted') {
    if (rescued?.thread === 'dana') {
      const t = thread('dana')
      t.npc = null
      st.flags.dana = day()
      finish('dana', THREADS.dana.done)
    }
    if (rescued?.personal) {
      const p = st.personal.find((x) => x.id === rescued.personal)
      if (p) {
        p.done = day()
        p.npc = { first: p.npc.first, name: p.npc.name }
        const who = getS(p.asker)
        addMoraleEvent(`${p.npc.first} found alive`, 10, 2)
        log(`${who ? who.first + '\'s' : 'A'} ${p.rel} ${p.npc.first} is safe in camp.`, 'good')
      }
    }
    if (st.keys.truckKeys && threadActive('convoy') && thread('convoy').target === locId) {
      addVehicle('truck', { log: 'Harbor Light Seven rolls in through the gate, plated and loaded.' })
      gain({ rammo: 60, pammo: 80, meds: 10, parts: 20, beams: 6 })
      finish('convoy', THREADS.convoy.done)
    }
  }
  // cross off empty leads
  for (const t of [...Object.values(st.threads), ...st.personal]) {
    if (t.done || t.failed || !t.cands?.includes(locId)) continue
    if (t.target === locId) continue
    if (!t.checked.includes(locId)) t.checked.push(locId)
    t.cands = t.cands.filter((id) => id !== locId)
  }
  bus.emit('story')
}
// Every lead that points at a location, for map markers and the planner.
export function leadsAt(locId) {
  const st = storyState()
  const out = []
  const what = { dana: () => 'Dana Okoye', kessler: () => 'The KX-9 sample case', codebook: (t) => (t.stage ? 'The comms safe' : 'Colonel Hart\'s keycard'), convoy: () => 'Harbor Light Seven' }
  for (const [id, t] of Object.entries(st.threads)) if (!t.done && t.cands?.includes(locId)) out.push(what[id]?.(t) || THREADS[id].name)
  for (const p of st.personal) if (!p.done && !p.failed && p.cands?.includes(locId)) out.push(`${p.npc.first}, ${p.askerName}'s ${p.rel}`)
  return out
}

// ---------------------------------------------------------------- notes
// Searching a desk, a locker or a shelf now and then turns up a note: lore,
// and sometimes a clue. Kessler files turn up far more often while that
// thread is open.
const NOTE_SPOTS = { desk: 0.07, filing: 0.08, bookshelf: 0.06, locker: 0.05, server: 0.06, chemshelf: 0.06, medcab: 0.04, dresser: 0.04, wardrobe: 0.03, trash: 0.03, dumpster: 0.03, fridge: 0.03, cabinet: 0.02, counter: 0.02 }
export function rollNote(locType, kind, level) {
  const base = NOTE_SPOTS[kind]
  if (!base) return null
  const st = storyState()
  const pool = Object.entries(NOTES).filter(([id, n]) => !st.notes.includes(id) && n.where.includes(kind) && (n.minLevel || 1) <= level && (!n.types || n.types.includes(locType)))
  if (!pool.length) return null
  const kOpen = threadActive('kessler') && thread('kessler').stage === 0
  const weights = pool.map(([, n]) => (n.thread === 'kessler' ? (kOpen ? 3 : 0.8) : n.thread === 'dana' ? (threadActive('dana') ? 2.5 : 0.3) : 1))
  const chance = base * (1 + level * 0.15) * (kOpen ? 1.6 : 1)
  if (Math.random() > chance) return null
  let r = Math.random() * weights.reduce((a, b) => a + b, 0)
  for (let i = 0; i < pool.length; i++) {
    r -= weights[i]
    if (r <= 0) return addNote(pool[i][0])
  }
  return addNote(pool[pool.length - 1][0])
}
export function addNote(id) {
  const st = storyState()
  if (st.notes.includes(id)) return null
  st.notes.push(id)
  st.unread.push(id)
  if (S.stats) S.stats.notes = (S.stats.notes || 0) + 1
  const n = NOTES[id]
  log(`Found a note: ${n.title}.`, 'story')
  if (n.clue === 'danaKitchen') danaKitchen('Dana\'s own notebook: "Somewhere with a walk-in fridge and a back door." A diner, a school kitchen or a supermarket.')
  if (n.clue === 'hartAt') {
    if (!thread('codebook')) startThread('codebook', { target: policeLoc() })
    hartAt('The Harbor PD radio log: Hart fell back to {police}, keycard and all.')
  }
  if (n.clue === 'codebookHint' && !thread('codebook')) startThread('codebook', { target: policeLoc() })
  bus.emit('story')
  return id
}
export const noteText = (id) => fill(NOTES[id].text)

// ---------------------------------------------------------------- the Signal
// Two phases of the mast wait on the story. Phase 3 needs the dish array
// calibrated (Dana, or the long way round through research); phase 5 needs
// the codebook so Coastal Command believes the call.
export function signalLock(i) {
  const st = S.story
  if (i === 2 && !st?.flags?.dana && !S.research?.done?.broadcast) return { short: 'Needs calibration', text: 'The dish array has to be phased by hand, by someone who knows how. Find Dana Okoye (see the journal), or research Broadcast Engineering at the desk.' }
  if (i === 4 && !st?.keys?.codebook) return { short: 'Needs the codebook', text: 'Coastal Command only answers an authenticated call. Find the evacuation codebook (see the journal).' }
  return null
}
// Dana knows the mast: every phase costs a tenth less with her in camp.
export const signalDiscount = () => (S.survivors.some((s) => s.story === 'dana') ? 0.9 : 1)
export const unreadNotes = () => (S.story?.unread || []).length
// New since the journal was last opened: unread notes and new threads.
export function storyBadge() {
  const st = S?.story
  if (!st) return 0
  return st.unread.length + Math.max(0, Object.keys(st.threads).length + st.personal.length - (st.seen || 0))
}
export function markJournalSeen() {
  const st = storyState()
  st.seen = Object.keys(st.threads).length + st.personal.length
}
export const clampStage = (t, n) => clamp(t.stage, 0, n)
export { THREADS, NOTES }
