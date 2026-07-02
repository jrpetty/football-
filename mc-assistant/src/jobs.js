'use strict'

// ---------------------------------------------------------------------------
// The job queue. Long tasks (gather, build, smelt, mine trips, ...) run one at
// a time: give the bot three jobs and it works through them in order, telling
// you as each finishes. "stop" cancels the current job AND the queue. Quick
// commands (status, follow, equip, ...) never queue — they act immediately.
//
// The runner deliberately does NOT know about idle chores; chores.js only
// starts something when this queue is empty, so player orders always win.
// ---------------------------------------------------------------------------

function initState(bot) {
  bot.assistant.jobs = { queue: [], current: null }
}

function isBusy(bot) {
  return !!(bot.assistant.jobs && bot.assistant.jobs.current)
}

function queueLength(bot) {
  return bot.assistant.jobs ? bot.assistant.jobs.queue.length : 0
}

// Called by dispatch for queued actions. Runs now if free, otherwise enqueues.
// runFn: async () => resultString
async function submit(bot, label, runFn) {
  const jobs = bot.assistant.jobs
  if (jobs.current) {
    jobs.queue.push({ label, runFn })
    return `Queued: ${label} (${jobs.queue.length} in line after "${jobs.current.label}").`
  }
  return runJob(bot, { label, runFn })
}

async function runJob(bot, job) {
  const jobs = bot.assistant.jobs
  jobs.current = job
  try {
    return await job.runFn()
  } finally {
    jobs.current = null
    // Chain to the next queued job (if "stop" didn't clear it meanwhile).
    const next = jobs.queue.shift()
    if (next) {
      bot.assistant.reply(`Next up: ${next.label}`)
      runJob(bot, next)
        .then((res) => { if (res) bot.assistant.reply(res) })
        .catch((err) => bot.assistant.log.warn('queued job failed:', err && err.message))
    }
  }
}

function clear(bot) {
  const jobs = bot.assistant.jobs
  if (!jobs) return 0
  const dropped = jobs.queue.length
  jobs.queue = []
  return dropped
}

function describe(bot) {
  const jobs = bot.assistant.jobs
  if (!jobs || (!jobs.current && jobs.queue.length === 0)) return 'No jobs — standing by.'
  const parts = []
  if (jobs.current) parts.push(`Now: ${jobs.current.label}`)
  if (jobs.queue.length > 0) {
    parts.push(`Queued: ${jobs.queue.map((j, i) => `${i + 1}. ${j.label}`).join('  ')}`)
  }
  return parts.join(' | ')
}

module.exports = { initState, isBusy, queueLength, submit, clear, describe }
