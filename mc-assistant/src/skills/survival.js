'use strict'

const { goals } = require('mineflayer-pathfinder')
const { threatsNear, ownerEntity, firstFood } = require('../state')
const movement = require('./movement')
const defense = require('./defense')
const food = require('./food')

// ---------------------------------------------------------------------------
// The autonomy loop. This is the bot's reflexive "instinct": every tick it
// looks at its own health, hunger, and threats and acts without being told —
// eating when hungry, fighting what attacks it, guarding its owner, and running
// when it's about to die. Explicit commands set goals; this keeps it alive.
// Priority order: survive (flee) > defend > feed > resume standing orders.
// ---------------------------------------------------------------------------

function start(bot, intervalMs = 700) {
  stop(bot)
  bot.assistant._survivalTimer = setInterval(() => {
    try {
      tick(bot)
    } catch (err) {
      bot.assistant.log.debug('survival tick error:', err && err.message)
    }
  }, intervalMs)
}

function stop(bot) {
  if (bot.assistant._survivalTimer) {
    clearInterval(bot.assistant._survivalTimer)
    bot.assistant._survivalTimer = null
  }
}

function tick(bot) {
  if (!bot.entity || bot.health == null || bot.isAlive === false) return
  const cfg = bot.assistant.config
  const nearestThreat = threatsNear(bot, cfg.guardRadius)[0] || null

  // 0) ASLEEP — stay down unless something hostile shows up.
  if (bot.isSleeping) {
    if (nearestThreat) {
      require('./rest').wake(bot).catch(() => {})
      bot.assistant.narrate('Something woke me up!')
    }
    return
  }

  // 1) SURVIVE — critically hurt with a threat present: break off and run.
  if (bot.health <= cfg.fleeHealth && nearestThreat) {
    if (!bot.assistant.fleeing) {
      bot.assistant.fleeing = true
      bot.assistant.narrate('Health low — falling back!')
    }
    defense.disengage(bot)
    const owner = ownerEntity(bot)
    if (owner && owner.position.distanceTo(bot.entity.position) > 4) {
      try { bot.pathfinder.setGoal(new goals.GoalFollow(owner, 2), true) } catch (_) { /* ignore */ }
    } else {
      movement.retreatFrom(bot, nearestThreat.entity.position)
    }
    maybeEat(bot) // eat mid-retreat if we safely can
    return
  }
  if (bot.assistant.fleeing && (bot.health > cfg.fleeHealth + 4 || !nearestThreat)) {
    bot.assistant.fleeing = false
    movement.stopMoving(bot)
    resumeStandingOrders(bot)
  }

  // 2) DEFEND — engage the highest-priority hostile (self, then owner if guarding).
  const foe = defense.autoDefend(bot)
  if (foe) {
    if (bot.pvp.target !== foe) {
      if (!bot.assistant.combat) bot.assistant.narrate(`${pretty(foe.name)} spotted — engaging.`)
      defense.engage(bot, foe).catch(() => {})
    }
    return
  }
  if (bot.assistant.combat && !(bot.pvp.target && bot.pvp.target.isValid)) {
    defense.disengage(bot)
    bot.assistant.narrate('Threat cleared.')
    resumeStandingOrders(bot)
  }

  // 3) FEED — top up hunger when it's safe to do so.
  maybeEat(bot)

  // 4) STANDING ORDERS — if we're meant to be following but the pathfinder
  // goal got dropped (owner left render distance, a one-shot task finished,
  // combat cleared while they were away), quietly pick it back up. Patrol
  // advances to its next point the same way.
  if (bot.assistant.mode === 'follow' && !bot.assistant.busy && !bot.assistant.eating) {
    const owner = ownerEntity(bot)
    if (owner && !bot.pathfinder.goal) {
      try {
        bot.pathfinder.setGoal(new goals.GoalFollow(owner, cfg.followRange), true)
      } catch (_) { /* pathfinder busy */ }
    }
  }
  if (bot.assistant.mode === 'patrol' && !bot.assistant.busy && !bot.pathfinder.goal) {
    require('./patrol').advance(bot)
  }

  // 5) PROACTIVE — nothing to do at all? Find useful work (cook, stash,
  // harvest, replant, torch, fish, sleep). chores.maybeRun checks that the
  // queue is empty and we're truly idle before starting anything.
  require('./chores').maybeRun(bot)
}

function maybeEat(bot) {
  const cfg = bot.assistant.config
  if (bot.assistant.eating || bot.assistant.combat) return
  if (bot.food == null || bot.food > cfg.eatThreshold) return
  if (!firstFood(bot)) {
    // Hungry with nothing to eat — mention it once in a while.
    if (bot.food <= 6) bot.assistant.narrate("I'm starving and out of food — I should hunt.")
    return
  }
  if (bot.food <= cfg.eatThreshold) {
    bot.assistant.narrate('Getting hungry — eating.')
    food.eat(bot).catch(() => {})
  }
}

// After combat/flight ends, pick our standing order back up.
function resumeStandingOrders(bot) {
  if (bot.assistant.mode === 'follow') {
    const owner = ownerEntity(bot)
    if (owner) {
      try {
        bot.pathfinder.setGoal(new goals.GoalFollow(owner, bot.assistant.config.followRange), true)
      } catch (_) { /* ignore */ }
    }
  }
}

function pretty(name) {
  return name ? name.replace(/_/g, ' ') : 'something'
}

module.exports = { start, stop, tick }
