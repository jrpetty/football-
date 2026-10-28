'use strict'

const rules = require('./brain/rules')
const menu = require('./menu')
const memory = require('./memory')
const { dispatch, statusLine } = require('./commands')

// ---------------------------------------------------------------------------
// Chat routing: decides which messages are meant for the bot, enforces the
// owner policy, then hands the text to the Claude brain (or the offline parser)
// and executes whatever actions come back.
// ---------------------------------------------------------------------------

const REPORTING = new Set(['status', 'inventory'])

function setup(bot, brain) {
  const cfg = bot.assistant.config

  bot.on('chat', (username, message) => {
    if (username === bot.username) return
    const { addressed, body } = classify(bot, username, message, false)
    if (addressed) handle(bot, brain, username, body, message).catch((e) => bot.assistant.log.warn('chat handler:', e && e.message))
  })

  // Whispers/DMs are always treated as addressed to the bot.
  bot.on('whisper', (username, message) => {
    if (username === bot.username) return
    handle(bot, brain, username, message, message).catch((e) => bot.assistant.log.warn('whisper handler:', e && e.message))
  })
}

// Is this message aimed at the bot, and what's the payload without the prefix/name?
function classify(bot, username, message, isWhisper) {
  const cfg = bot.assistant.config
  if (isWhisper) return { addressed: true, body: message }
  const name = bot.username.toLowerCase()
  const lower = message.toLowerCase()

  if (cfg.prefix && message.startsWith(cfg.prefix)) {
    return { addressed: true, body: message.slice(cfg.prefix.length).trim() }
  }
  // Whole-word match only, so a short bot name doesn't fire on substrings
  // (e.g. a bot named "Bot" must not trigger on "what about diamonds").
  const re = new RegExp(`\\b${bot.username.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i')
  if (re.test(message)) {
    // Strip the first mention of the bot's name so parsers see just the request.
    return { addressed: true, body: message.replace(re, '').replace(/^[,:\s]+/, '').trim() }
  }
  return { addressed: false, body: '' }
}

async function handle(bot, brain, username, body, rawMessage) {
  const cfg = bot.assistant.config

  // Claim ownership on first contact if no owner was configured — and
  // remember it across restarts.
  if (!bot.assistant.owner) {
    bot.assistant.owner = username
    memory.set('owner', username, bot.assistant.log)
    bot.assistant.reply(`You're my owner now, ${username}. I've got your back.`)
  }

  // Owner policy (case-insensitive: MC_OWNER casing may not match the account).
  if (cfg.ownerOnly && username.toLowerCase() !== String(bot.assistant.owner).toLowerCase()) {
    bot.assistant.log.debug(`Ignoring non-owner ${username}: ${rawMessage}`)
    return
  }

  return processBody(bot, brain, username, body, rawMessage)
}

// The command pipeline after addressing/ownership — also the entry point the
// web dashboard uses (its caller is the owner by definition).
async function processBody(bot, brain, username, body, rawMessage) {
  if (!body) { bot.assistant.reply(statusLine(bot)); return }

  // Bare "help" only — "help me fight the creeper" must reach the brain.
  if (/^help[!.?\s]*$/i.test(body)) {
    bot.assistant.reply('I can: menu (task list), come, follow, stop, goto x y z, gather <res> [n], bring me <res>, hunt, eat, cook/smelt <item>, farm, guard, attack, build <wall|house|tower|platform|pillar|bridge>, craft <item>, equip <item>, give me <item>, deposit, drop <item>, status, inventory. Just talk normally too.')
    return
  }

  // The task menu: "menu" shows it; "m 3" (or a bare "3" right after the menu
  // was shown, or a click on the tellraw list) picks a preset.
  if (/^(menu|tasks|options|presets)[!.?\s]*$/i.test(body)) {
    menu.show(bot, username)
    return
  }
  const pickMatch =
    body.match(/^m(?:enu)?\s*#?\s*(\d{1,2})[!.?\s]*$/i) ||
    (menu.isRecent(bot, username) ? body.match(/^#?(\d{1,2})[!.?\s]*$/) : null)
  if (pickMatch) {
    await menu.pick(bot, username, Number(pickMatch[1]))
    return
  }

  // Keep a little rolling memory for the brain's context.
  bot.assistant.history = bot.assistant.history || []
  bot.assistant.history.push(`${username}: ${rawMessage}`)
  if (bot.assistant.history.length > 12) bot.assistant.history.shift()

  bot.assistant.log.info(`${username} -> ${body}`)

  // Try the Claude brain first; on unavailability it returns null and we fall
  // back to the offline keyword parser.
  let plan = null
  if (brain) plan = await brain.interpret(bot, body, username)

  if (plan) {
    if (plan.reply) {
      bot.assistant.reply(plan.reply)
      bot.assistant.history.push(`${bot.username}: ${plan.reply}`)
    }
    if (plan.steps.length === 0 && !plan.reply) {
      bot.assistant.reply('Not sure what you want — try "gather 10 wood", "follow me", "guard", or "status".')
    }
    for (const step of plan.steps) {
      const res = await dispatch(bot, step.action, step.args)
      if (res && (REPORTING.has(step.action) || !plan.reply)) bot.assistant.reply(res)
    }
    return
  }

  // Offline path.
  const parsed = rules.parse(body)
  if (!parsed) {
    bot.assistant.reply('Not sure what you mean. I can: come, follow, gather <res>, hunt, guard, attack, deposit, status.')
    return
  }
  const res = await dispatch(bot, parsed.action, parsed.args)
  if (res) bot.assistant.reply(res)
}

module.exports = { setup, classify, processBody }
