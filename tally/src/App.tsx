// ---------------------------------------------------------------------------
// The shell.
//
// Three tabs and a drawer, rather than a router. The app lives at a path that
// differs between the site root, /tally/ on Pages and a home-screen launch; a
// router would need configuring for each and buy nothing a pub landlady would
// ever notice.
//
// Why these three. The app is one loop: photograph the roll, and read off what
// went out of the door and what it took. So the bar is that loop — Tonight puts
// the pictures in, Sold is what they add up to, and the Cellar is what is left
// downstairs. The rota, the price list, the settings and the list of nights are
// all real work, and none of them is the work of a Tuesday, so they live behind
// More: one tap away, and out of the way of the job she opened the app to do.
//
// Looking a night up again is not a fourth tab, because Sold already ends in a
// list of the nights and tapping one opens it.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { NewDay } from './screens/NewDay.tsx'
import { History } from './screens/History.tsx'
import { DayDetail } from './screens/DayDetail.tsx'
import { Dashboard } from './screens/Dashboard.tsx'
import { Settings } from './screens/Settings.tsx'
import { Prices } from './screens/Prices.tsx'
import { Stock } from './screens/Stock.tsx'
import { Rota } from './screens/Rota.tsx'
import { ZReadReview } from './screens/ZReadReview.tsx'
import { useToast } from './components/toast.ts'
import { formatShort, tradingDayKey } from './core/date.ts'
import type { ZRead } from './core/zread.ts'
import {
  IconBarrel, IconBook, IconChart, IconChevronRight, IconMoon, IconMore,
  IconPeople, IconReceipt, IconSliders, TallyMark,
} from './components/icons.tsx'

/** The three that are on the bar, then the four that live behind More. */
type Tab = 'tonight' | 'sold' | 'stock' | 'more'
type Deeper = 'history' | 'rota' | 'prices' | 'settings'

/** An open review, together with where to write the corrected roll back to. */
interface Reviewing {
  zRead: ZRead
  apply: (next: ZRead) => void
}

const DEEPER: { key: Deeper; label: string; blurb: string; icon: React.ReactNode }[] = [
  { key: 'history', label: 'Nights', blurb: 'Every night, one by one', icon: <IconBook /> },
  { key: 'rota', label: 'Rota', blurb: 'Who is on, and what it costs', icon: <IconPeople /> },
  { key: 'prices', label: 'Price list', blurb: 'What everything sells for', icon: <IconReceipt /> },
  { key: 'settings', label: 'Settings', blurb: 'Backups, the float, the lot', icon: <IconSliders /> },
]

export function App() {
  const [tab, setTab] = useState<Tab>('tonight')
  const [deeper, setDeeper] = useState<Deeper | null>(null)
  const [openDate, setOpenDate] = useState<string | null>(null)
  const [editDate, setEditDate] = useState<string | undefined>(undefined)
  const [refreshKey, setRefreshKey] = useState(0)
  const [saved, saySaved] = useToast()
  const [reviewing, setReviewing] = useState<Reviewing | null>(null)

  function bump() {
    setRefreshKey((k) => k + 1)
  }

  function afterSave(date: string) {
    bump()
    setEditDate(undefined)
    saySaved(`${formatShort(date)} saved`)
    // Saving lands on Sold, where the night she has just put in has become part
    // of a total. Landing on a list of nights was right when the app was a
    // ledger; it is the wrong answer to "what did we take".
    setDeeper(null)
    setTab('sold')
  }

  function go(next: Tab) {
    setTab(next)
    setOpenDate(null)
    setReviewing(null)
    // Tapping More while already inside one of its screens comes back to the
    // list, which is the only way back that does not need a second control.
    setDeeper(null)
  }

  return (
    <div className="app">
      <header className="header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true"><TallyMark size={20} /></span>
          <h1>Tally</h1>
        </div>
        <nav className="tabs" aria-label="Sections">
          <button type="button" aria-current={tab === 'tonight' ? 'page' : undefined} onClick={() => go('tonight')}>
            <IconMoon /><span>Tonight</span>
          </button>
          <button type="button" aria-current={tab === 'sold' ? 'page' : undefined} onClick={() => go('sold')}>
            <IconChart /><span>Sold</span>
          </button>
          <button type="button" aria-current={tab === 'stock' ? 'page' : undefined} onClick={() => go('stock')}>
            <IconBarrel /><span>Cellar</span>
          </button>
          <button type="button" aria-current={tab === 'more' ? 'page' : undefined} onClick={() => go('more')}>
            <IconMore /><span>More</span>
          </button>
        </nav>
      </header>

      {reviewing ? (
        <ZReadReview
          zRead={reviewing.zRead}
          onChange={(next) => {
            reviewing.apply(next)
            setReviewing({ ...reviewing, zRead: next })
          }}
          onBack={() => setReviewing(null)}
        />
      ) : (
        <>
          {tab === 'tonight' && (
            // Keyed on the date so switching between tonight and a night being
            // corrected remounts with the right record rather than merging them.
            <NewDay
              key={editDate ?? 'tonight'}
              initialDate={editDate}
              onSaved={afterSave}
              onReviewRoll={(zRead, apply) => setReviewing({ zRead, apply })}
              onOpenCellar={() => go('stock')}
            />
          )}

          {tab === 'sold' && openDate === null && (
            <Dashboard refreshKey={refreshKey} onOpen={setOpenDate} />
          )}

          {tab === 'stock' && <Stock onChanged={bump} />}

          {tab === 'more' && deeper === 'history' && openDate === null && (
            <History
              refreshKey={refreshKey}
              onOpen={setOpenDate}
              onStart={() => { setEditDate(undefined); setTab('tonight') }}
            />
          )}

          {tab === 'more' && deeper === null && (
            <div className="main">
              <nav className="doors" aria-label="Everything else">
                {DEEPER.map((d) => (
                  <button key={d.key} type="button" className="door" onClick={() => setDeeper(d.key)}>
                    <span className="door-icon" aria-hidden="true">{d.icon}</span>
                    <span className="door-text">
                      <strong>{d.label}</strong>
                      <span>{d.blurb}</span>
                    </span>
                    <IconChevronRight />
                  </button>
                ))}
              </nav>
            </div>
          )}

          {/* A night opens over whichever list she came from — the Sold screen's
              own list of nights, or the one behind More. Both land here. */}
          {(tab === 'sold' || (tab === 'more' && deeper === 'history')) && openDate !== null && (
            <DayDetail
              date={openDate}
              onBack={() => setOpenDate(null)}
              onEdit={(date) => {
                setEditDate(date === tradingDayKey() ? undefined : date)
                setOpenDate(null)
                setTab('tonight')
              }}
              onDeleted={() => { setOpenDate(null); bump() }}
            />
          )}

          {tab === 'more' && deeper === 'rota' && <Rota onChanged={bump} />}

          {tab === 'more' && deeper === 'prices' && <Prices onChanged={bump} />}

          {tab === 'more' && deeper === 'settings' && (
            <Settings onChanged={bump} onOpenPrices={() => setDeeper('prices')} />
          )}
        </>
      )}

      {saved && <div className="toast" role="status">{saved}</div>}
    </div>
  )
}
