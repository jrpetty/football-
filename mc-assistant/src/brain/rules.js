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
  platform: 'platform', floor: 'platform',
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

function parse(text) {
  if (!text) return null
  const t = text.toLowerCase().trim()

  // stop / hold — but "stay with/close/near/by me" means follow, not stop
  if (/\b(stop|halt|wait|stay(?!\s+(?:with|close|near|by))|hold|freeze|chill|stand down|nevermind|cancel)\b/.test(t)) {
    return { action: 'stop', args: {} }
  }

  // status / how are you
  if (/\b(status|report|how are you|you ok|sitrep|how's it going|health)\b/.test(t)) {
    return { action: 'status', args: {} }
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
      const dims = t.match(/\b(\d+)\s*(?:x|by)\s*(\d+)\b/)
      if (dims) { args.width = Number(dims[1]); args.length = Number(dims[2]) }
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

  // attack / kill — only treat the next word as a target if it's a known mob,
  // so "attack now" / "kill him" mean "nearest hostile", not a mob named "now".
  const atk = t.match(/\b(?:attack|kill|fight|slay)\b(?:\s+(?:the|that|a)?\s*([a-z_]+))?/)
  if (atk) {
    const target = atk[1] && KNOWN_MOBS.has(atk[1]) ? atk[1] : undefined
    return { action: 'attack', args: target ? { target } : {} }
  }

  // come here
  if (/\b(come|here|to me|by me|over here)\b/.test(t)) {
    return { action: 'come', args: {} }
  }

  return null
}

module.exports = { parse, RESOURCES }
