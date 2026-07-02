'use strict'

const { goals } = require('mineflayer-pathfinder')
const { FOODS, ownerEntity } = require('../state')

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

// Which inventory stacks match a requested "thing" — accepts exact item names
// ("oak_log"), resource words ("wood" -> logs; see gather's DROP_MATCHERS),
// or loot/everything for all non-kit items.
function matchingStacks(bot, item) {
  const items = bot.inventory.items()
  if (!item || /^(everything|all|loot|it)$/i.test(String(item).trim())) {
    return items.filter((it) => !isKeeper(it.name))
  }
  const want = String(item).toLowerCase().trim().replace(/\s+/g, '_')
  const { DROP_MATCHERS } = require('./gather') // lazy: gather requires us back
  const dropMatch = DROP_MATCHERS[want]
  if (dropMatch) return items.filter((it) => dropMatch(it.name))
  return items.filter((it) => it.name === want || it.name.includes(want))
}

// Walk to the owner and toss items at their feet ("bring me the wood").
async function give(bot, { item, amount } = {}) {
  const owner = ownerEntity(bot)
  if (!owner) return "I can't see you to hand anything over — come closer."

  const stacks = matchingStacks(bot, item)
  if (stacks.length === 0) {
    return item ? `I'm not carrying any ${String(item).replace(/_/g, ' ')}.` : "I've got nothing to hand over."
  }

  bot.assistant.currentTask = 'bringing you the goods'
  try {
    const p = owner.position
    try {
      await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
    } catch (_) {
      return "I couldn't reach you to make the delivery."
    }
    // Face the owner so the toss lands at their feet.
    try { await bot.lookAt(owner.position.offset(0, 1, 0)) } catch (_) { /* fine */ }

    let remaining = amount ? Math.floor(amount) : Infinity
    let handed = 0
    for (const stack of stacks) {
      if (remaining <= 0) break
      const n = Math.min(stack.count, remaining)
      try {
        await bot.toss(stack.type, null, n)
        handed += n
        remaining -= n
      } catch (err) {
        bot.assistant.log.debug('toss failed:', err && err.message)
      }
    }
    return handed > 0 ? `Here you go — ${handed} items.` : "Couldn't toss the items over."
  } finally {
    bot.assistant.currentTask = null
  }
}

module.exports = { deposit, drop, give, matchingStacks, isKeeper }
