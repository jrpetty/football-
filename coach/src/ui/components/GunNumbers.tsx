// A gun's numbers from the game's weapon file: damage per hit and hits to
// kill at each distance (head with and without a helmet, body with and
// without kevlar, stomach, legs), and every gun side by side. Numbers carry
// the meaning; the one-hit cells are also marked in words for screen readers.

import { useMemo, useState } from 'react'
import type { ArsenalItem, WeaponBallistics } from '../../core/profile/schema.ts'
import { ballisticsLines, damageTable, gunSummary, type DamageCell, type GunSummary } from '../../core/knowledge/ballistics.ts'

function Cell({ c }: { c: DamageCell }) {
  if (!c.damage) return <td className="muted">—</td>
  return (
    <td className={c.hits === 1 ? 'one' : undefined}>
      {c.damage}
      <span className="htk" aria-label={c.hits === null ? 'no kill' : c.hits === 1 ? 'kills in one hit' : `${c.hits} hits to kill`}>
        {c.hits === null ? '' : ` ×${c.hits}`}
      </span>
    </td>
  )
}

export function BallisticsTable({ item }: { item: Pick<ArsenalItem, 'name' | 'ballistics'> }) {
  const b = item.ballistics
  const rows = useMemo(() => (b ? damageTable(b) : []), [b])
  if (!b) return null
  const pellets = (b.pellets ?? 1) > 1
  return (
    <div className="gun-numbers">
      <ul className="gun-lines">
        {ballisticsLines(item).map((l) => (
          <li key={l}>{l}</li>
        ))}
      </ul>
      <div className="table-scroll">
        <table className="gun-table small">
          <caption>
            Damage per {pellets ? 'pellet' : 'hit'} to health, and {pellets ? 'pellets' : 'hits'} to kill from 100 health (×). Kevlar covers the body and arms, a helmet the head; nothing covers the legs.
          </caption>
          <thead>
            <tr>
              <th scope="col">Distance</th>
              <th scope="col">Head</th>
              <th scope="col">Head, helmet</th>
              <th scope="col">Body</th>
              <th scope="col">Body, kevlar</th>
              <th scope="col">Stomach, kevlar</th>
              <th scope="col">Legs</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.metres}>
                <th scope="row">{r.metres} m</th>
                <Cell c={r.head} />
                <Cell c={r.headHelmet} />
                <Cell c={r.chest} />
                <Cell c={r.chestArmour} />
                <Cell c={r.stomachArmour} />
                <Cell c={r.legs} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">
        From the game’s weapon file: damage falls to ×{b.rangeModifier} every 500 units (about 12.7 m); armour penetration {Math.min(100, b.armorRatio * 50)}%; head ×{b.headshotMultiplier},
        stomach ×1.25, legs ×0.75. Every hit landing; real fights land fewer.
      </p>
    </div>
  )
}

type Col = { key: string; label: string; title: string; value: (g: GunSummary, b: WeaponBallistics, i: ArsenalItem) => number | null; fmt: (v: number) => string; better: 'low' | 'high' }

const m = (v: number) => (v >= 100 ? `${Math.round(v)}` : v >= 10 ? v.toFixed(0) : v.toFixed(1))
const COLS: readonly Col[] = [
  { key: 'price', label: 'Price', title: 'Price in the buy menu', value: (_g, _b, i) => (typeof i.stats?.price === 'number' ? i.stats.price : null), fmt: (v) => `$${v.toLocaleString('en-US')}`, better: 'low' },
  { key: 'rpm', label: 'Rounds/min', title: 'Rounds a minute', value: (g) => g.rpm, fmt: (v) => String(v), better: 'high' },
  { key: 'hits', label: 'Body hits, 10 m', title: 'Body hits through kevlar to kill at 10 m (shotguns: pellets)', value: (g) => g.chestHits10, fmt: (v) => String(v), better: 'low' },
  { key: 'ttk', label: 'Time to kill', title: 'Seconds to land those hits at 10 m, every one landing', value: (g) => g.ttk10, fmt: (v) => `${v.toFixed(2)} s`, better: 'low' },
  { key: 'tap', label: 'Helmet one-tap', title: 'How far one headshot kills through a helmet (m)', value: (g, b) => ((b.pellets ?? 1) > 1 ? null : g.oneShotHelmet), fmt: (v) => `${m(v)} m`, better: 'high' },
  { key: 'acc', label: 'Accurate to', title: 'Where the widest first-shot miss is 15 cm, standing still (scoped for sniper rifles)', value: (g) => g.accurateScoped ?? g.accurateStand, fmt: (v) => `${m(v)} m`, better: 'high' },
  { key: 'move', label: 'Miss running', title: 'Widest first-shot miss at 10 m while running at full speed', value: (g) => g.movingMiss10, fmt: (v) => `${v.toFixed(2)} m`, better: 'low' },
  { key: 'pen', label: 'Armour pen.', title: 'Share of damage that goes through kevlar', value: (g) => g.armourPen, fmt: (v) => `${v}%`, better: 'high' },
  { key: 'speed', label: 'Speed', title: 'Running speed with it out (units a second; the knife is 250)', value: (_g, b) => b.maxSpeed, fmt: (v) => String(v), better: 'high' },
]

/** Every gun side by side, sortable by any column. */
export function GunCompare({ items, onOpen }: { items: readonly ArsenalItem[]; onOpen?: (id: string) => void }) {
  const guns = useMemo(() => items.filter((i) => i.ballistics).map((i) => ({ i, g: gunSummary(i.ballistics!), b: i.ballistics! })), [items])
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 }>({ key: 'ttk', dir: 1 })
  const col = COLS.find((c) => c.key === sort.key) ?? COLS[3]
  const rows = [...guns].sort((x, y) => {
    const a = col.value(x.g, x.b, x.i)
    const b = col.value(y.g, y.b, y.i)
    if (a === null && b === null) return 0
    if (a === null) return 1
    if (b === null) return -1
    return (a - b) * sort.dir
  })
  if (!guns.length) return null
  return (
    <div className="table-scroll">
      <table className="gun-compare small">
        <caption className="muted">Every gun from the game’s weapon file, against an armoured player at 10 m unless the column says otherwise. Click a heading to sort.</caption>
        <thead>
          <tr>
            <th scope="col">Gun</th>
            {COLS.map((c) => (
              <th key={c.key} scope="col" title={c.title} aria-sort={sort.key === c.key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}>
                <button type="button" className="link-btn" onClick={() => setSort((s) => ({ key: c.key, dir: s.key === c.key ? (s.dir === 1 ? -1 : 1) : c.better === 'low' ? 1 : -1 }))}>
                  {c.label}
                  {sort.key === c.key ? (sort.dir === 1 ? ' ▲' : ' ▼') : ''}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ i, g, b }) => (
            <tr key={i.id}>
              <th scope="row">
                {onOpen ? (
                  <button type="button" className="link-btn" onClick={() => onOpen(i.id)}>
                    {i.name}
                  </button>
                ) : (
                  i.name
                )}
                {i.class ? <span className="muted"> {i.class.replace(/_/g, ' ')}</span> : null}
              </th>
              {COLS.map((c) => {
                const v = c.value(g, b, i)
                return <td key={c.key}>{v === null ? <span className="muted">—</span> : c.fmt(v)}</td>
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
