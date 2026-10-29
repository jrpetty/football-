// Check the AoE IV pack's units against data parsed from the game files
// (aoe4world/data, https://data.aoe4world.com). The data is only read to
// check ours; it is never copied into the app (the repository has no licence
// file). For each unit in the pack's roster: the age it first appears in, and
// its cost, for the civilisations the pack says have it.
//
//   node scripts/check-aoe4-data.mjs [units/all.json]   (downloads it when no file is given)
//
// Writes docs/AOE4_DATA_CHECK.md and prints a summary. Exit code 1 when anything disagrees.

import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const packPath = join(here, '..', 'src', 'profiles', 'aoe4.json')
const pack = JSON.parse(readFileSync(packPath, 'utf8'))
const source = process.argv[2]
const raw = source ? JSON.parse(readFileSync(source, 'utf8')) : await (await fetch('https://raw.githubusercontent.com/aoe4world/data/main/units/all.json')).json()
const data = raw.data

// The pack's civilisation ids → the data's codes.
const CIV = {
  english: 'en', french: 'fr', 'holy-roman-empire': 'hr', rus: 'ru', mongols: 'mo', chinese: 'ch', 'delhi-sultanate': 'de', 'abbasid-dynasty': 'ab',
  malians: 'ma', ottomans: 'ot', byzantines: 'by', japanese: 'ja', ayyubids: 'ay', 'jeanne-darc': 'je', 'order-of-the-dragon': 'od', 'zhu-xis-legacy': 'zx',
  'house-of-lancaster': 'hl', 'knights-templar': 'kt', 'golden-horde': 'gol', 'macedonian-dynasty': 'mac', 'sengoku-daimyo': 'sen', 'tughlaq-dynasty': 'tug',
  'jin-dynasty': 'jin', vikings: 'vik', scots: 'sco',
}

const norm = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
const byBase = new Map()
for (const u of data) {
  const keys = new Set([u.baseId, norm(u.name)])
  for (const k of keys) byBase.set(k, [...(byBase.get(k) ?? []), u])
}

const parseCost = (s) => {
  const c = { food: 0, wood: 0, gold: 0, stone: 0 }
  for (const m of String(s ?? '').matchAll(/(\d+)\s*([FWGS])/gi)) c[{ f: 'food', w: 'wood', g: 'gold', s: 'stone' }[m[2].toLowerCase()]] += Number(m[1])
  return c
}
const costText = (c) => ['food', 'wood', 'gold', 'stone'].filter((k) => c[k]).map((k) => `${c[k]}${k[0].toUpperCase()}`).join(' ') || '0'

const rows = []
let checked = 0
let notFound = 0
for (const u of pack.knowledge.roster.units) {
  // A name like "Archer Ship (Galley, Dhow, Junk)" lists the names each civilisation uses.
  const names = [u.id, norm(u.name), ...(/\(([^)]*)\)/.exec(u.name)?.[1].split(',').map(norm) ?? [])]
  const variants = [...new Set(names.flatMap((n) => byBase.get(n) ?? []))]
  if (!variants.length) {
    notFound++
    rows.push({ unit: u.name, issue: 'not in the game data under this name', ours: '', theirs: '' })
    continue
  }
  checked++
  const civs = (u.factions ?? []).map((f) => CIV[f]).filter(Boolean)
  const mine = civs.length ? variants.filter((v) => v.civs.some((c) => civs.includes(c))) : variants
  const pool = mine.length ? mine : variants
  // The age it first appears in, civilisation by civilisation (the pack's age, or its age for that civilisation).
  const firstByCiv = new Map()
  for (const v of pool) for (const c of v.civs) firstByCiv.set(c, Math.min(firstByCiv.get(c) ?? 9, v.age))
  const codeToFaction = Object.fromEntries(Object.entries(CIV).map(([f, c]) => [c, f]))
  const checkCivs = civs.length ? civs : [...firstByCiv.keys()]
  const off = []
  for (const c of checkCivs) {
    const theirs = firstByCiv.get(c)
    if (theirs === undefined) continue
    const ours = u.ageFor?.[codeToFaction[c]] ?? u.age
    if (typeof ours === 'number' && ours !== theirs) off.push(`${codeToFaction[c] ?? c}: ${theirs} (pack ${ours})`)
  }
  // A unit every civilisation has: report only when most disagree (variants keep their own ageFor).
  if (off.length && (civs.length || off.length > checkCivs.length / 2)) rows.push({ unit: u.name, issue: 'age', ours: u.age, theirs: off.join('; ') })
  if (u.cost) {
    const first = pool.filter((v) => v.age === Math.min(...pool.map((x) => x.age)))
    const theirs = first.map((v) => ({ food: v.costs.food, wood: v.costs.wood, gold: v.costs.gold, stone: v.costs.stone }))
    const ours = parseCost(u.cost)
    const match = theirs.some((t) => ['food', 'wood', 'gold', 'stone'].every((k) => t[k] === ours[k]))
    if (!match) rows.push({ unit: u.name, issue: 'cost', ours: u.cost, theirs: [...new Set(theirs.map(costText))].join(' or ') })
  }
}

const date = new Date().toISOString().slice(0, 10)
const md = [
  '# AoE IV pack checked against the game files',
  '',
  `Checked ${date} against [aoe4world/data](https://github.com/aoe4world/data) (data version ${raw.__version__}), which is parsed from the game's own files. The data is only read to check the pack; nothing from it is copied into the app. Run \`node scripts/check-aoe4-data.mjs\` to check again after a patch.`,
  '',
  `Units in the pack: ${pack.knowledge.roster.units.length}. Found in the game data: ${checked}. Not found under the pack's name: ${notFound}.`,
  '',
  rows.length ? '| Unit | What differs | The pack | The game files |\n|---|---|---|---|\n' + rows.map((r) => `| ${r.unit} | ${r.issue} | ${r.ours} | ${r.theirs} |`).join('\n') : 'Everything checked agrees.',
  '',
].join('\n')
writeFileSync(join(here, '..', 'docs', 'AOE4_DATA_CHECK.md'), md)
const differ = rows.filter((r) => r.issue !== 'not in the game data under this name').length
console.log(`checked ${checked}, not found ${notFound}, differences ${differ}`)
for (const r of rows.filter((r) => r.issue !== 'not in the game data under this name')) console.log(`  ${r.unit}: ${r.issue} — pack ${r.ours}, game files ${r.theirs}`)
process.exitCode = differ ? 1 : 0
