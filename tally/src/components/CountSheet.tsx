// ---------------------------------------------------------------------------
// A stock sheet: one row per line, whole containers and loose servings.
//
// Two screens ask for the same thing — the Cellar for an ad-hoc take or a
// delivery, and Tonight for the count that closes a night — so the rows live
// here once. What is typed stays as text in the caller's drafts until it is
// saved; the arithmetic is sheetLines in the core.
//
// A line whose keg has been weighed empty and full gets a third box: the
// reading off the scales. It is a calculator sat in the row, because that is
// where she is when she needs it — it fills the loose box and says what it
// made of the reading, and the loose box stays hers to overrule.
// ---------------------------------------------------------------------------

import {
  formatServings,
  inMeasures,
  kegReading,
  pluralServing,
  weighable,
  type Measure,
  type StockItem,
} from '../core/stock.ts'

interface Props {
  items: readonly StockItem[]
  drafts: Readonly<Record<string, string>>
  onChange: (next: Record<string, string>) => void
  /** "counted" or "delivered" — the word in every box's label. */
  word: string
  /** Whether the scales boxes are offered. A delivery is not weighed. */
  scales?: boolean
  /**
   * The serve each line counted in millilitres pours, so what is typed is
   * read back in shots as it is typed. Nobody types a shot; the app works
   * them out of the millilitres.
   */
  measures?: ReadonlyMap<string, Measure>
}

export function CountSheet({ items, drafts, onChange, word, scales = false, measures }: Props) {
  return (
    <>
      {items.map((item) => {
        const perContainer = item.container ? Math.round(item.container.baseUnits / item.servingBaseUnits) : 0
        // A container worth counting is one that holds more than a serving.
        const counted = perContainer > 1
        const weighed = scales && counted && weighable(item)
        const kgText = drafts[`${item.id}:kg`] ?? ''
        const reading = weighed && kgText.trim() !== '' ? kegReading(item, Number(kgText)) : null
        // What has been typed so far, read back in the serves it pours.
        const measure = measures?.get(item.id)
        const typed = Number(drafts[item.id] ?? '')
        const full = Number(drafts[`${item.id}:full`] ?? '')
        const countedBase =
          (Number.isFinite(typed) ? typed : 0) * item.servingBaseUnits +
          (Number.isFinite(full) ? full : 0) * (item.container?.baseUnits ?? 0)
        const inServes =
          measure && countedBase > 0 ? `${formatServings(countedBase, item)} is ${inMeasures(countedBase, measure)}` : null
        return (
          <div className={`zrow${weighed ? ' weighed' : ''}`} key={item.id}>
            <span className="zname">
              {item.name}
              {(counted || inServes) && (
                <small>
                  {counted && `a ${item.container!.name} is ${perContainer} ${pluralServing(item.servingName)}`}
                  {counted && inServes && ' · '}
                  {inServes}
                  {reading && (
                    <>
                      {' · '}
                      <span className={reading.outside ? 'bad' : undefined}>
                        {reading.outside === 'under'
                          ? 'lighter than an empty one — check the reading'
                          : reading.outside === 'over'
                            ? 'heavier than a full one — check the reading'
                            : `${kgText} kg on the scales is ${reading.servings} ${reading.servings === 1 ? item.servingName : pluralServing(item.servingName)}`}
                      </span>
                    </>
                  )}
                </small>
              )}
            </span>
            {counted && (
              <span className="zcell">
                <input
                  aria-label={`${item.name} ${item.container!.name}s ${word}`}
                  inputMode="decimal"
                  placeholder="—"
                  value={drafts[`${item.id}:full`] ?? ''}
                  onChange={(e) => onChange({ ...drafts, [`${item.id}:full`]: e.target.value })}
                />
                <small>{item.container!.name}s</small>
              </span>
            )}
            {weighed && (
              <span className="zcell">
                <input
                  aria-label={`${item.name} on the scales`}
                  inputMode="decimal"
                  placeholder="—"
                  value={kgText}
                  onChange={(e) => {
                    const text = e.target.value
                    const r = text.trim() === '' ? null : kegReading(item, Number(text))
                    // The reading fills the loose box; a blank or nonsense reading
                    // leaves whatever was typed there alone.
                    onChange({
                      ...drafts,
                      [`${item.id}:kg`]: text,
                      ...(r ? { [item.id]: String(r.servings) } : {}),
                    })
                  }}
                />
                <small>kg on the scales</small>
              </span>
            )}
            <span className="zcell">
              <input
                aria-label={`${item.name} ${word}`}
                inputMode="decimal"
                placeholder="—"
                value={drafts[item.id] ?? ''}
                onChange={(e) => {
                  // Typed by hand, the reading no longer describes the box.
                  const next = { ...drafts, [item.id]: e.target.value }
                  delete next[`${item.id}:kg`]
                  onChange(next)
                }}
              />
              <small>{counted ? `loose ${pluralServing(item.servingName)}` : pluralServing(item.servingName)}</small>
            </span>
          </div>
        )
      })}
    </>
  )
}
