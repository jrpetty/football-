'use strict'

// ---------------------------------------------------------------------------
// Offline "brain": a keyword parser that turns plain chat into actions with no
// API key required. It's deliberately forgiving. When an Anthropic key is
// present, llm.js takes over and this becomes the fallback for when the API is
// unreachable. Returns { action, args } or null if nothing matched.
// ---------------------------------------------------------------------------

const RESOURCES = [
  'wood', 'log', 'stone', 'cobblestone', 'coal', 'iron', 'gold', 'diamond',
  'redstone', 'lapis', 'copper', 'emerald', 'dirt', 'sand', 'gravel', 'netherrack',
]

function parse(text) {
  if (!text) return null
  const t = text.toLowerCase().trim()

  // stop / hold
  if (/\b(stop|halt|wait|stay|hold|freeze|chill|stand down|nevermind|cancel)\b/.test(t)) {
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
  if (/\b(follow|come with|stick with|stay with|tag along)\b/.test(t)) {
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

  // goto coordinates
  const coord = t.match(/\b(?:go\s*to|goto|head to|travel to|move to)\b[^-\d]*(-?\d+)[ ,]+(-?\d+)[ ,]+(-?\d+)/)
  if (coord) {
    return { action: 'goto', args: { x: Number(coord[1]), y: Number(coord[2]), z: Number(coord[3]) } }
  }

  // hunt for food
  if (/\b(hunt|get food|find food|kill.*(cow|pig|chicken|sheep|animal)|get me food)\b/.test(t)) {
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

  // attack / kill (optionally a named target)
  const atk = t.match(/\b(?:attack|kill|fight|slay)\b(?:\s+(?:the|that)?\s*([a-z_]+))?/)
  if (atk) {
    const target = atk[1] && !['it', 'them', 'that', 'the'].includes(atk[1]) ? atk[1] : undefined
    return { action: 'attack', args: target ? { target } : {} }
  }

  // come here
  if (/\b(come|here|to me|by me|over here)\b/.test(t)) {
    return { action: 'come', args: {} }
  }

  return null
}

module.exports = { parse, RESOURCES }
