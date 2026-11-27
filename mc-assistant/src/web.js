'use strict'

const http = require('http')

// ---------------------------------------------------------------------------
// The web dashboard: the task menu as big buttons in your browser, plus live
// status (HP / hunger / task / queue), the recent chat transcript, and a box
// to type commands as the owner. Plain Node http, no dependencies, bound to
// localhost only — it's a local control panel, not a public site.
// The server outlives reconnects; it always talks to the *current* bot.
// ---------------------------------------------------------------------------

const PAGE = /* html */ `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>mc-assistant</title>
<style>
  :root { color-scheme: dark; }
  body { font: 14px/1.45 system-ui, sans-serif; background:#111418; color:#e6e6e6; margin:0; padding:16px; }
  h1 { font-size:18px; margin:0 0 12px; }  h1 small { color:#8a9199; font-weight:normal; }
  .row { display:flex; gap:16px; flex-wrap:wrap; align-items:flex-start; }
  .card { background:#1a1f26; border:1px solid #2a313b; border-radius:10px; padding:14px; }
  #stats { display:flex; gap:18px; flex-wrap:wrap; }
  .stat b { display:block; font-size:11px; color:#8a9199; text-transform:uppercase; letter-spacing:.05em; }
  #presets { display:grid; grid-template-columns:repeat(auto-fill,minmax(190px,1fr)); gap:8px; min-width:340px; flex:1; }
  button.preset { background:#233041; color:#dbe7f5; border:1px solid #33465e; border-radius:8px; padding:10px 12px; cursor:pointer; text-align:left; font-size:13px; }
  button.preset:hover { background:#2c3d54; }
  #log { flex:1; min-width:320px; max-width:560px; }
  #chat { height:300px; overflow-y:auto; white-space:pre-wrap; font: 12px/1.5 ui-monospace, monospace; color:#bcc7d2; }
  form { display:flex; gap:8px; margin-top:10px; }
  input { flex:1; background:#10151b; color:#e6e6e6; border:1px solid #2a313b; border-radius:8px; padding:9px 11px; }
  input:focus { outline:1px solid #4a90d9; }
  #send { background:#2f6b3a; color:#fff; border:0; border-radius:8px; padding:9px 16px; cursor:pointer; }
  .muted { color:#8a9199; font-size:12px; margin-top:8px; }
  #offline { display:none; background:#5b2320; border:1px solid #8a3a35; border-radius:8px; padding:10px 12px; margin-bottom:12px; }
</style></head><body>
<h1>mc-assistant <small id="who"></small></h1>
<div id="offline">Bot is offline / reconnecting…</div>
<div class="card" style="margin-bottom:14px"><div id="stats"></div></div>
<div class="row">
  <div class="card" style="flex:1;min-width:340px">
    <div id="presets"></div>
    <div class="muted">Buttons run the same presets as the in-game menu (presets.json).</div>
  </div>
  <div class="card" id="log">
    <div id="chat"></div>
    <form id="f"><input id="cmd" placeholder='Tell it anything: "bring me 16 wood", "build a house", "status"…' autocomplete="off"><button id="send">Send</button></form>
  </div>
</div>
<script>
const el = (id) => document.getElementById(id)
function stat(label, value) { return '<div class="stat"><b>'+label+'</b>'+value+'</div>' }
async function refresh() {
  try {
    const r = await fetch('/api/status'); const s = await r.json()
    el('offline').style.display = s.online ? 'none' : 'block'
    el('who').textContent = s.online ? (s.name + ' — owner: ' + (s.owner || 'unclaimed')) : ''
    if (s.online) {
      el('stats').innerHTML =
        stat('Health', (s.health ?? '?') + '/20') + stat('Food', (s.food ?? '?') + '/20') +
        stat('Position', s.position ? s.position.x + ', ' + s.position.y + ', ' + s.position.z : '?') +
        stat('Doing', s.currentTask || s.mode) + stat('Jobs', s.jobs) +
        stat('Threats', s.threats && s.threats.length ? s.threats.map(t=>t.mob).join(', ') : 'none')
    }
    if (!el('presets').childElementCount && s.presets) {
      el('presets').innerHTML = s.presets.map((p,i)=>'<button class="preset" data-i="'+(i+1)+'">'+(i+1)+'. '+p+'</button>').join('')
      el('presets').addEventListener('click', async (e) => {
        const b = e.target.closest('button.preset'); if (!b) return
        await fetch('/api/pick', {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({n:Number(b.dataset.i)})})
      })
    }
    const chat = el('chat')
    const stick = chat.scrollTop + chat.clientHeight >= chat.scrollHeight - 8
    chat.textContent = (s.chat || []).map(c=>c.line).join('\\n')
    if (stick) chat.scrollTop = chat.scrollHeight
  } catch (_) { el('offline').style.display = 'block' }
}
el('f').addEventListener('submit', async (e) => {
  e.preventDefault()
  const text = el('cmd').value.trim(); if (!text) return
  el('cmd').value = ''
  await fetch('/api/command', {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({text})})
  setTimeout(refresh, 400)
})
refresh(); setInterval(refresh, 2000)
</script></body></html>`

function readBody(req) {
  return new Promise((resolve) => {
    let data = ''
    let done = false
    const finish = (value) => { if (!done) { done = true; resolve(value) } }
    req.on('data', (c) => { data += c; if (data.length > 8192) req.destroy() })
    req.on('end', () => {
      try { finish(JSON.parse(data || '{}')) } catch (_) { finish({}) }
    })
    // A destroyed/aborted request never emits 'end' — settle anyway so the
    // awaiting route handler doesn't leak.
    req.on('close', () => finish({}))
    req.on('error', () => finish({}))
  })
}

function send(res, code, body, type = 'application/json') {
  res.writeHead(code, { 'content-type': type })
  res.end(type === 'application/json' ? JSON.stringify(body) : body)
}

// getBot() returns the live bot (or null mid-reconnect); getBrain() the LLM brain.
function start({ getBot, getBrain, config, log }) {
  if (!config.webPort) return null
  const { snapshot } = require('./state')
  const menu = require('./menu')
  const jobs = require('./jobs')
  const chat = require('./chat')

  const server = http.createServer(async (req, res) => {
    try {
      const bot = getBot()
      const ready = !!(bot && bot.entity && bot.assistant)

      if (req.method === 'GET' && req.url === '/') return send(res, 200, PAGE, 'text/html')

      if (req.method === 'GET' && req.url === '/api/status') {
        const presets = menu.loadPresets(log).map((p) => p.label)
        if (!ready) return send(res, 200, { online: false, presets })
        const s = snapshot(bot)
        return send(res, 200, {
          online: true,
          ...s,
          jobs: jobs.describe(bot),
          chat: bot.assistant.chatLog.slice(-40),
          presets,
        })
      }

      if (req.method === 'POST' && req.url === '/api/command') {
        if (!ready) return send(res, 503, { error: 'bot offline' })
        const { text } = await readBody(req)
        if (!text || typeof text !== 'string') return send(res, 400, { error: 'text required' })
        const owner = bot.assistant.owner || bot.username
        chat.processBody(bot, getBrain(), owner, String(text).slice(0, 200), text)
          .catch((err) => log.warn('web command failed:', err && err.message))
        return send(res, 200, { ok: true })
      }

      if (req.method === 'POST' && req.url === '/api/pick') {
        if (!ready) return send(res, 503, { error: 'bot offline' })
        const { n } = await readBody(req)
        const owner = bot.assistant.owner || bot.username
        menu.pick(bot, owner, Number(n)).catch((err) => log.warn('web pick failed:', err && err.message))
        return send(res, 200, { ok: true })
      }

      send(res, 404, { error: 'not found' })
    } catch (err) {
      log.warn('web request failed:', err && err.message)
      try { send(res, 500, { error: 'internal' }) } catch (_) { /* closed */ }
    }
  })

  server.on('error', (err) => log.warn(`web dashboard failed to start on port ${config.webPort}:`, err && err.message))
  server.listen(config.webPort, config.webHost, () => {
    log.info(`Web dashboard: http://${config.webHost}:${config.webPort}`)
  })
  return server
}

module.exports = { start }
