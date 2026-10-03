'use strict'

const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const { ownerEntity } = require('../state')

// Movement & navigation, built on mineflayer-pathfinder. Every "go somewhere"
// action funnels through here so combat/gather can cleanly interrupt it.

function install(bot) {
  bot.loadPlugin(pathfinder)
}

function configureMovements(bot) {
  const mcData = require('minecraft-data')(bot.version)
  // Second arg is required on old pathfinder builds and ignored on new ones.
  const moves = new Movements(bot, mcData)
  moves.allowParkour = true
  moves.canDig = true // allow it to mine through to reach a target
  // Never path through things that burn/hurt (fire and cobwebs are avoided
  // by default; lava/magma are the ones that actually kill it).
  for (const name of ['lava', 'magma_block', 'sweet_berry_bush', 'powder_snow', 'cactus']) {
    const def = mcData.blocksByName[name]
    if (def) moves.blocksToAvoid.add(def.id)
  }
  bot.pathfinder.setMovements(moves)
  bot.assistant.movements = moves
}

function stopMoving(bot) {
  try {
    bot.pathfinder.setGoal(null)
  } catch (_) { /* pathfinder may not be ready */ }
}

// One-shot travel to a coordinate. Resolves when we arrive (or throws).
async function goto(bot, { x, y, z, range = 1 }) {
  if ([x, y, z].some((v) => typeof v !== 'number' || Number.isNaN(v))) {
    return 'I need valid x, y, z coordinates to go there.'
  }
  // A one-shot destination supersedes a follow standing order.
  if (bot.assistant.mode === 'follow') bot.assistant.mode = 'idle'
  bot.assistant.currentTask = `going to ${Math.round(x)}, ${Math.round(y)}, ${Math.round(z)}`
  await bot.pathfinder.goto(new goals.GoalNear(x, y, z, range))
  bot.assistant.currentTask = null
  return `Arrived near ${Math.round(x)}, ${Math.round(y)}, ${Math.round(z)}.`
}

// Walk to the owner once and stop.
async function come(bot) {
  const owner = ownerEntity(bot)
  if (!owner) return "I can't see you right now — get closer or say where you are."
  // "Come" is one-shot; it replaces a follow standing order.
  if (bot.assistant.mode === 'follow') bot.assistant.mode = 'idle'
  bot.assistant.currentTask = 'coming to you'
  const p = owner.position
  await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, bot.assistant.config.followRange))
  bot.assistant.currentTask = null
  return 'Here I am.'
}

// Continuously trail the owner (dynamic goal — updates as they move).
function follow(bot) {
  const owner = ownerEntity(bot)
  if (!owner) return "I can't see you to follow — get within render distance."
  bot.assistant.mode = 'follow'
  bot.assistant.currentTask = 'following you'
  bot.pathfinder.setGoal(new goals.GoalFollow(owner, bot.assistant.config.followRange), true)
  return "Following you. Say 'stop' when you want me to hold."
}

// Retreat away from a threat position (used by the survival loop when hurt).
function retreatFrom(bot, threatPos) {
  if (!bot.entity || !threatPos) return
  const me = bot.entity.position
  const dx = me.x - threatPos.x
  const dz = me.z - threatPos.z
  const len = Math.hypot(dx, dz) || 1
  const tx = me.x + (dx / len) * 12
  const tz = me.z + (dz / len) * 12
  try {
    bot.pathfinder.setGoal(new goals.GoalXZ(tx, tz))
  } catch (_) { /* ignore */ }
}

module.exports = { install, configureMovements, stopMoving, goto, come, follow, retreatFrom, goals }
