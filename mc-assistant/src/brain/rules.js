'use strict'

// ---------------------------------------------------------------------------
// Offline "brain": a keyword parser that turns plain chat into actions with no
// API key required. It's deliberately forgiving. When an Anthropic key is
// present, llm.js takes over and this becomes the fallback for when the API is
// unreachable. Returns { action, args } or null if nothing matched.
// ---------------------------------------------------------------------------

const { HOSTILES, FOOD_MOBS } = require('../state')

const RESOURCES = [
  'wood', 'log', 'stone', 'cobblestone', 'coal', 'iron', 'gold', 'diamond',
  'redstone', 'lapis', 'copper', 'emerald', 'dirt', 'sand', 'gravel', 'netherrack',
]

// Structure words -> canonical blueprint names (see skills/build.js).
const STRUCTURES = {
  wall: 'wall', walls: 'wall',
  house: 'house', hut: 'house', shelter: 'house', cabin: 'house', base: 'house', shack: 'house', home: 'house',
  tower: 'tower', turret: 'tower',
  pillar: 'pillar', column: 'pillar', post: 'pillar',
  platform: 'platform', floor: 'platform', pad: 'platform',
  bridge: 'bridge', walkway: 'bridge',
}

const MATERIAL_WORDS = [
  'cobblestone', 'cobble', 'deepslate', 'sandstone', 'stone', 'dirt',
  'planks', 'plank', 'logs', 'log', 'wood', 'sand', 'bricks', 'brick',
  'netherrack', 'glass',
]

// Mob names the offline parser accepts as attack targets; anything else after
// "attack"/"kill" (e.g. "attack now", "kill him") means "nearest hostile".
const KNOWN_MOBS = new Set([...HOSTILES, ...FOOD_MOBS, 'enderman', 'wolf', 'piglin', 'iron_golem', 'zombified_piglin'])

// Words "smelt/cook X" understands offline (mapped further in skills/smelt.js).
const SMELTABLE_WORDS = [
  'iron', 'gold', 'copper', 'meat', 'food', 'fish', 'beef', 'porkchop', 'pork',
  'chicken', 'mutton', 'rabbit', 'cod', 'salmon', 'potatoes', 'potato', 'kelp',
  'sand', 'glass', 'cobblestone', 'stone', 'clay',
]

// Things "craft/make X" understands offline. Longest first so "stone pickaxe"
// wins over "pickaxe" and "crafting table" over "table".
const CRAFTABLES = [
  'crafting table', 'crafting_table', 'workbench',
  'wooden pickaxe', 'stone pickaxe', 'iron pickaxe',
  'wooden axe', 'stone axe', 'iron axe',
  'wooden sword', 'stone sword', 'iron sword',
  'wooden shovel', 'stone shovel', 'iron shovel',
  'fishing rod', 'fishing_rod', 'rod',
  'pickaxe', 'sword', 'shovel', 'hoe',
  'planks', 'sticks', 'stick', 'torches', 'torch',
  'furnace', 'chest', 'shield', 'ladder', 'boat', 'bed',
  'axe',
]

function parse(text) {
  if (!text) return null
  const t = text.toLowerCase().trim()

  // stop / hold — but "stay with me" means follow, and "hold the sword" means
  // equip; "hold" only stops when it means hold position.
  if (/\b(stop|halt|wait|stay(?!\s+(?:with|close|near|by))|hold(?=\s*$|\s+(?:position|up|on|still|here|it)\b)|freeze|chill|stand down|nevermind|cancel)\b/.test(t)) {
    return { action: 'stop', args: {} }
  }

  // job queue status
  if (/^(jobs?|queue|what(?:'s| is) (?:next|queued))[!.?\s]*$/.test(t)) {
    return { action: 'jobs', args: {} }
  }

  // status / how are you
  if (/\b(status|report|how are you|you ok|sitrep|how's it going|health)\b/.test(t)) {
    return { action: 'status', args: {} }
  }

  // recover dropped gear (before inventory — "get your items back" is not an
  // inv query; but plain mentions of "your gear" belong to other branches)
  if (/\brecover\b|\b(?:get|grab|fetch|go(?:\s+back)?\s+for)\b.*\byour (stuff|items|gear)\b/.test(t)) {
    return { action: 'recover', args: {} }
  }

  // inventory
  if (/\b(inventory|what do you have|what are you carrying|items)\b/.test(t)) {
    return { action: 'inventory', args: {} }
  }

  // follow
  if (/\b(follow|come with|stick with|stay (?:with|close|near|by)|tag along)\b/.test(t)) {
    return { action: 'follow', args: {} }
  }

  // guard / protect
  if (/\b(guard|protect|defend me|watch my back|cover me|keep me safe|bodyguard)\b/.test(t)) {
    return { action: 'guard', args: {} }
  }

  // sleep
  if (/\b(sleep|go to bed|take a nap|bedtime)\b/.test(t)) {
    return { action: 'sleep', args: {} }
  }

  // patrol
  const pat = t.match(/\bpatrol\b(?:.*?\b(\d+)\b)?/)
  if (pat) {
    return { action: 'patrol', args: pat[1] ? { radius: Number(pat[1]) } : {} }
  }

  // place a torch
  if (/\b(?:place|put|drop)\s+(?:a\s+|some\s+)?torch(es)?\b|\blight (?:it|this|the area|this place) up\b/.test(t)) {
    return { action: 'torch', args: {} }
  }

  // chest labels — BEFORE waypoint-remember ("remember this chest as ores"
  // is storage, not a place)
  const lc = t.match(/\b(?:remember|mark|label)\b[^.]*?\bchest\b[^.]*?\bas\s+([a-z0-9_ ]+)/) ||
    t.match(/\blabel\s+(?:this\s+|that\s+)?chest\s+(?:as\s+)?([a-z0-9_ ]+)/)
  if (lc) {
    const name = lc[1].replace(/\b(please|now|thanks)\b/g, '').trim()
    return { action: 'labelchest', args: { name } }
  }
  if (/^(chests|chest labels|storage)[!.?\s]*$/.test(t)) {
    return { action: 'chests', args: {} }
  }

  // enchanting
  const en = t.match(/\benchant\b(?:\s+(?:my|your|the|an?))?\s*([a-z_ ]+)?/)
  if (en) {
    const raw = (en[1] || '').replace(/\b(please|now|gear|something|stuff)\b/g, '').trim()
    return { action: 'enchant', args: raw ? { item: raw } : {} }
  }

  // scouting / exploring
  const sc = t.match(/\b(?:scout|explore)\b(?:\s+(?:out\s+|to\s+the\s+)?(north|south|east|west))?(?:\s+(\d+))?/)
  if (sc) {
    const args = {}
    if (sc[1]) args.direction = sc[1]
    if (sc[2]) args.distance = Number(sc[2])
    return { action: 'scout', args }
  }

  // waypoints: remember / forget / list / go home
  const rem = t.match(/\b(?:remember|mark|save)\b.*?\b(?:as|this is)\s+([a-z0-9_ ]+)/) ||
    t.match(/\bset\s+([a-z0-9_ ]+?)\s+here\b/)
  if (rem) {
    const name = rem[1]
      .replace(/\b(please|now|thanks|thank you|mate|bro|for me)\b/g, '')
      .trim()
    return { action: 'remember', args: { name } }
  }
  if (/\bthis is home\b|\bset home\b/.test(t)) {
    return { action: 'remember', args: { name: 'home' } }
  }
  const fg = t.match(/\bforget\s+(?:the\s+)?([a-z0-9_ ]+)/)
  if (fg) {
    return { action: 'forget', args: { name: fg[1].trim() } }
  }
  if (/\b(waypoints|saved places|where can you go|locations)\b/.test(t)) {
    return { action: 'waypoints', args: {} }
  }
  if (/\b(?:go|head|get|walk|run|return)\b.{0,12}\bhome\b|^home[!.?\s]*$/.test(t)) {
    return { action: 'gowaypoint', args: { name: 'home' } }
  }

  // withdraw from storage — before gather, so "get stone from the chest"
  // isn't a mining request.
  const wd = t.match(/\b(?:get|grab|take|fetch|bring)\b\s+(?:me\s+)?(?:(\d+)\s+)?(?:the\s+|my\s+|some\s+)?([a-z_ ]+?)\s+(?:out\s+)?(?:of|from)\s+(?:the\s+)?(?:chest|barrel|storage)\b/)
  if (wd) {
    return { action: 'withdraw', args: { item: wd[2].trim(), amount: wd[1] ? Number(wd[1]) : undefined } }
  }

  // deposit / stash
  if (/\b(deposit|stash|store|put.*chest|unload)\b/.test(t)) {
    return { action: 'deposit', args: {} }
  }

  // build a structure — must run before gather, so "build a stone wall"
  // doesn't read "stone" as a mining request.
  if (/\b(build|construct|erect|make|create)\b/.test(t)) {
    const word = Object.keys(STRUCTURES).find((k) => new RegExp(`\\b${k}\\b`).test(t))
    if (word) {
      const args = { structure: STRUCTURES[word] }
      // "NxM" means different things per layout: a 10x4 wall is long x high,
      // a 4x8 tower is wide x high, a 5x5 platform/house is wide x long.
      const dims = t.match(/\b(\d+)\s*(?:x|by)\s*(\d+)\b/)
      if (dims) {
        const [a, b] = [Number(dims[1]), Number(dims[2])]
        if (args.structure === 'wall') { args.length = a; args.height = b }
        else if (args.structure === 'tower') { args.width = a; args.height = b }
        else if (args.structure === 'pillar') { args.height = b }
        else { args.width = a; args.length = b }
      }
      const high = t.match(/\b(\d+)\s*(?:blocks?\s*)?(?:high|tall)\b/)
      if (high) args.height = Number(high[1])
      const long = t.match(/\b(\d+)\s*(?:blocks?\s*)?long\b/)
      if (long) args.length = Number(long[1])
      const wide = t.match(/\b(\d+)\s*(?:blocks?\s*)?wide\b/)
      if (wide) args.width = Number(wide[1])
      const mat = MATERIAL_WORDS.find((m) => new RegExp(`\\b${m}\\b`).test(t))
      if (mat) args.material = mat
      return { action: 'build', args }
    }
  }

  // goto coordinates
  const coord = t.match(/\b(?:go\s*to|goto|head to|travel to|move to)\b[^-\d]*(-?\d+)[ ,]+(-?\d+)[ ,]+(-?\d+)/)
  if (coord) {
    return { action: 'goto', args: { x: Number(coord[1]), y: Number(coord[2]), z: Number(coord[3]) } }
  }

  // smelt / cook — before gather ("smelt iron" must not become a mining trip)
  if (/\b(smelt|cook|bake|roast)\b/.test(t)) {
    const word = SMELTABLE_WORDS.find((w) => new RegExp(`\\b${w}\\b`).test(t))
    const amt = t.match(/\b(\d+)\b/)
    return { action: 'smelt', args: { item: word || 'meat', amount: amt ? Number(amt[1]) : 8 } }
  }

  // farm / harvest crops — before gather ("harvest the wheat")
  if (/\b(farm|crops?|replant|wheat|carrots?|potato(es)?|beetroots?)\b/.test(t) &&
      /\b(farm|harvest|tend|plant|replant|pick)\b/.test(t)) {
    return { action: 'farm', args: {} }
  }

  // give / hand over what it's carrying
  const giveMatch = t.match(/\b(?:give me|hand me|hand over|pass me)\b\s*(?:the|my|your|some)?\s*(?:(\d+)\s+)?([a-z_ ]+)?/)
  if (giveMatch) {
    const raw = (giveMatch[2] || '')
      .replace(/\b(please|now|thanks|thank you|mate|bro)\b/g, '')
      .trim()
    const item = raw && !/^(everything|all|loot|items|stuff)$/.test(raw) ? raw : undefined
    const args = item ? { item } : {}
    if (giveMatch[1] && item) args.amount = Number(giveMatch[1])
    return { action: 'give', args }
  }

  // bring me X — gather it (if needed) and deliver it
  if (/\b(bring|deliver|fetch)\b.*\bme\b|\bbring me\b/.test(t)) {
    const res = RESOURCES.find((r) => new RegExp(`\\b${r}s?\\b`).test(t))
    if (res) {
      const amt = t.match(/\b(\d+)\b/)
      const resource = res === 'log' ? 'wood' : res
      return { action: 'gather', args: { resource, amount: amt ? Number(amt[1]) : 8, deliver: true } }
    }
  }

  // breeding
  const br = t.match(/\bbreed(?:ing)?\b(?:\s+(?:the|some|my|your))?\s*([a-z]+)?/)
  if (br) {
    const animal = br[1] && !['them', 'animals', 'something'].includes(br[1]) ? br[1] : undefined
    return { action: 'breed', args: animal ? { animal } : {} }
  }

  // craft an item — after build (structure words win), before gather so
  // "craft a stone pickaxe" doesn't read "stone" as a mining request.
  if (/\b(craft|make|create|whip up)\b/.test(t)) {
    const found = CRAFTABLES.find((c) => new RegExp(`\\b${c.replace(/_/g, '[_ ]')}\\b`).test(t))
    if (found) {
      const amt = t.match(/\b(\d+)\b/)
      return { action: 'craft', args: { item: found.replace(/ /g, '_'), amount: amt ? Number(amt[1]) : 1 } }
    }
  }

  // equip / hold a tool ("take out" removed — "take out the zombie" is an attack)
  const eq = t.match(/\b(?:equip|hold|wield|draw)\b\s+(?:your|the|a|an)?\s*([a-z_ ]+)/)
  if (eq) {
    return { action: 'equip', args: { item: eq[1].trim() } }
  }

  // fishing — after craft/equip so "craft a fishing rod" stays a craft
  if (/\b(fish|fishing)\b/.test(t)) {
    const amt = t.match(/\b(\d+)\b/)
    return { action: 'fish', args: amt ? { amount: Number(amt[1]) } : {} }
  }

  // hunt for food
  if (/\b(hunt|kill.*(cow|pig|chicken|sheep|animal)|(?:get|find|grab|make|fetch).{0,12}food)\b/.test(t)) {
    return { action: 'hunt', args: {} }
  }

  // eat
  if (/\b(eat|feed yourself|have a snack)\b/.test(t)) {
    return { action: 'eat', args: {} }
  }

  // drop
  const drop = t.match(/\b(?:drop|toss|throw)\b\s+(?:(\d+)\s+)?([a-z_]+)/)
  if (drop) {
    return { action: 'drop', args: { item: drop[2], amount: drop[1] ? Number(drop[1]) : undefined } }
  }

  // gather / mine / collect a resource
  if (/\b(gather|mine|collect|get|chop|dig|harvest|fetch|bring)\b/.test(t)) {
    const res = RESOURCES.find((r) => new RegExp(`\\b${r}s?\\b`).test(t))
    if (res) {
      const amt = t.match(/\b(\d+)\b/)
      const resource = res === 'log' ? 'wood' : res
      return { action: 'gather', args: { resource, amount: amt ? Number(amt[1]) : 8 } }
    }
  }

  // mining trip — after gather ("mine 3 iron" stays a gather); "mine to y11",
  // "branch mine", "go mining"
  const mt = t.match(/\b(?:strip|branch)\s*mine\b|\bmine\b\s+(?:down\s+)?(?:to|at)\s+y?\s*(-?\d+)|\bgo\s+mining\b/)
  if (mt) {
    const args = {}
    if (mt[1] !== undefined) args.y = Number(mt[1])
    const len = t.match(/\b(\d+)\s+blocks?\b/)
    if (len) args.length = Number(len[1])
    return { action: 'mine', args }
  }

  // attack / kill — only treat the next word as a target if it's a known mob,
  // so "attack now" / "kill him" mean "nearest hostile", not a mob named "now".
  const atk = t.match(/\b(?:attack|kill|fight|slay|take\s+out)\b(?:\s+(?:the|that|a)?\s*([a-z_]+))?/)
  if (atk) {
    const target = atk[1] && KNOWN_MOBS.has(atk[1]) ? atk[1] : undefined
    return { action: 'attack', args: target ? { target } : {} }
  }

  // "go to the mine" — a named waypoint (checked at runtime; unknown names
  // get a friendly "I don't know that place" from the action itself).
  const wpGo = t.match(/\b(?:go|head|travel|walk|return)\s+(?:back\s+)?to\s+(?:the\s+)?([a-z][a-z0-9_]*)\b/)
  if (wpGo && !['sleep', 'bed', 'me', 'him', 'her', 'them', 'it', 'chest', 'water', 'work'].includes(wpGo[1])) {
    return { action: 'gowaypoint', args: { name: wpGo[1] } }
  }

  // come here
  if (/\b(come|here|to me|by me|over here)\b/.test(t)) {
    return { action: 'come', args: {} }
  }

  return null
}

module.exports = { parse, RESOURCES }
