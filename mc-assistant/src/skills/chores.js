'use strict'

const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const jobs = require('../jobs')
const rest = require('./rest')
const fishing = require('./fishing')
const torch = require('./torch')
const smeltSkill = require('./smelt')

// ---------------------------------------------------------------------------
// Proactive idle behavior. When the bot has no job, no queue, and no standing
// order, it looks for useful work around it instead of standing still:
// sleep at night, cook raw meat, stash overflow, harvest ripe crops, replant
// saplings, top up torches, fish when the larder is low. One chore at a time,
// on a cooldown, and any player order immediately takes priority (chores run
// through the same job runner, so new commands queue right behind — seconds,
// not minutes, because chores are small).
// ---------------------------------------------------------------------------

const COOLDOWN_MS = 25_000

// Each chore: name, when (cheap check), run (does the thing, returns text).
const CHORES = [
  {
    name: 'sleep through the night',
    when: (bot) => bot.time && !bot.time.isDay && !bot.isSleeping && rest.findBed(bot),
    run: (bot) => rest.sleep(bot),
  },
  {
    name: 'cook my raw meat',
    when: (bot) => {
      const raw = countMatching(bot, ['beef', 'porkchop', 'chicken', 'mutton', 'rabbit', 'cod', 'salmon'])
      if (raw < 4) return false
      if (!smeltSkill.pickFuel(bot, 4)) return false
      return !!findNamedBlock(bot, 'furnace', 16)
    },
    run: (bot) => smeltSkill.smelt(bot, { item: 'food', amount: 8 }),
  },
  {
    name: 'stash the overflow',
    when: (bot) => {
      const slots = bot.inventory.items().length
      if (slots < 27) return false // inventory getting full (36 slots total)
      return !!findAnyBlock(bot, ['chest', 'barrel'], 16)
    },
    run: (bot) => require('./inventory').deposit(bot),
  },
  {
    name: 'harvest the ripe crops',
    when: (bot) => hasRipeCrop(bot),
    run: (bot) => require('./farm').farm(bot, {}),
  },
  {
    name: 'replant some saplings',
    when: (bot) => countMatching(bot, null, (n) => n.endsWith('_sapling')) > 0 && !!plantableGround(bot),
    run: (bot) => plantSaplings(bot),
  },
  {
    name: 'light this place up',
    when: (bot) => !!torch.torchItem(bot),
    run: async (bot) => {
      const placed = await torch.placeTorchIfDark(bot)
      return placed ? 'Placed a torch — it was getting dark here.' : null
    },
  },
  {
    name: 'catch some food',
    when: (bot) => {
      const foodStock = countMatching(bot, ['cooked_beef', 'cooked_porkchop', 'cooked_chicken', 'cooked_mutton', 'bread', 'cooked_cod', 'cooked_salmon', 'baked_potato'])
      return foodStock < 4 && !!fishing.rodItem(bot) && !!fishing.findWater(bot, 20)
    },
    run: (bot) => fishing.fish(bot, { amount: 3 }),
  },
]

// --- helpers ---

function countMatching(bot, names, pred) {
  const set = names ? new Set(names) : null
  return bot.inventory.items().reduce((s, it) => {
    const hit = set ? set.has(it.name) : pred(it.name)
    return hit ? s + it.count : s
  }, 0)
}

function findNamedBlock(bot, name, maxDistance) {
  const mcData = require('minecraft-data')(bot.version)
  const def = mcData.blocksByName[name]
  return def ? bot.findBlock({ matching: def.id, maxDistance }) : null
}

function findAnyBlock(bot, names, maxDistance) {
  const mcData = require('minecraft-data')(bot.version)
  const ids = names.map((n) => mcData.blocksByName[n] && mcData.blocksByName[n].id).filter((v) => v != null)
  return ids.length ? bot.findBlock({ matching: ids, maxDistance }) : null
}

function hasRipeCrop(bot) {
  const farm = require('./farm')
  const info = farm.cropInfo(bot)
  const ids = Object.values(info).map((c) => c.id)
  if (ids.length === 0) return false
  const found = bot.findBlocks({ matching: ids, maxDistance: bot.assistant.config.gatherRadius, count: 16 })
  return found.some((pos) => {
    const b = bot.blockAt(pos)
    if (!b || !info[b.name] || !b.getProperties) return false
    return Number(b.getProperties().age) === info[b.name].maxAge
  })
}

function plantableGround(bot, maxDistance = 12) {
  const mcData = require('minecraft-data')(bot.version)
  const ids = ['dirt', 'grass_block', 'podzol']
    .map((n) => mcData.blocksByName[n] && mcData.blocksByName[n].id)
    .filter((v) => v != null)
  const spots = bot.findBlocks({ matching: ids, maxDistance, count: 24 })
  for (const pos of spots) {
    const above = bot.blockAt(pos.offset(0, 1, 0))
    if (above && (above.name === 'air')) return bot.blockAt(pos)
  }
  return null
}

async function plantSaplings(bot, max = 4) {
  let planted = 0
  for (let i = 0; i < max; i++) {
    const sapling = bot.inventory.items().find((it) => it.name.endsWith('_sapling'))
    if (!sapling) break
    const ground = plantableGround(bot)
    if (!ground) break
    try {
      const p = ground.position
      if (bot.entity.position.distanceTo(p.offset(0.5, 1, 0.5)) > 4) {
        await bot.pathfinder.goto(new goals.GoalNear(p.x, p.y + 1, p.z, 2))
      }
      await bot.equip(sapling, 'hand')
      const fresh = bot.blockAt(p)
      if (!fresh) break
      await bot.placeBlock(fresh, new Vec3(0, 1, 0))
      planted++
    } catch (err) {
      bot.assistant.log.debug('sapling plant failed:', err && err.message)
      break
    }
  }
  return planted > 0 ? `Replanted ${planted} sapling${planted === 1 ? '' : 's'} for later.` : null
}

// Called from the survival loop. Picks at most one chore whose conditions
// hold, and runs it as a normal job so player commands queue right behind it.
function maybeRun(bot) {
  const a = bot.assistant
  if (!a.config.proactive) return
  if (a.mode !== 'idle' || a.busy || a.combat || a.fleeing || a.eating || bot.isSleeping) return
  if (jobs.isBusy(bot) || jobs.queueLength(bot) > 0) return
  const now = Date.now()
  if (now - (a._lastChoreAt || 0) < COOLDOWN_MS) return
  a._lastChoreAt = now

  for (const chore of CHORES) {
    let due = false
    try { due = !!chore.when(bot) } catch (_) { continue }
    if (!due) continue
    bot.assistant.narrate(`No orders — I'll ${chore.name}.`)
    jobs.submit(bot, chore.name, () => chore.run(bot))
      .then((res) => { if (res) bot.assistant.reply(res) })
      .catch((err) => a.log.debug('chore failed:', err && err.message))
    return
  }
}

module.exports = { maybeRun, plantSaplings, CHORES }
