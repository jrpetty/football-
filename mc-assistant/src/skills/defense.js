'use strict'

const pvpPlugin = require('mineflayer-pvp').plugin
const armorManager = require('mineflayer-armor-manager')
const { threatsNear, ownerEntity, isHostile } = require('../state')

// Combat & protection, built on mineflayer-pvp. The bot equips its best gear
// (armor-manager auto-equips on pickup) and swaps to its best weapon before
// engaging.

function install(bot) {
  bot.loadPlugin(pvpPlugin)
  bot.loadPlugin(armorManager)
}

const WEAPON_RANK = ['netherite_sword', 'diamond_sword', 'iron_sword', 'stone_sword', 'golden_sword', 'wooden_sword', 'netherite_axe', 'diamond_axe', 'iron_axe', 'stone_axe']

async function equipBestWeapon(bot) {
  const items = bot.inventory.items()
  for (const name of WEAPON_RANK) {
    const w = items.find((it) => it.name === name)
    if (w) {
      try { await bot.equip(w, 'hand') } catch (_) { /* ignore */ }
      return
    }
  }
}

function hasBowAndArrows(bot) {
  const items = bot.inventory.items()
  return items.some((it) => it.name === 'bow') && items.some((it) => it.name === 'arrow')
}

// Start attacking a specific entity. Sets the `combat` flag so the survival
// loop and gather routine yield to the fight. Distant targets get an arrow
// first (if the optional hawkeye aim plugin loaded and we carry bow + arrows);
// melee handles everything close.
async function engage(bot, entity) {
  if (!entity || !entity.isValid) return
  bot.assistant.combat = true
  bot.assistant.currentTask = `fighting ${entity.name || 'a hostile'}`

  const dist = bot.entity ? entity.position.distanceTo(bot.entity.position) : 0
  if (dist > 10 && bot.hawkEye && hasBowAndArrows(bot)) {
    const now = Date.now()
    if (now - (bot.assistant._lastShotAt || 0) > 2000) {
      bot.assistant._lastShotAt = now
      try {
        bot.hawkEye.oneShot(entity, 'bow')
        return // survival re-engages next tick; melee takes over once close
      } catch (err) {
        bot.assistant.log.debug('bow shot failed:', err && err.message)
      }
    } else {
      return // between shots — hold
    }
  }

  await equipBestWeapon(bot)
  bot.pvp.attack(entity)
}

function disengage(bot) {
  bot.assistant.combat = false
  try { bot.pvp.stop() } catch (_) { /* ignore */ }
  if (bot.assistant.currentTask && bot.assistant.currentTask.startsWith('fighting')) {
    bot.assistant.currentTask = null
  }
}

// Explicit "attack" command: hit the nearest hostile, or a named player/mob.
async function attack(bot, { target } = {}) {
  let entity = null
  if (target) {
    const t = String(target).toLowerCase()
    entity = bot.nearestEntity((e) =>
      (e.name && e.name.toLowerCase() === t) ||
      (e.username && e.username.toLowerCase() === t))
    if (!entity) return `I can't see "${target}" nearby.`
  } else {
    const near = threatsNear(bot, bot.assistant.config.guardRadius)
    if (near.length === 0) return 'Nothing hostile in range.'
    entity = near[0].entity
  }
  await engage(bot, entity)
  return `Engaging ${entity.name || entity.username}.`
}

// Toggle guard mode — the survival loop will now also defend the owner, not
// just the bot itself.
function guard(bot) {
  bot.assistant.mode = 'guard'
  bot.assistant.currentTask = 'guarding you'
  return "Guard mode on. I'll watch your back."
}

// Called every tick by the survival loop. Returns the entity it decided to
// fight (or null). Priority: an existing valid target, then anything near the
// bot, then (in guard mode) anything near the owner.
function autoDefend(bot) {
  const cfg = bot.assistant.config

  // Keep hitting the current target until it's gone.
  const current = bot.pvp.target
  if (current && current.isValid && isHostile(current)) return current

  const selfThreats = threatsNear(bot, cfg.defendRadius)
  if (selfThreats.length > 0) return selfThreats[0].entity

  if (bot.assistant.mode === 'guard') {
    const owner = ownerEntity(bot)
    if (owner) {
      const ownerThreats = threatsNear(bot, cfg.guardRadius, owner.position)
      if (ownerThreats.length > 0) return ownerThreats[0].entity
    }
  }
  return null
}

module.exports = { install, engage, disengage, attack, guard, autoDefend, equipBestWeapon }
