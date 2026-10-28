// The game knowledge base: everything worth knowing about a game before the
// coach watches it — modes, maps, menus and screens, the HUD, the roster
// (weapons, units, civilisations…), mechanics, strategy, common mistakes and
// terms. A pack ships a curated reference; the player can have Claude research
// the game on the web to build a much larger one (cited, dated). It is
// background knowledge for recognising and explaining what is on screen. It
// is never treated as an observation.

import type { ArsenalItem, GameMap, PlaybookEntry, Roster } from '../profile/schema.ts'

export const KNOWLEDGE_SECTIONS = [
  { id: 'overview', title: 'Overview and current version' },
  { id: 'modes', title: 'Modes' },
  { id: 'maps', title: 'Maps' },
  { id: 'screens', title: 'Menus and screens (how to recognise them)' },
  { id: 'hud', title: 'HUD: where things are on screen' },
  { id: 'roster', title: 'Roster (weapons, units, civilisations, equipment…)' },
  { id: 'mechanics', title: 'Mechanics and numbers' },
  { id: 'strategy', title: 'Strategy and what good play looks like' },
  { id: 'mistakes', title: 'Common mistakes and how to spot them' },
  { id: 'glossary', title: 'Terms' },
] as const

export type KnowledgeSectionId = (typeof KNOWLEDGE_SECTIONS)[number]['id']

export interface KnowledgeItem {
  readonly name: string
  readonly detail: string
}

export interface KnowledgeSection {
  readonly id: KnowledgeSectionId
  readonly items: readonly KnowledgeItem[]
}

export interface KnowledgeBase {
  readonly v: 1
  readonly packId: string
  readonly gameTitle: string
  /** The game version / season / patch the research describes. */
  readonly gameVersion: string
  readonly createdAt: string
  readonly model: string
  readonly sections: readonly KnowledgeSection[]
  readonly sources: readonly { readonly url: string; readonly title: string }[]
}

export const KB_LIMITS = { items: 400, name: 80, detail: 400, sources: 60 } as const

const SECTION_IDS = new Set<string>(KNOWLEDGE_SECTIONS.map((s) => s.id))

export function isKnowledgeBase(v: unknown): v is KnowledgeBase {
  const k = v as KnowledgeBase
  return (
    !!k &&
    k.v === 1 &&
    typeof k.packId === 'string' &&
    typeof k.gameTitle === 'string' &&
    typeof k.gameVersion === 'string' &&
    typeof k.createdAt === 'string' &&
    typeof k.model === 'string' &&
    Array.isArray(k.sections) &&
    k.sections.every(isSection) &&
    Array.isArray(k.sources) &&
    k.sources.length <= KB_LIMITS.sources &&
    k.sources.every((s) => typeof s?.url === 'string' && /^https:\/\//.test(s.url) && typeof s.title === 'string')
  )
}

function isSection(s: unknown): s is KnowledgeSection {
  const x = s as KnowledgeSection
  return (
    !!x &&
    SECTION_IDS.has(x.id) &&
    Array.isArray(x.items) &&
    x.items.length <= KB_LIMITS.items &&
    x.items.every((i) => typeof i?.name === 'string' && i.name.length <= KB_LIMITS.name && typeof i.detail === 'string' && i.detail.length <= KB_LIMITS.detail)
  )
}

/** Trim untrusted model output into a valid knowledge base (drops what does not fit). */
export function cleanKnowledge(raw: unknown, meta: { packId: string; gameTitle: string; model: string; createdAt: string; sources: readonly { url: string; title: string }[] }): KnowledgeBase {
  const r = (raw ?? {}) as { gameVersion?: unknown; sections?: unknown }
  const sections: KnowledgeSection[] = []
  for (const s of Array.isArray(r.sections) ? r.sections : []) {
    const id = (s as { id?: unknown })?.id
    if (typeof id !== 'string' || !SECTION_IDS.has(id)) continue
    const items: KnowledgeItem[] = []
    for (const i of (Array.isArray((s as { items?: unknown }).items) ? (s as { items: unknown[] }).items : []).slice(0, KB_LIMITS.items)) {
      const it = i as { name?: unknown; detail?: unknown }
      if (typeof it?.name !== 'string' || typeof it.detail !== 'string') continue
      const name = clean(it.name, KB_LIMITS.name)
      if (name) items.push({ name, detail: clean(it.detail, KB_LIMITS.detail) })
    }
    const existing = sections.find((x) => x.id === id)
    if (existing) (existing.items as KnowledgeItem[]).push(...items)
    else sections.push({ id: id as KnowledgeSectionId, items })
  }
  return {
    v: 1,
    packId: meta.packId,
    gameTitle: meta.gameTitle,
    gameVersion: typeof r.gameVersion === 'string' ? clean(r.gameVersion, 120) : 'unknown',
    createdAt: meta.createdAt,
    model: meta.model,
    sections,
    sources: meta.sources.filter((s) => /^https:\/\//.test(s.url)).slice(0, KB_LIMITS.sources).map((s) => ({ url: s.url.slice(0, 500), title: clean(s.title, 200) })),
  }
}

function clean(s: string, max: number): string {
  return s.replace(/\s+/g, ' ').trim().slice(0, max)
}

/**
 * Render knowledge as compact text for a model's context, within a character
 * budget. Sections are emitted in the given priority order; items that do not
 * fit are dropped (the budget keeps the cached prompt, and cost, bounded).
 */
export function knowledgeText(
  sources: readonly (Pick<KnowledgeBase, 'sections'> & { readonly label: string })[],
  opts: { readonly budget: number; readonly sections?: readonly KnowledgeSectionId[] },
): string {
  const order = opts.sections ?? KNOWLEDGE_SECTIONS.map((s) => s.id)
  const out: string[] = []
  let used = 0
  for (const id of order) {
    const title = KNOWLEDGE_SECTIONS.find((s) => s.id === id)!.title
    const lines: string[] = []
    const seen = new Set<string>()
    for (const src of sources) {
      for (const sec of src.sections) {
        if (sec.id !== id) continue
        for (const it of sec.items) {
          const key = it.name.toLowerCase()
          if (seen.has(key)) continue
          seen.add(key)
          lines.push(it.detail ? `- ${it.name}: ${it.detail}` : `- ${it.name}`)
        }
      }
    }
    if (!lines.length) continue
    const header = `## ${title}`
    if (used + header.length + 1 > opts.budget) break
    out.push(header)
    used += header.length + 1
    for (const l of lines) {
      if (used + l.length + 1 > opts.budget) break
      out.push(l)
      used += l.length + 1
    }
  }
  return out.join('\n')
}

/** Item counts per section, for the UI. */
export function knowledgeStats(kb: Pick<KnowledgeBase, 'sections'> | null): { items: number; sections: number } {
  if (!kb) return { items: 0, sections: 0 }
  return { items: kb.sections.reduce((n, s) => n + s.items.length, 0), sections: kb.sections.filter((s) => s.items.length).length }
}

/**
 * The pack's coaching playbook as compact prompt text: how good players and
 * coaches judge play, what it looks like on screen, and the fix. Entries are
 * kept whole; the budget drops later entries, never half an entry.
 */
export function playbookText(entries: readonly PlaybookEntry[], budget: number): string {
  const out: string[] = []
  let used = 0
  for (const p of entries) {
    const kinds = [...new Set(p.sources.map((s) => s.kind))].join('/')
    const block = [
      `- [${p.id}] ${p.title}: ${p.principle}${p.why ? ` Why: ${p.why}` : ''}`,
      `  Good: ${p.good.join('; ')}. Poor: ${p.bad.join('; ')}.`,
      `  On screen: ${p.signals.join('; ')}. Fix: ${p.fix}${p.drill ? ` Drill: ${p.drill}` : ''} (sources: ${kinds})`,
    ].join('\n')
    if (used + block.length + 1 > budget) break
    out.push(block)
    used += block.length + 1
  }
  return out.join('\n')
}

/**
 * A strategy game's roster as prompt text. For the recogniser: the exact names
 * to report and how to tell units and buildings apart. For answers: also
 * counters, civilisations' bonuses and how to play against them.
 */
/**
 * The roster as prompt text. With `factions` (ids or names, e.g. this match's two civilisations), only
 * their units and buildings and the shared ones are listed in full; the other civilisations are named.
 */
export function rosterText(roster: Roster, opts: { readonly budget: number; readonly detail: 'vision' | 'answer'; readonly ages?: readonly string[]; readonly factions?: readonly string[] }): string {
  const out: string[] = []
  let used = 0
  const push = (line: string) => {
    if (used + line.length + 1 > opts.budget) return false
    out.push(line)
    used += line.length + 1
    return true
  }
  const unitName = new Map(roster.units.map((u) => [u.id, u.name]))
  const factionName = new Map(roster.factions.map((f) => [f.id, f.name]))
  const names = (ids: readonly string[]) => ids.map((id) => unitName.get(id) ?? id).join(', ')
  const wanted = (opts.factions ?? []).map((x) => x.toLowerCase())
  const only = new Set(roster.factions.filter((f) => wanted.includes(f.id) || wanted.includes(f.name.toLowerCase())).map((f) => f.id))
  // Only narrow when the opponent's civilisation was recognised; anything else gets the whole roster.
  const narrow = only.size > 0
  const inMatch = (factions: readonly string[] | undefined) => !narrow || !factions?.length || factions.some((f) => only.has(f))
  const ageName = (n: number) => {
    const a = opts.ages?.[n - 1]
    return a ? `${a[0].toUpperCase()}${a.slice(1)} Age` : `age ${n}`
  }
  const age = (n: number | undefined) => (n ? ` · ${ageName(n)}` : '')
  push(`## Roster (${roster.patch}, checked ${roster.checkedAt}); report these exact names`)
  if (narrow) push(`This match: ${[...only].map((id) => factionName.get(id)).join(' and ')}. Only their units and buildings and the shared ones are listed.`)
  if (opts.detail === 'answer') push('Ages matter: a unit or building marked with an age is not available before it, so never expect or suggest it earlier.')
  push('### Buildings')
  for (const b of roster.buildings) {
    if (!inMatch(b.factions)) continue
    if (!push(`- ${b.name}${b.factions?.length ? ` (${b.factions.map((f) => factionName.get(f) ?? f).join(', ')})` : ''}${age(b.age)}: makes ${names(b.produces) || 'no units'}${b.look ? `. Looks: ${b.look}` : ''}${opts.detail === 'answer' && b.signals ? `. Means: ${b.signals}` : ''}`)) break
  }
  // Civilisations (with their landmarks) before units: the opponent's civ and landmarks matter most if the budget runs out.
  push('### Civilisations')
  if (narrow) push(`- Other civilisations (not in this match): ${roster.factions.filter((f) => !only.has(f.id)).map((f) => f.name).join(', ')}`)
  for (const f of roster.factions) {
    if (!only.has(f.id) && narrow) continue
    const landmarks = (f.landmarks ?? []).map((l) => `${l.name}${l.age ? ` (${ageName(l.age)})` : ''}`).join(', ')
    const base = `- ${f.name}${f.status === 'upcoming' ? ' (UPCOMING, not released)' : ''}${f.variantOf ? ` (variant of ${factionName.get(f.variantOf) ?? f.variantOf})` : ''}: unique ${names(f.uniqueUnits) || 'none'}${landmarks ? `; landmarks ${landmarks}` : ''}`
    const more = opts.detail === 'answer' ? `. ${f.bonuses.slice(0, 5).join('; ')}${f.howToPlayAgainst ? `. Against them: ${f.howToPlayAgainst}` : ''}` : ''
    if (!push(base + more)) break
  }
  push('### Units')
  for (const u of roster.units) {
    if (!inMatch(u.factions)) continue
    const who = u.factions?.length ? ` (${u.factions.map((f) => factionName.get(f) ?? f).join(', ')})` : ''
    const answer = opts.detail === 'answer' ? `; beats ${names(u.counters)}; beaten by ${names(u.counteredBy)}${u.why ? `. ${u.why}` : ''}` : ''
    const early = u.ageFor ? Object.entries(u.ageFor).map(([f, n]) => `${factionName.get(f) ?? f} ${ageName(n)}`) : []
    if (!push(`- ${u.name}${who} [${u.classes.join(' ')}${age(u.age)}${early.length ? `; ${early.join(', ')}` : ''}]${u.look ? `: ${u.look}` : ''}${answer}`)) break
  }
  if (opts.detail === 'answer') {
    push('### Counter rules')
    for (const r of roster.counterRules ?? []) if (!push(`- If ${r.if}: build ${r.build}${r.why ? ` (${r.why})` : ''}${r.avoid ? `; avoid ${r.avoid}` : ''}`)) break
  }
  return out.join('\n')
}

/** Pushes lines while they fit the budget. */
function budgeted(budget: number) {
  const out: string[] = []
  let used = 0
  return {
    push(line: string): boolean {
      if (used + line.length + 1 > budget) return false
      out.push(line)
      used += line.length + 1
      return true
    },
    text: () => out.join('\n'),
  }
}

/**
 * One map as prompt text. For the recogniser: every callout with its rough
 * position, heights and how it looks from the player's own view, so it can
 * say where the player is. For answers: also links, power positions, held
 * sightlines, spawns, objectives and per-mode tips.
 */
export function mapText(map: GameMap, opts: { readonly budget: number; readonly detail: 'vision' | 'answer' }): string {
  const b = budgeted(opts.budget)
  b.push(`## Map: ${map.name}${map.setting ? ` (${map.setting})` : ''}`)
  if (opts.detail === 'vision') b.push('Report "location" with one of these callout names when the player\'s own view (not a killcam) shows where they are; "killerSpot" on a killcam with the callout the killer shot from.')
  if (map.layout) b.push(`Layout: ${map.layout}`)
  for (const c of map.callouts) {
    const where = [c.grid, c.elevation?.join('/')].filter(Boolean).join('; ')
    const extra = opts.detail === 'answer' && c.use ? ` Use: ${c.use}` : ''
    if (!b.push(`- ${c.name}${c.aliases?.length ? ` (also ${c.aliases.join(', ')})` : ''}${where ? ` [${where}]` : ''}${c.look ? `: ${c.look}` : ''}${extra}`)) break
  }
  if (opts.detail === 'answer') {
    if (map.connections?.length) b.push(`Links: ${map.connections.map(([a, c]) => `${a}–${c}`).join('; ')}`)
    for (const pp of map.powerPositions ?? []) b.push(`Power position ${pp.callout}: ${pp.why}${pp.counter ? ` Counter: ${pp.counter}` : ''}`)
    for (const sl of map.sightlines ?? []) b.push(`Sightline ${sl.from} → ${sl.to}${sl.why ? `: ${sl.why}` : ''}`)
    if (map.spawns) b.push(`Spawns: ${map.spawns}`)
    const o = map.objectives
    if (o?.hardpoint?.length) b.push(`Hardpoint rotation: ${o.hardpoint.join(' → ')}`)
    if (o?.domination) b.push(`Domination: ${Object.entries(o.domination).map(([k, v]) => `${k} ${v}`).join(', ')}`)
    if (o?.searchAndDestroy) b.push(`Search & Destroy: ${Object.entries(o.searchAndDestroy).map(([k, v]) => `${k} ${v}`).join(', ')}`)
    for (const [mode, tip] of Object.entries(map.modeTips ?? {})) b.push(`${mode}: ${tip}`)
    if (map.weaponFit) b.push(`Weapons: ${map.weaponFit}`)
    for (const m of map.mistakes ?? []) b.push(`Mistake: ${m}`)
  }
  return b.text()
}

/** All maps in short, for recognising which map this is (vision) or answering about any of them. */
export function mapsIndexText(maps: readonly GameMap[], opts: { readonly budget: number; readonly detail: 'vision' | 'answer' }): string {
  const b = budgeted(opts.budget)
  b.push('## Maps (report "mapName" with one of these names)')
  for (const m of maps) {
    const pp = opts.detail === 'answer' && m.powerPositions?.length ? ` Power: ${m.powerPositions.map((x) => x.callout).join(', ')}.` : ''
    const layout = opts.detail === 'answer' && m.layout ? ` ${m.layout}` : ''
    if (!b.push(`- ${m.name}${m.aliases?.length ? ` (also ${m.aliases.join(', ')})` : ''}${m.type ? ` [${m.type}]` : ''}${m.setting ? `: ${m.setting}` : ''}${layout}${pp}`)) break
  }
  return b.text()
}

const CATEGORY_TITLE: Readonly<Record<ArsenalItem['category'], string>> = {
  weapon: 'Weapons',
  lethal: 'Lethal equipment',
  tactical: 'Tactical equipment',
  perk: 'Perks',
  field_upgrade: 'Field upgrades',
  killstreak: 'Killstreaks',
  wildcard: 'Wildcards',
}

/**
 * The arsenal as prompt text. For the recogniser: names (to report the
 * weapon in hand, the killer's weapon, killstreaks) and how to tell them
 * apart. For answers: how each works, its range and numbers, and how to beat it.
 */
export function arsenalText(items: readonly ArsenalItem[], opts: { readonly budget: number; readonly detail: 'vision' | 'answer' }): string {
  const b = budgeted(opts.budget)
  b.push('## Arsenal (report these exact names)')
  for (const cat of Object.keys(CATEGORY_TITLE) as ArsenalItem['category'][]) {
    const list = items.filter((i) => i.category === cat)
    if (!list.length) continue
    // The recogniser cannot see perks or field upgrades being chosen: their names are enough.
    if (opts.detail === 'vision' && (cat === 'perk' || cat === 'field_upgrade' || cat === 'wildcard')) {
      if (!b.push(`${CATEGORY_TITLE[cat]}: ${list.map((i) => i.name).join(', ')}`)) return b.text()
      continue
    }
    b.push(`### ${CATEGORY_TITLE[cat]}`)
    for (const i of list) {
      const tag = [i.class?.replace(/_/g, ' '), i.slot, i.cost, i.bestRange ? `best ${i.bestRange}` : null].filter(Boolean).join(', ')
      let line = `- ${i.name}${tag ? ` (${tag})` : ''}`
      if (opts.detail === 'vision') line += i.look ? `: ${i.look.length > 150 ? `${i.look.slice(0, 149).replace(/\s+\S*$/, '')}…` : i.look}` : ''
      else {
        const stats = i.stats ? Object.entries(i.stats).map(([k, v]) => `${k} ${v}`).join(', ') : ''
        line += `: ${i.summary}${stats ? ` [${stats}]` : ''}`
        if (i.strengths?.length) line += ` Strong: ${i.strengths.slice(0, 2).join('; ')}.`
        if (i.weaknesses?.length) line += ` Weak: ${i.weaknesses.slice(0, 2).join('; ')}.`
        if (i.counters?.length) line += ` Beat it: ${i.counters.slice(0, 2).join('; ')}.`
        if (i.unverified) line += ' (unverified)'
      }
      if (!b.push(line)) return b.text()
    }
  }
  return b.text()
}

