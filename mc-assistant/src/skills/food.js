'use strict'

const { goals } = require('mineflayer-pathfinder')
const { firstFood, nearestFoodMob } = require('../state')

// Eating & hunting. `eat` consumes the best food on hand; `hunt` finds an
// animal, kills it, and collects the drops so `eat` has something to work with.

// Eat the best available food. Guarded so the survival loop and explicit
// commands never try to eat at the same time.
async function eat(bot, { force = false } = {}) {
  if (bot.assistant.eating) return 'Already eating.'
  if (!force && bot.food >= 20) return "I'm full."
  const food = firstFood(bot)
  if (!food) return "I've got no food on me. Ask me to hunt."

  bot.assistant.eating = true
  try {
    await bot.equip(food, 'hand')
    await bot.consume()
    return `Ate ${food.name.replace(/_/g, ' ')}.`
  } catch (err) {
    bot.assistant.log.debug('eat failed:', err && err.message)
    return "Couldn't finish eating."
  } finally {
    bot.assistant.eating = false
  }
}

// Track drops the hunt should sweep up.
function isPickup(entity) {
  return entity && (entity.name === 'item' || entity.objectType === 'Item' || entity.entityType != null && entity.name === 'item')
}

// Hunt the nearest food animal, then eat if hungry.
async function hunt(bot, { radius = 32 } = {}) {
  const target = nearestFoodMob(bot, radius)
  if (!target) return `No animals to hunt within ${radius} blocks.`

  const seq = bot.assistant.taskSeq
  bot.assistant.mode = 'hunt'
  bot.assistant.currentTask = `hunting ${target.name}`
  bot.assistant.busy = true
  const killPos = target.position.clone()
  const cancelled = () => bot.assistant.taskSeq !== seq

  try {
    // Chase and attack until it dies, wanders off, or we're told to stop.
    bot.pvp.attack(target)
    await waitUntil(bot, () => !target.isValid || bot.assistant.combat || cancelled(), 15000)
    bot.pvp.stop()
    if (cancelled()) return 'Called off the hunt.'

    // Sweep up drops around where it fell.
    try {
      await bot.pathfinder.goto(new goals.GoalNear(killPos.x, killPos.y, killPos.z, 1))
    } catch (_) { /* good enough */ }
    await sleep(800) // let pickups settle
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'hunt') bot.assistant.mode = 'idle'
  }

  if (bot.food < bot.assistant.config.eatThreshold && firstFood(bot)) {
    const ate = await eat(bot, { force: true })
    return `Hunted a ${target.name}. ${ate}`
  }
  return `Hunted a ${target.name}. Got the drops.`
}

function sleep(ms) {
  return new Promise((res) => setTimeout(res, ms))
}

function waitUntil(bot, pred, timeoutMs) {
  return new Promise((resolve) => {
    const start = Date.now()
    const tick = () => {
      if (pred() || Date.now() - start > timeoutMs) return resolve()
      setTimeout(tick, 250)
    }
    tick()
  })
}

module.exports = { eat, hunt, isPickup }
