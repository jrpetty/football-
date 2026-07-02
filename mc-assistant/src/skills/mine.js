'use strict'

const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const torch = require('./torch')

// ---------------------------------------------------------------------------
// Mining trips: descend to a target Y level (pathfinder digs a safe route),
// carve a 1x2 branch tunnel, torch it every 8 blocks, grab every ore vein the
// tunnel exposes, then walk back to the start. Stops honestly at hazards
// (water/lava ahead) and respects tool tiers via the same harvest rules.
// ---------------------------------------------------------------------------

const LIQUIDS = new Set(['water', 'lava', 'flowing_water', 'flowing_lava'])

function isOre(name) {
  return name.endsWith('_ore') || name === 'ancient_debris'
}

function canDigWith(bot, block) {
  if (block.canHarvest(null)) return true
  return bot.inventory.items().some((it) => block.canHarvest(it.type))
}

// Dig one block (equipping the best tool) — returns false on failure.
async function digBlock(bot, block) {
  try {
    if (bot.tool && bot.tool.equipForBlock) {
      await bot.tool.equipForBlock(block, {})
    }
    await bot.dig(block)
    return true
  } catch (err) {
    bot.assistant.log.debug('dig failed:', err && err.message)
    return false
  }
}

// Collect an exposed ore vein (follows adjacent matching ore blocks, capped).
async function mineVein(bot, startBlock, cap = 12) {
  let mined = 0
  const queue = [startBlock.position]
  const seen = new Set()
  while (queue.length > 0 && mined < cap) {
    const pos = queue.shift()
    const key = `${pos.x},${pos.y},${pos.z}`
    if (seen.has(key)) continue
    seen.add(key)
    const block = bot.blockAt(pos)
    if (!block || !isOre(block.name) || !canDigWith(bot, block)) continue
    try {
      await bot.collectBlock.collect(block)
      mined++
    } catch (_) { continue }
    for (const d of [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]]) {
      queue.push(pos.offset(d[0], d[1], d[2]))
    }
  }
  return mined
}

async function mine(bot, { y, length = 24 } = {}) {
  if (!bot.entity) return "I'm not in the world yet."
  const mcData = require('minecraft-data')(bot.version)

  // Need at least a pickaxe of some kind to bother.
  const hasPick = bot.inventory.items().some((it) => it.name.endsWith('_pickaxe'))
  if (!hasPick) return "I can't go mining without a pickaxe — ask me to craft one."

  const seq = bot.assistant.taskSeq
  const tunnelLen = Math.max(4, Math.min(48, Math.floor(Number(length)) || 24))
  const start = bot.entity.position.clone()

  bot.assistant.mode = 'mine'
  bot.assistant.busy = true
  bot.assistant.currentTask = 'on a mining trip'

  let ores = 0
  let dug = 0
  let stoppedBy = null
  try {
    // --- 1) Get to the target depth (pathfinder digs its own safe way). ---
    if (y !== undefined && y !== null && Number.isFinite(Number(y))) {
      const targetY = Math.max(-58, Math.min(200, Math.floor(Number(y))))
      bot.assistant.currentTask = `digging down to y${targetY}`
      try {
        await bot.pathfinder.goto(new goals.GoalY(targetY))
      } catch (_) {
        return `Couldn't find a way down to y${targetY} from here.`
      }
    }

    // --- 2) Carve the branch tunnel along the facing cardinal. ---
    const yaw = bot.entity.yaw
    const dx = -Math.sin(yaw)
    const dz = -Math.cos(yaw)
    const forward = Math.abs(dx) > Math.abs(dz)
      ? new Vec3(Math.sign(dx) || 1, 0, 0)
      : new Vec3(0, 0, Math.sign(dz) || 1)

    const base = bot.entity.position.floored()
    bot.assistant.currentTask = `tunneling ${tunnelLen} blocks`

    for (let i = 1; i <= tunnelLen; i++) {
      if (bot.assistant.taskSeq !== seq) { stoppedBy = 'orders'; break }
      if (bot.assistant.combat || bot.assistant.fleeing) { stoppedBy = 'a fight'; break }

      const feet = base.plus(forward.scaled(i))
      const head = feet.offset(0, 1, 0)

      // Hazard check one step ahead before we open the wall.
      for (const probe of [feet, head, feet.plus(forward), head.plus(forward)]) {
        const b = bot.blockAt(probe)
        if (b && LIQUIDS.has(b.name)) { stoppedBy = `${b.name.replace('flowing_', '')} ahead`; break }
      }
      if (stoppedBy) break

      for (const pos of [head, feet]) {
        const block = bot.blockAt(pos)
        if (!block || block.name === 'air' || block.name === 'cave_air') continue
        if (!canDigWith(bot, block)) { stoppedBy = `a block I can't mine (${block.name})`; break }
        if (!(await digBlock(bot, block))) { stoppedBy = 'a stubborn block'; break }
        dug++
      }
      if (stoppedBy) break

      // Step into the fresh section (also scoops up drops).
      try {
        await bot.pathfinder.goto(new goals.GoalNear(feet.x, feet.y, feet.z, 0.5))
      } catch (_) { /* close enough */ }

      // Grab any ore the new walls expose.
      for (const d of [[1,0,0],[-1,0,0],[0,0,1],[0,0,-1],[0,-1,0],[0,2,0]]) {
        for (const cell of [feet, head]) {
          const b = bot.blockAt(cell.offset(d[0], d[1], d[2]))
          if (b && isOre(b.name) && canDigWith(bot, b)) {
            ores += await mineVein(bot, b)
          }
        }
      }

      // Light the tunnel as we go.
      if (i % 8 === 0) await torch.placeTorch(bot)
    }

    // --- 3) Walk home to the start point. ---
    bot.assistant.currentTask = 'heading back from the mine'
    try {
      await bot.pathfinder.goto(new goals.GoalNear(start.x, start.y, start.z, 2))
    } catch (_) { /* report from wherever we ended up */ }
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'mine') bot.assistant.mode = 'idle'
  }

  const bits = [`dug ${dug} blocks`, `collected ${ores} ore${ores === 1 ? '' : 's'}`]
  if (stoppedBy && stoppedBy !== 'orders') bits.push(`stopped early — ${stoppedBy}`)
  return `Mining trip done: ${bits.join(', ')}.`
}

module.exports = { mine, isOre, mineVein }
