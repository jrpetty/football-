// ---------------------------------------------------------------------------
// The shell.
//
// Four tabs and a little state, rather than a router. The app lives at a path
// that differs between the site root, /tally/ on Pages and a home-screen
// launch; a router would need configuring for each and buy nothing a pub
// landlady would ever notice.
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
import { formatShort, tradingDayKey } from './core/date.ts'
import type { ZRead } from './core/zread.ts'
import { IconBarrel, IconBook, IconChart, IconMoon, IconPeople, IconSliders, TallyMark } from './components/icons.tsx'

type Tab = 'tonight' | 'dashboard' | 'stock' | 'rota' | 'history' | 'settings'

/** An open review, together with where to write the corrected roll back to. */
interface Reviewing {
  zRead: ZRead
  apply: (next: ZRead) => void
}

export function App() {
  const [tab, setTab] = useState<Tab>('tonight')
  const [openDate, setOpenDate] = useState<string | null>(null)
  const [editDate, setEditDate] = useState<string | undefined>(undefined)
  const [refreshKey, setRefreshKey] = useState(0)
  const [saved, setSaved] = useState('')
  const [reviewing, setReviewing] = useState<Reviewing | null>(null)
  const [pricing, setPricing] = useState(false)

  function afterSave(date: string) {
    setRefreshKey((k) => k + 1)
    setEditDate(undefined)
    setSaved(`${formatShort(date)} saved`)
    setTimeout(() => setSaved(''), 4000)
    setTab('history')
  }

  function go(next: Tab) {
    setTab(next)
    setOpenDate(null)
    setReviewing(null)
    setPricing(false)
  }

  const subtitle = pricing
    ? 'The price list'
    : reviewing
    ? 'Checking the roll'
    : tab === 'tonight'
      ? editDate
        ? 'Correcting a saved night'
        : 'Tonight’s count'
      : tab === 'dashboard'
        ? 'How trade is going'
        : tab === 'stock'
        ? 'What is in the cellar'
        : tab === 'rota'
        ? 'Who is on'
        : tab === 'history'
          ? 'Every night so far'
          : 'Settings'

  return (
    <div className="app">
      <header className="header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true"><TallyMark size={26} /></span>
          <div>
            <h1>Tally</h1>
            <p className="sub">{subtitle}</p>
          </div>
        </div>
        <nav className="tabs" aria-label="Sections">
          <button type="button" aria-current={tab === 'tonight' ? 'page' : undefined} onClick={() => go('tonight')}>
            <IconMoon /><span>Tonight</span>
          </button>
          <button type="button" aria-current={tab === 'dashboard' ? 'page' : undefined} onClick={() => go('dashboard')}>
            <IconChart /><span>Trade</span>
          </button>
          <button type="button" aria-current={tab === 'stock' ? 'page' : undefined} onClick={() => go('stock')}>
            <IconBarrel /><span>Cellar</span>
          </button>
          <button type="button" aria-current={tab === 'rota' ? 'page' : undefined} onClick={() => go('rota')}>
            <IconPeople /><span>Rota</span>
          </button>
          <button type="button" aria-current={tab === 'history' ? 'page' : undefined} onClick={() => go('history')}>
            <IconBook /><span>Nights</span>
          </button>
          <button type="button" aria-current={tab === 'settings' ? 'page' : undefined} onClick={() => go('settings')}>
            <IconSliders /><span>Settings</span>
          </button>
        </nav>
      </header>

      {pricing ? (
        <Prices onChanged={() => setRefreshKey((k) => k + 1)} />
      ) : reviewing ? (
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
            />
          )}

          {tab === 'dashboard' && openDate === null && (
            <Dashboard refreshKey={refreshKey} onOpen={setOpenDate} />
          )}

          {tab === 'stock' && <Stock onChanged={() => setRefreshKey((k) => k + 1)} />}

          {tab === 'rota' && <Rota onChanged={() => setRefreshKey((k) => k + 1)} />}

          {tab === 'history' && openDate === null && (
            <History
              refreshKey={refreshKey}
              onOpen={setOpenDate}
              onStart={() => { setEditDate(undefined); setTab('tonight') }}
            />
          )}

          {(tab === 'history' || tab === 'dashboard') && openDate !== null && (
            <DayDetail
              date={openDate}
              onBack={() => setOpenDate(null)}
              onEdit={(date) => {
                setEditDate(date === tradingDayKey() ? undefined : date)
                setOpenDate(null)
                setTab('tonight')
              }}
              onDeleted={() => { setOpenDate(null); setRefreshKey((k) => k + 1) }}
            />
          )}

          {tab === 'settings' && (
            <Settings onChanged={() => setRefreshKey((k) => k + 1)} onOpenPrices={() => setPricing(true)} />
          )}
        </>
      )}

      {saved && <div className="toast" role="status">{saved}</div>}
    </div>
  )
}
