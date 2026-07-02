'use strict'

const mineflayer = require('mineflayer')

const movement = require('./skills/movement')
const gather = require('./skills/gather')
const defense = require('./skills/defense')
const survival = require('./skills/survival')
const commands = require('./commands')
const chat = require('./chat')
const memory = require('./memory')
const jobs = require('./jobs')

// ---------------------------------------------------------------------------
// Assembles a single bot: connects, loads the skill plugins, wires up chat and
// the survival loop, and exposes a small `bot.assistant` namespace that every
// module shares (config, logger, mutable state, and throttled chat helpers).
// ---------------------------------------------------------------------------

function createAssistantBot(config, log, brain) {
  const bot = mineflayer.createBot({
    host: config.host,
    port: config.port,
    username: config.username,
    auth: config.auth,
    password: config.password,
    version: config.version, // undefined => auto-detect
  })

  // Shared state + helpers hung off the bot so every module can reach them.
  bot.assistant = {
    config,
    log,
    // Configured owner wins; otherwise whoever it remembered from last time.
    owner: config.owner || memory.get('owner') || null,
    mode: 'idle', // idle | follow | guard | gather | hunt
    currentTask: null,
    busy: false, // a long action (gather/hunt/build) is running
    combat: false, // survival loop has taken over for a fight
    fleeing: false, // retreating due to low health
    eating: false,
    taskSeq: 0, // bumped by stop() so long-running loops know to bail
    history: [],
    chatLog: [], // last ~60 lines for the web dashboard
    patrol: null,
    _chatQueue: [],
    _chatTimer: null,
    _lastNarration: { text: '', at: 0 },
    _lastChoreAt: 0,
    reply: (text) => enqueueChat(bot, text),
    narrate: (text) => narrate(bot, text),
  }
  jobs.initState(bot)

  loadPlugins(bot)
  wireEvents(bot, brain)
  return bot
}

function loadPlugins(bot) {
  movement.install(bot) // pathfinder
  gather.install(bot) // collectblock (needs pathfinder loaded first)
  defense.install(bot) // pvp + armor-manager

  // Optional ballistic aiming for bow shots — combat falls back to melee-only
  // if this plugin isn't available or fails to load.
  try {
    let hawkeye = require('minecrafthawkeye')
    hawkeye = hawkeye.default || hawkeye
    bot.loadPlugin(hawkeye)
  } catch (err) {
    bot.assistant.log.debug('hawkeye (bow aiming) not loaded:', err && err.message)
  }
}

function wireEvents(bot, brain) {
  const { log } = bot.assistant

  bot.once('spawn', () => {
    movement.configureMovements(bot)
    survival.start(bot)
    chat.setup(bot, brain)
    try { bot.armorManager.equipAll() } catch (_) { /* nothing to equip yet */ }
    log.info(`Spawned as ${bot.username} on ${bot.assistant.config.host}:${bot.assistant.config.port}` +
      ` (${bot.version}). Owner: ${bot.assistant.owner || '<first to speak>'}.`)
    bot.assistant.reply(`${bot.username} online. Say "${bot.assistant.config.prefix}help" or just talk to me.`)
  })

  // Re-equip armor whenever we pick better gear up.
  bot.on('playerCollect', (collector) => {
    if (collector === bot.entity) {
      try { bot.armorManager.equipAll() } catch (_) { /* ignore */ }
    }
  })

  // Keep a short chat transcript for the web dashboard.
  bot.on('chat', (username, message) => {
    if (username !== bot.username) pushChatLog(bot, `<${username}> ${message}`)
  })

  bot.on('death', () => {
    bot.assistant.combat = false
    bot.assistant.fleeing = false
    bot.assistant.busy = false
    bot.assistant.eating = false // a consume() cut short by death never clears it
    bot.assistant.taskSeq++ // abandon whatever long task was running
    bot.assistant.currentTask = null
    bot.assistant.mode = 'idle'
    bot.assistant.patrol = null
    // Remember where the gear dropped so we can go back for it.
    if (bot.entity && bot.entity.position) {
      const p = bot.entity.position
      memory.set('deathAt', { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) }, log)
    }
    bot.assistant._justDied = true
    log.warn('I died.')
    bot.assistant.narrate('Ugh — I died. Respawning.')
  })

  // After a respawn (not the first spawn), go recover the dropped items —
  // they despawn in five minutes, so this queues immediately.
  bot.on('spawn', () => {
    if (!bot.assistant._justDied) return
    bot.assistant._justDied = false
    if (!bot.assistant.config.recoverItems || !memory.get('deathAt')) return
    setTimeout(() => {
      bot.assistant.narrate('Heading back for my stuff.')
      commands.dispatch(bot, 'recover', {})
        .then((res) => { if (res) bot.assistant.reply(res) })
        .catch((err) => log.debug('recover failed:', err && err.message))
    }, 1500)
  })

  bot.on('health', () => {
    // Survival loop reads health each tick; nothing to do here beyond keeping
    // the event alive so mineflayer populates bot.health/bot.food promptly.
  })

  bot.on('kicked', (reason) => log.warn('Kicked:', typeof reason === 'string' ? reason : JSON.stringify(reason)))
  bot.on('error', (err) => log.error('Bot error:', err && err.message ? err.message : err))

  // Tear down this bot's timers on disconnect so reconnects don't leak an
  // ever-growing pile of survival intervals firing against dead bot objects.
  bot.once('end', () => {
    survival.stop(bot)
    if (bot.assistant._chatTimer) {
      clearTimeout(bot.assistant._chatTimer)
      bot.assistant._chatTimer = null
    }
    bot.assistant._chatQueue = []
  })
}

// ------------------------------- chat plumbing -----------------------------

const CHAT_MAX = 236 // stay under the vanilla 256-char cap with room to spare
const CHAT_INTERVAL = 1100 // ms between messages, to avoid spam-kicks

function pushChatLog(bot, line) {
  const logArr = bot.assistant.chatLog
  logArr.push({ at: Date.now(), line })
  if (logArr.length > 60) logArr.shift()
}

function enqueueChat(bot, text) {
  if (!text) return
  const clean = String(text).replace(/\s+/g, ' ').trim().slice(0, CHAT_MAX)
  if (!clean) return
  pushChatLog(bot, `<${bot.username}> ${clean}`)
  bot.assistant._chatQueue.push(clean)
  scheduleFlush(bot)
}

function scheduleFlush(bot) {
  if (bot.assistant._chatTimer) return
  const flush = () => {
    const next = bot.assistant._chatQueue.shift()
    if (next == null) { bot.assistant._chatTimer = null; return }
    try { bot.chat(next) } catch (err) { bot.assistant.log.debug('chat send failed:', err && err.message) }
    bot.assistant._chatTimer = setTimeout(flush, CHAT_INTERVAL)
  }
  bot.assistant._chatTimer = setTimeout(flush, 0)
}

// Self-aware narration: only when enabled, and de-duplicated so the bot doesn't
// repeat the same line every tick.
function narrate(bot, text) {
  if (!bot.assistant.config.narrate || !text) return
  const now = Date.now()
  const last = bot.assistant._lastNarration
  if (last.text === text && now - last.at < 8000) return
  bot.assistant._lastNarration = { text, at: now }
  enqueueChat(bot, text)
}

module.exports = { createAssistantBot }
