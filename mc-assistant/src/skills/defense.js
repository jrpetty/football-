'use strict'

const pvpPlugin = require('mineflayer-pvp').plugin
const armorManager = require('mineflayer-armor-manager')
const { goals } = require('mineflayer-pathfinder')
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
      try {
        // Only loose an arrow when hawkeye actually has a firing solution —
        // oneShot with no solution holds the bow drawn forever and stacks
        // physics listeners on every retry. No solution → close to melee.
        const { Vec3 } = require('vec3')
        const grade = bot.hawkEye.getMasterGrade(entity, new Vec3(0, 0, 0), 'bow')
        if (grade) {
          bot.assistant._lastShotAt = now
          bot.hawkEye.oneShot(entity, 'bow')
          return // survival re-engages next tick; melee takes over once close
        }
      } catch (err) {
        bot.assistant.log.debug('bow shot failed:', err && err.message)
      }
      // fall through to melee — no line of sight / out of range
    } else {
      return // between shots — hold
    }
  }

  await equipBestWeapon(bot)
  bot.pvp.attack(entity)
}

function disengage(bot) {
  bot.assistant.combat = false
  bot.assistant._customTarget = null
  bot.assistant._creeperBackoffUntil = 0
  try { bot.pvp.stop() } catch (_) { /* ignore */ }
  if (bot.assistant.currentTask && bot.assistant.currentTask.startsWith('fighting')) {
    bot.assistant.currentTask = null
  }
}

// Creepers are the one mob melee-brawling gets you killed by: the fuse lights
// inside ~3 blocks, but it RESETS when you back out of range. So: dash in,
// one knockback swing, back off until the hiss dies, repeat. Bow from range
// when possible. Called every survival tick while a creeper is the target.
async function creeperDance(bot, creeper) {
  if (!creeper || !creeper.isValid || !bot.entity) return
  if (!bot.assistant.combat) bot.assistant.narrate('Creeper — hit-and-run, stay back.')
  bot.assistant.combat = true
  bot.assistant._customTarget = creeper
  bot.assistant.currentTask = 'fighting a creeper'

  const now = Date.now()
  const dist = creeper.position.distanceTo(bot.entity.position)

  // Prefer an arrow whenever we can shoot from outside fuse range.
  if (dist > 7 && bot.hawkEye && hasBowAndArrows(bot)) {
    if (now - (bot.assistant._lastShotAt || 0) > 2000) {
      try {
        const { Vec3 } = require('vec3')
        const grade = bot.hawkEye.getMasterGrade(creeper, new Vec3(0, 0, 0), 'bow')
        if (grade) {
          bot.assistant._lastShotAt = now
          bot.hawkEye.oneShot(creeper, 'bow')
          return
        }
      } catch (_) { /* fall through to melee dance */ }
    } else {
      return // between shots
    }
  }

  // Backing off after a swing — keep moving until the fuse has reset.
  if ((bot.assistant._creeperBackoffUntil || 0) > now) {
    const movement = require('./movement')
    movement.retreatFrom(bot, creeper.position)
    return
  }

  if (dist > 3.0) {
    // Close the gap (weapon out as we come in).
    try { await equipBestWeapon(bot) } catch (_) { /* bare fists then */ }
    try { bot.pathfinder.setGoal(new goals.GoalFollow(creeper, 2.5), true) } catch (_) { /* retry next tick */ }
    return
  }

  // In range: one knockback swing, then straight back out of fuse range.
  try {
    await equipBestWeapon(bot)
    await bot.lookAt(creeper.position.offset(0, 1, 0), true)
    bot.attack(creeper)
  } catch (err) {
    bot.assistant.log.debug('creeper swing failed:', err && err.message)
  }
  bot.assistant._creeperBackoffUntil = Date.now() + 1500
  try { bot.pathfinder.setGoal(null) } catch (_) { /* ignore */ }
  const movement = require('./movement')
  movement.retreatFrom(bot, creeper.position)
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

  // Keep hitting the current target until it's gone (pvp-held or the
  // custom creeper-dance target).
  const current = bot.pvp.target
  if (current && current.isValid && isHostile(current)) return current
  const custom = bot.assistant._customTarget
  if (custom && custom.isValid && isHostile(custom)) return custom

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

module.exports = { install, engage, disengage, attack, guard, autoDefend, creeperDance, equipBestWeapon }
