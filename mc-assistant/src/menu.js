'use strict'

const fs = require('fs')
const path = require('path')

// ---------------------------------------------------------------------------
// The task menu: preset jobs the player can fire without typing commands.
// Say "menu" and the bot presents its presets two ways at once:
//   - a clickable list via /tellraw (needs the bot to have op / tellraw perms;
//     clicking whispers the pick back to the bot), and
//   - plain numbered chat lines as the universal fallback — reply "m 3" or
//     just "3" within 90 seconds to pick.
// Presets live in presets.json next to package.json — edit it, restart, done.
// ---------------------------------------------------------------------------

const PRESETS_FILE = path.join(__dirname, '..', 'presets.json')
const PICK_WINDOW_MS = 90_000

const DEFAULT_PRESETS = [
  { label: 'Get logs (16)', action: 'gather', args: { resource: 'wood', amount: 16 } },
  { label: 'Get stone (16)', action: 'gather', args: { resource: 'stone', amount: 16 } },
  { label: 'Get iron (8)', action: 'gather', args: { resource: 'iron', amount: 8 } },
  { label: 'Hunt some food', action: 'hunt', args: {} },
  { label: 'Follow me', action: 'follow', args: {} },
  { label: 'Guard me', action: 'guard', args: {} },
  { label: 'Build a shelter', action: 'build', args: { structure: 'house' } },
  { label: 'Stop', action: 'stop', args: {} },
]

let cached = null

function loadPresets(log) {
  if (cached) return cached
  try {
    const raw = JSON.parse(fs.readFileSync(PRESETS_FILE, 'utf8'))
    const list = Array.isArray(raw) ? raw : raw.presets
    // A preset is either a single action or a multi-step job (steps: [...]).
    const valid = (list || []).filter(
      (p) => p && typeof p.label === 'string' &&
        (typeof p.action === 'string' ||
          (Array.isArray(p.steps) && p.steps.every((s) => s && typeof s.action === 'string'))),
    )
    if (valid.length > 0) {
      cached = valid
      return cached
    }
    if (log) log.warn('presets.json had no valid entries — using built-in presets.')
  } catch (err) {
    if (log) log.warn('Could not read presets.json — using built-in presets:', err && err.message)
  }
  cached = DEFAULT_PRESETS
  return cached
}

// '1.21.5' renamed text-component keys (clickEvent -> click_event, value ->
// command); pick the right dialect for the server we're on.
function versionAtLeast(version, target) {
  const a = String(version).split('.').map((n) => parseInt(n, 10) || 0)
  const b = String(target).split('.').map((n) => parseInt(n, 10) || 0)
  for (let i = 0; i < 3; i++) {
    if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) > (b[i] || 0)
  }
  return true
}

// Pure builder so it's testable offline. Returns the tellraw JSON string.
function renderClickable(botName, presets, { newKeys = false, click = 'run' } = {}) {
  const clickKey = newKeys ? 'click_event' : 'clickEvent'
  const cmdKey = newKeys ? 'command' : 'value'
  const action = click === 'suggest' ? 'suggest_command' : 'run_command'
  const parts = [{ text: `— ${botName}'s tasks (click one) —`, color: 'gold' }]
  presets.forEach((p, i) => {
    parts.push({ text: `\n[${i + 1}] `, color: 'gray' })
    parts.push({
      text: p.label,
      color: 'aqua',
      [clickKey]: { action, [cmdKey]: `/w ${botName} m ${i + 1}` },
    })
  })
  return JSON.stringify(parts)
}

// Compact numbered lines for plain chat (a few presets per line).
function renderTextLines(presets, maxLen = 220) {
  const lines = []
  let line = ''
  presets.forEach((p, i) => {
    const piece = `${i + 1}) ${p.label}`
    if (line && (line + '  ' + piece).length > maxLen) {
      lines.push(line)
      line = piece
    } else {
      line = line ? `${line}  ${piece}` : piece
    }
  })
  if (line) lines.push(line)
  return lines
}

function show(bot, username) {
  const cfg = bot.assistant.config
  const presets = loadPresets(bot.assistant.log)
  bot.assistant._menuAt = bot.assistant._menuAt || {}
  bot.assistant._menuAt[username.toLowerCase()] = Date.now()

  if (cfg.menuClickable) {
    const json = renderClickable(bot.username, presets, {
      newKeys: versionAtLeast(bot.version, '1.21.5'),
      click: cfg.menuClick,
    })
    try { bot.chat(`/tellraw ${username} ${json}`) } catch (_) { /* no perms */ }
    bot.assistant.reply(`Menu sent — click a task, or say "m <number>". (No clickable menu? I need op; say "m 1"–"m ${presets.length}".)`)
  } else {
    for (const line of renderTextLines(presets)) bot.assistant.reply(line)
    bot.assistant.reply('Pick one: say "m <number>" (or just the number).')
  }
}

function isRecent(bot, username) {
  const at = bot.assistant._menuAt && bot.assistant._menuAt[username.toLowerCase()]
  return !!at && Date.now() - at < PICK_WINDOW_MS
}

async function pick(bot, username, n) {
  const presets = loadPresets(bot.assistant.log)
  const idx = Math.floor(n) - 1
  if (idx < 0 || idx >= presets.length) {
    bot.assistant.reply(`No task ${n} — the menu has 1 to ${presets.length}.`)
    return
  }
  const preset = presets[idx]
  bot.assistant.reply(`On it: ${preset.label}`)
  // Lazy require avoids a cycle (commands.js exposes a menu action too).
  const { dispatch } = require('./commands')
  const steps = Array.isArray(preset.steps)
    ? preset.steps
    : [{ action: preset.action, args: preset.args }]
  const seq = bot.assistant.taskSeq
  for (let i = 0; i < steps.length; i++) {
    if (bot.assistant.taskSeq !== seq) break // "stop" cancels the rest of the job
    const step = steps[i]
    const result = await dispatch(bot, step.action, step.args || {})
    if (result) bot.assistant.reply(steps.length > 1 ? `[${i + 1}/${steps.length}] ${result}` : result)
  }
}

module.exports = { show, pick, isRecent, loadPresets, renderClickable, renderTextLines, versionAtLeast, DEFAULT_PRESETS }
