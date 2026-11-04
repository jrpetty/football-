// A tiny local CORS proxy so the browser app can reach model APIs that don't
// allow direct browser calls (most cloud providers, including DeepSeek, Qwen,
// Kimi and GLM). Run `npm run proxy`, then tick "Use local proxy" on a mind
// profile. Requests to http://localhost:8787/<full-url> are forwarded to
// <full-url> with your headers; responses come back with CORS headers added.
//
// It only listens on 127.0.0.1 and only forwards https:// URLs or localhost,
// so it can't be used by other machines on your network.
import http from 'node:http'

const PORT = Number(process.env.PORT ?? 8787)
const PASS = ['authorization', 'content-type', 'x-api-key', 'anthropic-version', 'anthropic-beta', 'accept']

const server = http.createServer(async (req, res) => {
  res.setHeader('access-control-allow-origin', req.headers.origin ?? '*')
  res.setHeader('access-control-allow-headers', '*')
  res.setHeader('access-control-allow-methods', 'GET, POST, OPTIONS')
  res.setHeader('access-control-allow-private-network', 'true')
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return }
  const target = decodeURIComponent((req.url ?? '/').slice(1))
  let url
  try { url = new URL(target) } catch { res.writeHead(400); res.end('Usage: http://localhost:8787/https://api.example.com/v1/...'); return }
  const local = ['localhost', '127.0.0.1', '::1'].includes(url.hostname)
  if (url.protocol !== 'https:' && !local) { res.writeHead(400); res.end('Only https:// targets (or localhost) are forwarded.'); return }
  const chunks = []
  for await (const c of req) chunks.push(c)
  const headers = {}
  for (const h of PASS) if (req.headers[h]) headers[h] = req.headers[h]
  try {
    const r = await fetch(url, { method: req.method, headers, body: chunks.length ? Buffer.concat(chunks) : undefined })
    res.writeHead(r.status, { 'content-type': r.headers.get('content-type') ?? 'application/json' })
    res.end(Buffer.from(await r.arrayBuffer()))
    console.log(`${new Date().toLocaleTimeString()}  ${req.method} ${url.host}${url.pathname} → ${r.status}`)
  } catch (e) {
    res.writeHead(502)
    res.end(JSON.stringify({ error: { message: `Proxy could not reach ${url.host}: ${e.message}` } }))
  }
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Quartet model proxy listening on http://localhost:${PORT}`)
  console.log('Tick "Use local proxy" on any mind profile that the browser cannot reach directly.')
})
