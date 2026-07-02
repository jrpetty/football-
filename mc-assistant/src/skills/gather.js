'use strict'

const collectBlockPlugin = require('mineflayer-collectblock').plugin

// Resource gathering via mineflayer-collectblock (which handles pathing +
// digging + picking up drops). We map friendly resource words to the set of
// block names that yield them.

function install(bot) {
  bot.loadPlugin(collectBlockPlugin)
}

// Friendly name -> predicate over a minecraft-data block name.
const RESOURCE_MATCHERS = {
  wood: (n) => n.endsWith('_log') || n.endsWith('_wood') || n.endsWith('_stem') || n.endsWith('_hyphae'),
  log: (n) => n.endsWith('_log') || n.endsWith('_stem'),
  stone: (n) => n === 'stone' || n === 'cobblestone' || n === 'deepslate' || n === 'cobbled_deepslate' || n === 'andesite' || n === 'diorite' || n === 'granite' || n === 'tuff',
  cobblestone: (n) => n === 'stone' || n === 'cobblestone',
  coal: (n) => n === 'coal_ore' || n === 'deepslate_coal_ore',
  iron: (n) => n === 'iron_ore' || n === 'deepslate_iron_ore' || n === 'raw_iron_block',
  gold: (n) => n === 'gold_ore' || n === 'deepslate_gold_ore' || n === 'nether_gold_ore',
  diamond: (n) => n === 'diamond_ore' || n === 'deepslate_diamond_ore',
  redstone: (n) => n === 'redstone_ore' || n === 'deepslate_redstone_ore',
  lapis: (n) => n === 'lapis_ore' || n === 'deepslate_lapis_ore',
  copper: (n) => n === 'copper_ore' || n === 'deepslate_copper_ore',
  emerald: (n) => n === 'emerald_ore' || n === 'deepslate_emerald_ore',
  dirt: (n) => n === 'dirt' || n === 'grass_block' || n === 'coarse_dirt' || n === 'rooted_dirt',
  sand: (n) => n === 'sand' || n === 'red_sand',
  gravel: (n) => n === 'gravel',
  netherrack: (n) => n === 'netherrack',
}

function blockIdsFor(bot, resource) {
  const mcData = require('minecraft-data')(bot.version)
  const match = RESOURCE_MATCHERS[resource]
  if (!match) return null
  const ids = []
  for (const name of Object.keys(mcData.blocksByName)) {
    if (match(name)) ids.push(mcData.blocksByName[name].id)
  }
  return ids
}

// Gather `amount` of a resource within the configured search radius (default
// 20 blocks; override per-call). Digs one target at a time, re-scanning after
// each so it keeps finding fresh veins/trees until it hits the quota.
async function gather(bot, { resource, amount = 8, maxDistance }) {
  const key = String(resource || '').toLowerCase()
  const ids = blockIdsFor(bot, key)
  if (!ids || ids.length === 0) {
    return `I don't know how to gather "${resource}". Try wood, stone, coal, iron, gold, diamond, dirt, or sand.`
  }

  const radius = Math.max(4, Math.min(64, Math.floor(Number(maxDistance)) || bot.assistant.config.gatherRadius))
  const want = Math.max(1, Math.min(64, Math.floor(amount) || 8))
  const seq = bot.assistant.taskSeq
  bot.assistant.mode = 'gather'
  bot.assistant.currentTask = `gathering ${want} ${key}`
  bot.assistant.busy = true

  let collected = 0
  let misses = 0
  try {
    while (collected < want && misses < 3) {
      // Bail out if told to stop, or if survival reflexes took over.
      if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) break

      const block = bot.findBlock({ matching: ids, maxDistance: radius, count: 1 })
      if (!block) { misses++; continue }
      misses = 0
      try {
        await bot.collectBlock.collect(block)
        collected++
      } catch (err) {
        // Unreachable/interrupted block — skip it and keep going.
        misses++
        bot.assistant.log.debug('collect failed:', err && err.message)
      }
    }
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'gather') bot.assistant.mode = 'idle'
  }

  if (collected === 0) return `Couldn't find any ${key} within ${radius} blocks.`
  if (collected < want) return `Got ${collected} ${key} — couldn't reach any more nearby.`
  return `Done — gathered ${collected} ${key}.`
}

module.exports = { install, gather, RESOURCE_MATCHERS }
