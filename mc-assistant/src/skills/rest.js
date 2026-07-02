'use strict'

const { goals } = require('mineflayer-pathfinder')

// Sleeping — skips the night with you and keeps phantoms away. Player rules:
// needs an actual bed within reach, night time, and no monsters nearby (the
// server enforces the last part; we just relay its complaint).

function findBed(bot, maxDistance = 16) {
  const mcData = require('minecraft-data')(bot.version)
  const ids = Object.keys(mcData.blocksByName)
    .filter((n) => n.endsWith('_bed'))
    .map((n) => mcData.blocksByName[n].id)
  if (ids.length === 0) return null
  return bot.findBlock({ matching: ids, maxDistance })
}

async function sleep(bot) {
  if (bot.isSleeping) return 'Already sleeping.'
  if (bot.time && bot.time.isDay) return "It's daytime — nothing to sleep through."
  const bed = findBed(bot)
  if (!bed) return 'No bed within 16 blocks of me.'

  bot.assistant.currentTask = 'going to bed'
  try {
    const p = bed.position
    try {
      await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
    } catch (_) {
      return "I can't reach the bed."
    }
    await bot.sleep(bed)
    return 'Good night — waking at dawn.'
  } catch (err) {
    // Vanilla refuses with useful reasons (monsters nearby, too far, occupied).
    const msg = err && err.message ? err.message : 'unknown reason'
    return `Can't sleep: ${msg}.`
  } finally {
    bot.assistant.currentTask = null
  }
}

async function wake(bot) {
  if (!bot.isSleeping) return null
  try { await bot.wake() } catch (_) { /* already up */ }
  return 'Awake.'
}

module.exports = { sleep, wake, findBed }
