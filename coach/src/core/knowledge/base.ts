// The game knowledge base: everything worth knowing about a game before the
// coach watches it — modes, maps, menus and screens, the HUD, the roster
// (weapons, units, civilisations…), mechanics, strategy, common mistakes and
// terms. A pack ships a curated reference; the player can have Claude research
// the game on the web to build a much larger one (cited, dated). It is
// background knowledge for recognising and explaining what is on screen. It
// is never treated as an observation.

import type { PlaybookEntry } from '../profile/schema.ts'

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
      `- [${p.id}] ${p.title}: ${p.principle}`,
      `  Good: ${p.good.join('; ')}. Poor: ${p.bad.join('; ')}.`,
      `  On screen: ${p.signals.join('; ')}. Fix: ${p.fix}${p.drill ? ` Drill: ${p.drill}` : ''} (sources: ${kinds})`,
    ].join('\n')
    if (used + block.length + 1 > budget) break
    out.push(block)
    used += block.length + 1
  }
  return out.join('\n')
}
