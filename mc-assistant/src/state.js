'use strict'

// ---------------------------------------------------------------------------
// The bot's "world model" — the small, honest snapshot of itself and its
// surroundings that both the survival loop and the Claude brain reason over.
// This is the substrate of the "somewhat self-aware" behaviour: the bot always
// knows its health, hunger, position, what it's carrying, and what's a threat.
// ---------------------------------------------------------------------------

// Actively dangerous mobs we defend against. Neutral mobs (enderman, wolf,
// piglin, zombified_piglin, bee, llama, ...) are intentionally excluded so the
// bot doesn't provoke fights it can avoid.
const HOSTILES = new Set([
  'zombie', 'husk', 'drowned', 'zombie_villager',
  'skeleton', 'stray', 'bogged', 'wither_skeleton',
  'creeper', 'spider', 'cave_spider', 'silverfish',
  'witch', 'slime', 'magma_cube', 'phantom',
  'blaze', 'ghast', 'breeze',
  'pillager', 'vindicator', 'evoker', 'ravager', 'vex', 'illusioner',
  'zoglin', 'hoglin', 'guardian', 'elder_guardian',
  'shulker', 'endermite', 'warden', 'piglin_brute',
])

// Passive animals worth hunting for food.
const FOOD_MOBS = new Set([
  'cow', 'mooshroom', 'pig', 'chicken', 'sheep', 'rabbit',
])

// Items that restore hunger, best-first so we eat the good stuff before scraps.
const FOODS_PREFERRED = [
  'cooked_beef', 'cooked_porkchop', 'cooked_mutton', 'cooked_chicken',
  'cooked_rabbit', 'cooked_cod', 'cooked_salmon', 'bread', 'baked_potato',
  'pumpkin_pie', 'golden_carrot', 'beetroot_soup', 'mushroom_stew',
  'rabbit_stew', 'apple', 'carrot', 'melon_slice', 'sweet_berries',
  'cookie', 'dried_kelp', 'beef', 'porkchop', 'mutton', 'chicken',
  'rabbit', 'cod', 'salmon', 'potato', 'rotten_flesh',
]
const FOODS = new Set(FOODS_PREFERRED)

function isHostile(entity) {
  return !!entity && entity.name != null && HOSTILES.has(entity.name)
}

function isFoodMob(entity) {
  return !!entity && entity.name != null && FOOD_MOBS.has(entity.name)
}

// The player the bot answers to (may be out of render distance → entity null).
function ownerEntity(bot) {
  const name = bot.assistant.owner
  if (!name) return null
  const p = bot.players[name]
  return p && p.entity ? p.entity : null
}

// Hostiles sorted nearest-first, within `radius` of `origin` (defaults to bot).
function threatsNear(bot, radius, origin) {
  const from = origin || bot.entity?.position
  if (!from) return []
  const out = []
  for (const id of Object.keys(bot.entities)) {
    const e = bot.entities[id]
    if (!isHostile(e) || !e.position) continue
    const d = e.position.distanceTo(from)
    if (d <= radius) out.push({ entity: e, distance: d })
  }
  return out.sort((a, b) => a.distance - b.distance)
}

function nearestFoodMob(bot, radius) {
  return bot.nearestEntity(
    (e) => isFoodMob(e) && e.position && bot.entity &&
      e.position.distanceTo(bot.entity.position) <= radius,
  )
}

// Condensed inventory: { name: count }, plus a total item count.
function inventorySummary(bot) {
  const items = bot.inventory ? bot.inventory.items() : []
  const counts = {}
  for (const it of items) counts[it.name] = (counts[it.name] || 0) + it.count
  return { counts, total: items.reduce((s, it) => s + it.count, 0) }
}

function firstFood(bot) {
  const items = bot.inventory ? bot.inventory.items() : []
  for (const name of FOODS_PREFERRED) {
    const match = items.find((it) => it.name === name)
    if (match) return match
  }
  // Fall back to anything the server considers food.
  return items.find((it) => FOODS.has(it.name)) || null
}

// The full self-report used by narration, `status`, and the Claude brain.
function snapshot(bot) {
  const pos = bot.entity ? bot.entity.position : null
  const owner = ownerEntity(bot)
  const threats = threatsNear(bot, Math.max(bot.assistant.config.defendRadius, bot.assistant.config.guardRadius))
  const inv = inventorySummary(bot)
  return {
    name: bot.username,
    owner: bot.assistant.owner || null,
    ownerVisible: !!owner,
    ownerDistance: owner && pos ? Number(owner.position.distanceTo(pos).toFixed(1)) : null,
    health: bot.health != null ? Math.round(bot.health) : null,
    food: bot.food != null ? Math.round(bot.food) : null,
    position: pos ? { x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z) } : null,
    dimension: bot.game ? bot.game.dimension : null,
    timeOfDay: bot.time ? (bot.time.isDay ? 'day' : 'night') : null,
    mode: bot.assistant.mode,
    currentTask: bot.assistant.currentTask,
    threats: threats.slice(0, 5).map((t) => ({ mob: t.entity.name, distance: Number(t.distance.toFixed(1)) })),
    hasFood: !!firstFood(bot),
    inventory: inv.counts,
    itemCount: inv.total,
  }
}

module.exports = {
  HOSTILES,
  FOOD_MOBS,
  FOODS,
  isHostile,
  isFoodMob,
  ownerEntity,
  threatsNear,
  nearestFoodMob,
  inventorySummary,
  firstFood,
  snapshot,
}
