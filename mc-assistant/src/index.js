'use strict'

const config = require('./config')
const { make } = require('./logger')
const { createAssistantBot } = require('./bot')
const { createBrain } = require('./brain/llm')

// ---------------------------------------------------------------------------
// Entry point: build the logger and Claude brain once, then (re)spawn the bot,
// reconnecting after disconnects so the companion stays with you across
// server restarts and the occasional kick.
// ---------------------------------------------------------------------------

const log = make(config.logLevel)
const brain = createBrain(config, log)

let stopping = false

function spawnBot() {
  log.info(`Connecting to ${config.host}:${config.port} as ${config.username}…`)
  const bot = createAssistantBot(config, log, brain)

  // Recover exactly once per bot, whether we learn of the disconnect via 'end'
  // (normal kicks, socket-level errors) or only via 'error' — a failed
  // Microsoft auth, for instance, emits 'error' with no following 'end'.
  let recovered = false
  const recover = (why) => {
    if (stopping || recovered) return
    recovered = true
    log.warn('Disconnected:', why || 'unknown')
    if (config.autoReconnect) {
      log.info(`Reconnecting in ${config.reconnectDelayMs}ms…`)
      setTimeout(spawnBot, config.reconnectDelayMs)
    } else {
      process.exit(0)
    }
  }

  bot.on('end', (reason) => recover(reason))

  bot.on('error', (err) => {
    const msg = err && err.message ? err.message : String(err)
    if (/ENOTFOUND|ECONNREFUSED|EAI_AGAIN/.test(msg)) {
      log.error(`Can't reach the server (${msg}). Check MC_HOST / MC_PORT and that the server is running.`)
    }
    // A fatal error (bad host/auth) shouldn't spin forever silently; drive
    // recovery here too, deduped with 'end' so socket errors don't reconnect twice.
    recover(msg)
  })
}

process.on('SIGINT', () => { stopping = true; log.info('Shutting down.'); process.exit(0) })
process.on('unhandledRejection', (err) => log.warn('Unhandled rejection:', err && err.message ? err.message : err))

spawnBot()
