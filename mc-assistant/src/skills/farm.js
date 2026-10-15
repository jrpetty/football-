'use strict'

const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')

// ---------------------------------------------------------------------------
// Farming: harvest ripe crops in the search radius, replant from the drops,
// and seed any empty farmland nearby. Same player rules as everything else —
// it can only replant with seeds it actually holds.
// ---------------------------------------------------------------------------

// crop block -> the item used to replant it.
const CROPS = {
  wheat: 'wheat_seeds',
  carrots: 'carrot',
  potatoes: 'potato',
  beetroots: 'beetroot_seeds',
}

function cropInfo(bot) {
  const mcData = require('minecraft-data')(bot.version)
  const info = {}
  for (const name of Object.keys(CROPS)) {
    const def = mcData.blocksByName[name]
    if (!def) continue
    const ageState = (def.states || []).find((s) => s.name === 'age')
    info[name] = {
      id: def.id,
      maxAge: ageState ? ageState.num_values - 1 : 7,
      seed: CROPS[name],
    }
  }
  return info
}

function seedItem(bot, seedName) {
  return bot.inventory.items().find((it) => it.name === seedName) || null
}

// Plant a seed on a farmland block (click its top face).
async function plantOn(bot, farmlandBlock, seed) {
  const p = farmlandBlock.position
  if (bot.entity.position.distanceTo(p.offset(0.5, 1, 0.5)) > 4) {
    await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y + 1, p.z, 2))
  }
  await bot.equip(seed, 'hand')
  const fresh = bot.blockAt(p)
  if (!fresh || fresh.name !== 'farmland') throw new Error('farmland gone')
  await bot.placeBlock(fresh, new Vec3(0, 1, 0))
}

async function farm(bot, { radius } = {}) {
  const mcData = require('minecraft-data')(bot.version)
  const r = Math.max(4, Math.min(64, Math.floor(Number(radius)) || bot.assistant.config.gatherRadius))
  const info = cropInfo(bot)
  const ids = Object.values(info).map((c) => c.id)
  if (ids.length === 0) return "This server version doesn't seem to have crops I know."

  const seq = bot.assistant.taskSeq
  bot.assistant.mode = 'farm'
  bot.assistant.busy = true
  bot.assistant.currentTask = 'tending the crops'

  let harvested = 0
  let replanted = 0
  try {
    // --- 1) Harvest everything ripe, replanting as we go. ---
    const positions = bot.findBlocks({ matching: ids, maxDistance: r, count: 128 })
    for (const pos of positions) {
      if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) break
      const block = bot.blockAt(pos)
      if (!block) continue
      const crop = info[block.name]
      if (!crop) continue
      const props = block.getProperties ? block.getProperties() : {}
      if (Number(props.age) !== crop.maxAge) continue // not ripe

      try {
        await bot.collectBlock.collect(block)
        harvested++
      } catch (err) {
        bot.assistant.log.debug('harvest failed:', err && err.message)
        continue
      }

      // Replant on the farmland underneath, if we picked up a seed.
      const farmland = bot.blockAt(pos.offset(0, -1, 0))
      const seed = seedItem(bot, crop.seed)
      if (farmland && farmland.name === 'farmland' && seed) {
        try {
          await plantOn(bot, farmland, seed)
          replanted++
        } catch (err) {
          bot.assistant.log.debug('replant failed:', err && err.message)
        }
      }
    }

    // --- 2) Seed any bare farmland nearby with whatever seeds we hold. ---
    const farmlandDef = mcData.blocksByName.farmland
    if (farmlandDef) {
      const bare = bot.findBlocks({ matching: farmlandDef.id, maxDistance: r, count: 64 })
      for (const pos of bare) {
        if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) break
        const above = bot.blockAt(pos.offset(0, 1, 0))
        if (!above || above.name !== 'air') continue
        const seed = Object.values(info).map((c) => seedItem(bot, c.seed)).find(Boolean)
        if (!seed) break
        const farmland = bot.blockAt(pos)
        try {
          await plantOn(bot, farmland, seed)
          replanted++
        } catch (err) {
          bot.assistant.log.debug('plant failed:', err && err.message)
        }
      }
    }
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'farm') bot.assistant.mode = 'idle'
  }

  if (harvested === 0 && replanted === 0) {
    return `Can't do that here — no ripe crops or plantable farmland within ${r} blocks.`
  }
  const bits = []
  if (harvested > 0) bits.push(`harvested ${harvested} crops`)
  if (replanted > 0) bits.push(`planted ${replanted}`)
  return `Done — ${bits.join(' and ')}.`
}

module.exports = { farm, CROPS, cropInfo }
