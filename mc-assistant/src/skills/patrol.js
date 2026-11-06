'use strict'

const { goals } = require('mineflayer-pathfinder')

// Patrol mode — a standing order like follow/guard. The bot walks a loop of
// points around a center (its position when you say "patrol", or the saved
// "home" waypoint) and the survival loop's auto-defense handles anything
// hostile it meets. Movement is re-asserted from survival's standing-orders
// tick, so fights and flights resume the route automatically.

function makeRoute(center, radius, points = 8) {
  const route = []
  for (let i = 0; i < points; i++) {
    const a = (i / points) * Math.PI * 2
    route.push({
      x: Math.round(center.x + Math.cos(a) * radius),
      z: Math.round(center.z + Math.sin(a) * radius),
      y: Math.round(center.y),
    })
  }
  return route
}

function patrol(bot, { radius } = {}) {
  const memory = require('../memory')
  const r = Math.max(6, Math.min(32, Math.floor(Number(radius)) || 10))
  const home = memory.getWaypoint('home')
  const center = home || (bot.entity ? bot.entity.position : null)
  if (!center) return "I don't know where to patrol yet."

  bot.assistant.mode = 'patrol'
  bot.assistant.currentTask = 'patrolling'
  bot.assistant.patrol = { route: makeRoute(center, r), index: 0 }
  advance(bot) // start moving now; survival keeps it going
  return `Patrolling a ${r}-block loop around ${home ? 'home' : 'here'}. Say "stop" to end it.`
}

// Called from the survival loop each tick while patrolling. Moves to the next
// point when the goal is empty — and also when a point has been "in progress"
// too long (pathfinder keeps unreachable goals forever, which would otherwise
// freeze the patrol on rough terrain).
const STALL_MS = 30_000

function tick(bot) {
  const p = bot.assistant.patrol
  if (!p || bot.assistant.mode !== 'patrol') return
  const stalled = p.advancedAt && Date.now() - p.advancedAt > STALL_MS
  if (bot.pathfinder.goal && !stalled) return
  if (stalled) {
    try { bot.pathfinder.setGoal(null) } catch (_) { /* ignore */ }
  }
  advance(bot)
}

function advance(bot) {
  const p = bot.assistant.patrol
  if (!p || bot.assistant.mode !== 'patrol') return
  const point = p.route[p.index % p.route.length]
  p.index++
  p.advancedAt = Date.now()
  try {
    bot.pathfinder.setGoal(new goals.GoalNear(point.x, point.y, point.z, 2))
  } catch (_) { /* try again next tick */ }
}

module.exports = { patrol, advance, tick, makeRoute }
