'use strict'

const { goals } = require('mineflayer-pathfinder')

// ---------------------------------------------------------------------------
// Crafting — the bot plays by player rules, so when it lacks a tool it has to
// make one the same way you would: logs -> planks -> sticks -> crafting table
// -> tools. Uses mineflayer's recipe engine: recipesFor() only returns recipes
// we hold the materials for, so failures can be reported honestly (missing
// materials vs. missing crafting table vs. no such recipe).
// ---------------------------------------------------------------------------

// Friendly names -> candidate item names, best-first. Generic tool words try
// stone before wood so "craft a pickaxe" upgrades naturally.
const GENERIC = {
  pickaxe: ['stone_pickaxe', 'wooden_pickaxe'],
  axe: ['stone_axe', 'wooden_axe'],
  sword: ['stone_sword', 'wooden_sword'],
  shovel: ['stone_shovel', 'wooden_shovel'],
  hoe: ['stone_hoe', 'wooden_hoe'],
  table: ['crafting_table'],
  workbench: ['crafting_table'],
  sticks: ['stick'],
  torches: ['torch'],
}

// Resolve a requested name to a list of concrete item names to try, in order.
function resolveCandidates(bot, requested) {
  const mcData = require('minecraft-data')(bot.version)
  const r = String(requested || '').toLowerCase().trim().replace(/\s+/g, '_')
  if (!r) return []
  if (r === 'planks' || r === 'plank') {
    // Any planks recipe — whichever logs we're carrying decide the wood type.
    return Object.keys(mcData.itemsByName).filter((n) => n.endsWith('_planks'))
  }
  if (GENERIC[r]) return GENERIC[r].filter((n) => mcData.itemsByName[n])
  if (mcData.itemsByName[r]) return [r]
  // Loose match ("stone pick" -> stone_pickaxe)
  const loose = Object.keys(mcData.itemsByName).find((n) => n.includes(r))
  return loose ? [loose] : []
}

// Describe what the first known recipe consumes, e.g. "3 oak_planks + 2 stick".
function ingredientsLine(bot, recipe) {
  const mcData = require('minecraft-data')(bot.version)
  const needs = {}
  for (const d of recipe.delta) {
    if (d.count < 0) {
      const name = mcData.items[d.id] ? mcData.items[d.id].name : `item ${d.id}`
      needs[name] = (needs[name] || 0) + -d.count
    }
  }
  return Object.entries(needs).map(([n, c]) => `${c} ${n.replace(/_/g, ' ')}`).join(' + ')
}

function findCraftingTable(bot, maxDistance = 16) {
  const mcData = require('minecraft-data')(bot.version)
  const def = mcData.blocksByName.crafting_table
  if (!def) return null
  return bot.findBlock({ matching: def.id, maxDistance })
}

async function craft(bot, { item, amount = 1 } = {}) {
  const mcData = require('minecraft-data')(bot.version)
  const candidates = resolveCandidates(bot, item)
  if (candidates.length === 0) {
    return `I don't know an item called "${item}".`
  }

  const want = Math.max(1, Math.min(64, Math.floor(Number(amount)) || 1))
  bot.assistant.currentTask = `crafting ${item}`
  bot.assistant.busy = true

  try {
    // First pass: anything craftable right now in the 2x2 inventory grid?
    for (const name of candidates) {
      const id = mcData.itemsByName[name].id
      const recipes = bot.recipesFor(id, null, 1, null)
      if (recipes.length > 0) {
        return await doCraft(bot, recipes[0], name, want, null)
      }
    }

    // Second pass: recipes that need a crafting table.
    const table = findCraftingTable(bot)
    if (table) {
      for (const name of candidates) {
        const id = mcData.itemsByName[name].id
        const recipes = bot.recipesFor(id, null, 1, table)
        if (recipes.length > 0) {
          const p = table.position
          try {
            await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
          } catch (_) {
            return "There's a crafting table nearby but I can't reach it."
          }
          return await doCraft(bot, recipes[0], name, want, table)
        }
      }
    }

    // Nothing craftable — say exactly why.
    const first = candidates[0]
    const firstId = mcData.itemsByName[first].id
    const allRecipes = bot.recipesAll(firstId, null, true)
    if (allRecipes.length === 0) {
      return `${pretty(first)} isn't something I can craft.`
    }
    const materialsReady = bot.recipesFor(firstId, null, 1, true).length > 0
    if (materialsReady && !table) {
      return `I have the materials for a ${pretty(first)}, but I need a crafting table nearby — ask me to craft one, or place one within 16 blocks.`
    }
    return `I can't craft ${pretty(first)} yet — I'd need ${ingredientsLine(bot, allRecipes[0])}.`
  } catch (err) {
    bot.assistant.log.debug('craft failed:', err && err.message)
    return `Crafting didn't work: ${err && err.message ? err.message : 'unknown error'}.`
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
  }
}

async function doCraft(bot, recipe, name, want, table) {
  const per = recipe.result && recipe.result.count ? recipe.result.count : 1
  const times = Math.max(1, Math.ceil(want / per))
  await bot.craft(recipe, times, table)
  const made = times * per
  return `Crafted ${made} ${pretty(name)}.`
}

function pretty(name) {
  return String(name).replace(/_/g, ' ')
}

module.exports = { craft, resolveCandidates, ingredientsLine, GENERIC }
