'use strict'

const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')

// ---------------------------------------------------------------------------
// Blueprint-driven building. The player picks a layout (wall, platform,
// pillar, tower, house, bridge) and optional size/material; we generate the
// block positions in a local frame (u = right, v = up, w = forward), anchor it
// a couple of blocks in front of the bot facing away, and place block by block
// with mineflayer's placeBlock. A multi-pass loop retries spots that had no
// supporting neighbor yet, so roofs and spans fill in as their edges appear.
// ---------------------------------------------------------------------------

// Blocks we can safely build into (the game replaces them on placement).
const REPLACEABLE = new Set([
  'air', 'cave_air', 'void_air', 'water', 'lava',
  'grass', 'short_grass', 'tall_grass', 'fern', 'large_fern',
  'dead_bush', 'seagrass', 'tall_seagrass', 'snow', 'vine', 'light',
])

// Neighbor faces to click against, below-first (placing on top is most reliable).
const FACES = [
  new Vec3(0, -1, 0), new Vec3(0, 1, 0),
  new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
  new Vec3(0, 0, 1), new Vec3(0, 0, -1),
]

// Inventory blocks we should never use as filler when no material is named.
const NEVER_AUTO = new Set([
  'chest', 'trapped_chest', 'barrel', 'furnace', 'blast_furnace', 'smoker',
  'crafting_table', 'anvil', 'chipped_anvil', 'damaged_anvil', 'tnt',
  'beacon', 'enchanting_table', 'shulker_box',
])

// ------------------------------- blueprints --------------------------------
// Generators return cells {u,v,w}: u = right (centered), v = up, w = forward.

function centered(n) { return -Math.floor(n / 2) }

function wallCells({ length = 5, height = 3 }) {
  const cells = []
  const off = centered(length)
  for (let v = 0; v < height; v++) {
    for (let u = 0; u < length; u++) cells.push({ u: u + off, v, w: 0 })
  }
  return cells
}

function platformCells({ width = 5, length = 5 }) {
  const cells = []
  const off = centered(width)
  for (let w = 0; w < length; w++) {
    for (let u = 0; u < width; u++) cells.push({ u: u + off, v: 0, w })
  }
  return cells
}

function pillarCells({ height = 5 }) {
  const cells = []
  for (let v = 0; v < height; v++) cells.push({ u: 0, v, w: 0 })
  return cells
}

function bridgeCells({ length = 8, width = 2 }) {
  const cells = []
  const off = centered(width)
  for (let w = 0; w < length; w++) {
    for (let u = 0; u < width; u++) cells.push({ u: u + off, v: 0, w })
  }
  return cells
}

// Hollow square shaft with a 1x2 doorway centered on the near side.
function towerCells({ width = 3, height = 6 }) {
  const s = Math.max(3, width)
  const off = centered(s)
  const doorU = off + Math.floor(s / 2)
  const cells = []
  for (let v = 0; v < height; v++) {
    for (let w = 0; w < s; w++) {
      for (let u = 0; u < s; u++) {
        const edge = u === 0 || u === s - 1 || w === 0 || w === s - 1
        if (!edge) continue
        if (w === 0 && u + off === doorU && v < 2) continue // doorway
        cells.push({ u: u + off, v, w })
      }
    }
  }
  return cells
}

// Four walls with a doorway facing the player, plus a flat roof.
function houseCells({ width = 5, length = 5, height = 3 }) {
  const wSize = Math.max(3, width)
  const lSize = Math.max(3, length)
  const off = centered(wSize)
  const doorU = off + Math.floor(wSize / 2)
  const cells = []
  for (let v = 0; v < height; v++) {
    for (let w = 0; w < lSize; w++) {
      for (let u = 0; u < wSize; u++) {
        const edge = u === 0 || u === wSize - 1 || w === 0 || w === lSize - 1
        if (!edge) continue
        if (w === 0 && u + off === doorU && v < 2) continue // doorway
        cells.push({ u: u + off, v, w })
      }
    }
  }
  for (let w = 0; w < lSize; w++) {
    for (let u = 0; u < wSize; u++) cells.push({ u: u + off, v: height, w })
  }
  return cells
}

const BLUEPRINTS = {
  wall: wallCells,
  platform: platformCells,
  pillar: pillarCells,
  tower: towerCells,
  house: houseCells,
  bridge: bridgeCells,
}

const STRUCTURE_ALIASES = {
  wall: 'wall', walls: 'wall',
  platform: 'platform', floor: 'platform', pad: 'platform',
  pillar: 'pillar', column: 'pillar', post: 'pillar',
  tower: 'tower', turret: 'tower',
  house: 'house', hut: 'house', shelter: 'house', cabin: 'house', base: 'house', shack: 'house', home: 'house',
  bridge: 'bridge', walkway: 'bridge',
}

function normalizeStructure(s) {
  return STRUCTURE_ALIASES[String(s || '').toLowerCase().trim()] || null
}

function clampDim(v, max = 16) {
  const n = Math.floor(Number(v))
  return Number.isFinite(n) && n >= 1 ? Math.min(n, max) : undefined
}

// ------------------------------- materials ---------------------------------

// Friendly material word -> predicate over an inventory item name.
function materialMatcher(requested) {
  if (!requested) return null
  // Item names use underscores; the brain may pass "oak planks" verbatim.
  const r = String(requested).toLowerCase().trim().replace(/\s+/g, '_')
  const table = {
    cobble: (n) => n === 'cobblestone' || n === 'cobbled_deepslate',
    cobblestone: (n) => n === 'cobblestone' || n === 'cobbled_deepslate',
    stone: (n) => ['stone', 'cobblestone', 'stone_bricks', 'cobbled_deepslate', 'andesite', 'diorite', 'granite', 'tuff'].includes(n),
    deepslate: (n) => n.includes('deepslate'),
    dirt: (n) => ['dirt', 'coarse_dirt', 'rooted_dirt', 'grass_block'].includes(n),
    wood: (n) => n.endsWith('_planks') || n.endsWith('_log'),
    plank: (n) => n.endsWith('_planks'),
    planks: (n) => n.endsWith('_planks'),
    log: (n) => n.endsWith('_log'),
    logs: (n) => n.endsWith('_log'),
    sand: (n) => n === 'sand' || n === 'red_sand' || n === 'sandstone',
    sandstone: (n) => n.includes('sandstone'),
    brick: (n) => n === 'bricks' || n === 'stone_bricks',
    bricks: (n) => n === 'bricks' || n === 'stone_bricks',
    netherrack: (n) => n === 'netherrack',
    glass: (n) => n === 'glass' || n.endsWith('_stained_glass'),
  }
  if (table[r]) return table[r]
  return (n) => n === r || n.includes(r) // exact/loose name like "oak_planks"
}

function isPlaceableBlockItem(bot, name) {
  const mcData = require('minecraft-data')(bot.version)
  const def = mcData.blocksByName[name]
  return !!def && def.boundingBox === 'block'
}

// Blocks we'd rather build with when the player didn't name a material.
const AUTO_PREFERENCE = ['cobblestone', 'cobbled_deepslate', 'dirt', 'netherrack', 'stone', 'stone_bricks', 'sandstone']

function findMaterialItem(bot, pred) {
  const items = bot.inventory.items()
  if (pred) {
    return items.find((it) => pred(it.name) && isPlaceableBlockItem(bot, it.name)) || null
  }
  for (const name of AUTO_PREFERENCE) {
    const hit = items.find((it) => it.name === name)
    if (hit) return hit
  }
  const planks = items.find((it) => it.name.endsWith('_planks'))
  if (planks) return planks
  // Most plentiful remaining solid block that isn't precious/functional.
  const candidates = items
    .filter((it) => isPlaceableBlockItem(bot, it.name) && !NEVER_AUTO.has(it.name))
    .sort((a, b) => b.count - a.count)
  return candidates[0] || null
}

function countMaterial(bot, pred) {
  return bot.inventory.items().reduce((sum, it) => {
    const usable = pred
      ? pred(it.name) && isPlaceableBlockItem(bot, it.name)
      : isPlaceableBlockItem(bot, it.name) && !NEVER_AUTO.has(it.name)
    return usable ? sum + it.count : sum
  }, 0)
}

// ------------------------------- placement ---------------------------------

// Anchor: 2 blocks ahead of the bot, snapped to the dominant cardinal axis so
// layouts stay grid-aligned, then snapped down/up to ground level.
function buildFrame(bot) {
  const yaw = bot.entity.yaw
  const dx = -Math.sin(yaw)
  const dz = -Math.cos(yaw)
  const forward = Math.abs(dx) > Math.abs(dz)
    ? new Vec3(Math.sign(dx) || 1, 0, 0)
    : new Vec3(0, 0, Math.sign(dz) || 1)
  const right = new Vec3(-forward.z, 0, forward.x)
  const origin = snapToGround(bot, bot.entity.position.floored().plus(forward.scaled(2)))
  return { origin, forward, right }
}

function snapToGround(bot, pos) {
  let p = pos.clone()
  for (let i = 0; i < 6; i++) {
    const here = bot.blockAt(p)
    const below = bot.blockAt(p.offset(0, -1, 0))
    if (!here || !below) break
    if (here.boundingBox === 'block') { p = p.offset(0, 1, 0); continue } // buried → up
    if (below.boundingBox !== 'block') { p = p.offset(0, -1, 0); continue } // floating → down
    break
  }
  return p
}

// Try to place one block at pos. Returns:
// 'placed' | 'present' (already solid) | 'nomaterial' | 'unsupported' |
// 'unreachable' | 'failed' | 'unloaded' — the last four get retried next pass.
async function placeOne(bot, pos, pred) {
  const block = bot.blockAt(pos)
  if (!block) return 'unloaded'
  if (!REPLACEABLE.has(block.name)) return 'present'

  let refPos = null
  let face = null
  for (const f of FACES) {
    const nb = bot.blockAt(pos.plus(f))
    if (nb && nb.boundingBox === 'block') { refPos = nb.position; face = f.scaled(-1); break }
  }
  if (!refPos) return 'unsupported'

  const item = findMaterialItem(bot, pred)
  if (!item) return 'nomaterial'

  // Step out of the way if we're standing where the block goes.
  const feet = bot.entity.position.floored()
  if (feet.equals(pos) || feet.offset(0, 1, 0).equals(pos)) {
    try {
      await bot.pathfinder.goto(new goals.GoalInvert(new goals.GoalNear(pos.x, pos.y, pos.z, 2)))
    } catch (_) { return 'failed' }
  }

  // Walk into placement range.
  if (bot.entity.position.distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.2) {
    try {
      await bot.pathfinder.goto(new goals.GoalNear(pos.x, pos.y, pos.z, 3))
    } catch (_) { return 'unreachable' }
  }

  const ref = bot.blockAt(refPos) // refresh after moving
  if (!ref || ref.boundingBox !== 'block') return 'unsupported'

  try {
    await bot.equip(item, 'hand')
    await bot.lookAt(ref.position.offset(0.5, 0.5, 0.5).plus(face.scaled(0.5)), true)
    await bot.placeBlock(ref, face)
    return 'placed'
  } catch (err) {
    bot.assistant.log.debug('place failed:', err && err.message)
    return 'failed'
  }
}

// ------------------------------- entry point --------------------------------

async function build(bot, { structure, material, width, length, height } = {}) {
  const kind = normalizeStructure(structure)
  if (!kind) {
    return `I don't know that layout. I can build: wall, platform, pillar, tower, house, bridge.`
  }
  if (!bot.entity) return "I'm not in the world yet."

  const dims = { width: clampDim(width), length: clampDim(length), height: clampDim(height) }
  // Forgive dimension-name mixups: walls only have a length, towers a width —
  // if the caller sent the other one, use it rather than silently defaulting.
  if (kind === 'wall' && dims.length === undefined) dims.length = dims.width
  if (kind === 'tower' && dims.width === undefined) dims.width = dims.length
  Object.keys(dims).forEach((k) => dims[k] === undefined && delete dims[k])
  const cells = BLUEPRINTS[kind](dims)
    .sort((a, b) => a.v - b.v || a.w - b.w || Math.abs(a.u) - Math.abs(b.u))

  const pred = materialMatcher(material)
  const have = countMaterial(bot, pred)
  if (have === 0) {
    return material
      ? `I don't have any ${material} on me — ask me to gather some first.`
      : "I've got no blocks to build with — ask me to gather stone, dirt, or wood first."
  }

  const frame = buildFrame(bot)
  const targets = cells.map((c) =>
    frame.origin
      .plus(frame.right.scaled(c.u))
      .plus(frame.forward.scaled(c.w))
      .offset(0, c.v, 0))

  const seq = bot.assistant.taskSeq
  bot.assistant.mode = 'build'
  bot.assistant.busy = true
  bot.assistant.currentTask = `building a ${kind}`
  // Warn about shortages against spots that actually need filling — cells
  // already occupied by solid blocks (rebuilds, terrain) cost nothing.
  const need = targets.filter((p) => {
    const b = bot.blockAt(p)
    return !b || REPLACEABLE.has(b.name)
  }).length
  if (have < need) {
    bot.assistant.narrate(`Heads up: I have ${have} blocks for ~${need} spots — I'll build what I can.`)
  }

  let placed = 0
  let present = 0
  let ranOut = false
  let interrupted = false
  let pending = targets

  try {
    for (let pass = 0; pass < 20 && pending.length && !ranOut && !interrupted; pass++) {
      const retry = []
      let progress = 0
      for (const pos of pending) {
        if (bot.assistant.taskSeq !== seq || bot.assistant.combat || bot.assistant.fleeing) {
          interrupted = true
          break
        }
        const r = await placeOne(bot, pos, pred)
        if (r === 'placed') { placed++; progress++ }
        else if (r === 'present') { present++; progress++ }
        else if (r === 'nomaterial') { ranOut = true; break }
        else retry.push(pos)
      }
      pending = retry
      if (!progress) break
    }
  } finally {
    bot.assistant.busy = false
    bot.assistant.currentTask = null
    if (bot.assistant.mode === 'build') bot.assistant.mode = 'idle'
  }

  const remaining = targets.length - placed - present
  if (interrupted) return `Had to break off the ${kind} — ${placed} blocks placed. Say "build ${kind}" again to keep going.`
  if (ranOut) return `Ran out of blocks after placing ${placed} — I need about ${remaining} more for the ${kind}.`
  if (placed === 0 && present > 0 && remaining === 0) return `Looks like that ${kind} is already built.`
  if (remaining > 0) return `${kind} mostly done: ${placed} placed, ${remaining} spots I couldn't reach.`
  return `Done — ${kind} built (${placed} blocks).`
}

module.exports = { build, normalizeStructure, BLUEPRINTS, STRUCTURE_ALIASES, materialMatcher, buildFrame }
