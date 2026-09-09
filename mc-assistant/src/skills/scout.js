'use strict'

const { goals } = require('mineflayer-pathfinder')
const { isFoodMob } = require('../state')
const memory = require('../memory')

// ---------------------------------------------------------------------------
// Scouting: head out in a direction, in ~25-block legs, noting the biomes it
// crosses, animal herds, and structure signatures (bells = village, spawners
// = dungeon, portals). Anything interesting is saved as a waypoint so "go to
// the village_1" works afterwards. Then it walks home and reports.
// ---------------------------------------------------------------------------

const DIRS = {
  north: { x: 0, z: -1 },
  south: { x: 0, z: 1 },
  east: { x: 1, z: 0 },
  west: { x: -1, z: 0 },
}

// Structure-signature blocks worth flagging and remembering.
const SIGNATURES = [
  { block: 'bell', label: 'village' },
  { block: 'spawner', label: 'dungeon' },
  { block: 'nether_portal', label: 'portal' },
  { block: 'end_portal_frame', label: 'stronghold' },
]

function facingDir(bot) {
  const yaw = bot.entity.yaw
  const dx = -Math.sin(yaw)
  const dz = -Math.cos(yaw)
  if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 'east' : 'west'
  return dz > 0 ? 'south' : 'north'
}

function biomeHere(bot) {
  try {
    const block = bot.blockAt(bot.entity.position.floored())
    return block && block.biome && block.biome.name ? String(block.biome.name) : null
  } catch (_) {
    return null
  }
}

// Save a find as a waypoint unless one of the same kind is already nearby.
function saveFind(bot, label, pos) {
  for (const entry of memory.listWaypoints()) {
    if (!entry.startsWith(label)) continue
    const m = entry.match(/\((-?\d+),(-?\d+),(-?\d+)\)/)
    if (m) {
      const d = Math.hypot(pos.x - Number(m[1]), pos.z - Number(m[3]))
      if (d < 48) return null // already known
    }
  }
  let name = label
  for (let i = 1; memory.getWaypoint(name); i++) name = `${label}_${i + 1}`
  memory.setWaypoint(name, pos, bot.game && bot.game.dimension, bot.assistant.log)
  return name
}

async function scout(bot, { direction, distance = 100 } = {}) {
  if (!bot.entity) return "I'm not in the world yet."
  const mcData = require('minecraft-data')(bot.version)

  const dirName = DIRS[String(direction || '').toLowerCase()] ? String(direction).toLowerCase() : facingDir(bot)
  const dir = DIRS[dirName]
  const dist = Math.max(30, Math.min(300, Math.floor(Number(distance)) || 100))

  const seq = bot.assistant.taskSeq
  const start = bot.entity.position.clone()
  bot.assistant.mode = 'scout'
  bot.assistant.busy = true
  bot.assistant.currentTask = `scouting ${dist} blocks ${dirName}`

  const biomes = []
  const animals = {}
  const finds = []
  let traveled = 0
  let blocked = false

  const sigIds = SIGNATURES
    .map((s) => ({ ...s, id: mcData.blocksByName[s.block] && mcData.blocksByName[s.block].id }))
    .filter((s) => s.id != null)

  try {
    const legs = Math.ceil(dist / 25)
    for (let leg = 1; leg <= legs; leg++) {
      if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) break

      const step = Math.min(25 * leg, dist)
      const tx = start.x + dir.x * step
      const tz = start.z + dir.z * step
      try {
        await bot.pathfinder.goto(new goals.GoalNearXZ(tx, tz, 4))
        traveled = step
      } catch (_) {
        blocked = true
        break
      }

      // What's here?
      const biome = biomeHere(bot)
      if (biome && biomes[biomes.length - 1] !== biome) biomes.push(biome)

      for (const id of Object.keys(bot.entities)) {
        const e = bot.entities[id]
        if (!e || !e.position || !isFoodMob(e)) continue
        if (e.position.distanceTo(bot.entity.position) > 24) continue
        animals[e.name] = (animals[e.name] || 0) + 1
      }

      for (const sig of sigIds) {
        const hit = bot.findBlock({ matching: sig.id, maxDistance: 32 })
        if (hit) {
          const saved = saveFind(bot, sig.label, hit.position)
          if (saved) finds.push(`a ${sig.label} (saved as "${saved}")`)
        }
      }
    }

    // Head home unless we were told to stop.
    if (bot.assistant.taskSeq === seq) {
      bot.assistant.currentTask = 'heading back from the scout'
      try {
        await bot.pathfinder.goto(new goals.GoalNear(start.x, start.y, start.z, 2))
      } catch (_) { /* report from wherever */ }
    }
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'scout') bot.assistant.mode = 'idle'
  }

  const bits = []
  if (biomes.length) bits.push(`terrain: ${biomes.map((b) => b.replace(/_/g, ' ')).join(' → ')}`)
  const herd = Object.entries(animals).sort((a, b) => b[1] - a[1]).slice(0, 4)
  if (herd.length) bits.push(`animals: ${herd.map(([n, c]) => `${c} ${n}${c > 1 ? 's' : ''}`).join(', ')}`)
  if (finds.length) bits.push(`found ${finds.join(' and ')}`)
  if (blocked) bits.push('the route got impassable partway')
  if (bits.length === 0) bits.push('nothing notable out there')
  return `Scouted ${traveled} blocks ${dirName}: ${bits.join('; ')}.`
}

module.exports = { scout, DIRS, facingDir, SIGNATURES }
