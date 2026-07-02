'use strict'

const fs = require('fs')
const path = require('path')

// ---------------------------------------------------------------------------
// Long-term memory: a small JSON file so the bot survives restarts knowing its
// owner, its named waypoints ("home", "mine", ...), and where it last died.
// ---------------------------------------------------------------------------

const DATA_DIR = path.join(__dirname, '..', 'data')
const FILE = path.join(DATA_DIR, 'memory.json')

let state = null

function load(log) {
  if (state) return state
  try {
    state = JSON.parse(fs.readFileSync(FILE, 'utf8'))
  } catch (_) {
    state = {}
  }
  state.waypoints = state.waypoints || {}
  if (log) log.debug(`memory loaded: owner=${state.owner || '-'}, waypoints=${Object.keys(state.waypoints).length}`)
  return state
}

function save(log) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true })
    fs.writeFileSync(FILE, JSON.stringify(state, null, 2))
  } catch (err) {
    if (log) log.warn('could not save memory:', err && err.message)
  }
}

function get(key) {
  return load()[key]
}

function set(key, value, log) {
  load()
  state[key] = value
  save(log)
}

// --- waypoints ---

function normName(name) {
  return String(name || '').toLowerCase().trim().replace(/\s+/g, '_').slice(0, 32)
}

function setWaypoint(name, pos, dimension, log) {
  load()
  const key = normName(name)
  if (!key) return null
  state.waypoints[key] = { x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z), dimension: dimension || null }
  save(log)
  return key
}

function getWaypoint(name) {
  return load().waypoints[normName(name)] || null
}

function deleteWaypoint(name, log) {
  load()
  const key = normName(name)
  if (!state.waypoints[key]) return false
  delete state.waypoints[key]
  save(log)
  return true
}

function listWaypoints() {
  return Object.entries(load().waypoints)
    .map(([name, p]) => `${name} (${p.x},${p.y},${p.z})`)
}

module.exports = { load, save, get, set, setWaypoint, getWaypoint, deleteWaypoint, listWaypoints, normName }
