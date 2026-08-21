import React, { useEffect, useMemo, useState } from 'react';
import { HashRouter, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppCtx, CORE_LOOP, decodeState, encodeState, engineData, makeBuild } from './ui/store.ts';
import { useApp } from './ui/store.ts';
import { ProvenanceBanner } from './ui/components/Banner.tsx';
import { BuildWizard } from './ui/pages/BuildWizard.tsx';
import { BuildAnalyser } from './ui/pages/BuildAnalyser.tsx';
import { ComparisonMatrix } from './ui/pages/ComparisonMatrix.tsx';
import { UpgradeAdvisor } from './ui/pages/UpgradeAdvisor.tsx';
import { InventoryOptimiser } from './ui/pages/InventoryOptimiser.tsx';
import { MachineReport } from './ui/pages/MachineReport.tsx';
import { TradeDesk } from './ui/pages/TradeDesk.tsx';
import { Detect } from './ui/pages/Detect.tsx';
import { DataExplorer } from './ui/pages/DataExplorer.tsx';
import { ModelHealth } from './ui/pages/ModelHealth.tsx';
import { SystemHealth } from './ui/pages/SystemHealth.tsx';
import { Start } from './ui/pages/Start.tsx';
import { CommandPalette } from './ui/components/Palette.tsx';
import type { Build, Resolution } from './core/types.ts';

/**
 * The twelve screens.
 *
 * `label` is what the top bar shows; `full` is the screen's real name, used in
 * the drawer, the command palette, the tooltip and the current-screen readout.
 *
 * The short labels are not only about width, though width was the trigger: at
 * full length the row needed 1545px, so every common laptop width silently hid
 * three or four destinations behind a fade with no scrollbar. They also read
 * better. "Comparison Matrix" and "Inventory Optimiser" are this project's
 * names for things; "Compare" and "Inventory" are the user's.
 */
const NAV = [
  { to: '/start', label: 'Start', full: 'Start' },
  { to: '/wizard', label: 'Build', full: 'Build a PC' },
  { to: '/analyser', label: 'Analyse', full: 'Build Analyser' },
  { to: '/matrix', label: 'Compare', full: 'Comparison Matrix' },
  { to: '/upgrade', label: 'Upgrade', full: 'Upgrade Advisor' },
  { to: '/inventory', label: 'Inventory', full: 'Inventory Optimiser' },
  { to: '/machine', label: 'Machine', full: 'Machine Report' },
  { to: '/trade', label: 'Trade', full: 'Trade Desk' },
  { to: '/system', label: 'My PC', full: 'System Health' },
  { to: '/detect', label: 'Identify', full: 'Identify a Machine' },
  { to: '/data', label: 'Data', full: 'Data Explorer' },
  { to: '/health', label: 'Accuracy', full: 'Model Health' },
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
          {NAV.map((n) => (
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
          <ShareButton />
        </div>
      </header>
      <CommandPalette screens={NAV} />
      <ProvenanceBanner />
      <main className="main" id="main" tabIndex={-1}>{children}</main>
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
            <Route path="/matrix" element={<ComparisonMatrix />} />
            <Route path="/upgrade" element={<UpgradeAdvisor />} />
            <Route path="/inventory" element={<InventoryOptimiser />} />
            <Route path="/machine" element={<MachineReport />} />
            <Route path="/trade" element={<TradeDesk />} />
            <Route path="/system" element={<SystemHealth />} />
            <Route path="/detect" element={<Detect />} />
            <Route path="/data" element={<DataExplorer />} />
            <Route path="/health" element={<ModelHealth />} />
            <Route path="/start" element={<Start onDismiss={dismissStart} />} />
            <Route path="*" element={<Navigate to={seenStart ? '/analyser' : '/start'} replace />} />
          </Routes>
        </Shell>
      </HashRouter>
    </AppCtx.Provider>
  );
}
