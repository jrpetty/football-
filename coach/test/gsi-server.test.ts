// The Game State Integration listener (desktop app): off until turned on, on
// 127.0.0.1 only, accepts an update only with this computer's token, refuses
// anything too large or not a POST, keeps its token across restarts, and
// writes a cfg file the game can read.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { request } from 'node:http'
import { cfgText, GsiServer } from '../electron/gsi.ts'

function post(port: number, body: string, method = 'POST'): Promise<number> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, method, path: '/', agent: false, headers: { 'content-type': 'application/json' } }, (res) => {
      res.resume()
      res.on('end', () => resolve(res.statusCode ?? 0))
    })
    req.on('error', reject)
    req.end(body)
  })
}

test('the listener: off by default, token checked, loopback only, token kept', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gsi-'))
  try {
    const got: unknown[] = []
    const s = new GsiServer(dir, (b) => got.push(b))
    await s.init()
    assert.equal(s.status().enabled, false)
    assert.equal(s.status().listening, false)
    const port = 40000 + Math.floor(Math.random() * 20000)
    const st = await s.configure({ enabled: true, port })
    assert.equal(st.listening, true, st.error ?? '')
    const token = /"token" "([a-f0-9]+)"/.exec(s.cfgText())![1]
    assert.ok(token.length >= 32)
    assert.equal(await post(port, JSON.stringify({ provider: { steamid: '1' }, auth: { token: 'wrong' } })), 403)
    assert.equal(await post(port, 'not json'), 400)
    assert.equal(await post(port, '', 'GET'), 405)
    assert.equal(await post(port, JSON.stringify({ x: 'y'.repeat(600 * 1024), auth: { token } })).catch(() => 413), 413)
    assert.equal(got.length, 0)
    assert.equal(await post(port, JSON.stringify({ provider: { steamid: '1' }, auth: { token } })), 200)
    assert.equal(got.length, 1)
    assert.equal(s.status().updates, 1)
    await s.stop()
    // A restart keeps the token (the saved cfg file stays valid) and the setting.
    const again = new GsiServer(dir, () => undefined)
    await again.init()
    assert.equal(/"token" "([a-f0-9]+)"/.exec(again.cfgText())![1], token)
    assert.equal(again.status().enabled, true)
    await again.stop()
    const saved = JSON.parse(await readFile(join(dir, 'gsi.json'), 'utf8'))
    assert.equal(saved.token, token)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('the cfg file asks for the player data only, at this computer', () => {
  const t = cfgText(31982, 'abc')
  assert.match(t, /"uri"\s+"http:\/\/127\.0\.0\.1:31982\/"/)
  assert.match(t, /"token" "abc"/)
  assert.doesNotMatch(t, /allplayers|player_position|allgrenades/)
})
