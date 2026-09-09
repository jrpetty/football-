'use strict'

const { goals } = require('mineflayer-pathfinder')

// ---------------------------------------------------------------------------
// Enchanting — true player progression: the bot's XP from mining and kills is
// real, so with an enchanting table, lapis, and levels it improves its own
// gear. Picks the best affordable option the table offers and reports what it
// got (or exactly what it's missing: table, lapis, levels, or a clean item).
// ---------------------------------------------------------------------------

// What to enchant first when no item is named (best unenchanted gear wins).
const TARGET_PRIORITY = [
  'netherite_sword', 'diamond_sword', 'iron_sword', 'stone_sword',
  'netherite_pickaxe', 'diamond_pickaxe', 'iron_pickaxe', 'stone_pickaxe',
  'bow', 'crossbow',
  'netherite_axe', 'diamond_axe', 'iron_axe',
  'netherite_chestplate', 'diamond_chestplate', 'iron_chestplate',
  'netherite_helmet', 'diamond_helmet', 'iron_helmet',
  'netherite_leggings', 'diamond_leggings', 'iron_leggings',
  'netherite_boots', 'diamond_boots', 'iron_boots',
  'fishing_rod', 'shield',
]

function enchantsOf(item) {
  try {
    return item.enchants || []
  } catch (_) {
    return [] // version quirk — treat as unenchanted
  }
}

// Choose what to put on the table. Named request ("sword", "iron pickaxe")
// matches loosely; otherwise the best unenchanted item by priority.
function pickTarget(bot, requested) {
  const items = bot.inventory.items()
  if (requested) {
    const want = String(requested).toLowerCase().trim().replace(/\s+/g, '_')
    return items.find((it) =>
      (it.name === want || it.name.endsWith(`_${want}`) || it.name.includes(want)) &&
      enchantsOf(it).length === 0,
    ) || null
  }
  for (const name of TARGET_PRIORITY) {
    const it = items.find((i) => i.name === name && enchantsOf(i).length === 0)
    if (it) return it
  }
  return null
}

function findTable(bot, maxDistance = 16) {
  const mcData = require('minecraft-data')(bot.version)
  const def = mcData.blocksByName.enchanting_table
  return def ? bot.findBlock({ matching: def.id, maxDistance }) : null
}

function lapisCount(bot) {
  return bot.inventory.items()
    .filter((it) => it.name === 'lapis_lazuli')
    .reduce((s, it) => s + it.count, 0)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function enchant(bot, { item } = {}) {
  const tableBlock = findTable(bot)
  if (!tableBlock) return 'No enchanting table within 16 blocks.'

  const lapis = lapisCount(bot)
  if (lapis < 1) return 'I need lapis lazuli to enchant — I have none.'

  const target = pickTarget(bot, item)
  if (!target) {
    return item
      ? `I don't have an unenchanted ${String(item).replace(/_/g, ' ')} on me.`
      : "Nothing worth enchanting — my gear is either already enchanted or too basic."
  }

  const seq = bot.assistant.taskSeq
  bot.assistant.busy = true
  bot.assistant.currentTask = `enchanting my ${target.name.replace(/_/g, ' ')}`

  let table = null
  try {
    const p = tableBlock.position
    try {
      await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
    } catch (_) {
      return "I can't reach the enchanting table."
    }

    table = await bot.openEnchantmentTable(tableBlock)
    await table.putTargetItem(target)
    const lapisStack = bot.inventory.items().find((it) => it.name === 'lapis_lazuli')
    if (lapisStack) {
      try { await table.putLapis(lapisStack) } catch (_) { /* may auto-place */ }
    }

    // Options stream in via packets — wait for at least one real offer.
    let options = []
    for (let i = 0; i < 25; i++) {
      options = table.enchantments || []
      if (options.some((o) => o && o.level > 0)) break
      if (bot.assistant.taskSeq !== seq) break
      await sleep(200)
    }

    // Best slot we can afford: slot i costs (i+1) lapis and needs the shown
    // level; enchanting spends (i+1) levels.
    const myLevel = bot.experience ? bot.experience.level : 0
    let choice = -1
    for (let i = options.length - 1; i >= 0; i--) {
      const o = options[i]
      if (o && o.level > 0 && o.level <= myLevel && lapis >= i + 1) { choice = i; break }
    }
    if (choice === -1) {
      const cheapest = options.find((o) => o && o.level > 0)
      try { await table.takeTargetItem() } catch (_) { /* leave it */ }
      if (!cheapest) return "The table offered nothing for that item."
      return `Not enough for the table's offers — the cheapest wants level ${cheapest.level} (I'm level ${myLevel}) and lapis to match.`
    }

    await table.enchant(choice)
    const done = await table.takeTargetItem()
    const gained = enchantsOf(done)
      .map((e) => `${String(e.name || '').replace(/_/g, ' ')} ${e.lvl || ''}`.trim())
      .filter(Boolean)
      .join(', ')
    return `Enchanted my ${target.name.replace(/_/g, ' ')}${gained ? ` — got ${gained}` : ''} (spent ${choice + 1} level${choice ? 's' : ''}).`
  } catch (err) {
    bot.assistant.log.debug('enchant failed:', err && err.message)
    return `Enchanting didn't work: ${err && err.message ? err.message : 'unknown error'}.`
  } finally {
    try { if (table) table.close() } catch (_) { /* closed */ }
    bot.assistant.busy = false
    bot.assistant.currentTask = null
  }
}

module.exports = { enchant, pickTarget, TARGET_PRIORITY, enchantsOf }
