'use strict'

// Tiny leveled logger — no dependency needed. Timestamps + level tags.

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 }

function make(level) {
  const threshold = LEVELS[level] ?? LEVELS.info

  function emit(lvl, args) {
    if (LEVELS[lvl] < threshold) return
    const ts = new Date().toISOString().slice(11, 19)
    const tag = lvl.toUpperCase().padEnd(5)
    const line = `[${ts}] ${tag}`
    const stream = lvl === 'error' || lvl === 'warn' ? console.error : console.log
    stream(line, ...args)
  }

  return {
    level,
    debug: (...a) => emit('debug', a),
    info: (...a) => emit('info', a),
    warn: (...a) => emit('warn', a),
    error: (...a) => emit('error', a),
  }
}

module.exports = { make }
