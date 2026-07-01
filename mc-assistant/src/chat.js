'use strict'

const rules = require('./brain/rules')
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
  if (lower.includes(name)) {
    // Strip the first mention of the bot's name so parsers see just the request.
    const re = new RegExp(bot.username.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i')
    return { addressed: true, body: message.replace(re, '').replace(/^[,:\s]+/, '').trim() }
  }
  return { addressed: false, body: '' }
}

async function handle(bot, brain, username, body, rawMessage) {
  const cfg = bot.assistant.config

  // Claim ownership on first contact if no owner was configured.
  if (!bot.assistant.owner) {
    bot.assistant.owner = username
    bot.assistant.reply(`You're my owner now, ${username}. I've got your back.`)
  }

  // Owner policy.
  if (cfg.ownerOnly && username !== bot.assistant.owner) {
    bot.assistant.log.debug(`Ignoring non-owner ${username}: ${rawMessage}`)
    return
  }

  if (!body) { bot.assistant.reply(statusLine(bot)); return }

  if (/^help\b/i.test(body)) {
    bot.assistant.reply('I can: come, follow, stop, goto x y z, gather <res> [n], hunt, eat, guard, attack, deposit, drop <item>, status, inventory. Just talk normally too.')
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

module.exports = { setup, classify }
