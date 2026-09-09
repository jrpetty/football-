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
        // bot.fish() only resolves on a bite — a bobber stuck on a block
        // would hang forever and wedge the job queue. Race a timeout that
        // reels the line back in and ends the trip.
        let timer
        const timeout = new Promise((_, reject) => {
          timer = setTimeout(() => {
            try { bot.activateItem() } catch (_) { /* reel in */ }
            reject(new Error('cast timed out'))
          }, 60_000)
        })
        try {
          await Promise.race([bot.fish(), timeout])
        } finally {
          clearTimeout(timer)
        }
        caught++
      } catch (err) {
        // Interrupted or timed-out cast — reel in and call it a day.
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
