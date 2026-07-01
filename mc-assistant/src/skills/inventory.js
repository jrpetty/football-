'use strict'

const { goals } = require('mineflayer-pathfinder')
const { FOODS } = require('../state')

// Inventory management: stash loot into a nearby chest, or drop items on demand.

// Things the bot keeps on itself even when depositing (its kit + food).
function isKeeper(name) {
  return (
    FOODS.has(name) ||
    /_sword$|_axe$|_pickaxe$|_shovel$|_hoe$/.test(name) ||
    /_helmet$|_chestplate$|_leggings$|_boots$/.test(name) ||
    name === 'shield' || name === 'torch' || name === 'bow' || name === 'crossbow' || name === 'arrow'
  )
}

async function deposit(bot) {
  const mcData = require('minecraft-data')(bot.version)
  const chestIds = ['chest', 'trapped_chest', 'barrel']
    .map((n) => mcData.blocksByName[n] && mcData.blocksByName[n].id)
    .filter((v) => v != null)

  const chestBlock = bot.findBlock({ matching: chestIds, maxDistance: 32 })
  if (!chestBlock) return 'No chest or barrel within 32 blocks.'

  bot.assistant.currentTask = 'depositing loot'
  try {
    const p = chestBlock.position
    await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
    const container = await bot.openContainer(chestBlock)
    let moved = 0
    for (const item of bot.inventory.items()) {
      if (isKeeper(item.name)) continue
      try {
        await container.deposit(item.type, null, item.count)
        moved += item.count
      } catch (_) { /* chest full or slot issue — skip */ }
    }
    container.close()
    bot.assistant.currentTask = null
    return moved > 0 ? `Stashed ${moved} items.` : 'Nothing to stash (kept my kit and food).'
  } catch (err) {
    bot.assistant.currentTask = null
    bot.assistant.log.debug('deposit failed:', err && err.message)
    return "Couldn't reach or open the chest."
  }
}

async function drop(bot, { item, amount } = {}) {
  if (!item) return 'Tell me what to drop.'
  const target = String(item).toLowerCase()
  const stacks = bot.inventory.items().filter((it) => it.name === target || it.name.includes(target))
  if (stacks.length === 0) return `I'm not carrying any ${item}.`
  let remaining = amount ? Math.floor(amount) : Infinity
  let dropped = 0
  for (const stack of stacks) {
    if (remaining <= 0) break
    const n = Math.min(stack.count, remaining)
    try {
      await bot.toss(stack.type, null, n)
      dropped += n
      remaining -= n
    } catch (_) { /* ignore */ }
  }
  return dropped > 0 ? `Dropped ${dropped} ${target}.` : `Couldn't drop ${item}.`
}

module.exports = { deposit, drop, isKeeper }
