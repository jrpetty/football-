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

  bot.on('end', (reason) => {
    if (stopping) return
    log.warn('Disconnected:', reason || 'unknown')
    if (config.autoReconnect) {
      log.info(`Reconnecting in ${config.reconnectDelayMs}ms…`)
      setTimeout(spawnBot, config.reconnectDelayMs)
    } else {
      process.exit(0)
    }
  })

  // A fatal error before 'end' (bad host, auth) shouldn't spin forever silently.
  bot.on('error', (err) => {
    const msg = err && err.message ? err.message : String(err)
    if (/ENOTFOUND|ECONNREFUSED|EAI_AGAIN/.test(msg)) {
      log.error(`Can't reach the server (${msg}). Check MC_HOST / MC_PORT and that the server is running.`)
    }
  })
}

process.on('SIGINT', () => { stopping = true; log.info('Shutting down.'); process.exit(0) })
process.on('unhandledRejection', (err) => log.warn('Unhandled rejection:', err && err.message ? err.message : err))

spawnBot()
