'use strict'

// ---------------------------------------------------------------------------
// Configuration is read entirely from the environment (see .env.example). We
// keep sane defaults so a fresh `npm start` connects to a local Java-Edition
// server. Nothing here is Minecraft-specific magic — just parsing + defaults.
// ---------------------------------------------------------------------------

require('dotenv').config()

function num(name, fallback) {
  const raw = process.env[name]
  if (raw === undefined || raw === '') return fallback
  const n = Number(raw)
  return Number.isFinite(n) ? n : fallback
}

function bool(name, fallback) {
  const raw = process.env[name]
  if (raw === undefined || raw === '') return fallback
  return /^(1|true|yes|on)$/i.test(raw.trim())
}

function str(name, fallback) {
  const raw = process.env[name]
  return raw === undefined || raw === '' ? fallback : raw
}

const config = {
  // --- Connection (Mineflayer only supports Java Edition) ---
  host: str('MC_HOST', 'localhost'),
  port: num('MC_PORT', 25565),
  username: str('MC_USERNAME', 'Assistant'),
  // 'offline' for cracked/LAN servers, or 'microsoft' for premium accounts.
  auth: str('MC_AUTH', 'offline'),
  password: str('MC_PASSWORD', undefined),
  // Pin the protocol version if auto-detection misbehaves (e.g. '1.20.4').
  version: str('MC_VERSION', undefined),

  // --- Ownership & control ---
  // The player the bot listens to and protects. If unset, the first player to
  // whisper it or say its name becomes the owner for the session.
  owner: str('MC_OWNER', undefined),
  // Only the owner can issue commands, vs. anyone in chat.
  ownerOnly: bool('MC_OWNER_ONLY', true),
  // Chat prefix that addresses the bot (also responds to its own name / whispers).
  prefix: str('MC_PREFIX', '!'),

  // --- Brain (Claude) ---
  anthropicApiKey: str('ANTHROPIC_API_KEY', undefined),
  // Per the Anthropic model catalog: opus 4.8 is the default; set to
  // 'claude-haiku-4-5' for snappier, cheaper in-game responses.
  model: str('MC_MODEL', 'claude-opus-4-8'),

  // --- Survival tuning ---
  eatThreshold: num('MC_EAT_THRESHOLD', 16), // hunger (0-20) at/below which we eat
  fleeHealth: num('MC_FLEE_HEALTH', 6), // health (0-20) at/below which we retreat
  defendRadius: num('MC_DEFEND_RADIUS', 8), // blocks: hostiles this close trigger self-defense
  guardRadius: num('MC_GUARD_RADIUS', 12), // blocks around owner we watch while guarding
  followRange: num('MC_FOLLOW_RANGE', 3), // how close we trail the owner
  gatherRadius: num('MC_GATHER_RADIUS', 20), // blocks: how far it searches for resources

  // --- Task menu ---
  // Send a clickable /tellraw menu (needs the bot to be op'd on the server).
  // Numbered "m <n>" picks always work regardless.
  menuClickable: bool('MC_MENU_CLICKABLE', true),
  // 'run' fires the pick on click; 'suggest' pre-fills chat for confirmation.
  menuClick: str('MC_MENU_CLICK', 'run'),

  // --- Proactivity ---
  // With no orders, find useful work: sleep at night, cook raw meat, stash
  // overflow, harvest ripe crops, replant saplings, torch dark spots, fish
  // when food runs low. Player commands always take priority.
  proactive: bool('MC_PROACTIVE', true),
  // Run back for its dropped items after dying.
  recoverItems: bool('MC_RECOVER_ITEMS', true),

  // --- Web dashboard (local browser control panel) ---
  // Binds to localhost only. Set MC_WEB_PORT=0 to disable.
  webPort: num('MC_WEB_PORT', 3210),
  webHost: str('MC_WEB_HOST', '127.0.0.1'),

  // --- Behaviour ---
  // Narrate what it's doing / feeling in chat (the "self-aware" voice).
  narrate: bool('MC_NARRATE', true),
  // Reconnect automatically after a kick/disconnect.
  autoReconnect: bool('MC_AUTO_RECONNECT', true),
  reconnectDelayMs: num('MC_RECONNECT_DELAY_MS', 5000),

  logLevel: str('MC_LOG_LEVEL', 'info'), // 'debug' | 'info' | 'warn' | 'error'
}

module.exports = config
