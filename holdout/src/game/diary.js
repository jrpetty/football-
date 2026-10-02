// Diaries. Every survivor keeps a few lines about what happens to them: a
// run, a night at the wall, a bite, a friend lost, a name earned, and on
// quiet days how the work and the camp are going. The voice follows the
// person: what they did before, their traits, how the camp is doing. Lines
// are picked with a seed per survivor and day, so a camp reads the same for
// everyone in it. The sheet shows them newest first.
import { S, day, hour } from './state.js'
import { STATIONS, SKILLS, ITEMS, MILESTONES } from './data.js'
import { bus } from '../core/util.js'
import { callName } from './deeds.js'

const MAX = 60
// ---------------------------------------------------------------- writing
function hash(str) {
  let h = 2166136261
  for (let i = 0; i < str.length; i++) h = Math.imul(h ^ str.charCodeAt(i), 16777619)
  return h >>> 0
}
function rng(...keys) {
  let s = hash(keys.join('|')) || 1
  return () => {
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const pick = (r, a) => a[Math.floor(r() * a.length)]
const has = (s, t) => !!s.traits?.includes(t)
const alive = (s) => S.survivors.includes(s)
const inCamp = (s) => alive(s) && s.status !== 'mission' && s.status !== 'outpost' && s.status !== 'scout'
const plural = (n, a, b = a + 's') => `${n} ${n === 1 ? a : b}`

// Add a line to someone's diary. One quiet-day line a day at most.
export function write(s, text, kind = 'day') {
  if (!s?.id || !text || !S) return
  const d = (s.diary ??= [])
  const today = day()
  if (kind === 'day' && d.some((e) => e.day === today)) return
  if (d.some((e) => e.day === today && e.text === text)) return
  d.push({ day: today, h: Math.floor(hour()), kind, text })
  if (d.length > MAX) d.splice(0, d.length - MAX)
  bus.emit('diary', s)
}
// The time of day an entry was written, in words.
export const whenText = (e) => (e.h < 5 ? 'night' : e.h < 12 ? 'morning' : e.h < 17 ? 'afternoon' : e.h < 21 ? 'evening' : 'night')

// ---------------------------------------------------------------- first pages
const OCC_INTRO = {
  doctor: ['I used to have a pager and a parking space. Now I have a bag of bandages and a camp full of people who need me.', 'Nobody here knows how to set a bone. I suppose that is why I am here.'],
  nurse: ['Twelve-hour shifts were supposed to be the hard part. I would give a lot for a twelve-hour shift now.'],
  paramedic: ['I spent ten years getting to people fast. Now the trick is getting away fast.'],
  police: ['I still have the badge in my pocket. I am not sure why.', 'Old habits: I counted the exits before I counted the beds.'],
  soldier: ['They told us to hold the line at the bridge. There is no bridge now, and no line. Just this fence.'],
  firefighter: ['Thirty years running into burning buildings. Funny how the fires were the easy part.'],
  farmer: ['The soil here is tired, but it will grow something if you ask it nicely.', 'First thing I looked at was the ground. Second thing was the rain barrels.'],
  chef: ['Somebody has to make tinned beans taste like something. Might as well be me.'],
  carpenter: ['Every wall in this place leans. I can fix that.'],
  mechanic: ['That van in the yard has a cracked manifold and a dead battery. I have fixed worse with less.'],
  electrician: ['I can hear the generator from my bunk. It is missing a beat every eleven seconds. It is all I can think about.'],
  engineer: ['There is a whole factory\'s worth of junk out there. Somebody has to make it into something.'],
  tailor: ['Everyone here is wearing rags. Good rags, some of them. I can work with rags.'],
  gunsmith: ['Half the guns I have seen out there are jammed with rust. They just need someone who cares.'],
  hunter: ['Used to sit in a tree stand for hours waiting for deer. Now I sit on the wall waiting for worse.'],
  athlete: ['I ran marathons. Turns out it was practice.'],
  student: ['I had an exam the week it started. I never found out how I did.', 'Everything I know is from books. Time to learn the rest.'],
  teacher: ['Thirty kids in a classroom were harder to manage than this lot. Mostly.'],
  plumber: ['Water is the whole game. Clean water, moving water, water that goes where you tell it.'],
  builder: ['I have built houses, car parks, a church once. A fence is a fence. I can build a fence.'],
  clerk: ['I know what everything is worth. Out here that is more useful than you would think.'],
  excon: ['They looked at my tattoos when I came in. Nobody said anything. That is more than I expected.'],
  drifter: ['I have slept in worse places. I have slept in better ones too, but not lately.'],
  scout: ['I walked here from the coast road, mostly at night. I know how to be quiet.'],
  guard: ['Twelve years watching car parks. Turns out I was good at watching.'],
}
// The first entry: how they came to be here.
function intro(s, how = null) {
  const r = rng(s.id, 'intro')
  const lines = OCC_INTRO[s.occ] || ['I have been walking a long time.']
  const start = how || (s.joined <= 1 ? pick(r, ['We dragged the old van into the yard and lit a fire. Whatever comes, we start here.', 'Day one. A fence, a fire and a handful of strangers. It will have to do.', 'We found the yard by the old road. It has a fence. That is enough to start with.']) : pick(r, ['Found this camp today. They had a fire going and they let me in.', 'They gave me a bunk and a bowl of something warm. I have not slept this well in weeks.', 'A fence, people who talk to each other, a fire at night. I had forgotten what that looks like.']))
  return `${start} ${pick(r, lines)}`
}
// Make sure someone has a first page.
export function ensureDiary(s) {
  if (!s || s.diary?.length) return
  s.diary = [{ day: s.joined || 1, h: 18, kind: 'join', text: intro(s) }]
}

// ---------------------------------------------------------------- runs
// Before a run: what each of the squad had done so far, to tell the run's
// own story afterwards.
export function runStart(list) {
  for (const s of list) {
    if (!s) continue
    const d = s.deeds || {}
    s._run = { k: (d.melee || 0) + (d.ranged || 0), downs: d.downs || 0, revives: d.revives || 0, hp: s.hp }
  }
}
// After a run: a line from each of the squad (and a first page for anyone
// they brought home).
export function runEnd(list, report) {
  const loc = report.loc?.name || 'the city'
  const lost = report.lost || []
  const mates = list.filter(Boolean)
  // who went out together
  for (const a of mates) for (const b of mates) if (a !== b) (a.mates ??= {})[b.id] = (a.mates[b.id] || 0) + 1
  for (const s of mates) {
    const r = rng(s.id, 'run', day(), loc)
    const z = s._run || { k: 0, downs: 0, revives: 0 }
    const d = s.deeds || {}
    const k = (d.melee || 0) + (d.ranged || 0) - z.k
    const downs = (d.downs || 0) - z.downs
    const revives = (d.revives || 0) - z.revives
    delete s._run
    if (s.first === report.rescued) {
      write(s, `They found me at ${loc}. ${pick(r, ['I had been hiding in there for days, rationing a jar of peanut butter.', 'I heard voices that were not screaming and I nearly cried.', 'I did not believe they were real until they opened the door.'])} Now I have a bunk here.`, 'join')
      continue
    }
    if (!alive(s)) continue
    const parts = []
    if (report.result === 'extracted') {
      const got = Object.values(report.loot || {}).reduce((a, b) => a + b, 0)
      parts.push(got > 60 ? pick(r, [`Back from ${loc} with the van riding low.`, `${loc} paid off. We could barely close the doors on the way home.`, `A good haul from ${loc}.`]) : got > 10 ? pick(r, [`Back from ${loc}.`, `Made it home from ${loc}.`, `${loc} today.`]) : pick(r, [`${loc} was picked clean. A long trip for nothing.`, `Hardly anything worth carrying at ${loc}.`]))
      if (k >= 12) parts.push(pick(r, [`I put down ${k} of them. I stopped counting faces after the fifth.`, `${k} of them. My arms are still shaking.`, `${k} went down in front of me. I keep seeing the last one.`]))
      else if (k >= 4) parts.push(pick(r, [`Put down ${k} of them.`, `${k} of them got close. None got closer.`]))
      else if (k === 0 && has(s, 'coward')) parts.push('I kept to the back. Nobody said anything, but they noticed.')
      if (downs > 0) parts.push(pick(r, ['One of them got me down on the floor. Someone pulled me up. I do not remember who.', 'I went down. For a second I thought that was it.']))
      if (revives > 0) parts.push(revives > 1 ? `Got ${plural(revives, 'of the others', 'of the others')} back on their feet when it went bad.` : 'Dragged one of ours back up off the floor. We both made it.')
      if (s.status === 'injured') parts.push(pick(r, ['I am hurt worse than I let on.', 'Stitches tonight. Lots of them.', 'Everything hurts. The infirmary smells of bleach and smoke.']))
      if (report.rescued) parts.push(`We brought ${report.rescued} home with us.`)
      if (lost.length) parts.push(`${lost.join(' and ')} did not come back with us.`)
      if (report.cleared) parts.push(`There is nothing left alive in ${loc} now.`)
    } else {
      parts.push(pick(r, [`${loc} went wrong. I do not remember the way home.`, `We lost ${loc}. We nearly lost everything.`, `It all went wrong at ${loc}.`]))
      if (lost.length) parts.push(`${lost.join(' and ')} ${lost.length > 1 ? 'are' : 'is'} gone.`)
      parts.push(pick(r, ['I keep washing my hands.', 'I should have been faster.', 'Nobody is talking tonight.']))
    }
    write(s, parts.join(' '), 'run')
  }
}

// ---------------------------------------------------------------- camp life
const JOB_LINES = {
  farm: ['The beans are coming up. Small mercies.', 'Weeded the rows until my back gave out.', 'Carried water to the rows all afternoon. The plot looks greener than the rest of the world.'],
  collector: ['Checked the rain barrels. Fuller than yesterday.'],
  filter: ['Changed the filters again. The water comes out clear. I check it twice anyway.', 'The pump has a squeak I cannot find.'],
  lumber: ['Felled two pines today. Every one I cut, I count the ones left.', 'Sawdust in everything: my hair, my food, my dreams.'],
  scrapyard: ['Stripped another wreck down to the frame. There was a child seat in the back. I left it.', 'Cut my hand on a fender. Third time this week.'],
  forge: ['The forge ran hot all day. My eyebrows are thinner.', 'Poured a good batch of bars. There is a satisfaction in it I did not expect.', 'Kept the fire fed. The bellows creak like an old man.'],
  kiln: ['Banked the kiln and watched the smoke. Charcoal takes patience.'],
  still: ['The still smells like a bad night out. The van will be grateful.'],
  generator: ['Kept the generator running. The lights stayed on. Nobody noticed, which is how it should be.'],
  boiler: ['Fed the steam engine all shift. The flywheel sings when it is happy.', 'Shovelled until I could not lift the shovel.'],
  kitchen: ['Made something out of nothing again. They ate all of it.', 'Burnt the porridge. Nobody complained. That says a lot about all of us.'],
  infirmary: ['Changed dressings all morning. Everyone is healing slower than I would like.', 'Stitched a cut that was not as bad as it looked. Small wins.'],
  training: ['Drills in the yard until dark. Getting faster.', 'Sparred all afternoon. I have bruises on my bruises.'],
  workbench: ['Spent the day at the bench. Made something useful out of rubbish. My favourite kind of day.'],
  weapons: ['Fitted a new firing pin. The click is clean now.'],
  ammo: ['Pressed rounds until my fingers went numb. Counted every one.'],
  tailor: ['Patched three coats and a pair of boots.', 'The needle broke twice. I made a new one out of a bicycle spoke.'],
  electronics: ['Soldered all day. The smoke makes my eyes water.'],
  chemlab: ['Mixed a batch that did not explode. I am calling that a success.', 'Everything in the lab smells of solvent. Including me.'],
  fabricator: ['The line ran all day. Plates, bolts, plates. I dream in rivets.'],
  assembler: ['Rewound a motor today. Took six hours. It spins.'],
  research: ['Read a manual about water pumps cover to cover. There were diagrams. I was happy.', 'Notes everywhere. Some of it is starting to make sense.'],
  radio: ['Listened to static for six hours. Then, for a moment, a voice. Then static.', 'Talked to someone two towns over. They sounded tired too.'],
  watchtower: ['Long watch on the tower. Saw shapes at the treeline at dusk. They did not come closer.', 'Counted crows from the tower to stay awake.'],
  recycler: ['Fed old junk into the shredder all day. Nothing goes to waste here, not even rubbish.'],
  coop: ['The hens are laying again. Found six eggs and a very angry rooster.', 'One of the hens follows me around. I have named her.'],
  goatpen: ['The goats got out again. Nobody knows how.', 'Milked the goats. They do not like me. I do not blame them.'],
  mast: ['Climbed the mast to check the cables. You can see the whole valley from up there.'],
}
const IDLE_LINES = ['No job today. Hauled scrap and dead branches in from the yard.', 'Helped wherever there was something to carry.', 'Nothing to do but forage the yard and listen for engines.']
const WEATHER_LINES = {
  rain: ['Rain all day.', 'It would not stop raining.'],
  snow: ['Snow on everything.', 'Cold enough to see your breath inside.'],
  fog: ['Fog so thick you could not see the gate.'],
  storm: ['Wind howling through the gaps in the walls all night.'],
  clear: ['Clear skies.', 'Sun on my face for once.'],
}
// A quiet day: a line about work, food, mood and weather.
function dayLine(s) {
  const r0 = rng(s.id, 'day', day())
  // never the same line two entries running
  const last = (s.diary || []).filter((e) => e.kind === 'day').slice(-2).map((e) => e.text).join(' ')
  const r = r0
  const pickNew = (rr, a) => {
    const fresh = a.filter((x) => !last.includes(x))
    return pick(rr, fresh.length ? fresh : a)
  }
  const parts = []
  const n = S.survivors.length || 1
  const job = s.job ? S.stations.find((x) => x.id === s.job) : null
  if (s.infection > 0) parts.push(pick(r, ['The fever comes and goes.', 'I can feel it in my blood. I try not to think about it.', 'They watch me when they think I am not looking.']))
  else if (s.status === 'injured') parts.push(pick(r, ['Still laid up. The ceiling has forty-one cracks.', 'Healing slowly. Too slowly.', 'Cannot do much but sleep and listen to the others work.']))
  else if (job) parts.push(pickNew(r, JOB_LINES[job.type] || [`Another day at the ${STATIONS[job.type].name.toLowerCase()}.`, `The ${STATIONS[job.type].name.toLowerCase()} again. It is good to have something to do with my hands.`]))
  else parts.push(pickNew(r, IDLE_LINES))
  if ((S.res.food || 0) < n * 2) parts.push(pick(r, ['Half rations again. Everyone is thinner.', 'The food is running low. Nobody says it out loud.']))
  else if ((S.res.water || 0) < n * 2.4) parts.push('Water is getting scarce. We share cups now.')
  const m = S.morale ?? 50
  if (m < 25) parts.push(pick(r, ['Nobody sang at the fire tonight.', 'It feels like we are waiting for something bad.']))
  else if (m > 75 && r() < 0.5) parts.push(pick(r, ['Someone played guitar at the fire. We laughed. It has been a while.', 'Good day, all told.', 'For an hour tonight it almost felt normal.']))
  const w = S.weather?.type
  if (w && WEATHER_LINES[w] && r() < 0.35) parts.push(pick(r, WEATHER_LINES[w]))
  if (has(s, 'lazy') && r() < 0.25) parts.push('Took a long lunch. Nobody missed me.')
  if (has(s, 'hardworker') && r() < 0.25) parts.push('Stayed late again.')
  return parts.join(' ')
}
// What made a friend who they were, for the ones left behind.
function memory(dead, r) {
  const d = dead.deeds || {}
  const fav = Object.entries(d.work || {}).sort((a, b) => b[1] - a[1])[0]
  const opts = []
  if (fav && fav[1] >= 3) opts.push(`${dead.first} kept the ${STATIONS[fav[0]]?.name.toLowerCase() || 'camp'} going when nobody else could.`)
  if ((d.melee || 0) + (d.ranged || 0) > 30) opts.push(`${dead.first} stood in front of me more than once.`)
  if (dead.runs > 5) opts.push(`${dead.first} always drove. Always.`)
  if (dead.nick) opts.push(`We called ${dead.first === callName(dead) ? 'them' : 'them ' + callName(dead)} that for a reason.`)
  opts.push(`${dead.first} always saved me a seat at the fire.`, `${dead.first} used to hum while they worked.`, `I still have the ${pick(r, ['knife', 'lighter', 'scarf', 'deck of cards'])} ${dead.first} lent me.`)
  return pick(r, opts)
}

// ---------------------------------------------------------------- listening
let wired = false
export function wireDiaries() {
  if (wired) return
  wired = true
  bus.on('newDay', () => {
    if (!S) return
    for (const s of S.survivors) {
      ensureDiary(s)
      if (!inCamp(s) && s.status !== 'injured') continue
      // most days are quiet; write on about a third of them
      if (rng(s.id, 'quiet', day())() < 0.34) write(s, dayLine(s), 'day')
    }
  })
  bus.on('death', (dead) => {
    if (!S) return
    // the ones who knew them best write first
    const close = S.survivors.filter((s) => s !== dead).sort((a, b) => (b.mates?.[dead.id] || 0) - (a.mates?.[dead.id] || 0) || (a.id < b.id ? -1 : 1))
    for (const s of close.slice(0, 3)) {
      const rr = rng(s.id, 'grief', dead.id)
      write(s, `${callName(dead)} is gone. ${memory(dead, rr)} ${pick(rr, ['We buried them by the east fence.', 'I do not know what to write.', 'The fire felt smaller tonight.', 'Someone has to take their bunk. Not me. Not yet.'])}`, 'loss')
    }
  })
  bus.on('turned', (s) => {
    for (const o of S.survivors.filter((x) => x !== s).slice(0, 2)) write(o, `${callName(s)} turned. We did what had to be done. I keep hearing it.`, 'loss')
  })
  bus.on('infected', (s) => {
    const r = rng(s.id, 'bite', day())
    write(s, pick(r, ['The bite is on my forearm. Small. It itches like fire.', 'Something got its teeth in me. I cleaned it with everything we had.', 'I am infected. There. I wrote it down.']), 'bite')
  })
  bus.on('nickname', (s) => {
    if (!s?.nick || s.nickBy === 'player' || !alive(s)) return
    const r = rng(s.id, 'nick', callName(s))
    write(s, `They have started calling me ${callName(s)}. For ${s.nick.why}. ${pick(r, ['I suppose it could be worse.', 'I pretend to hate it.', 'It has a ring to it.'])}`, 'name')
  })
  bus.on('levelup', (s, skill) => {
    const lv = s?.skills?.[skill]
    if (!s || ![5, 10, 15].includes(lv)) return
    const r = rng(s.id, 'skill', skill, lv)
    const name = SKILLS[skill]?.name.toLowerCase()
    write(s, lv === 15 ? `Nobody in camp knows ${name} like I do now. Strange thing to be proud of.` : pick(r, [`${SKILLS[skill]?.name} comes easier now. My hands know what to do before I do.`, `Getting good at ${name}. The others are asking me how.`]), 'skill')
  })
  bus.on('raidResolved', (rep) => {
    if (!S || !rep) return
    const held = !rep.dead?.length && (rep.ratio ?? 1) >= 0.55
    for (const s of S.survivors) {
      if (!inCamp(s)) continue
      const r = rng(s.id, 'raid', day())
      const hurt = rep.injured?.includes(s.first)
      if (!hurt && r() > 0.6) continue
      const head = held ? pick(r, [`The horde came after dark: ${rep.count} of them. The wall held.`, `${rep.count} of them at the wall tonight. We held.`, `They came. ${rep.count} of them. We are still here.`]) : pick(r, [`They broke through the wall tonight.`, `The wall gave. ${rep.count} of them. I cannot stop shaking.`])
      const tail = hurt ? pick(r, ['Took a bad hit. I am in the infirmary writing this.', 'One of them got through my guard.']) : rep.dead?.length ? `${rep.dead.join(' and ')} did not make it.` : pick(r, ['My hands are still shaking.', 'Nobody slept after.', 'We patched the fence by torchlight.'])
      write(s, `${head} ${tail}`, 'raid')
    }
  })
  bus.on('recruitJoined', (s) => {
    if (s && !s.diary?.length) s.diary = [{ day: day(), h: Math.floor(hour()), kind: 'join', text: intro(s) }]
  })
  bus.on('milestone', (id) => {
    const M = MILESTONES[id]
    const writer = S.survivors.filter(inCamp).sort((a, b) => hash(a.id + id) - hash(b.id + id))[0]
    if (M && writer) write(writer, `We finished ${M.name.toLowerCase()} today. ${M.desc}`, 'camp')
  })
  bus.on('expanded', (X) => {
    const writer = S.survivors.filter(inCamp).sort((a, b) => hash(a.id + X.id) - hash(b.id + X.id))[0]
    if (writer) write(writer, `We pushed the fence out into the ${X.name}. More room to breathe. More fence to watch.`, 'camp')
  })
  bus.on('season', (Z) => {
    const writer = S.survivors.filter(inCamp).sort((a, b) => hash(a.id + Z.id + day()) - hash(b.id + Z.id + day()))[0]
    const L = { spring: 'Spring. Green at the edges of everything.', summer: 'Summer. The heat makes the dead slow and the living short-tempered.', autumn: 'Autumn. Harvest, if we are lucky. Fog, if we are not.', winter: 'Winter. We are burning everything that will burn.' }
    if (writer && L[Z.id]) write(writer, L[Z.id], 'camp')
  })
  bus.on('scoutBack', (s, rep) => {
    if (!s || !alive(s)) return
    const r = rng(s.id, 'scout', day())
    const loc = rep.target === 'horde' ? null : rep.count != null
    const parts = []
    if (rep.target === 'horde') parts.push(rep.seen ? pick(r, [`Found the horde. I lay on a roof for an hour and watched it go past. ${rep.seen.size} of them, maybe more. You can hear it before you see it.`, `Tracked the horde through the streets for most of a day. ${rep.seen.size} of them, moving like weather.`]) : 'Walked half the city looking for the horde. Nothing but tracks.')
    else if (loc) {
      parts.push(pick(r, [`Spent hours watching the place from across the street. ${rep.count} of them inside, give or take.`, `Counted ${rep.count} of them through the windows. Drew the floor plan on the back of my hand.`, `${rep.count} inside. I wrote the rooms down. Nobody saw me.`]))
      if (rep.horde) parts.push('And the horde was close. Too close. Whoever goes in there next needs to know.')
    }
    if (rep.fate === 'hurt') parts.push('They spotted me on the way out. I ran until my lungs burned.')
    if (rep.fate === 'bitten') parts.push('One of them got me as I went over a fence. I am not going to think about what that means.')
    write(s, parts.join(' '), 'run')
  })
  bus.on('penRaid', (st, lost) => {
    const keeper = S.survivors.find((s) => s.job === st.id) || S.survivors.filter(inCamp)[0]
    const goat = STATIONS[st.type].livestock === 'goat'
    if (!keeper) return
    const r = rng(keeper.id, 'pen', day())
    write(keeper, lost ? pick(r, [`Something got into the ${goat ? 'goat pen' : 'coop'} last night. ${lost} ${goat ? (lost === 1 ? 'goat' : 'goats') : lost === 1 ? 'hen' : 'hens'} gone, and blood on the straw.`, `I counted the ${goat ? 'goats' : 'hens'} this morning. Then I counted again.`]) : `Claw marks on the fence round the ${goat ? 'goats' : 'hens'} this morning. It held. This time.`, 'loss')
  })
  bus.on('flock', (st) => {
    const keeper = S.survivors.find((s) => s.job === st.id)
    if (!keeper) return
    const goat = STATIONS[st.type].livestock === 'goat'
    write(keeper, goat ? 'A kid was born in the pen this morning, all legs and noise. Everyone came to look.' : 'Chicks! Little yellow things under the heat lamp. I have named all of them. I will regret that.', 'camp')
  })
  bus.on('crafted', (st, r, it) => {
    if (!it || (it.q ?? 1) < 3) return
    const crafter = S.survivors.find((s) => s.job === st.id)
    if (crafter) write(crafter, `Made a masterwork ${ITEMS[it.id]?.name.toLowerCase()} today. Best thing I have ever made. I keep picking it up.`, 'craft')
  })
}
