'use strict'

const { goals } = require('mineflayer-pathfinder')

// ---------------------------------------------------------------------------
// Smelting & cooking, player-rules edition: needs a real furnace nearby, real
// fuel from the inventory, and real furnace time (~10s per item). "cook my
// meat" cooks whatever raw meat it's carrying; "smelt iron" turns raw iron
// into ingots. Honest refusals when the furnace, fuel, or inputs are missing.
// ---------------------------------------------------------------------------

// input item -> output item (the practical vanilla set).
const SMELTS = {
  raw_iron: 'iron_ingot',
  raw_gold: 'gold_ingot',
  raw_copper: 'copper_ingot',
  beef: 'cooked_beef',
  porkchop: 'cooked_porkchop',
  chicken: 'cooked_chicken',
  mutton: 'cooked_mutton',
  rabbit: 'cooked_rabbit',
  cod: 'cooked_cod',
  salmon: 'cooked_salmon',
  potato: 'baked_potato',
  kelp: 'dried_kelp',
  sand: 'glass',
  red_sand: 'glass',
  cobblestone: 'stone',
  clay_ball: 'brick',
  cactus: 'green_dye',
  ancient_debris: 'netherite_scrap',
}

// Friendly words -> candidate input items, first-with-stock wins.
const INPUT_ALIASES = {
  iron: ['raw_iron'],
  gold: ['raw_gold'],
  copper: ['raw_copper'],
  meat: ['beef', 'porkchop', 'chicken', 'mutton', 'rabbit'],
  food: ['beef', 'porkchop', 'chicken', 'mutton', 'rabbit', 'cod', 'salmon', 'potato'],
  fish: ['cod', 'salmon'],
  pork: ['porkchop'],
  glass: ['sand', 'red_sand'],
  stone: ['cobblestone'],
  brick: ['clay_ball'],
  potatoes: ['potato'],
}

// fuel item -> items it smelts. Best fuels first.
const FUELS = [
  { name: 'coal_block', per: 80 },
  { name: 'coal', per: 8 },
  { name: 'charcoal', per: 8 },
  { match: (n) => n.endsWith('_planks'), label: 'planks', per: 1.5 },
  { match: (n) => n.endsWith('_log'), label: 'logs', per: 1.5 },
  { name: 'stick', per: 0.5 },
]

function resolveInputs(bot, requested) {
  const r = String(requested || 'meat').toLowerCase().trim().replace(/\s+/g, '_')
  if (INPUT_ALIASES[r]) return INPUT_ALIASES[r]
  if (SMELTS[r]) return [r]
  // Asked for the OUTPUT ("iron ingot", "cooked beef")? Map back to inputs.
  const asOutput = Object.keys(SMELTS).filter((input) => SMELTS[input] === r)
  if (asOutput.length > 0) return asOutput
  return []
}

function stockOf(bot, name) {
  return bot.inventory.items().filter((it) => it.name === name).reduce((s, it) => s + it.count, 0)
}

function pickFuel(bot, itemsNeeded) {
  for (const f of FUELS) {
    const stacks = bot.inventory.items().filter((it) => (f.match ? f.match(it.name) : it.name === f.name))
    const count = stacks.reduce((s, it) => s + it.count, 0)
    if (count === 0) continue
    const usable = Math.min(count, Math.ceil(itemsNeeded / f.per))
    return {
      item: stacks[0],
      count: usable,
      covers: Math.floor(usable * f.per),
      label: f.label || f.name,
    }
  }
  return null
}

function findFurnace(bot, maxDistance = 16) {
  const mcData = require('minecraft-data')(bot.version)
  const def = mcData.blocksByName.furnace
  return def ? bot.findBlock({ matching: def.id, maxDistance }) : null
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function smelt(bot, { item, amount = 8 } = {}) {
  const mcData = require('minecraft-data')(bot.version)
  const candidates = resolveInputs(bot, item)
  if (candidates.length === 0) {
    return `I don't know how to smelt "${item}". Try iron, gold, copper, meat, fish, sand, or cobblestone.`
  }

  const inputName = candidates.find((n) => stockOf(bot, n) > 0)
  if (!inputName) {
    const label = String(item || 'meat').replace(/_/g, ' ')
    return `I've got no ${label} to smelt on me.`
  }

  const want = Math.max(1, Math.min(64, Math.floor(Number(amount)) || 8))
  let n = Math.min(want, stockOf(bot, inputName))

  const fuel = pickFuel(bot, n)
  if (!fuel) {
    return "I've got nothing to burn — I need coal, charcoal, planks, or logs for fuel."
  }
  if (fuel.covers < n) n = fuel.covers
  if (n <= 0) return "Not enough fuel to smelt anything — get me some coal or wood."

  const furnaceBlock = findFurnace(bot)
  if (!furnaceBlock) {
    return "No furnace within 16 blocks — place one, or ask me to craft a furnace (I need 8 cobblestone)."
  }

  const seq = bot.assistant.taskSeq
  const outName = SMELTS[inputName]
  bot.assistant.mode = 'smelt'
  bot.assistant.busy = true
  bot.assistant.currentTask = `smelting ${n} ${inputName.replace(/_/g, ' ')}`

  let taken = 0
  let furnace = null
  try {
    const p = furnaceBlock.position
    try {
      await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
    } catch (_) {
      return "I can't reach the furnace from here."
    }

    furnace = await bot.openFurnace(furnaceBlock)

    // Clear any leftover output so counts are ours.
    if (furnace.outputItem()) {
      try { await furnace.takeOutput() } catch (_) { /* full inventory? keep going */ }
    }

    const inputId = mcData.itemsByName[inputName].id
    await furnace.putFuel(fuel.item.type, null, fuel.count)
    await furnace.putInput(inputId, null, n)

    // ~10s per item; poll and pull output as it appears.
    const deadline = Date.now() + n * 11000 + 15000
    while (taken < n && Date.now() < deadline) {
      if (bot.assistant.taskSeq !== seq || bot.assistant.fleeing) break
      await sleep(1000)
      const out = furnace.outputItem()
      if (out && out.count > 0) {
        const got = out.count
        try {
          await furnace.takeOutput()
          taken += got
        } catch (_) { /* inventory full — stop pulling */ break }
      }
      // Input gone and nothing cooking means fuel died early.
      if (!furnace.inputItem() && !furnace.outputItem()) break
    }

    // Reclaim whatever didn't smelt.
    try { if (furnace.inputItem()) await furnace.takeInput() } catch (_) { /* leave it */ }
  } catch (err) {
    bot.assistant.log.debug('smelt failed:', err && err.message)
    return `Smelting didn't work: ${err && err.message ? err.message : 'unknown error'}.`
  } finally {
    try { if (furnace) furnace.close() } catch (_) { /* already closed */ }
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'smelt') bot.assistant.mode = 'idle'
  }

  const outLabel = String(outName).replace(/_/g, ' ')
  if (taken === 0) return `The furnace didn't produce anything — fuel may have run out.`
  if (taken < n) return `Smelted ${taken} ${outLabel} — the rest didn't finish, I took my materials back.`
  return `Done — smelted ${taken} ${outLabel}.`
}

module.exports = { smelt, resolveInputs, pickFuel, SMELTS, INPUT_ALIASES, FUELS }
