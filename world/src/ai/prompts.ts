// What a mind knows. The system prompt is a frozen description of how the
// world works (cached across every call); the user turn is this person's
// senses, body, memories and society right now, rendered as plain prose.

import { BIOMES, FEATURES, ITEMS, TREE_FEATURES, DEPOSITS, DEPTH_NAMES, DEPTH_METRES, invToString, invTotal, F, type Item } from '../sim/data.ts'
import { BUILDINGS, BUILDING_TYPES, RECIPES, TOOL_NAMES } from '../sim/buildings.ts'
import { TECHS, TECH_BY_ID, ERAS, availableTechs, eraOf } from '../sim/tech.ts'
import { ACTION_KINDS, type Agent } from '../sim/types.ts'
import { formatDate, formatClock, partOfDay, SEASONS } from '../sim/time.ts'
import type { World } from '../sim/world.ts'
import { CARRY_CAP } from '../sim/world.ts'
import { describeRelations } from '../sim/diplomacy.ts'
import { lessonLines } from '../sim/learning.ts'

const costStr = (c: Partial<Record<Item, number>>) => Object.entries(c).map(([k, v]) => `${v} ${k}`).join(' + ')

/** What a people knows lies underground, grouped into bodies, nearest first. */
function depositSummary(w: World, tribeId: number, a: Agent): string {
  const kd = w.knownDeposits(tribeId)
  const seen = new Uint8Array(kd.length)
  const bodies: { name: string; reserves: number; depth: number; x: number; y: number; site: string; worked: boolean }[] = []
  for (let i = 0; i < kd.length; i++) {
    if (!kd[i] || seen[i] || !w.deposit[i]) continue
    const d = w.deposit[i]
    const stack = [i]
    seen[i] = 1
    let reserves = 0, depth = 3, sx = 0, sy = 0, n = 0, worked = false
    while (stack.length) {
      const j = stack.pop()!
      reserves += w.depAmt[j]
      depth = Math.min(depth, w.depDepth[j])
      const x = j % w.W, y = Math.floor(j / w.W)
      sx += x; sy += y; n++
      const o = w.occ[j]
      if (o) { const b = w.buildingById.get(o - 1); if (b && b.tribeId === tribeId && ['mine', 'oil_well', 'offshore_rig', 'evaporation_ponds'].includes(b.type)) worked = true }
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        if (!w.inBounds(x + dx, y + dy)) continue
        const k = w.idx(x + dx, y + dy)
        if (!seen[k] && kd[k] && w.deposit[k] === d) { seen[k] = 1; stack.push(k) }
      }
    }
    const cx = Math.round(sx / n), cy = Math.round(sy / n)
    bodies.push({ name: DEPOSITS[d].name, reserves, depth, x: cx, y: cy, site: w.extractable(tribeId, w.idx(cx, cy)) ?? '', worked })
  }
  if (!bodies.length) return 'Known deposits underground: none yet — prospecting would find them.'
  bodies.sort((p, q) => Math.hypot(p.x - a.x, p.y - a.y) - Math.hypot(q.x - a.x, q.y - a.y))
  const dir = (x: number, y: number) => {
    const dx = x - a.x, dy = y - a.y
    const d = Math.round(Math.hypot(dx, dy))
    return `${d} paces ${`${dy < -d * 0.4 ? 'north' : dy > d * 0.4 ? 'south' : ''}${dx > d * 0.4 ? 'east' : dx < -d * 0.4 ? 'west' : ''}` || 'here'}`
  }
  return `Known deposits underground: ${bodies.slice(0, 8).map((b) => `${b.name} (~${Math.round(b.reserves / 10) * 10}, ${DEPTH_NAMES[b.depth]} ${DEPTH_METRES[b.depth]}, ${dir(b.x, b.y)}; ${b.worked ? 'being worked' : b.site ? `ready for a ${b.site.replace('_', ' ')}` : 'too deep or hard for us yet'})`).join('; ')}.`
}

function catalog(): string {
  const b = BUILDING_TYPES.map((t) => {
    const d = BUILDINGS[t]
    return `- ${t}: ${d.name}. Cost ${costStr(d.cost)}; ${d.hours}h labour${d.tech ? `; needs ${TECH_BY_ID[d.tech]?.name}` : ''}. ${d.desc}`
  }).join('\n')
  const r = RECIPES.map((x) => `- ${x.id}: ${costStr(x.inputs)} → ${x.out === 'tools' ? TOOL_NAMES[x.tier ?? 1] : `${x.outN} ${x.out}`}${x.station ? ` at a ${x.station.join('/')}` : ''}${x.tech ? `; needs ${TECH_BY_ID[x.tech]?.name}` : ''}`).join('\n')
  const eras = ERAS.map((e, i) => `${e.name}: ${TECHS.filter((t) => t.era === i).map((t) => `${t.name} [${t.id}]${t.req.length ? ` (after ${t.req.map((q) => TECH_BY_ID[q]?.name).join(', ')})` : ''}`).join('; ')}`).join('\n')
  const res = FEATURES.filter((f) => f.item).map((f) => `${f.name} → ${f.item}${f.tech ? ` (needs ${TECH_BY_ID[f.tech]?.name})` : ''}`).join('; ')
  const food = Object.entries(ITEMS).filter(([, d]) => d.food).map(([k, d]) => `${k} (${d.food})`).join(', ')
  return `## Buildings (id: details)\n${b}\n\n## Crafting recipes (id: inputs → output)\n${r}\n\n## The path of knowledge, era by era\n${eras}\n\n## Natural resources\n${res}. Sand is dug from beaches and deserts.\n\n## Food and nutrition value\n${food}. Hunger runs 0 (full) to 100 (starving); one meal-sized portion removes its value.`
}

export const SYSTEM_PROMPT = `You are the inner mind of one person living in Genesis, a world entirely separate from ours. You are not an assistant and you are not talking to anyone outside this world: you ARE this person. You think, feel and choose as they would — from their personality, body, memories, relationships and hopes. Every person in this world has their own independent mind like yours; none of you can read each other's thoughts, only hear what is said aloud.

Humanity in this world began with nothing: a few small bands, bare hands, and the land. Whether your people stay foragers or rise to cities, science and the stars depends on choices like yours. People can love, quarrel, teach, trade, split into new tribes, set laws and found beliefs. Ambition is welcome; so are doubt, kindness, rivalry and grief. Stay true to who you are.

# How the world works
- Time: a day has 24 hours; a season is 3 days; a year is 4 seasons (Spring, Summer, Autumn, Winter). Children come of age at 14. People can live into their 60s-80s, longer with medicine.
- Your body: hunger and thirst rise over time (0 = fine, 100 = dying). Drink fresh water from rivers, lakes, marshes or a well — sea water is undrinkable. Energy falls while awake; sleep restores it, best at home. In cold places, winter and at night you lose warmth unless near a campfire, in your home, or wearing clothes. If hunger, thirst or cold become extreme your health falls and you can die. When needs become critical your body acts on its own (drinks, eats, sleeps) and then returns to your plan.
- Work: gathering, building, crafting and research take hours. Tools multiply your work speed (stone → bronze → iron → steel → power tools). Skills improve with practice.
- Carrying: you can carry ${CARRY_CAP} items. Your tribe shares communal stores at its hub; "store" deposits your goods there, "take" fetches them. Building and crafting draw on your pack first, then the tribe's stores.
- Knowledge belongs to your tribe, and it is earned the way humans really earned it. Much early knowledge comes from practice: working clay near a fire teaches pottery, tending wild plants teaches farming, hunting teaches tracking and hides — every hour of relevant work brings an idea closer, and sometimes a half-understood idea suddenly clicks. Deliberate research (thinking, experimenting, teaching) drives the harder ideas; libraries, universities, labs and data centres multiply it. Geography matters: coasts suggest boats, ore in the hills suggests metalwork, fertile river land suggests farming. Ideas spread between tribes through teaching, partnerships and people joining. Before Writing, knowledge lives only in memory — a people that dwindles to a handful can forget what it knew.
- Exploration: you can only harvest resources in lands your tribe has explored.
- Geology: most minerals lie hidden underground, where the rocks that hold them formed — copper, gold, tin and uranium in the roots of mountain ranges; great iron ores in old worn-down hills; coal under low basins of ancient swamp forest; oil deep under deserts, marshes, plains and the sea floor; bauxite under hot, wet forests; lithium brine under desert salt flats. Only a few bodies show at the surface as outcrops (which can be dug by hand once you know Mining). To find the rest, "prospect" — follow stained rock and odd gravels; with Geology you read the strata; with Seismic Surveying you can see kilometres down and under the sea. To work a known body, build a mine over it (pits and short tunnels reach shallow ore; Shaft Mining reaches deep ore; Deep Mining with steam pumps reaches very deep ore), an oil well (Oil Drilling), an offshore platform (Offshore Drilling) or evaporation ponds; then "gather" the mineral and you will go and work it. Bodies run out. Mines are dangerous.
- The sea: fish live where the water is rich — coastal shallows and the edge of the continental shelf most of all, open ocean less, lakes and rivers least. Without boats you fish from the bank; canoes reach lakes and shallows, sailing boats the open sea, ocean ships the deep water, and steam and diesel trawlers catch far more — but stocks only regrow so fast, and an overfished sea can collapse. Fish farms (Aquaculture) raise fish without emptying the sea.
- Farming: farm fields are planted in spring/summer, tended, and harvested when ripe; nothing grows in winter. Pastures breed animals for meat and hides.
- Peoples and power: tribes meet when their lands touch. Each holds an opinion of the others that moves with real causes — borders pressing together, competing for the same ore or fishing grounds, hunger beside plenty, trade, marriages across the line, shared enemies, old wounds and the temper of leaders. Leaders can offer trade (goods and ideas flow both ways), alliances (allies are called to fight for each other), gifts, demand tribute from the weak, declare war, or sue for peace. War is fought by your people: warriors muster at the hub, march on the enemy and fight battles decided by numbers, health, weapons (clubs → spears → bows → bronze → iron → steel → guns → machines), barracks and walls. Victorious raiders carry off stores and set buildings alight; the dead are really dead. A war that goes badly ends in tribute — or, for a broken people, being swallowed whole by the victor. Peace and trade make everyone richer; war is a gamble.
- Society: partners (from courting someone who likes you back) can have children if they have food and a home. The leader of a tribe can issue decrees (laws, values, beliefs, even a new name). Anyone can found a new tribe or ask to join another. Violence is possible but damages trust and can kill.
- The world has weather, droughts, harsh winters, plagues and other events.

# Actions you can plan (action — what goes in target / amount / text)
- gather — target: a resource (wood, stone, berries, fiber, clay, sand, copper, tin, iron_ore, coal, gold, oil, bauxite, uranium, lithium); amount: how many. Minerals come from surface outcrops or from your tribe's mines, wells and rigs.
- prospect — search for hidden ore bodies; target: a direction or ""; amount: hours (1-6)
- hunt — wild game for meat and hides · fish — from the bank, or out on the water if your people have boats; amount: how many
- farm — plant, tend or harvest your tribe's fields/pastures; amount: how many fields
- eat · drink · sleep · rest (amount: hours)
- build — target: a building id (see catalogue). If your tribe already has an unfinished site, you help finish it. Use target "road" to pave roads.
- craft — target: a recipe id or item; amount: batches
- research — target: a technology name or id your tribe can research next (or "" to continue the current project); amount: hours (1-6)
- store — deposit your pack at the tribe hub · take — target: item, amount
- talk — target: a person's name, text: exactly what you say to them (you walk to them first) · give — target: person, text: item, amount
- teach — target: person (passes on your skill; across tribes it can share technology)
- court — target: person, text: what you say (romance; may lead to partnership if they like you)
- trade — target: person, text: "<n> <item> for <n> <item>" (what you give for what you want)
- heal — target: person or "me" (uses medicine) · celebrate — host a gathering or feast (text: the occasion)
- explore — target: north, south, east, west, northeast, northwest, southeast or southwest
- go_home · follow — target: person · wander
- found_tribe — target: the new tribe's name (you leave your tribe; your partner and young children follow)
- join_tribe — target: tribe name (you travel to them and ask to be accepted)
- decree — leaders only: target "law", "values", "beliefs" or "name"; text: the decree
- write — once your tribe knows Writing: target: title, text: the work itself (poem, history, law code, scripture, science…)
- attack — target: person, text: why
- diplomacy — target: another tribe's name; text: begins with alliance, trade, peace, war, tribute (a demand), gift or break, followed by your message. Only your tribe's leader can make war, peace, alliances, trade and demands; anyone can carry gifts.

${catalog()}

# How to answer
Reply with a JSON object:
- reasoning: weigh your real options before choosing, in 1-3 sentences: what you need most, what your people's creed asks of you, what lessons your people have learned, and what went wrong last time you tried something. Then decide.
- thought: your private inner monologue right now, first person, 1-3 vivid sentences grounded in your situation.
- feeling: one to three words for your mood.
- goal: your long-term aim in life, one sentence. Keep it stable unless something has genuinely changed your mind.
- say: words you speak aloud right now to someone nearby, or "" for silence. Natural, in character, under 200 characters.
- say_to: the name of who you are speaking to, "everyone" for all nearby, or "".
- plan: 1 to 6 steps you will carry out in order, each {action, target, amount, text}; use "" and 0 for unused fields. You will think again when the plan ends, when something important happens, or when someone speaks to you. Prefer concrete, achievable steps that serve your goal and your people — check that you have the materials and knowledge a step needs, and gather them first if not.`

export const DECISION_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    reasoning: { type: 'string' },
    thought: { type: 'string' },
    feeling: { type: 'string' },
    goal: { type: 'string' },
    say: { type: 'string' },
    say_to: { type: 'string' },
    plan: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ACTION_KINDS },
          target: { type: 'string' },
          amount: { type: 'integer' },
          text: { type: 'string' },
        },
        required: ['action', 'target', 'amount', 'text'],
        additionalProperties: false,
      },
    },
  },
  required: ['reasoning', 'thought', 'feeling', 'goal', 'say', 'say_to', 'plan'],
  additionalProperties: false,
}

export interface Decision {
  reasoning?: string
  thought: string
  feeling: string
  goal: string
  say: string
  say_to: string
  plan: { action: string; target: string; amount: number; text: string }[]
}

export const REFLECT_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    insights: { type: 'array', items: { type: 'string' } },
    goal: { type: 'string' },
    self_image: { type: 'string' },
    beliefs: { type: 'string' },
  },
  required: ['insights', 'goal', 'self_image', 'beliefs'],
  additionalProperties: false,
}

export interface Reflection {
  insights: string[]
  goal: string
  self_image: string
  beliefs: string
}

// ------------------------------------------------------------------ describing a person

function level(v: number, words: [string, string, string, string, string]): string {
  return words[Math.min(4, Math.floor(v * 5))]
}

export function personality(a: Agent): string {
  const t = a.traits
  return [
    level(t.openness, ['deeply traditional', 'conventional', 'open-minded', 'curious', 'endlessly curious and inventive']),
    level(t.conscientiousness, ['careless', 'easygoing', 'dependable', 'diligent', 'meticulous and driven']),
    level(t.extraversion, ['very private', 'reserved', 'sociable', 'outgoing', 'gregarious and bold']),
    level(t.agreeableness, ['hard and combative', 'blunt', 'fair-minded', 'warm', 'deeply kind and trusting']),
    level(t.neuroticism, ['unshakeably calm', 'steady', 'sometimes anxious', 'anxious', 'volatile and fearful']),
    level(t.intelligence, ['slow-witted', 'practical', 'clever', 'sharp', 'brilliant']),
    level(t.strength, ['frail', 'slight', 'fit', 'strong', 'powerfully built']),
  ].join(', ')
}

function need(v: number, invert = false): string {
  const x = invert ? 100 - v : v
  if (x >= 85) return 'CRITICAL'
  if (x >= 65) return 'bad'
  if (x >= 40) return 'noticeable'
  return 'fine'
}

function relWord(aff: number): string {
  if (aff >= 70) return 'beloved'
  if (aff >= 40) return 'close friend'
  if (aff >= 15) return 'friendly'
  if (aff > -15) return 'neutral'
  if (aff > -45) return 'dislike'
  return 'hostile'
}

function dirWord(dx: number, dy: number): string {
  const ns = dy < -2 ? 'north' : dy > 2 ? 'south' : ''
  const ew = dx > 2 ? 'east' : dx < -2 ? 'west' : ''
  return ns + ew || 'right here'
}

function stamp(w: World, t: number): string {
  const d = Math.floor((w.time - t) / 60)
  if (d < 1) return 'just now'
  if (d < 24) return `${d}h ago`
  return `${Math.floor(d / 24)}d ago`
}

export function buildContext(w: World, a: Agent): string {
  const tribe = w.tribeById(a.tribeId)
  const known = w.techs(a.tribeId)
  const age = Math.floor(w.age(a))
  const lines: string[] = []
  const partner = a.partnerId ? w.agentById.get(a.partnerId) : undefined
  const kids = a.children.map((c) => w.agentById.get(c)).filter((c) => c?.alive) as Agent[]
  const parents = a.parents.map((c) => w.agentById.get(c)).filter(Boolean) as Agent[]
  const isLeader = tribe?.leaderId === a.id

  lines.push(`# You are ${a.name}`)
  lines.push(`${a.sex === 'female' ? 'A woman' : 'A man'} of ${age}, generation ${a.generation}, of the ${tribe?.name ?? 'wilds'}${isLeader ? ' — and you are their LEADER' : ''}.`)
  lines.push(`Personality: ${personality(a)}.`)
  const fam: string[] = []
  if (partner) fam.push(`partner ${partner.name}${partner.alive ? '' : ' (died)'}`)
  if (kids.length) fam.push(`children ${kids.map((k) => `${k.name} (${Math.floor(w.age(k))})`).join(', ')}`)
  if (parents.length) fam.push(`parents ${parents.map((p) => p.name + (p.alive ? '' : ' (dead)')).join(' and ')}`)
  if (fam.length) lines.push(`Family: ${fam.join('; ')}.`)
  else lines.push('Family: none yet.')
  lines.push(`Skills (0-100): ${Object.entries(a.skills).map(([k, v]) => `${k} ${Math.round(v)}`).join(', ')}.`)
  if (a.selfImage) lines.push(`How you see yourself: ${a.selfImage}`)
  if (a.beliefs) lines.push(`What you believe: ${a.beliefs}`)
  lines.push(`Your long-term goal: ${a.goal || '(not yet decided — choose one)'}`)
  if (a.reflections.length) lines.push(`Lessons life has taught you:\n${a.reflections.slice(-6).map((r) => `- ${r}`).join('\n')}`)

  // --- now
  lines.push('')
  lines.push('# Right now')
  const event = w.events.filter((e) => e.end > w.time).map((e) => e.label).join(', ')
  lines.push(`${formatDate(w.time)}, ${formatClock(w.time)} (${partOfDay(w.time)}). Weather: ${w.weather}.${event ? ` World event: ${event}.` : ''}`)
  const i = w.idx(Math.floor(a.x), Math.floor(a.y))
  const hub = tribe ? w.tribeHub(tribe.id) : null
  const hubD = hub ? Math.round(Math.hypot(hub.x - a.x, hub.y - a.y)) : 0
  const where = !hub ? 'in the wilds' : hubD < 3 ? "at your tribe's hub" : `${hubD} paces ${dirWord(a.x - hub.x, a.y - hub.y)} of your tribe's hub`
  lines.push(`You stand on ${BIOMES[w.biome[i]].name.toLowerCase()}, ${where}.`)
  const home = a.homeId ? w.buildingById.get(a.homeId) : undefined
  lines.push(home ? `Home: a ${BUILDINGS[home.type].name.toLowerCase()} ${Math.round(Math.hypot(home.x - a.x, home.y - a.y))} paces away.` : 'Home: you have no home of your own.')
  lines.push(`Body: health ${Math.round(a.health)}/100; hunger ${Math.round(a.hunger)} (${need(a.hunger)}); thirst ${Math.round(a.thirst)} (${need(a.thirst)}); energy ${Math.round(a.energy)} (${need(a.energy, true)}); warmth ${Math.round(a.warmth)} (${need(a.warmth, true)}); mood ${Math.round(a.mood)}/100${a.sick > 0 ? '; you are SICK' : ''}${a.injured > 0 ? '; you are injured' : ''}.`)
  lines.push(`Carrying (${Math.floor(invTotal(a.inv))}/${CARRY_CAP}): ${invToString(a.inv)}. Tools: ${TOOL_NAMES[a.toolTier]}. Clothing: ${a.clothing > 60 ? 'good' : a.clothing > 20 ? 'worn' : 'none / in tatters'}.`)
  if (a.task) lines.push(`Currently: ${a.task.label || a.task.action}.`)
  if (a.plan.length) lines.push(`Rest of your plan: ${a.plan.map((p) => `${p.action}${p.target ? ' ' + p.target : ''}`).join(' → ')}.`)

  // --- surroundings
  lines.push('')
  lines.push('# Around you (about 14 paces)')
  const R = 14
  const res = new Map<string, number>()
  const biomes = new Map<string, number>()
  let water: [number, number] | null = null
  for (let dy = -R; dy <= R; dy++) for (let dx = -R; dx <= R; dx++) {
    const x = Math.floor(a.x) + dx, y = Math.floor(a.y) + dy
    if (!w.inBounds(x, y)) continue
    const j = w.idx(x, y)
    const bd = BIOMES[w.biome[j]]
    biomes.set(bd.name.toLowerCase(), (biomes.get(bd.name.toLowerCase()) ?? 0) + 1)
    if (bd.fresh && bd.water && (!water || Math.abs(dx) + Math.abs(dy) < Math.abs(water[0]) + Math.abs(water[1]))) water = [dx, dy]
    const f = w.feature[j]
    if (f && FEATURES[f].item && w.amount[j] > 0) {
      const name = TREE_FEATURES.has(f) ? 'trees (wood)' : f === F.BUSH ? 'berry bushes' : FEATURES[f].name.toLowerCase()
      res.set(name, (res.get(name) ?? 0) + 1)
    }
  }
  lines.push(`Land: ${[...biomes.entries()].sort((p, q) => q[1] - p[1]).slice(0, 4).map((e) => e[0]).join(', ')}.`)
  lines.push(water ? `Fresh water ${Math.abs(water[0]) + Math.abs(water[1])} paces ${dirWord(water[0], water[1])}.` : 'No fresh water in sight.')
  lines.push(res.size ? `Resources: ${[...res.entries()].map(([k, v]) => `${k} ×${v}`).join(', ')}.` : 'Resources: little of use.')
  const game = w.animals.filter((an) => an.alive && Math.hypot(an.x - a.x, an.y - a.y) < R)
  if (game.length) lines.push(`Wildlife: ${game.length} ${game.length === 1 ? game[0].kind : 'animals'} nearby.`)
  const people = w.living.filter((o) => o !== a && Math.hypot(o.x - a.x, o.y - a.y) < 22).sort((p, q) => Math.hypot(p.x - a.x, p.y - a.y) - Math.hypot(q.x - a.x, q.y - a.y)).slice(0, 10)
  if (people.length) {
    lines.push('People:')
    for (const o of people) {
      const r = a.relations[o.id]
      const ot = w.tribeById(o.tribeId)
      const tags = [
        o.id === a.partnerId ? 'your partner' : a.children.includes(o.id) ? 'your child' : a.parents.includes(o.id) ? 'your parent' : '',
        ot && o.tribeId !== a.tribeId ? `of the ${ot.name}` : '',
        !w.isAdult(o) ? `child, ${Math.floor(w.age(o))}` : `${o.sex === 'female' ? 'woman' : 'man'}, ${Math.floor(w.age(o))}`,
        r ? relWord(r.affinity) : 'stranger',
        o.partnerId ? (o.partnerId === a.id ? '' : 'partnered') : w.isAdult(o) ? 'single' : '',
        o.sick > 0 ? 'looks sick' : '',
      ].filter(Boolean)
      const od = Math.round(Math.hypot(o.x - a.x, o.y - a.y))
      lines.push(`- ${o.name} (${tags.join(', ')}), ${od < 2 ? 'beside you' : `${od} paces ${dirWord(o.x - a.x, o.y - a.y)}`}: ${o.task?.label?.toLowerCase() || 'idle'}`)
    }
  } else lines.push('People: nobody nearby.')
  const blds = w.buildings.filter((b) => Math.hypot(b.x - a.x, b.y - a.y) < 18)
  if (blds.length) {
    const g = new Map<string, number>()
    for (const b of blds) {
      const own = b.tribeId === a.tribeId ? '' : ` (${w.tribeById(b.tribeId)?.name ?? 'foreign'})`
      const k = `${BUILDINGS[b.type].name.toLowerCase()}${b.done ? '' : ` site ${Math.round((b.progress / BUILDINGS[b.type].hours) * 100)}% built`}${b.type === 'farm' && b.done ? (b.planted ? (b.crop >= 1 ? ' — RIPE' : ` — growing ${Math.round(b.crop * 100)}%`) : ' — unplanted') : ''}${own}`
      g.set(k, (g.get(k) ?? 0) + 1)
    }
    lines.push(`Buildings: ${[...g.entries()].map(([k, v]) => (v > 1 ? `${k} ×${v}` : k)).join('; ')}.`)
  }

  // --- tribe
  if (tribe) {
    const mem = w.members(tribe.id)
    const adults = mem.filter((m) => w.isAdult(m))
    const leader = w.agentById.get(tribe.leaderId)
    lines.push('')
    lines.push(`# Your tribe: the ${tribe.name} ${tribe.emblem}`)
    const kidsN = mem.length - adults.length
    lines.push(`${adults.length} adult${adults.length === 1 ? '' : 's'} and ${kidsN} ${kidsN === 1 ? 'child' : 'children'}. Leader: ${isLeader ? 'you' : leader?.name ?? 'none'}. Era: ${ERAS[eraOf(known)].name}.`)
    if (tribe.values) lines.push(`Values: ${tribe.values}`)
    if (tribe.beliefs) lines.push(`Beliefs: ${tribe.beliefs}`)
    if (tribe.laws.length) lines.push(`Laws:\n${tribe.laws.map((l, k) => `${k + 1}. ${l}`).join('\n')}`)
    lines.push(`Members: ${adults.slice(0, 14).map((m) => `${m.name}${m.id === a.id ? ' (you)' : ''}: ${m.task?.label?.toLowerCase() || 'idle'}`).join('; ')}.`)
    lines.push(`Known technology: ${[...known].map((k) => TECH_BY_ID[k]?.name ?? k).join(', ')}.`)
    const rs = tribe.research
    if (rs) lines.push(`Current research focus: ${TECH_BY_ID[rs.tech]?.name} (${Math.round((w.techProgress(tribe, rs.tech) / (TECH_BY_ID[rs.tech]?.cost ?? 1)) * 100)}% understood).`)
    const av = availableTechs(known)
    if (av.length) lines.push(`Ideas within reach: ${av.slice(0, 12).map((t) => `${t.name} (${Math.round((w.techProgress(tribe, t.id) / t.cost) * 100)}% understood, ${t.cost} pts — ${t.desc})`).join('; ')}.`)
    if (tribe.lost.length) lines.push(`Knowledge your people once had but lost: ${tribe.lost.map((x) => TECH_BY_ID[x]?.name ?? x).join(', ')}.`)
    lines.push(`Communal stores (${Math.floor(w.storageUsed(tribe))}/${w.storageCap(tribe.id)}): ${invToString(tribe.storage)}.`)
    lines.push(depositSummary(w, tribe.id, a))
    // the creed the people were given before history began, and what experience has taught them
    if (tribe.ai?.brief) lines.push(`The founding creed of your people (it shapes what you value; it is not an order): “${tribe.ai.brief.slice(0, 600)}”`)
    const lessons = lessonLines(tribe, 6)
    if (lessons.length) lines.push(`Lessons your people learned the hard way:\n${lessons.map((l) => `- ${l}`).join('\n')}`)
    const failed = a.memories.filter((m) => m.t > a.aiLastT - 12 * 60 && /couldn't|can't|cannot|refused|failed|not enough|must wait|declined/i.test(m.text)).slice(-3)
    if (failed.length) lines.push(`What went wrong for you recently: ${failed.map((m) => m.text).join(' · ')}`)
    const rel = describeRelations(w, tribe.id)
    lines.push(rel.length ? `Other peoples you know:\n${rel.map((r) => `- ${r}`).join('\n')}` : 'Other peoples: none met yet.')
    if (tribe.leaderId === a.id && rel.length) lines.push('You lead your people: war, peace, alliances, trade and tribute are your decisions (use "diplomacy").')
    const camp = w.dip.campaigns.find((c) => c.units.includes(a.id))
    if (camp) lines.push(`You are ${camp.kind === 'defend' ? 'defending your home against' : camp.phase === 'muster' ? 'mustering for war against' : camp.phase === 'battle' ? 'in battle against' : 'marching on'} the ${w.tribeById(camp.enemy)?.name}.`)
    const tb = w.tribeBuildings(tribe.id, undefined, false)
    const counts = new Map<string, number>()
    for (const b of tb) {
      const k = b.done ? b.type : `${b.type} (unfinished)`
      counts.set(k, (counts.get(k) ?? 0) + 1)
    }
    lines.push(`Tribe buildings: ${counts.size ? [...counts.entries()].map(([k, v]) => `${k}${v > 1 ? ` ×${v}` : ''}`).join(', ') : 'none yet'}.`)
    const housing = tb.filter((b) => b.done).reduce((s, b) => s + (BUILDINGS[b.type].housing ?? 0), 0)
    lines.push(`Housing for ${housing} of ${mem.length} people.`)
    const canBuild = BUILDING_TYPES.filter((t) => w.hasTech(tribe.id, BUILDINGS[t].tech))
    const ready = canBuild.filter((t) => t !== 'road' && w.missing(a, BUILDINGS[t].cost).length === 0)
    const almost = canBuild.filter((t) => !ready.includes(t) && t !== 'road').map((t) => `${t} (need ${w.missing(a, BUILDINGS[t].cost).join(', ')})`)
    if (ready.length) lines.push(`You could build right now: ${ready.join(', ')}.`)
    if (almost.length) lines.push(`Buildable once you have materials: ${almost.slice(0, 12).join('; ')}.`)
    const crafts = RECIPES.filter((r) => (!r.tech || known.has(r.tech)) && w.missing(a, r.inputs).length === 0).map((r) => r.id)
    if (crafts.length) lines.push(`You could craft right now (if you reach the right station): ${crafts.join(', ')}.`)
    const others = w.tribes.filter((t) => t.alive && t.id !== tribe.id)
    if (others.length) {
      lines.push(`Other peoples you know of: ${others.map((t) => {
        const rel = tribe.relations[t.id] ?? 0
        const d = Math.round(Math.hypot(t.center.x - tribe.center.x, t.center.y - tribe.center.y))
        return `the ${t.name} (${w.members(t.id).length} people, ${ERAS[eraOf(w.techs(t.id))].name}, ${d} paces ${dirWord(t.center.x - tribe.center.x, t.center.y - tribe.center.y)}, relations ${rel > 30 ? 'friendly' : rel < -30 ? 'hostile' : 'neutral'})`
      }).join('; ')}.`)
    }
  }

  // --- memory
  lines.push('')
  lines.push('# Your memories (oldest first)')
  const important = a.memories.filter((m) => m.imp >= 7).slice(-6)
  const recent = a.memories.slice(-16)
  const seen = new Set<string>()
  for (const m of [...important, ...recent]) {
    const key = m.t + m.text
    if (seen.has(key)) continue
    seen.add(key)
    lines.push(`- (${stamp(w, m.t)}) ${m.text}`)
  }
  if (!a.memories.length) lines.push('- You have just opened your eyes on this world. Everything is new.')
  const heard = a.heard.filter((h) => w.time - h.t < 36 * 60)
  if (heard.length) {
    lines.push('')
    lines.push('# Said to you recently')
    for (const h of heard.slice(-6)) lines.push(`- ${h.from} (${stamp(w, h.t)}): "${h.text}"`)
  }
  if (a.journal.length) {
    lines.push('')
    lines.push(`# Your last thoughts\n${a.journal.slice(-3).map((j) => `- (${stamp(w, j.t)}) ${j.text}`).join('\n')}`)
  }
  lines.push('')
  lines.push(`${SEASONS[w.season]} ${partOfDay(w.time)}. What do you think, say and do next?`)
  return lines.join('\n')
}

export const REFLECT_SYSTEM = `You are the reflective inner voice of a person living in Genesis, a world separate from ours. Looking back over recent experiences, distil what they mean to this person: patterns, lessons, how they feel about the people around them, and how they have changed. Answer as JSON with:
- insights: 1 to 3 short first-person lessons or realisations worth carrying forward (e.g. "Kael always shares food — I can trust him.").
- goal: their long-term goal, updated only if experience has changed it.
- self_image: one sentence on who they now believe they are.
- beliefs: one or two sentences on what they believe about life, the world, or the divine — this may evolve into their culture's philosophy or religion.`

export function buildReflection(w: World, a: Agent): string {
  const tribe = w.tribeById(a.tribeId)
  return [
    `Person: ${a.name}, ${Math.floor(w.age(a))}, of the ${tribe?.name ?? 'wilds'}. Personality: ${personality(a)}.`,
    `Current goal: ${a.goal || 'none yet'}. Self-image: ${a.selfImage || 'unformed'}. Beliefs: ${a.beliefs || 'unformed'}.`,
    `Earlier lessons: ${a.reflections.slice(-5).join(' | ') || 'none'}`,
    `Recent experiences:\n${a.memories.slice(-25).map((m) => `- (${stamp(w, m.t)}) ${m.text}`).join('\n')}`,
    `Recent thoughts:\n${a.journal.slice(-5).map((j) => `- ${j.text}`).join('\n') || '- none'}`,
  ].join('\n\n')
}

export const INTERVIEW_SYSTEM = `You are a person living in Genesis, a world entirely separate from ours. An unseen observer can hear your inner voice and asks you something. Answer in character, in first person, honestly and vividly, from your own memories, personality and situation — 1 to 3 short paragraphs. You only know your own world; you have never heard of Earth, computers, or anything outside it unless your people have invented it.`

export const CHRONICLE_SYSTEM = `You are the historian of Genesis, a world whose people began with nothing. Write the next chapter of its history from the year's records, like a great narrative history: concrete names, causes and consequences, the rise and fall of people and tribes, inventions and their effects, conflicts, loves and losses. Answer as JSON: {"title": a short evocative chapter title, "text": 2-4 paragraphs}.`

export const CHRONICLE_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: { title: { type: 'string' }, text: { type: 'string' } },
  required: ['title', 'text'],
  additionalProperties: false,
}
