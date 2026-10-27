// Screen memory and the knowledge base: compact, validated, bounded.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanScreen, dHash, hamming, isScreenCatalog, SCREEN_CAP, ScreenMemory, screensForPrompt } from '../src/core/vision/screens.ts'
import { cleanKnowledge, isKnowledgeBase, knowledgeText } from '../src/core/knowledge/base.ts'
import { frame } from './helpers.ts'

test('fingerprints: the same screen at another size matches; a different screen does not', () => {
  const menuA = frame(320, 180, [20, 20, 20], [{ r: { x: 0.05, y: 0.1, w: 0.3, h: 0.8 }, c: [220, 220, 220] }, { r: { x: 0.6, y: 0.7, w: 0.3, h: 0.1 }, c: [200, 60, 60] }])
  const menuA2 = frame(1280, 720, [22, 22, 22], [{ r: { x: 0.05, y: 0.1, w: 0.3, h: 0.8 }, c: [215, 215, 215] }, { r: { x: 0.6, y: 0.7, w: 0.3, h: 0.1 }, c: [200, 60, 60] }])
  const menuB = frame(320, 180, [20, 20, 20], [{ r: { x: 0.55, y: 0.05, w: 0.4, h: 0.3 }, c: [240, 240, 240] }, { r: { x: 0.1, y: 0.5, w: 0.2, h: 0.4 }, c: [90, 160, 220] }])
  const a = dHash(menuA)
  assert.match(a, /^[0-9a-f]{16}$/)
  assert.ok(hamming(a, dHash(menuA2)) <= 4)
  assert.ok(hamming(a, dHash(menuB)) > 12)
})

test('screen memory keeps only non-gameplay screens, dedupes, caps, and validates', () => {
  const mem = new ScreenMemory('cod-mw4')
  const now = new Date('2026-10-27T12:00:00Z')
  assert.equal(mem.learn('00ff00ff00ff00ff', { name: 'In match', kind: 'gameplay', summary: 'Fighting' }, now), null, 'gameplay is never cached')
  assert.equal(mem.learn('0f0f0f0f0f0f0f0f', { name: 'Loadout editor', kind: 'menu', summary: 'Pick weapons' }, now)?.isNew, true)
  assert.equal(mem.learn('0f0f0f0f0f0f0f0e', { name: 'Loadouts', kind: 'menu', summary: 'Pick primary, secondary, perks and streaks.' }, now)?.isNew, false)
  const [e] = mem.catalog.screens
  assert.equal(e.name, 'Loadout editor', 'the first name stays stable')
  assert.match(e.summary, /perks/, 'a richer summary replaces a thin one')
  assert.equal(mem.match('0f0f0f0f0f0f0f1f')?.name, 'Loadout editor')
  assert.equal(mem.match('f0f0f0f0f0f0f0f0'), null)
  for (let i = 0; i < SCREEN_CAP + 30; i++) mem.learn(i.toString(16).padStart(16, '0').replace(/./g, (c, j) => ((Number.parseInt(c, 16) + j * 5) % 16).toString(16)), { name: `Menu ${i}`, kind: 'menu', summary: '' }, now)
  assert.ok(mem.catalog.screens.length <= SCREEN_CAP)
  assert.ok(isScreenCatalog(mem.catalog))
  assert.equal(isScreenCatalog({ v: 1, packId: 'x', screens: [{ h: 'nothex', name: 'a', kind: 'menu', summary: '', n: 1, t: '' }] }), false)
  assert.equal(cleanScreen({ name: '  Main   menu ', kind: 'menu', summary: 'x'.repeat(500) })?.summary.length, 240)
  assert.equal(cleanScreen({ name: 'X', kind: 'hacked' }), null)
  assert.match(screensForPrompt(mem.catalog, 2), /^- .+ \(menu\)/)
})

test('knowledge: untrusted research is trimmed into valid sections and rendered within a budget', () => {
  const kb = cleanKnowledge(
    {
      gameVersion: 'Season 1',
      sections: [
        { id: 'maps', items: [{ name: 'Lotus', detail: 'Three-lane map.' }, { name: 'x'.repeat(200), detail: 'y'.repeat(900) }] },
        { id: 'roster', items: [{ name: 'M4', detail: 'Assault rifle.' }, { name: 42 }] },
        { id: 'not-a-section', items: [{ name: 'a', detail: 'b' }] },
      ],
    },
    { packId: 'cod-mw4', gameTitle: 'MW4', model: 'claude-opus-5-5', createdAt: '2026-10-27T00:00:00Z', sources: [{ url: 'https://example.com', title: 'Ex' }, { url: 'http://insecure', title: 'no' }] },
  )
  assert.ok(isKnowledgeBase(kb))
  assert.deepEqual(kb.sections.map((s) => s.id), ['maps', 'roster'])
  assert.equal(kb.sections[0].items[1].name.length, 80)
  assert.equal(kb.sources.length, 1)
  const text = knowledgeText([{ label: 'kb', sections: kb.sections }], { budget: 10_000 })
  assert.match(text, /## Maps\n- Lotus: Three-lane map\./)
  assert.match(text, /## Roster.*\n- M4: Assault rifle\./)
  assert.ok(knowledgeText([{ label: 'kb', sections: kb.sections }], { budget: 60 }).length <= 60)
  // The pack's curated reference and the research merge without duplicates.
  const merged = knowledgeText([{ label: 'pack', sections: [{ id: 'maps', items: [{ name: 'Lotus', detail: 'Pack wording.' }] }] }, { label: 'kb', sections: kb.sections }], { budget: 10_000, sections: ['maps'] })
  assert.equal(merged.match(/Lotus/g)?.length, 1)
})
