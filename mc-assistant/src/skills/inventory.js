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

// ----------------------------- labeled storage ------------------------------
// Chests can be labeled ("remember this chest as ores"); deposit then sorts
// loot into matching chests and withdraw checks the right chest first.
// Category labels below get smart matching; any other label matches items by
// name (a chest labeled "torches" receives torches).

const LABEL_MATCHERS = {
  ores: (n) => /(_ore$|^raw_|^coal$|^diamond$|^emerald$|^redstone$|^lapis_lazuli$|_ingot$|^ancient_debris$|^netherite_scrap$|^quartz$)/.test(n),
  food: (n) => FOODS.has(n),
  wood: (n) => /(_log$|_planks$|^stick$|_sapling$|_stem$)/.test(n),
  blocks: (n) => /^(cobblestone|stone|dirt|coarse_dirt|gravel|sand|red_sand|andesite|diorite|granite|tuff|netherrack|cobbled_deepslate|deepslate|sandstone)$/.test(n),
  tools: (n) => /(_pickaxe$|_axe$|_shovel$|_hoe$|_sword$|^bow$|^crossbow$|^shield$|^fishing_rod$|_helmet$|_chestplate$|_leggings$|_boots$)/.test(n),
  farm: (n) => /(seeds$|^wheat$|^carrot$|^potato$|^beetroot$|^melon_slice$|^pumpkin$|^sugar_cane$|^egg$|^bone_meal$)/.test(n),
}

function matcherForLabel(label) {
  if (LABEL_MATCHERS[label]) return LABEL_MATCHERS[label]
  // Accept the label and its singular forms ("torches" -> torch, "arrows" -> arrow).
  const forms = new Set([label])
  if (label.endsWith('es')) forms.add(label.slice(0, -2))
  if (label.endsWith('s')) forms.add(label.slice(0, -1))
  return (n) => [...forms].some((f) => f.length >= 3 && (n === f || n.includes(f)))
}

async function openChestAt(bot, pos) {
  const { Vec3 } = require('vec3')
  const block = bot.blockAt(new Vec3(pos.x, pos.y, pos.z))
  if (!block || !/chest|barrel/.test(block.name)) return null
  await bot.pathfinder.goto(new goals.GoalNear(pos.x, pos.y, pos.z, 2))
  return bot.openContainer(block)
}

async function deposit(bot) {
  const memory = require('../memory')
  const mcData = require('minecraft-data')(bot.version)
  const chestIds = ['chest', 'trapped_chest', 'barrel']
    .map((n) => mcData.blocksByName[n] && mcData.blocksByName[n].id)
    .filter((v) => v != null)

  bot.assistant.currentTask = 'depositing loot'
  let moved = 0
  const sortedInto = []
  try {
    // 1) Sort into labeled chests first.
    for (const { name, pos } of memory.listChests()) {
      const match = matcherForLabel(name)
      const toStash = bot.inventory.items().filter((it) => !isKeeper(it.name) && match(it.name))
      if (toStash.length === 0) continue
      try {
        const container = await openChestAt(bot, pos)
        if (!container) continue // chest was removed — leave the label for now
        let here = 0
        for (const item of toStash) {
          try {
            await container.deposit(item.type, null, item.count)
            here += item.count
          } catch (_) { /* chest full — skip */ }
        }
        container.close()
        if (here > 0) { moved += here; sortedInto.push(`${here} → ${name}`) }
      } catch (err) {
        bot.assistant.log.debug(`deposit to "${name}" failed:`, err && err.message)
      }
    }

    // 2) Everything left goes to the nearest chest (old behavior).
    const leftovers = bot.inventory.items().filter((it) => !isKeeper(it.name))
    if (leftovers.length > 0) {
      const chestBlock = bot.findBlock({ matching: chestIds, maxDistance: 32 })
      if (!chestBlock && moved === 0) return 'No chest or barrel within 32 blocks.'
      if (chestBlock) {
        const p = chestBlock.position
        await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
        const container = await bot.openContainer(chestBlock)
        for (const item of bot.inventory.items()) {
          if (isKeeper(item.name)) continue
          try {
            await container.deposit(item.type, null, item.count)
            moved += item.count
          } catch (_) { /* chest full or slot issue — skip */ }
        }
        container.close()
      }
    }

    if (moved === 0) return 'Nothing to stash (kept my kit and food).'
    return sortedInto.length > 0
      ? `Stashed ${moved} items (sorted: ${sortedInto.join(', ')}).`
      : `Stashed ${moved} items.`
  } catch (err) {
    bot.assistant.log.debug('deposit failed:', err && err.message)
    return moved > 0 ? `Stashed ${moved} items, then lost access to the chest.` : "Couldn't reach or open the chest."
  } finally {
    bot.assistant.currentTask = null
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

// Fetch items back OUT of storage ("get me arrows from the chest"). Labeled
// chests whose label matches the item are checked first, then nearby chests.
async function withdraw(bot, { item, amount } = {}) {
  if (!item) return 'Tell me what to fetch from storage.'
  const memory = require('../memory')
  const mcData = require('minecraft-data')(bot.version)
  const ids = ['chest', 'trapped_chest', 'barrel']
    .map((n) => mcData.blocksByName[n] && mcData.blocksByName[n].id)
    .filter((v) => v != null)

  const { Vec3 } = require('vec3')
  const want = String(item).toLowerCase().trim().replace(/\s+/g, '_')
  // Labeled chests that should hold this item come first, then a local scan.
  const labeled = memory.listChests()
    .filter(({ name }) => name === want || matcherForLabel(name)(want))
    .map(({ pos }) => new Vec3(pos.x, pos.y, pos.z))
  const nearby = bot.findBlocks({ matching: ids, maxDistance: 16, count: 4 })
  const spots = [...labeled, ...nearby]
  if (spots.length === 0) return 'No chest or barrel within 16 blocks (and no labeled chests).'

  let remaining = amount ? Math.floor(amount) : Infinity
  let got = 0
  bot.assistant.currentTask = `fetching ${want.replace(/_/g, ' ')} from storage`
  try {
    for (const pos of spots) {
      if (remaining <= 0) break
      const blockNow = bot.blockAt(pos)
      if (!blockNow) continue
      try {
        await bot.pathfinder.goto(new goals.GoalNear(pos.x, pos.y, pos.z, 2))
        const container = await bot.openContainer(blockNow)
        const inside = container.containerItems()
          .filter((it) => it.name === want || it.name.includes(want))
        for (const stack of inside) {
          if (remaining <= 0) break
          const n = Math.min(stack.count, remaining)
          try {
            await container.withdraw(stack.type, null, n)
            got += n
            remaining -= n
          } catch (_) { /* inventory full */ }
        }
        container.close()
      } catch (err) {
        bot.assistant.log.debug('withdraw failed:', err && err.message)
      }
    }
  } finally {
    bot.assistant.currentTask = null
  }
  return got > 0
    ? `Fetched ${got} ${want.replace(/_/g, ' ')} from storage.`
    : `Couldn't find any ${String(item).replace(/_/g, ' ')} in the chests nearby.`
}

module.exports = { deposit, drop, give, withdraw, matchingStacks, matcherForLabel, LABEL_MATCHERS, isKeeper }
