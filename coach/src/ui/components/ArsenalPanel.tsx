// The game's arsenal from the pack: every weapon, piece of equipment, perk,
// field upgrade and killstreak — how it works, its numbers and range, and
// how to beat it — searchable, with the player's own record and what has
// been killing them marked when the coach has seen it.

import { useMemo, useState } from 'react'
import type { ArsenalItem, GameProfile } from '../../core/profile/schema.ts'
import { Segmented } from './common.tsx'

const CATS: readonly { value: ArsenalItem['category']; label: string }[] = [
  { value: 'weapon', label: 'Weapons' },
  { value: 'lethal', label: 'Lethal' },
  { value: 'tactical', label: 'Tactical' },
  { value: 'perk', label: 'Perks' },
  { value: 'field_upgrade', label: 'Field upgrades' },
  { value: 'killstreak', label: 'Killstreaks' },
  { value: 'wildcard', label: 'Wildcards' },
]

export function ArsenalPanel({
  profile,
  yours,
  killers,
}: {
  profile: GameProfile
  /** Weapons the player used, with kills and deaths. */
  yours?: readonly { readonly name: string; readonly kills: number; readonly deaths: number }[]
  /** Weapons that killed the player, with counts. */
  killers?: readonly { readonly name: string; readonly n: number }[]
}) {
  const items = profile.knowledge.arsenal ?? []
  const cats = CATS.filter((c) => items.some((i) => i.category === c.value))
  const [cat, setCat] = useState<ArsenalItem['category']>(cats[0]?.value ?? 'weapon')
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const mine = useMemo(() => new Map((yours ?? []).map((w) => [w.name.toLowerCase(), w])), [yours])
  const killedBy = useMemo(() => new Map((killers ?? []).map((w) => [w.name.toLowerCase(), w.n])), [killers])
  const q = query.trim().toLowerCase()
  const list = items.filter((i) =>
    q ? [i.name, ...(i.aliases ?? []), i.class ?? '', i.summary].some((x) => x.toLowerCase().includes(q)) : i.category === cat,
  )
  if (!items.length) return null
  return (
    <div className="arsenal">
      <div className="arsenal-controls">
        {!q ? <Segmented label="Category" value={cat} onChange={setCat} options={cats} /> : null}
        <input type="search" placeholder="Search weapons, perks, streaks…" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search the arsenal" />
      </div>
      <ul className="arsenal-list">
        {list.map((i) => {
          const me = mine.get(i.name.toLowerCase())
          const kb = killedBy.get(i.name.toLowerCase())
          const s = i.stats ?? {}
          const nums = [
            i.bestRange ? `best ${i.bestRange}` : null,
            s.rpm ? `${s.rpm} rpm` : null,
            s.ttkCloseMs ? `TTK ${s.ttkCloseMs}${s.ttkFarMs ? `–${s.ttkFarMs}` : ''} ms` : null,
            s.adsMs ? `ADS ${s.adsMs} ms` : null,
            i.slot ?? null,
            i.cost ?? null,
          ].filter(Boolean)
          const isOpen = open === i.id
          return (
            <li key={i.id} className={`arsenal-item${isOpen ? ' open' : ''}`}>
              <button type="button" className="arsenal-row" onClick={() => setOpen(isOpen ? null : i.id)} aria-expanded={isOpen}>
                <span className="arsenal-name">
                  <b>{i.name}</b>
                  {i.class ? <span className="muted small"> {i.class.replace(/_/g, ' ')}</span> : null}
                  {i.unverified ? <span className="badge neutral">unverified</span> : null}
                  {me ? <span className="badge ok">you: {me.kills}–{me.deaths}</span> : null}
                  {kb ? <span className="badge warn">killed you {kb}×</span> : null}
                </span>
                <span className="muted small">{nums.join(' · ')}</span>
              </button>
              {isOpen ? (
                <div className="arsenal-detail small">
                  <p>{i.summary}</p>
                  {i.strengths?.length ? (
                    <p>
                      <span className="label">Strong</span> {i.strengths.join('; ')}
                    </p>
                  ) : null}
                  {i.weaknesses?.length ? (
                    <p>
                      <span className="label">Weak</span> {i.weaknesses.join('; ')}
                    </p>
                  ) : null}
                  {i.counters?.length ? (
                    <p>
                      <span className="label">Beat it</span> {i.counters.join('; ')}
                    </p>
                  ) : null}
                  {i.tips?.length ? (
                    <ul className="good">
                      {i.tips.map((t) => (
                        <li key={t}>{t}</li>
                      ))}
                    </ul>
                  ) : null}
                  {Object.keys(s).length ? (
                    <p className="muted">
                      {Object.entries(s)
                        .map(([k, v]) => `${k}: ${v}`)
                        .join(' · ')}
                    </p>
                  ) : null}
                  {i.look ? <p className="muted">On screen: {i.look}</p> : null}
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
      {!list.length ? <p className="muted small">Nothing matches.</p> : null}
    </div>
  )
}
