// ---------------------------------------------------------------------------
// The keg calculator.
//
// Weigh a keg empty and weigh one full, and from then on any keg on the scales
// is pints: the keg's own weight comes off, and what is left is a share of the
// fill. The pair of weights can be kept against a line — from then on its row
// on the count sheet has a box for the reading — or against every line that
// comes in the same size of keg.
//
// It has its own place on the Cellar tab, and sits above the count sheet on
// Tonight and on the stock take, because the cellar is where she is when she
// needs it.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import {
  kegNameFor,
  ML_PER_PINT,
  pluralServing,
  readScales,
  scalesUsable,
  type KegWeights,
  type StockItem,
} from '../core/stock.ts'

interface Props {
  items: readonly StockItem[]
  /**
   * Keep the weights on these lines. The reading on the scales at the time, if
   * there was one, comes with it so a count sheet can be filled in from it;
   * and for a line with no size yet, the keg it turns out to come in. Without
   * this the calculator only works the sum.
   */
  onKeep?: (
    weights: KegWeights,
    itemIds: string[],
    grossKg: number | null,
    size?: { name: string; baseUnits: number },
  ) => void | Promise<void>
}

/** The option for a keg that is not any line's: just the sum, in pints. */
const ANY = '*'

/**
 * The lines a keg calculator can be about: liquid, in a container of more than
 * a serving — or a tap beer with no size set yet, which the scales can set.
 */
export function weighableLines(items: readonly StockItem[]): StockItem[] {
  return items.filter(
    (i) =>
      i.kind === 'liquid' &&
      (i.container ? i.container.baseUnits > i.servingBaseUnits : i.servingName === 'pint'),
  )
}

function num(text: string): number | null {
  const t = text.trim()
  if (t === '') return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

function storedWeights(item: StockItem | undefined): KegWeights | null {
  const c = item?.container
  return c && scalesUsable(c) ? { emptyKg: c.emptyKg, fullKg: c.fullKg } : null
}

export function KegCalculator({ items, onKeep }: Props) {
  const lines = weighableLines(items)
  // Starts on a line that has a size, if any has: that is the one being
  // weighed on a normal night; setting a new line up is the rarer job.
  const [lineId, setLineId] = useState<string>(lines.find((l) => l.container)?.id ?? lines[0]?.id ?? ANY)
  const line = lines.find((l) => l.id === lineId)
  const stored = storedWeights(line)
  const [emptyText, setEmptyText] = useState(stored ? String(stored.emptyKg) : '')
  const [fullText, setFullText] = useState(stored ? String(stored.fullKg) : '')
  const [grossText, setGrossText] = useState('')
  const [holdsText, setHoldsText] = useState('72')
  const [kept, setKept] = useState('')

  function pick(id: string) {
    setLineId(id)
    setKept('')
    // A line weighed before starts from its own weights; one that has not
    // keeps whatever is in the boxes, which is likely the keg just weighed.
    const w = storedWeights(lines.find((l) => l.id === id))
    if (w) {
      setEmptyText(String(w.emptyKg))
      setFullText(String(w.fullKg))
    }
  }

  const empty = num(emptyText)
  const full = num(fullText)
  const gross = num(grossText)
  const weights: KegWeights | null =
    empty !== null && full !== null && scalesUsable({ emptyKg: empty, fullKg: full }) ? { emptyKg: empty, fullKg: full } : null
  const backwards = empty !== null && full !== null && !weights

  const servingBaseUnits = line ? line.servingBaseUnits : ML_PER_PINT
  const servingName = line ? line.servingName : 'pint'
  // A line with no size yet, and a keg on no line, take theirs from the box.
  const sized = !!line?.container
  const holdsBaseUnits = line?.container ? line.container.baseUnits : (num(holdsText) ?? 0) * servingBaseUnits
  const perContainer = servingBaseUnits > 0 ? Math.round(holdsBaseUnits / servingBaseUnits) : 0
  const reading = weights && gross !== null ? readScales(weights, holdsBaseUnits, servingBaseUnits, gross) : null

  const same = !!weights && !!stored && stored.emptyKg === weights.emptyKg && stored.fullKg === weights.fullKg
  const sameSize = line?.container ? lines.filter((l) => l.container?.name === line.container?.name) : []
  // A keg is named by the beer in it, not by the line's own unit: 40,896ml is
  // a firkin whether the line counts pints or millilitres.
  const newSize =
    line && !line.container && perContainer > 0
      ? { name: kegNameFor(Math.round(holdsBaseUnits / ML_PER_PINT)), baseUnits: holdsBaseUnits }
      : undefined

  async function keep(ids: string[]) {
    if (!weights || !onKeep || !line) return
    if (!sized && !newSize) return
    await onKeep(weights, ids, gross, newSize)
    setKept(
      ids.length > 1
        ? `Kept for ${ids.length} lines — each has a box for the scales on the count sheet now.`
        : `Kept — ${line.name}’s row on the count sheet has a box for the scales now.`,
    )
  }

  return (
    <div className="keg-calc">
      <div className="field">
        <label htmlFor="keg-line">Which keg</label>
        <select id="keg-line" aria-label="Which keg" value={line ? line.id : ANY} onChange={(e) => pick(e.target.value)}>
          {lines.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name} —{' '}
              {l.container
                ? `${l.container.name}, ${Math.round(l.container.baseUnits / l.servingBaseUnits)} ${pluralServing(l.servingName)}`
                : 'no size set yet'}
            </option>
          ))}
          <option value={ANY}>a keg on no line</option>
        </select>
      </div>
      <div className="keg-weights">
        {!sized && (
          <span className="stock-field">
            <small>holds</small>
            <input
              aria-label="Pints the keg holds"
              inputMode="numeric"
              placeholder="72"
              value={holdsText}
              onChange={(e) => setHoldsText(e.target.value)}
            />
            <small>{pluralServing(servingName)}</small>
          </span>
        )}
        <span className="stock-field">
          <small>empty</small>
          <input
            aria-label="Empty keg weight"
            inputMode="decimal"
            placeholder="—"
            value={emptyText}
            onChange={(e) => {
              setEmptyText(e.target.value)
              setKept('')
            }}
          />
          <small>kg</small>
        </span>
        <span className="stock-field">
          <small>full</small>
          <input
            aria-label="Full keg weight"
            inputMode="decimal"
            placeholder="—"
            value={fullText}
            onChange={(e) => {
              setFullText(e.target.value)
              setKept('')
            }}
          />
          <small>kg</small>
        </span>
        <span className="stock-field keg-gross">
          <small>on the scales</small>
          <input
            aria-label="Keg on the scales"
            inputMode="decimal"
            placeholder="—"
            value={grossText}
            onChange={(e) => setGrossText(e.target.value)}
          />
          <small>kg</small>
        </span>
      </div>

      {backwards ? (
        <p className="note bad">A full keg has to weigh more than an empty one.</p>
      ) : !weights ? (
        <p className="note">
          Weigh one empty and one full — once each — and any keg on the scales is{' '}
          {pluralServing(servingName)}.
        </p>
      ) : !sized && perContainer <= 0 ? (
        <p className="note">Say how many {pluralServing(servingName)} the keg holds when it is full.</p>
      ) : gross === null ? (
        <p className="note">Now put a keg on the scales.</p>
      ) : null}

      {reading && (
        <div className={`keg-result${reading.outside ? ' bad' : ''}`}>
          <strong>
            {reading.servings} {reading.servings === 1 ? servingName : pluralServing(servingName)}
          </strong>
          <span>
            {reading.outside === 'under'
              ? 'lighter than an empty keg — check the reading'
              : reading.outside === 'over'
                ? 'heavier than a full one — check the reading'
                : `in it, of ${perContainer} — ${Math.round(reading.share * 100)}% full`}
          </span>
        </div>
      )}

      {onKeep && line && weights && (sized || newSize) && (
        same ? (
          <p className="note">
            {kept || `${line.name} has these weights — its row on the count sheet has a box for the scales.`}
          </p>
        ) : (
          <div className="alts">
            {newSize && (
              <p className="note" style={{ margin: '0 0 2px', flexBasis: '100%' }}>
                {line.name} has no size yet: keeping this makes it a {newSize.name} of {perContainer}{' '}
                {pluralServing(servingName)}.
              </p>
            )}
            <button type="button" className="btn-small" onClick={() => void keep([line.id])}>
              Keep for {line.name}
            </button>
            {sameSize.length > 1 && (
              <button type="button" className="btn-small" onClick={() => void keep(sameSize.map((l) => l.id))}>
                Keep for all {sameSize.length} lines in {line.container!.name}s
              </button>
            )}
          </div>
        )
      )}
    </div>
  )
}
