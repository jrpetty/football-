// ---------------------------------------------------------------------------
// The price list.
//
// Every item the till has ever sold, with a box for what it should cost. The
// box starts empty but the till's own average is offered beside it — one tap to
// accept, or type the real price over it. That matters because a hundred-line
// list typed from nothing never gets finished, and a half-finished price list
// checks half the takings.
//
// What this enables is a loss the nightly reconciliation cannot see: a till
// told the wrong price counts correctly all evening and balances to the penny.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useState } from 'react'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import { formatQty } from '../core/zread.ts'
import { buildIndex, lookup, type PriceBookEntry, type SoldItem } from '../core/priceBook.ts'
import { dayStats, itemTotals } from '../core/analytics.ts'
import { listDays, loadPriceBook, savePriceBook } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'

export function Prices({ onChanged }: { onChanged: () => void }) {
  const [items, setItems] = useState<SoldItem[] | null>(null)
  const [book, setBook] = useState<PriceBookEntry[]>([])
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [toast, setToast] = useState('')
  const [onlyUnpriced, setOnlyUnpriced] = useState(false)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const tolerance = loadSettings().tolerancePence
      const [days, stored] = await Promise.all([listDays().catch(() => []), loadPriceBook().catch(() => [])])
      if (cancelled) return
      // Every item ever sold, biggest earner first — the ones worth pricing.
      const seen = itemTotals(days.map((d) => dayStats(d, tolerance)))
      setItems(seen.map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli, pence: i.pence })))
      setBook(stored)
      setDrafts(Object.fromEntries(stored.map((e) => [e.code ?? `name:${e.name}`, penceToInput(e.pence)])))
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const index = useMemo(() => buildIndex(book), [book])

  function say(message: string) {
    setToast(message)
    setTimeout(() => setToast(''), 3500)
  }

  async function commit(next: PriceBookEntry[]) {
    setBook(next)
    await savePriceBook(next)
    onChanged()
  }

  async function setPrice(item: SoldItem, text: string) {
    setDrafts((d) => ({ ...d, [item.code]: text }))
    const pence = parsePence(text)
    const without = book.filter((e) => (e.code ?? `name:${e.name}`) !== item.code && e.name !== item.name)
    if (pence === null) {
      // An emptied box removes the price rather than storing a zero, which
      // would read as "this is free" and quietly fail every check against it.
      if (text.trim() === '' && lookup(index, item)) await commit(without)
      return
    }
    await commit([...without, { code: item.code, name: item.name, pence }])
  }

  if (items === null) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>

  if (items.length === 0) {
    return (
      <div className="main">
        <div className="empty">
          <p>No items yet.</p>
          <p>Scan a till roll with its item list and every line the pub sells appears here, ready to price.</p>
        </div>
      </div>
    )
  }

  const priced = items.filter((i) => lookup(index, i)).length
  const shown = onlyUnpriced ? items.filter((i) => !lookup(index, i)) : items

  return (
    <div className="main">
      <section className="card">
        <div className="card-head">
          <h2>What things should cost</h2>
          <span className={`badge ${priced === items.length ? 'good' : 'warn'}`}>
            {priced} of {items.length} priced
          </span>
        </div>
        <p className="help" style={{ marginTop: 0 }}>
          Set the board price for each line and the app can tell you what the till <em>should</em> have
          taken, not just whether the drawer matched it. A till told the wrong price balances perfectly
          every night.
        </p>
        <div className="chip-row">
          <button type="button" className="chip" aria-pressed={!onlyUnpriced} onClick={() => setOnlyUnpriced(false)}>
            Everything
          </button>
          <button type="button" className="chip" aria-pressed={onlyUnpriced} onClick={() => setOnlyUnpriced(true)}>
            Still to price
          </button>
        </div>
      </section>

      <section className="card">
        {shown.map((item) => {
          const entry = lookup(index, item)
          const qty = item.qtyMilli / 1000
          const avg = qty > 0 ? Math.round(item.pence / qty) : 0
          const draft = drafts[item.code] ?? (entry ? penceToInput(entry.pence) : '')
          const gap = entry ? avg - entry.pence : 0

          return (
            <div className="zrow" key={item.code}>
              <span className="zname">
                {item.name}
                <small>
                  {formatQty(item.qtyMilli)} sold · till averaged {formatMoney(avg)}
                  {entry && Math.abs(gap) > 1 && (
                    <> · <strong style={{ color: gap < 0 ? 'var(--bad)' : 'var(--warn)' }}>
                      {gap < 0 ? 'under' : 'over'} by {formatMoney(Math.abs(gap))}
                    </strong></>
                  )}
                </small>
              </span>
              {!entry && (
                <button
                  type="button"
                  className="btn-small"
                  onClick={() => void setPrice(item, penceToInput(avg)).then(() => say(`${item.name} set to ${formatMoney(avg)}.`))}
                >
                  Use {formatMoney(avg)}
                </button>
              )}
              <input
                aria-label={`Board price for ${item.name}`}
                inputMode="decimal"
                placeholder="—"
                value={draft}
                onChange={(e) => void setPrice(item, e.target.value)}
              />
            </div>
          )
        })}
        {shown.length === 0 && <p className="note">Everything has a price. Nothing left to do here.</p>}
      </section>

      <p className="note">
        One caveat worth keeping in mind: the till's average is its takings divided by how many went
        out, so any sale rung at a discount — an OAP price, a staff drink — pulls it below the board
        price honestly. A gap is a question, not a finding.
      </p>

      {toast && <div className="toast" role="status">{toast}</div>}
    </div>
  )
}
