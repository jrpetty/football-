// Builds the DayZ pack's map data from two sources, kept in separate files
// because they carry different licences:
//
//   src/profiles/dayz/places.<map>.json  — named places (name, Cyrillic or
//     Polish, type, grid, loot tier, facilities) from the DayZ Wiki
//     (dayz.fandom.com, CC BY-NC-SA 3.0), each position checked against the
//     game's own building list and corrected or left out when it does not fit;
//   src/profiles/dayz/world.<map>.json   — water pumps, fuel, medical, police,
//     fire, military, lighthouses, helicopter crash and police car spawns,
//     contaminated zones, player spawns, and the buildings by loot type near
//     each place, from Bohemia Interactive's DayZ Central Economy files
//     (ADPL-SA: attribution, non-commercial, DayZ/Arma only, share alike).
//
//   node scripts/build-dayz-data.mjs <wiki-dir> <central-economy-repo>
//
// <wiki-dir> holds what the wiki's API returned: wiki_pages.json (title →
// wikitext of every page in the location categories), wiki_members.json
// (title → categories) and wikimap_<Map>.json (the Map:<Map> pages).

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const [wikiDir, ceDir] = process.argv.slice(2)
if (!wikiDir || !ceDir) {
  console.error('usage: node scripts/build-dayz-data.mjs <wiki-dir> <central-economy-repo>')
  process.exit(1)
}
const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'profiles', 'dayz')
mkdirSync(out, { recursive: true })

const MAPS = [
  { id: 'chernarus', tag: 'Chernarus', ce: 'dayzOffline.chernarusplus', world: 15360, wikiMap: 'Chernarus', signs: 'cyrillic' },
  { id: 'livonia', tag: 'Livonia', ce: 'dayzOffline.enoch', world: 12800, wikiMap: 'Livonia', signs: 'latin' },
  { id: 'sakhal', tag: 'Sakhal', ce: 'dayzOffline.sakhal', world: 15360, wikiMap: 'Sakhal', signs: 'cyrillic' },
]

const pages = JSON.parse(readFileSync(join(wikiDir, 'wiki_pages.json'), 'utf8'))
const members = JSON.parse(readFileSync(join(wikiDir, 'wiki_members.json'), 'utf8'))

// --- The game's buildings, by loot type ------------------------------------------------------

function readText(p) {
  return readFileSync(p, 'utf8')
}

function buildings(ceMap) {
  const proto = readText(join(ceDir, ceMap, 'mapgroupproto.xml'))
  const usage = new Map()
  for (const m of proto.matchAll(/<group name="([^"]+)"[^>]*>([\s\S]*?)<\/group>/g)) usage.set(m[1], [...new Set([...m[2].matchAll(/<usage name="([^"]+)"/g)].map((u) => u[1]))])
  const pos = readText(join(ceDir, ceMap, 'mapgrouppos.xml'))
  const list = []
  for (const m of pos.matchAll(/<group name="([^"]+)" pos="([-\d.]+) ([-\d.]+) ([-\d.]+)"/g)) list.push({ cls: m[1], x: Math.round(+m[2]), y: Math.round(+m[4]), usage: usage.get(m[1]) ?? [] })
  return list
}

function events(ceMap) {
  const s = readText(join(ceDir, ceMap, 'cfgeventspawns.xml'))
  const out = {}
  for (const m of s.matchAll(/<event name="([^"]+)">([\s\S]*?)<\/event>/g)) out[m[1]] = [...m[2].matchAll(/<pos x="([-\d.]+)" z="([-\d.]+)"/g)].map((p) => [Math.round(+p[1]), Math.round(+p[2])])
  return out
}

function spawns(ceMap) {
  const s = readText(join(ceDir, ceMap, 'cfgplayerspawnpoints.xml'))
  const fresh = /<fresh>([\s\S]*?)<\/fresh>/.exec(s)?.[1] ?? ''
  return [...fresh.matchAll(/<pos x="([-\d.]+)" z="([-\d.]+)"/g)].map((p) => [Math.round(+p[1]), Math.round(+p[2])])
}

function effectAreas(ceMap) {
  for (const f of ['cfgEffectArea.json', 'cfgeffectarea.json']) {
    try {
      const d = JSON.parse(readText(join(ceDir, ceMap, f)))
      return (d.Areas ?? []).map((a) => ({ name: a.AreaName, x: Math.round(a.Data.Pos[0]), y: Math.round(a.Data.Pos[2]), r: a.Data.Radius }))
    } catch {
      /* next spelling */
    }
  }
  return []
}

const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1])

// --- The wiki's places -------------------------------------------------------------------------

const CYR = /[А-Яа-яЁё][А-Яа-яЁё\- ]*[А-Яа-яЁё]/
const clean = (s) =>
  s
    .replace(/\[\[(?:[^|\]]*\|)?([^\]]+)\]\]/g, '$1')
    .replace(/\[https?:\/\/\S+\s+([^\]]+)\]/g, '$1')
    .replace(/<[^>]+>|'''|''/g, '')
    .replace(/\s+/g, ' ')
    .trim()

function field(t, ...keys) {
  for (const k of keys) {
    const m = new RegExp(`\\|\\s*${k}\\s*=\\s*([^\\n|}]*)`).exec(t)
    if (m && m[1].trim()) return m[1].trim()
  }
  return ''
}

function kindOf(title, t, cats) {
  const text = `${field(t, 'type')} ${field(t, 'category')} ${cats.join(' ')}`.toLowerCase()
  if (/capital|major town/.test(text)) return 'city'
  if (/small city|towns and cities|town|city/.test(text) && !/village/.test(field(t, 'type').toLowerCase())) return 'town'
  if (/village|hamlet|settlement/.test(text)) return 'village'
  if (/airfield|air base/.test(text + title.toLowerCase())) return 'airfield'
  if (/military base|military camp|military/.test(text)) return 'military'
  if (/hill|mountain|peak|ridge/.test(text + title.toLowerCase())) return 'hill'
  if (/island/.test(text)) return 'island'
  if (/sea|coast|bay|lake|river|marine/.test(text + title.toLowerCase())) return 'water'
  if (/ruin|castle/.test(text + title.toLowerCase())) return 'ruin'
  return 'local'
}

function facilities(t) {
  const m = /\|\s*facilities\s*=([\s\S]*?)(?=\n\s*\|\s*[a-z ]+\s*=|\}\})/.exec(t)
  if (!m) return []
  return m[1]
    .split(/\n|<br\s*\/?>/)
    .map((l) => clean(l.replace(/^\s*\*\s*/, '')))
    .filter((l) => l && l.length <= 60 && !/^port$/i.test(l))
    .slice(0, 16)
}

function wikiPlaces(map) {
  const list = []
  for (const [title, t] of Object.entries(pages)) {
    const tag = /\{\{Tag\|([^}|]+)/.exec(t)?.[1]?.trim()
    const cats = members[title] ?? []
    const onMap = tag ? tag === map.tag : cats.some((c) => c.includes(map.tag))
    if (!onMap || /Debug|:/.test(title)) continue
    const g = /(?:\|\s*coords?|\|\s*ref)\s*=\s*(?:\[[^\]\n]*?\s)?(\d{3})\s+(\d{3})/.exec(t)
    let cyr = ''
    for (const s of [field(t, 'russian'), /Russian:\s*([^)\n]+)/.exec(t)?.[1] ?? '', /\|\s*title\s*=[^|]*?\n\s*([^|}\n]+)/.exec(t)?.[1] ?? '']) {
      const c = CYR.exec(s)
      if (c) {
        cyr = c[0].trim()
        break
      }
    }
    const tier = /\|\s*tier\s*=\s*([\d/\- ]+)/.exec(t)?.[1]?.trim() ?? ''
    list.push({
      name: title.replace(/\s*\((?:Chernarus|Livonia|Sakhal|Village|Town)\)$/, ''),
      ...(cyr ? { cyrillic: cyr } : {}),
      kind: kindOf(title, t, cats),
      grid: g ? [+g[1], +g[2]] : null,
      ...(tier ? { tier } : {}),
      facilities: facilities(t),
    })
  }
  return list
}

/** The wiki map's marker categories that are places, and the kind of place each is. */
const MARKER_KIND = [
  [/major town|capital/i, 'city'],
  [/^towns?\b|town \(city\)|city/i, 'town'],
  [/village/i, 'village'],
  [/airfield/i, 'airfield'],
  [/military|base|camp/i, 'military'],
  [/hill/i, 'hill'],
  [/marine|naval|sea/i, 'water'],
  [/ruin/i, 'ruin'],
  [/local/i, 'local'],
]

/** Markers on the wiki's interactive map that are places: name → position in metres and kind. */
function wikiMarkers(map) {
  try {
    const d = JSON.parse(readText(join(wikiDir, `wikimap_${map.wikiMap}.json`)))
    const c = JSON.parse(Object.values(d.query.pages)[0].revisions[0].slots.main['*'])
    const [w, h] = c.mapBounds[1]
    const kinds = new Map((c.categories ?? []).map((k) => [String(k.id), MARKER_KIND.find(([re]) => re.test(k.name))?.[1] ?? null]))
    const out = new Map()
    for (const m of c.markers ?? []) {
      const kind = kinds.get(String(m.categoryId))
      if (!kind) continue
      const x = (m.position[0] / w) * map.world
      const y = c.origin === 'top-left' ? map.world - (m.position[1] / h) * map.world : (m.position[1] / h) * map.world
      out.set(m.popup.title.trim(), { at: [Math.round(x), Math.round(y)], kind })
    }
    return out
  } catch {
    return new Map()
  }
}

// --- Checking each place against the buildings -----------------------------------------------

const SETTLED = new Set(['city', 'town', 'village', 'military', 'airfield'])
const RADIUS = { city: 650, town: 450, village: 300, military: 350, airfield: 600 }
const LOOT_TYPES = ['Military', 'Police', 'Medic', 'Firefighter', 'Industrial', 'Hunting', 'Farm', 'Town', 'Village', 'Office', 'School', 'Prison', 'Coast']

function near(list, at, r) {
  return list.filter((b) => dist([b.x, b.y], at) <= r)
}

function build(map) {
  const bs = buildings(map.ce)
  const ev = events(map.ce)
  const areas = effectAreas(map.ce)
  const markers = wikiMarkers(map)
  const notes = []
  const seen = new Map()
  const places = []
  for (const p of wikiPlaces(map)) {
    const r = RADIUS[p.kind] ?? 250
    let at = null
    let how = ''
    if (p.grid) {
      // The wiki's grid: 100 m squares, counted east from the west edge and south from the north edge.
      const fromTop = [p.grid[0] * 100 + 50, map.world - p.grid[1] * 100 - 50]
      const fromBottom = [p.grid[0] * 100 + 50, p.grid[1] * 100 + 50]
      const nTop = near(bs, fromTop, r).length
      const nBottom = near(bs, fromBottom, r).length
      if (!SETTLED.has(p.kind)) [at, how] = [fromTop, 'grid']
      else if (nTop >= 3) [at, how] = [fromTop, 'grid, buildings there']
      else if (nBottom >= 3 && nBottom >= nTop * 3) {
        ;[at, how] = [fromBottom, 'grid read from the south edge (buildings there, none at the north reading)']
        notes.push(`${p.name}: the wiki's grid fits only when counted from the south edge; used that.`)
      } else {
        notes.push(`${p.name}: no buildings at the wiki's grid ${String(p.grid[0]).padStart(3, '0')} ${String(p.grid[1]).padStart(3, '0')}; left out.`)
        continue
      }
    } else if (markers.has(p.name)) [at, how] = [markers.get(p.name).at, "the wiki's interactive map"]
    else {
      const a = areas.find((x) => x.name.split('-')[0].toLowerCase() === p.name.toLowerCase().replace(/\s+/g, ''))
      if (a) [at, how] = [[a.x, a.y], "Bohemia's contaminated-zone file names it"]
      else {
        notes.push(`${p.name}: no position on the wiki; left out.`)
        continue
      }
    }
    const key = `${at[0]},${at[1]}`
    if (seen.has(key)) {
      notes.push(`${p.name}: the wiki gives exactly ${seen.get(key)}'s position; left out until checked.`)
      continue
    }
    seen.set(key, p.name)
    const { grid: _g, ...rest } = p
    places.push({ ...rest, x: at[0], y: at[1], from: how })
  }
  // Markers for places with no page.
  for (const [name, m] of markers) {
    if (places.some((p) => p.name === name) || seen.has(`${m.at[0]},${m.at[1]}`)) continue
    // A marker within 400 m of a place already listed is that place under another spelling.
    if (/collectable|current location|vault/i.test(name) || places.some((p) => dist([p.x, p.y], m.at) < 400)) continue
    places.push({ name, kind: m.kind, x: m.at[0], y: m.at[1], facilities: [], from: "the wiki's interactive map" })
  }
  places.sort((a, b) => a.name.localeCompare(b.name))

  // The world file: features and the buildings by loot type near each place.
  const pick = (re) => bs.filter((b) => re.test(b.cls)).map((b) => [b.x, b.y])
  const byUsage = (u) => bs.filter((b) => b.usage.includes(u))
  const nearCounts = {}
  for (const p of places) {
    const r = RADIUS[p.kind] ?? 250
    const counts = {}
    for (const b of near(bs, [p.x, p.y], r)) for (const u of b.usage) if (LOOT_TYPES.includes(u)) counts[u] = (counts[u] ?? 0) + 1
    if (Object.keys(counts).length) nearCounts[p.name] = counts
  }
  const world = {
    map: map.id,
    worldSize: map.world,
    water: pick(/Well_Pump/),
    fuel: pick(/FuelStation/),
    medical: byUsage('Medic').map((b) => [b.x, b.y, /Hospital/.test(b.cls) ? 'hospital' : /Tent/.test(b.cls) ? 'tent' : 'medical']),
    police: byUsage('Police').map((b) => [b.x, b.y]),
    fire: byUsage('Firefighter').map((b) => [b.x, b.y]),
    military: byUsage('Military').map((b) => [b.x, b.y]),
    prison: byUsage('Prison').map((b) => [b.x, b.y]),
    lighthouses: pick(/^Land_Lighthouse/),
    heliCrash: ev.StaticHeliCrash ?? [],
    policeCars: ev.StaticPoliceCar ?? [],
    convoys: ev.StaticMilitaryConvoy ?? [],
    contaminated: [...areas.map((a) => ({ name: a.name, x: a.x, y: a.y, r: a.r })), ...(ev.StaticContaminatedArea ?? []).map(([x, y]) => ({ name: 'dynamic', x, y, r: 150 }))],
    spawns: spawns(map.ce),
    near: nearCounts,
  }
  return { places, world, notes }
}

for (const map of MAPS) {
  const { places, world, notes } = build(map)
  writeFileSync(
    join(out, `places.${map.id}.json`),
    JSON.stringify(
      {
        map: map.id,
        worldSize: map.world,
        signs: map.signs,
        source: 'DayZ Wiki (dayz.fandom.com), Locations and the location pages; CC BY-NC-SA 3.0. Positions checked against the game\'s building list.',
        licence: 'CC BY-NC-SA 3.0 (https://creativecommons.org/licenses/by-nc-sa/3.0/): attribution, non-commercial, share alike.',
        notes,
        places,
      },
      null,
      1,
    ) + '\n',
  )
  writeFileSync(
    join(out, `world.${map.id}.json`),
    JSON.stringify(
      {
        source: "Bohemia Interactive, DayZ Central Economy (github.com/BohemiaInteractive/DayZ-Central-Economy), mission files for this map.",
        licence: 'Arma and DayZ Public License Share Alike (ADPL-SA): attribution, non-commercial, DayZ/Arma only, share alike. © Bohemia Interactive a.s.',
        ...world,
      },
      null,
      0,
    ) + '\n',
  )
  console.log(`${map.id}: ${places.length} places (${places.filter((p) => p.cyrillic).length} with the sign's spelling), ${world.water.length} water pumps, ${world.medical.length} medical, ${world.heliCrash.length} crash spots; ${notes.length} notes`)
  for (const n of notes) console.log('   ', n)
}
