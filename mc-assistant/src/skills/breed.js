'use strict'

const { goals } = require('mineflayer-pathfinder')

// Animal breeding — feed the right food to two animals of the same species so
// the herd grows. Player rules: uses real food items from the inventory.

// species -> items that put them in love mode.
const BREED_FOOD = {
  cow: ['wheat'],
  mooshroom: ['wheat'],
  sheep: ['wheat'],
  pig: ['carrot', 'potato', 'beetroot'],
  chicken: ['wheat_seeds', 'melon_seeds', 'pumpkin_seeds', 'beetroot_seeds'],
  rabbit: ['carrot', 'golden_carrot', 'dandelion'],
  wolf: [],
  cat: [],
}

function feedItemFor(bot, species) {
  const foods = BREED_FOOD[species] || []
  const items = bot.inventory.items()
  for (const f of foods) {
    const stack = items.find((it) => it.name === f)
    if (stack && stack.count >= 2) return stack
  }
  return null
}

function findPair(bot, species, radius) {
  const found = []
  for (const id of Object.keys(bot.entities)) {
    const e = bot.entities[id]
    if (!e || !e.position || e.name !== species) continue
    if (!bot.entity || e.position.distanceTo(bot.entity.position) > radius) continue
    found.push(e)
    if (found.length === 2) return found
  }
  return null
}

async function feedOne(bot, entity, stack) {
  const p = entity.position
  await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y, p.z, 2))
  await bot.equip(stack, 'hand')
  await bot.lookAt(entity.position.offset(0, 0.5, 0))
  await bot.activateEntity(entity)
}

async function breed(bot, { animal } = {}) {
  const radius = bot.assistant.config.gatherRadius
  let species = String(animal || '').toLowerCase().trim()
  if (species.endsWith('s')) species = species.slice(0, -1) // cows -> cow

  // No species given: breed whatever we have food + a pair for.
  const options = species ? [species] : Object.keys(BREED_FOOD)
  let pick = null
  for (const s of options) {
    if (!BREED_FOOD[s] || BREED_FOOD[s].length === 0) continue
    const stack = feedItemFor(bot, s)
    if (!stack) continue
    const pair = findPair(bot, s, radius)
    if (pair) { pick = { species: s, stack, pair }; break }
  }

  if (!pick) {
    if (species && !BREED_FOOD[species]) return `I don't know how to breed ${species}.`
    if (species) {
      const foods = (BREED_FOOD[species] || []).join('/')
      return findPair(bot, species, radius)
        ? `I need at least 2 ${foods || 'food'} to breed ${species}s.`
        : `Can't do it — I don't see two ${species}s within ${radius} blocks.`
    }
    return `Nothing I can breed nearby — I need two of a kind within ${radius} blocks and the right food (wheat, carrots, or seeds).`
  }

  const seq = bot.assistant.taskSeq
  bot.assistant.mode = 'breed'
  bot.assistant.busy = true
  bot.assistant.currentTask = `breeding ${pick.species}s`
  try {
    for (const target of pick.pair) {
      if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) {
        return 'Broke off the breeding run.'
      }
      if (!target.isValid) return `One of the ${pick.species}s wandered off.`
      try {
        await feedOne(bot, target, pick.stack)
      } catch (err) {
        bot.assistant.log.debug('feed failed:', err && err.message)
        return `Couldn't feed the ${pick.species} — it kept moving away.`
      }
    }
    return `Fed two ${pick.species}s — hearts! A baby's on the way.`
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'breed') bot.assistant.mode = 'idle'
  }
}

module.exports = { breed, BREED_FOOD, feedItemFor }
