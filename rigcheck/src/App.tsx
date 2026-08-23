import React, { useEffect, useMemo, useRef, useState } from 'react';
import { HashRouter, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppCtx, CORE_LOOP, decodeState, encodeState, engineData, makeBuild } from './ui/store.ts';
import { useApp } from './ui/store.ts';
import { ProvenanceBanner } from './ui/components/Banner.tsx';
import { BuildWizard } from './ui/pages/BuildWizard.tsx';
import { BuildAnalyser } from './ui/pages/BuildAnalyser.tsx';
import { UpgradeAdvisor } from './ui/pages/UpgradeAdvisor.tsx';
import { MachineReport } from './ui/pages/MachineReport.tsx';
import { Detect } from './ui/pages/Detect.tsx';
import { DataExplorer } from './ui/pages/DataExplorer.tsx';
import { ModelHealth } from './ui/pages/ModelHealth.tsx';
import { SystemHealth } from './ui/pages/SystemHealth.tsx';
import { Start } from './ui/pages/Start.tsx';
import { CommandPalette } from './ui/components/Palette.tsx';
import type { Build, Resolution } from './core/types.ts';

interface NavItem {
  to: string;
  label: string;
  full: string;
  /** Shown in the top bar. Everything else lives behind "more". */
  primary?: boolean;
}

/**
 * The nine screens, two of which are in the bar.
 *
 * `label` is what the bar and the menu show; `full` is a plain sentence saying
 * what the screen is FOR, used in the drawer, the command palette, the tooltip
 * and the current-screen readout.
 *
 * The full names used to be the project's names for things — "Comparison
 * Matrix", "Inventory Optimiser", "Model Health" — which told somebody what a
 * developer had called a module and nothing about whether it was the screen
 * they wanted. They are now descriptions, because the only reader who benefits
 * from a proper noun is one who already knows the app.
 */
const NAV: NavItem[] = [
  // Two destinations carry the whole app, because there are only two things
  // anyone comes here to do: work out what to buy, or find out whether the
  // machine they already have is behaving. Everything else is a way of looking
  // at one of those two, and a way of looking at something does not deserve a
  // place in the bar next to the thing itself.
  { to: '/wizard', label: 'Build', full: 'Build a PC', primary: true },
  { to: '/system', label: 'My PC', full: 'Check my PC', primary: true },

  // Behind "more". Reachable, searchable in the palette, and out of the way of
  // somebody who has just arrived and does not yet know which of eleven words
  // describes what they want.
  { to: '/analyser', label: 'Analyse', full: 'Analyse one build in detail' },
  { to: '/upgrade', label: 'Upgrade', full: 'What to upgrade first' },
  { to: '/machine', label: 'Machine', full: 'Power, heat and noise' },
  { to: '/detect', label: 'Identify', full: 'Identify a machine from a spec' },
  { to: '/data', label: 'Data', full: 'The catalogue behind the estimates' },
  { to: '/health', label: 'Evidence', full: 'What the estimates rest on' },
  { to: '/start', label: 'Start', full: 'Not sure? Start from a question' },
];

/**
 * Keeps the encoded build state on the URL.
 *
 * This lives inside the router on purpose. It used to run in `App`, keyed only
 * on the state itself — so navigating to another screen replaced the hash with
 * a bare `#/matrix` and nothing re-appended the `?s=`. Pressing "share" in that
 * window copied a link with no builds in it, which is the entire thing share
 * exists to do. Keyed on the location as well, the state is restored to the URL
 * after every navigation.
 */
function useUrlState() {
  const loc = useLocation();
  const { builds, games, resolutions } = useApp();
  useEffect(() => {
    const s = encodeState(builds, games, resolutions);
    const path = window.location.hash.split('?')[0] || '#/analyser';
    const next = `${path}?s=${s}`;
    if (window.location.hash !== next) window.history.replaceState(null, '', next);
  }, [builds, games, resolutions, loc.pathname, loc.key]);
}

function Shell({ children }: { children: React.ReactNode }) {
  const loc = useLocation();
  useUrlState();
  // On a phone the eleven-item bar becomes a drawer. A horizontally scrolling
  // row of eleven links is technically usable and practically not: you cannot
  // see where you are or what else exists without swiping blind.
  const [navOpen, setNavOpen] = useState(false);
  const here = NAV.find((n) => n.to === loc.pathname);

  // Route changes close the drawer. Leaving it open over the new screen is the
  // classic mobile-navigation bug.
  useEffect(() => setNavOpen(false), [loc.pathname]);

  return (
    <div className="app">
      <a href="#main" className="skip-link">Skip to content</a>
      <header className="topbar">
        <button
          className="nav-toggle"
          aria-expanded={navOpen}
          aria-controls="main-nav"
          aria-label={navOpen ? 'Close navigation' : 'Open navigation'}
          onClick={() => setNavOpen((o) => !o)}
        >
          <span aria-hidden="true">{navOpen ? '✕' : '☰'}</span>
        </button>
        <span className="brand">
          {/* A gauge reticle: outline in the chrome's neutral, needle in the
              accent. The one place the wordmark gets any ornament. */}
          <svg className="brand-mark" viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="10" cy="10" r="8.1" fill="none" stroke="currentColor" strokeOpacity="0.5" strokeWidth="1.5" />
            <path d="M10 1.9v2.3M18.1 10h-2.3M10 18.1v-2.3M1.9 10h2.3" stroke="currentColor" strokeOpacity="0.5" strokeWidth="1.3" strokeLinecap="round" />
            <path d="M10 10l3.4-3.4" stroke="var(--accent)" strokeWidth="1.8" strokeLinecap="round" />
            <circle cx="10" cy="10" r="1.7" fill="var(--accent)" />
          </svg>
          RIGCHECK<span> / build comparison</span>
        </span>
        <span className="here" aria-hidden="true">{here?.full ?? ''}</span>
        <nav className={`nav${navOpen ? ' open' : ''}`} id="main-nav" aria-label="Screens">
          {/* On a phone the drawer shows everything at once: an overflow menu
              inside a drawer is a menu inside a menu, and the drawer has the
              room the bar does not. `primary` only governs the bar. */}
          {NAV.filter((n) => n.primary || navOpen).map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              className={loc.pathname === n.to ? 'active' : ''}
              aria-current={loc.pathname === n.to ? 'page' : undefined}
              title={n.full}
            >
              {/* The bar is tight, the drawer is not: the drawer shows the full
                  name, so nothing is only ever known by its abbreviation. */}
              <span className="nav-short">{n.label}</span>
              <span className="nav-full">{n.full}</span>
            </NavLink>
          ))}
        </nav>
        <div className="topbar-right">
          <MoreMenu current={loc.pathname} />
          <ShareButton />
        </div>
      </header>
      <CommandPalette screens={NAV} />
      <ProvenanceBanner />
      <main className="main" id="main" tabIndex={-1}>{children}</main>
    </div>
  );
}

/**
 * Everything that is not one of the two things people come here to do.
 *
 * A menu rather than more items in the bar. Eleven destinations across a bar
 * present eleven equally-weighted choices at the moment somebody arrives, which
 * is precisely when they know least about which word describes what they want —
 * and the two that matter got no more prominence than "Inventory Optimiser".
 *
 * Closes on Escape and on any click outside it, both of which people expect and
 * neither of which a menu gets for free.
 */
function MoreMenu({ current }: { current: string }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const items = NAV.filter((n) => !n.primary);
  const hereInMenu = items.some((n) => n.to === current);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="more" ref={box}>
      <button
        className={`btn${hereInMenu ? ' primary' : ''}`}
        aria-expanded={open}
        aria-haspopup="menu"
        onClick={() => setOpen((o) => !o)}
      >
        more
      </button>
      {open && (
        <div className="more-menu" role="menu">
          {items.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              role="menuitem"
              className={current === n.to ? 'active' : ''}
              onClick={() => setOpen(false)}
            >
              <b>{n.label}</b>
              <span>{n.full}</span>
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}

function ShareButton() {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="btn"
      onClick={() => {
        navigator.clipboard?.writeText(window.location.href).then(
          () => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1400);
          },
          () => setCopied(false),
        );
      }}
      title="Builds are encoded in the URL — copy it to share this exact comparison"
    >
      {copied ? 'copied' : 'share'}
    </button>
  );
}

const SEEN_KEY = 'rigcheck.seenStart.v1';

export function App() {
  // A first visitor lands on Start; everyone after that lands where they were.
  // Stored rather than inferred, because "have they used this before" is not
  // something a URL can tell you.
  const [seenStart, setSeenStart] = useState(() => {
    try {
      return localStorage.getItem(SEEN_KEY) === '1';
    } catch {
      return true;
    }
  });
  const dismissStart = () => {
    try {
      localStorage.setItem(SEEN_KEY, '1');
    } catch {
      // Private browsing. Losing the flag means seeing Start again, which is
      // a mild annoyance rather than a failure.
    }
    setSeenStart(true);
  };

  const restored = useMemo(() => {
    const m = /[?&]s=([^&]+)/.exec(window.location.hash);
    return m ? decodeState(m[1]) : null;
  }, []);

  const [builds, setBuilds] = useState<Build[]>(
    restored?.builds ?? [
      makeBuild({ label: 'current', cpuId: 'amd-ryzen-5-3600', gpuId: 'nvidia-geforce-rtx-3060-12gb' }),
      makeBuild({ label: 'upgrade', cpuId: 'amd-ryzen-7-5800x3d', gpuId: 'amd-radeon-rx-6800-xt' }),
    ],
  );
  const [games, setGames] = useState<string[]>(restored?.games ?? CORE_LOOP);
  const [resolutions, setResolutions] = useState<Resolution[]>(restored?.resolutions ?? ['1080p', '1440p']);

  const value = { builds, setBuilds, games, setGames, resolutions, setResolutions, data: engineData };

  return (
    <AppCtx.Provider value={value}>
      <HashRouter>
        <Shell>
          <Routes>
            <Route path="/wizard" element={<BuildWizard />} />
            <Route path="/analyser" element={<BuildAnalyser />} />
            <Route path="/upgrade" element={<UpgradeAdvisor />} />
            <Route path="/machine" element={<MachineReport />} />
            <Route path="/system" element={<SystemHealth />} />
            <Route path="/detect" element={<Detect />} />
            <Route path="/data" element={<DataExplorer />} />
            <Route path="/health" element={<ModelHealth />} />
            <Route path="/start" element={<Start onDismiss={dismissStart} />} />
            {/* The analyser used to be the landing screen. It is the most
                detailed thing here and now sits behind "more"; landing someone
                on it meant their first sight of the app was a dense table of a
                build they never chose. */}
            <Route path="*" element={<Navigate to={seenStart ? '/wizard' : '/start'} replace />} />
          </Routes>
        </Shell>
      </HashRouter>
    </AppCtx.Provider>
  );
}
