'use strict'

const movement = require('./skills/movement')
const gather = require('./skills/gather')
const food = require('./skills/food')
const defense = require('./skills/defense')
const inventory = require('./skills/inventory')
const build = require('./skills/build')
const craft = require('./skills/craft')
const smelt = require('./skills/smelt')
const farm = require('./skills/farm')
const { snapshot } = require('./state')

// ---------------------------------------------------------------------------
// The action registry. Every capability the bot has is one entry here, taking
// (bot, args) and returning a short human-readable result string. Both the
// Claude brain (via tool calls) and the offline keyword parser produce
// { action, args } objects that flow through `dispatch`.
//
// Each entry also carries an Anthropic tool schema so the brain knows exactly
// what it can do — the registry is the single source of truth.
// ---------------------------------------------------------------------------

const registry = {
  come: {
    describe: 'Walk to the owner and stop.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => movement.come(bot),
  },

  follow: {
    describe: 'Continuously follow the owner until told to stop.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => movement.follow(bot),
  },

  goto: {
    describe: 'Travel to specific x, y, z coordinates.',
    schema: {
      type: 'object',
      properties: {
        x: { type: 'number' }, y: { type: 'number' }, z: { type: 'number' },
      },
      required: ['x', 'y', 'z'],
      additionalProperties: false,
    },
    run: (bot, a) => movement.goto(bot, a),
  },

  gather: {
    describe: 'Mine/collect a resource within a search radius (default 20 blocks). resource is one of: wood, stone, coal, iron, gold, diamond, redstone, lapis, copper, emerald, dirt, sand, gravel. Optional radius widens/narrows the search.',
    schema: {
      type: 'object',
      properties: {
        resource: { type: 'string' },
        amount: { type: 'integer', minimum: 1, maximum: 64 },
        radius: { type: 'integer', minimum: 4, maximum: 64 },
        deliver: { type: 'boolean', description: 'Bring the haul back to the owner and hand it over when done.' },
      },
      required: ['resource'],
      additionalProperties: false,
    },
    run: (bot, a) => gather.gather(bot, { resource: a.resource, amount: a.amount, maxDistance: a.radius, deliver: a.deliver }),
  },

  smelt: {
    describe: 'Smelt/cook items in a nearby furnace using real fuel from the inventory. item examples: iron, gold, copper, meat (cooks whatever raw meat it holds), fish, sand (glass), cobblestone (stone), potato.',
    schema: {
      type: 'object',
      properties: {
        item: { type: 'string' },
        amount: { type: 'integer', minimum: 1, maximum: 64 },
      },
      required: ['item'],
      additionalProperties: false,
    },
    run: (bot, a) => smelt.smelt(bot, a),
  },

  farm: {
    describe: 'Harvest ripe crops (wheat, carrots, potatoes, beetroot) within the search radius, replant them, and seed any bare farmland with seeds it holds.',
    schema: {
      type: 'object',
      properties: { radius: { type: 'integer', minimum: 4, maximum: 64 } },
      additionalProperties: false,
    },
    run: (bot, a) => farm.farm(bot, a),
  },

  give: {
    describe: 'Walk to the owner and hand items over (tosses them at their feet). item can be an exact name, a resource word (wood, iron, ...), or omitted for all loot (keeps tools/armor/food).',
    schema: {
      type: 'object',
      properties: {
        item: { type: 'string' },
        amount: { type: 'integer', minimum: 1 },
      },
      additionalProperties: false,
    },
    run: (bot, a) => inventory.give(bot, a),
  },

  build: {
    describe: 'Build a structure right in front of the bot, facing the way it looks. structure is one of: wall, platform, pillar, tower, house, bridge (aliases like hut/shelter/floor/column work). Optional material (cobblestone, stone, dirt, planks, sand, ...) — otherwise it uses common blocks it carries. Optional width/length/height in blocks (max 16).',
    schema: {
      type: 'object',
      properties: {
        structure: { type: 'string' },
        material: { type: 'string' },
        width: { type: 'integer', minimum: 1, maximum: 16 },
        length: { type: 'integer', minimum: 1, maximum: 16 },
        height: { type: 'integer', minimum: 1, maximum: 16 },
      },
      required: ['structure'],
      additionalProperties: false,
    },
    run: (bot, a) => build.build(bot, a),
  },

  hunt: {
    describe: 'Find and kill the nearest animal for food, then eat if hungry.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => food.hunt(bot),
  },

  eat: {
    describe: 'Eat the best food currently in the inventory.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => food.eat(bot, { force: true }),
  },

  guard: {
    describe: 'Guard the owner — attack any hostile mob that comes near them.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => defense.guard(bot),
  },

  attack: {
    describe: 'Attack the nearest hostile, or a named mob/player if target is given.',
    schema: {
      type: 'object',
      properties: { target: { type: 'string' } },
      additionalProperties: false,
    },
    run: (bot, a) => defense.attack(bot, a),
  },

  craft: {
    describe: 'Craft an item by player rules (needs materials, and a crafting table nearby for 3x3 recipes). item examples: planks, stick, crafting_table, wooden_pickaxe, stone_axe, torch, furnace, chest. Generic words work: "pickaxe" crafts the best tier it has materials for.',
    schema: {
      type: 'object',
      properties: {
        item: { type: 'string' },
        amount: { type: 'integer', minimum: 1, maximum: 64 },
      },
      required: ['item'],
      additionalProperties: false,
    },
    run: (bot, a) => craft.craft(bot, a),
  },

  equip: {
    describe: 'Hold a named item in hand, e.g. axe, pickaxe, sword, torch, or an exact item name.',
    schema: {
      type: 'object',
      properties: { item: { type: 'string' } },
      required: ['item'],
      additionalProperties: false,
    },
    run: (bot, a) => equipItem(bot, a),
  },

  menu: {
    describe: 'Show the task menu (preset jobs the player can pick by number or click).',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => {
      const menu = require('./menu') // lazy: menu.js requires commands.js
      menu.show(bot, bot.assistant.owner || bot.username)
      return null
    },
  },

  deposit: {
    describe: 'Store gathered loot into the nearest chest or barrel (keeps tools and food).',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => inventory.deposit(bot),
  },

  drop: {
    describe: 'Drop/toss items on the ground. item is the item name; amount optional.',
    schema: {
      type: 'object',
      properties: {
        item: { type: 'string' },
        amount: { type: 'integer', minimum: 1 },
      },
      required: ['item'],
      additionalProperties: false,
    },
    run: (bot, a) => inventory.drop(bot, a),
  },

  stop: {
    describe: 'Stop everything — cancel movement, combat, and current task; go idle.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => stopAll(bot),
  },

  status: {
    describe: 'Report health, hunger, position, threats, and what it is doing.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => statusLine(bot),
  },

  inventory: {
    describe: 'Report what the bot is carrying.',
    schema: { type: 'object', properties: {}, additionalProperties: false },
    run: (bot) => inventoryLine(bot),
  },

  say: {
    describe: 'Say a message in chat.',
    schema: {
      type: 'object',
      properties: { text: { type: 'string' } },
      required: ['text'],
      additionalProperties: false,
    },
    run: (bot, a) => { bot.assistant.reply(String(a.text || '')); return null },
  },
}

async function equipItem(bot, { item } = {}) {
  if (!item) return 'Tell me what to hold.'
  const want = String(item).toLowerCase().trim().replace(/\s+/g, '_')
  const items = bot.inventory.items()
  const match =
    items.find((it) => it.name === want) ||
    items.find((it) => it.name.endsWith(`_${want}`)) || // "axe" -> stone_axe
    items.find((it) => it.name.includes(want))
  if (!match) return `I'm not carrying a ${item}.`
  try {
    await bot.equip(match, 'hand')
    return `Holding my ${match.name.replace(/_/g, ' ')}.`
  } catch (err) {
    return `Couldn't equip that: ${err && err.message ? err.message : 'unknown error'}.`
  }
}

function stopAll(bot) {
  // Bump the task sequence so long-running loops (gather/hunt/build) notice
  // they've been superseded and bail out at their next check.
  bot.assistant.taskSeq++
  bot.assistant.mode = 'idle'
  bot.assistant.currentTask = null
  bot.assistant.busy = false
  bot.assistant.fleeing = false
  defense.disengage(bot)
  movement.stopMoving(bot)
  return 'Stopped. Standing by.'
}

function statusLine(bot) {
  const s = snapshot(bot)
  const parts = [
    `HP ${s.health ?? '?'}/20`,
    `food ${s.food ?? '?'}/20`,
    s.position ? `at ${s.position.x},${s.position.y},${s.position.z}` : 'position unknown',
    s.timeOfDay || '',
    s.currentTask ? `— ${s.currentTask}` : `— ${s.mode}`,
  ]
  if (s.threats.length) {
    parts.push(`| threats: ${s.threats.map((t) => `${t.mob}(${t.distance}m)`).join(', ')}`)
  }
  return parts.filter(Boolean).join(', ')
}

function inventoryLine(bot) {
  const s = snapshot(bot)
  const entries = Object.entries(s.inventory)
  if (entries.length === 0) return 'Inventory empty.'
  const listed = entries
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([name, n]) => `${n} ${name}`)
    .join(', ')
  return `Carrying: ${listed}${entries.length > 12 ? ', …' : ''}.`
}

// Run an action by name; always resolves to a string (or null for silent ones).
async function dispatch(bot, action, args = {}) {
  const entry = registry[action]
  if (!entry) return `I don't know how to "${action}".`
  try {
    const result = await entry.run(bot, args || {})
    return typeof result === 'string' ? result : null
  } catch (err) {
    bot.assistant.log.warn(`action "${action}" failed:`, err && err.message)
    return `That didn't work: ${err && err.message ? err.message : 'unknown error'}.`
  }
}

// Anthropic tool definitions, generated from the registry.
function toolDefinitions() {
  return Object.entries(registry).map(([name, entry]) => ({
    name,
    description: entry.describe,
    input_schema: entry.schema,
  }))
}

function actionNames() {
  return Object.keys(registry)
}

module.exports = { registry, dispatch, toolDefinitions, actionNames, stopAll, statusLine }
