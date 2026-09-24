import { Suspense, lazy, useEffect, useId, useRef, useState } from 'react';
import type { ComponentType, ReactNode } from 'react';
import { useRoute, Link } from './router.tsx';
import { MetaProvider, PrefsProvider, ToastProvider, useMeta, usePrefs } from './context.tsx';
import { Icon } from './components/icons.tsx';
import { Callout, LoadingPage, cx } from './components/ui.tsx';
import { useHotkeys, useNow } from './hooks.ts';
import { MOCK, api } from './api.ts';

const LeaderboardPage = lazy(() => import('./pages/LeaderboardPage.tsx'));
const NewRunPage = lazy(() => import('./pages/NewRunPage.tsx'));
const LiveArenaPage = lazy(() => import('./pages/LiveArenaPage.tsx'));
const RunsPage = lazy(() => import('./pages/RunsPage.tsx'));
const RunDetailPage = lazy(() => import('./pages/RunDetailPage.tsx'));
const TestsPage = lazy(() => import('./pages/TestsPage.tsx'));
const TestDetailPage = lazy(() => import('./pages/TestDetailPage.tsx'));
const TestBuilderPage = lazy(() => import('./pages/TestBuilderPage.tsx'));
const ModelsPage = lazy(() => import('./pages/ModelsPage.tsx'));
const ReviewPage = lazy(() => import('./pages/ReviewPage.tsx'));
const MethodologyPage = lazy(() => import('./pages/MethodologyPage.tsx'));
const InboxPage = lazy(() => import('./pages/InboxPage.tsx'));
const GradePage = lazy(() => import('./pages/GradePage.tsx'));
const CostsPage = lazy(() => import('./pages/CostsPage.tsx'));

interface NavItem {
  to: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  cta?: boolean;
  badge?: 'manual';
  section?: string;
  match: (path: string) => boolean;
}

const NAV: NavItem[] = [
  { to: '/run/new', label: 'New Run', icon: Icon.Rocket, cta: true, match: (p) => p === '/run/new' },
  { to: '/', label: 'Leaderboard', icon: Icon.Trophy, match: (p) => p === '/' || p === '/leaderboard' },
  { to: '/runs', label: 'Runs', icon: Icon.History, match: (p) => p.startsWith('/runs') },
  { to: '/inbox', label: 'Manual Inbox', icon: Icon.Inbox, badge: 'manual', match: (p) => p.startsWith('/inbox') },
  { to: '/review', label: 'Blind Review', icon: Icon.Eye, match: (p) => p.startsWith('/review') },
  { to: '/tests', label: 'Tests', icon: Icon.Flask, section: 'Lab', match: (p) => p.startsWith('/tests') },
  { to: '/grade', label: 'Grader', icon: Icon.Target, match: (p) => p.startsWith('/grade') },
  { to: '/models', label: 'Models', icon: Icon.Cpu, match: (p) => p.startsWith('/models') },
  { to: '/costs', label: 'Cost Planner', icon: Icon.Dollar, match: (p) => p.startsWith('/costs') },
  { to: '/methodology', label: 'Methodology', icon: Icon.Book, section: 'About', match: (p) => p.startsWith('/methodology') },
];

/** Pending copy & paste requests (polls GET /api/manual every 2 s while the tab is visible). */
function useManualCount(): number {
  const [n, setN] = useState(0);
  useEffect(() => {
    let alive = true;
    let timer: number | undefined;
    const tick = async () => {
      if (document.visibilityState === 'visible') {
        try {
          const list = await api.manualQueue();
          if (alive) setN(Array.isArray(list) ? list.length : 0);
        } catch {
          /* server offline: keep last count */
        }
      }
      if (alive) timer = window.setTimeout(tick, 2000);
    };
    void tick();
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, []);
  return n;
}

interface Resolved {
  el: ReactNode;
  crumb: string;
}

function resolve(parts: string[]): Resolved {
  const [a, b, c] = parts;
  if (!a || a === 'leaderboard') return { el: <LeaderboardPage />, crumb: 'Leaderboard' };
  if (a === 'run' && b === 'new') return { el: <NewRunPage />, crumb: 'New Run' };
  if (a === 'runs') {
    if (!b) return { el: <RunsPage />, crumb: 'Runs' };
    if (c === 'live') return { el: <LiveArenaPage key={b} runId={b} />, crumb: 'Live Arena' };
    return { el: <RunDetailPage key={b} runId={b} />, crumb: 'Run detail' };
  }
  if (a === 'tests') {
    if (!b) return { el: <TestsPage />, crumb: 'Test Library' };
    if (b === 'new') return { el: <TestBuilderPage key="new" />, crumb: 'Test Builder' };
    if (c === 'edit') return { el: <TestBuilderPage key={`edit-${b}`} editId={b} />, crumb: 'Edit test' };
    return { el: <TestDetailPage key={b} testId={b} />, crumb: 'Test detail' };
  }
  if (a === 'models') return { el: <ModelsPage />, crumb: 'Models' };
  if (a === 'review') return { el: <ReviewPage />, crumb: 'Blind Review' };
  if (a === 'methodology') return { el: <MethodologyPage />, crumb: 'Methodology' };
  if (a === 'inbox') return { el: <InboxPage />, crumb: 'Manual Inbox' };
  if (a === 'grade') return { el: <GradePage />, crumb: 'Grader' };
  if (a === 'costs') return { el: <CostsPage />, crumb: 'Cost Planner' };
  return {
    el: (
      <div className="page">
        <div className="card">
          <div className="empty">
            <div className="art">
              <Icon.Target />
            </div>
            <h3>Page not found</h3>
            <p>There is nothing at this address.</p>
            <div className="actions">
              <Link to="/" className="btn primary">
                Go to the leaderboard
              </Link>
            </div>
          </div>
        </div>
      </div>
    ),
    crumb: 'Not found',
  };
}

export function BrandMark({ className }: { className?: string }) {
  const gid = `gm-${useId().replace(/:/g, '')}`;
  return (
    <svg className={className} viewBox="0 0 36 36" aria-hidden="true">
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#22d3ee" />
          <stop offset="1" stopColor="#a78bfa" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="34" height="34" rx="9" fill="#0a0f16" stroke={`url(#${gid})`} strokeOpacity="0.55" />
      <path d="M9.5 25 L18 9 L26.5 25" fill="none" stroke={`url(#${gid})`} strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M13.4 19.6 H22.6" stroke="#eef3fa" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

function Wordmark({ tagline = true }: { tagline?: boolean }) {
  return (
    <div>
      <div className="brand-word">GAUNTLET</div>
      {tagline && <div className="brand-tag">AI Benchmark Lab</div>}
    </div>
  );
}

function Shell() {
  const route = useRoute();
  const { meta, error, reload, loading } = useMeta();
  const { theme, setTheme, broadcast, setBroadcast } = usePrefs();
  const [navOpen, setNavOpen] = useState(false);
  const [exitVisible, setExitVisible] = useState(false);
  const hideTimer = useRef<number | undefined>(undefined);
  const now = useNow(broadcast ? 1000 : null);
  const manualCount = useManualCount();

  const { el, crumb } = resolve(route.parts);

  useEffect(() => {
    setNavOpen(false);
    window.scrollTo({ top: 0 });
  }, [route.path]);

  useEffect(() => {
    document.title = `${crumb} · Gauntlet`;
  }, [crumb]);

  useHotkeys({
    b: () => setBroadcast(!broadcast),
    Escape: () => {
      if (broadcast && !document.querySelector('.drawer-root, .modal-root')) setBroadcast(false);
    },
  });

  // In broadcast mode, reveal the exit control only while the pointer moves.
  useEffect(() => {
    if (!broadcast) return;
    const show = () => {
      setExitVisible(true);
      window.clearTimeout(hideTimer.current);
      hideTimer.current = window.setTimeout(() => setExitVisible(false), 2200);
    };
    window.addEventListener('pointermove', show);
    return () => {
      window.removeEventListener('pointermove', show);
      window.clearTimeout(hideTimer.current);
    };
  }, [broadcast]);

  return (
    <div className={cx('app', navOpen && 'nav-open')}>
      <div className="app-backdrop" aria-hidden="true" />
      <div className="broadcast-backdrop" aria-hidden="true">
        <div className="glow a" />
        <div className="glow b" />
        <div className="grid" />
        <div className="scan" />
      </div>

      <aside className="sidebar" aria-label="Main navigation">
        <Link to="/" className="brand" aria-label="Gauntlet home">
          <BrandMark className="brand-mark" />
          <Wordmark />
        </Link>
        <nav className="nav">
          {NAV.map((n) => {
            const I = n.icon;
            const active = n.match(route.path);
            const count = n.badge === 'manual' ? manualCount : 0;
            return (
              <div key={n.to} style={{ display: 'contents' }}>
                {n.section && <div className="nav-section eyebrow">{n.section}</div>}
                <Link to={n.to} className={cx(n.cta && 'nav-cta')} aria-current={active ? 'page' : undefined} aria-label={count ? `${n.label}, ${count} waiting` : undefined}>
                  <I />
                  {n.label}
                  {count > 0 && <span className="nav-count">{count > 99 ? '99+' : count}</span>}
                </Link>
              </div>
            );
          })}
        </nav>
        <div className="sidebar-foot">
          <div className="row">
            <span>Harness</span>
            <span className="mono">{meta?.harnessVersion ?? '—'}</span>
          </div>
          <div className="row">
            <span>Protocol</span>
            <span className="mono">{meta?.protocolVersion ?? '—'}</span>
          </div>
          <div className="row">
            <span>Broadcast</span>
            <span>
              <kbd>B</kbd>
            </span>
          </div>
        </div>
      </aside>
      <div className="scrim" onClick={() => setNavOpen(false)} aria-hidden="true" />

      <header className="topbar">
        <button className="btn ghost icon menu-btn" aria-label="Open navigation" onClick={() => setNavOpen(true)}>
          <Icon.Menu />
        </button>
        <Link to="/" className="brand brand-inline" style={{ padding: 0 }} aria-label="Gauntlet home">
          <BrandMark className="brand-mark" />
          <Wordmark tagline={false} />
        </Link>
        <div className="topbar-title">
          <span className="crumb">{crumb}</span>
        </div>
        <div className="topbar-spacer" />
        {MOCK && (
          <span className="badge warn" title="Serving bundled fixtures (?mock=1) — not real results">
            <Icon.Layers /> Demo data
          </span>
        )}
        <span className="badge outline hide-mobile" title={error ? 'Server unreachable' : 'Connected to the Gauntlet server'}>
          <span className={cx('status-dot', meta ? 'ok' : error ? 'bad' : '')} />
          {meta ? (
            <>
              v{meta.harnessVersion} · protocol {meta.protocolVersion}
            </>
          ) : error ? (
            'offline'
          ) : (
            'connecting…'
          )}
        </span>
        <button className="btn ghost icon" aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} title="Toggle theme" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
          {theme === 'dark' ? <Icon.Sun /> : <Icon.Moon />}
        </button>
        <button className="btn sm" onClick={() => setBroadcast(true)} title="Broadcast mode (B)" aria-label="Enter broadcast mode">
          <Icon.Broadcast />
          <span className="hide-mobile">Broadcast</span>
          <kbd className="hide-mobile">B</kbd>
        </button>
      </header>

      <div className="broadcast-slate" aria-hidden={!broadcast}>
        <BrandMark className="brand-mark" />
        <div className="brand-word">GAUNTLET</div>
        <div className="slate-tag">AI Benchmark Lab</div>
        <div className="slate-right">
          <span className="eyebrow" style={{ color: 'var(--text-2)' }}>
            {crumb}
          </span>
          <span className="tnum">{new Date(now).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' })}</span>
          {meta && <span className="hash">protocol {meta.protocolVersion}</span>}
        </div>
      </div>

      <main className="main" id="main">
        {error && !meta && !loading && (
          <div className="page" style={{ marginBottom: 18 }}>
            <Callout tone="bad">
              <strong>Can’t reach the Gauntlet server.</strong> Start it with <code>npm run serve</code> (port 7777) and{' '}
              <button className="btn xs" onClick={reload}>
                retry
              </button>
              {!MOCK && (
                <>
                  {' '}
                  — or explore the UI with{' '}
                  <a href={`${window.location.pathname}?mock=1${window.location.hash}`}>demo data</a>.
                </>
              )}
            </Callout>
          </div>
        )}
        <Suspense fallback={<LoadingPage />}>{el}</Suspense>
      </main>

      {broadcast && (
        <button className={cx('btn sm broadcast-exit', exitVisible && 'visible')} onClick={() => setBroadcast(false)} aria-label="Exit broadcast mode">
          <Icon.X /> Exit broadcast <kbd>B</kbd>
        </button>
      )}
    </div>
  );
}

export default function App() {
  return (
    <PrefsProvider>
      <ToastProvider>
        <MetaProvider>
          <Shell />
        </MetaProvider>
      </ToastProvider>
    </PrefsProvider>
  );
}
