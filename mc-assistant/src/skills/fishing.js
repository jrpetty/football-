'use strict'

const { goals } = require('mineflayer-pathfinder')

// Fishing — a rod, open water, and patience. Each bot.fish() call is one full
// cast-and-catch cycle; drops land in the inventory automatically.

function rodItem(bot) {
  return bot.inventory.items().find((it) => it.name === 'fishing_rod') || null
}

function findWater(bot, maxDistance = 24) {
  const mcData = require('minecraft-data')(bot.version)
  const water = mcData.blocksByName.water
  if (!water) return null
  // Water with air above = castable surface.
  const spots = bot.findBlocks({ matching: water.id, maxDistance, count: 24 })
  for (const pos of spots) {
    const above = bot.blockAt(pos.offset(0, 1, 0))
    if (above && (above.name === 'air' || above.name === 'cave_air')) return bot.blockAt(pos)
  }
  return null
}

async function fish(bot, { amount = 3 } = {}) {
  const rod = rodItem(bot)
  if (!rod) return "I don't have a fishing rod — craft me one (3 sticks + 2 string)."
  const water = findWater(bot)
  if (!water) return 'No open water within 24 blocks to fish in.'

  const seq = bot.assistant.taskSeq
  const want = Math.max(1, Math.min(16, Math.floor(Number(amount)) || 3))
  bot.assistant.mode = 'fish'
  bot.assistant.busy = true
  bot.assistant.currentTask = 'fishing'

  let caught = 0
  try {
    const p = water.position
    try {
      await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y + 1, p.z, 3))
    } catch (_) {
      return "I couldn't get to the water's edge."
    }

    while (caught < want) {
      if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) break
      try {
        await bot.equip(rodItem(bot), 'hand')
        await bot.lookAt(water.position.offset(0.5, 1, 0.5))
        await bot.fish() // one full cast; resolves when something bites
        caught++
      } catch (err) {
        // Interrupted cast (moved/hit) — one retry-worthy failure, then bail.
        bot.assistant.log.debug('cast failed:', err && err.message)
        break
      }
    }
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'fish') bot.assistant.mode = 'idle'
  }

  if (caught === 0) return "The fish weren't biting — nothing caught."
  return `Caught ${caught} thing${caught === 1 ? '' : 's'} from the water.`
}

module.exports = { fish, rodItem, findWater }
