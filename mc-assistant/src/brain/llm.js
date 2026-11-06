'use strict'

const Anthropic = require('@anthropic-ai/sdk')
const { toolDefinitions } = require('../commands')
const { snapshot } = require('../state')

// ---------------------------------------------------------------------------
// The Claude brain. It turns free-form chat ("I'm getting mobbed, help!",
// "grab me a stack of wood then meet me at spawn") into concrete tool calls,
// grounded in a live self-report of the bot's state. The bot's actions ARE the
// tools, generated from the command registry, so the model can only ever ask
// for capabilities the bot actually has.
//
// One-shot per message: we send state + tools, execute whatever tools Claude
// picks, and speak its short text line. No API key → this module is inert and
// the caller uses the offline keyword parser instead.
// ---------------------------------------------------------------------------

function createBrain(config, log) {
  if (!config.anthropicApiKey) {
    log.info('No ANTHROPIC_API_KEY set — using the offline keyword brain.')
    return null
  }

  const client = new Anthropic({ apiKey: config.anthropicApiKey })
  const tools = toolDefinitions()

  const system = [
    `You are "${config.username}", an autonomous companion bot inside a Minecraft world.`,
    `Your owner is ${config.owner || 'the player who speaks to you'}. Serve and protect them.`,
    'You control an in-game character through the provided tools — each tool is a real action you can take.',
    'When the player asks you to DO something, call the matching tool(s). You may chain a few tools for a multi-step request.',
    'Always also emit a SHORT in-character spoken line (max ~18 words, no markdown, no lists) — this is said aloud in game chat.',
    'If the message is only conversation (a question, a greeting, checking on you), just reply in text using your current state; do not call tools.',
    'Be practical and terse, like a focused teammate. Never invent capabilities beyond your tools. Never narrate your reasoning.',
  ].join(' ')

  async function interpret(bot, message, speaker) {
    const state = snapshot(bot)
    const history = bot.assistant.history || []
    const contextLines = [
      'CURRENT STATE (JSON):',
      JSON.stringify(state),
    ]
    if (history.length) {
      contextLines.push('', 'RECENT CHAT:', ...history.slice(-6))
    }
    contextLines.push('', `${speaker} says: ${message}`)

    let resp
    try {
      resp = await client.messages.create({
        model: config.model,
        max_tokens: 1024,
        system,
        tools,
        messages: [{ role: 'user', content: contextLines.join('\n') }],
      })
    } catch (err) {
      log.warn('Claude request failed, falling back to keyword brain:', err && err.message)
      return null // signal caller to use the offline parser
    }

    const steps = []
    const textParts = []
    for (const block of resp.content || []) {
      if (block.type === 'tool_use') {
        steps.push({ action: block.name, args: block.input || {} })
      } else if (block.type === 'text' && block.text) {
        textParts.push(block.text.trim())
      }
    }

    return { steps, reply: textParts.join(' ').trim() }
  }

  return { interpret }
}

module.exports = { createBrain }
