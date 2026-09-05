'use strict'

const { Vec3 } = require('vec3')

// Torch placement — keeps work areas lit so mobs don't spawn in them. Light
// data isn't reliably tracked on every server/version, so callers use it two
// ways: placeTorch() plants one at the bot's feet unconditionally (mining
// tunnels do this on a fixed cadence), and placeTorchIfDark() checks measured
// block light first and quietly does nothing when light data is unavailable.

function torchItem(bot) {
  return bot.inventory.items().find((it) => it.name === 'torch') || null
}

// Plant a torch on the ground at (or next to) the bot's feet.
async function placeTorch(bot) {
  const torch = torchItem(bot)
  if (!torch) return false
  const feet = bot.entity.position.floored()
  // Find a spot: air with solid ground below, at feet or one of 4 neighbors.
  const spots = [feet, feet.offset(1, 0, 0), feet.offset(-1, 0, 0), feet.offset(0, 0, 1), feet.offset(0, 0, -1)]
  for (const pos of spots) {
    const here = bot.blockAt(pos)
    const ground = bot.blockAt(pos.offset(0, -1, 0))
    if (!here || !ground) continue
    if (here.name !== 'air' && here.name !== 'cave_air') continue
    if (ground.boundingBox !== 'block') continue
    try {
      await bot.equip(torch, 'hand')
      await bot.placeBlock(ground, new Vec3(0, 1, 0))
      return true
    } catch (_) { /* try next spot */ }
  }
  return false
}

// True only when we can *measure* darkness here (block light < 8, no
// skylight). Servers that don't send light data always return false, so the
// idle chore never spins on guesswork.
function isDarkHere(bot) {
  if (!bot.entity) return false
  const feet = bot.blockAt(bot.entity.position.floored())
  if (!feet) return false
  const light = feet.light
  const sky = feet.skyLight
  if (typeof light !== 'number' || typeof sky !== 'number') return false
  return sky === 0 && light < 8
}

async function placeTorchIfDark(bot) {
  if (!isDarkHere(bot)) return false
  return placeTorch(bot)
}

async function torchCommand(bot) {
  if (!torchItem(bot)) return "I don't have any torches — craft me some (coal + stick)."
  const ok = await placeTorch(bot)
  return ok ? 'Torch placed.' : "Couldn't find a good spot for a torch right here."
}

module.exports = { placeTorch, placeTorchIfDark, isDarkHere, torchCommand, torchItem }
