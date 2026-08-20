import React, { useEffect, useMemo, useState } from 'react';
import { HashRouter, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppCtx, CORE_LOOP, decodeState, encodeState, engineData, makeBuild } from './ui/store.ts';
import { ProvenanceBanner } from './ui/components/Banner.tsx';
import { BuildAnalyser } from './ui/pages/BuildAnalyser.tsx';
import { ComparisonMatrix } from './ui/pages/ComparisonMatrix.tsx';
import { UpgradeAdvisor } from './ui/pages/UpgradeAdvisor.tsx';
import { InventoryOptimiser } from './ui/pages/InventoryOptimiser.tsx';
import { MachineReport } from './ui/pages/MachineReport.tsx';
import { TradeDesk } from './ui/pages/TradeDesk.tsx';
import { Detect } from './ui/pages/Detect.tsx';
import { DataExplorer } from './ui/pages/DataExplorer.tsx';
import { ModelHealth } from './ui/pages/ModelHealth.tsx';
import type { Build, Resolution } from './core/types.ts';

const NAV = [
  { to: '/analyser', label: 'Build Analyser' },
  { to: '/matrix', label: 'Comparison Matrix' },
  { to: '/upgrade', label: 'Upgrade Advisor' },
  { to: '/inventory', label: 'Inventory Optimiser' },
  { to: '/machine', label: 'Machine Report' },
  { to: '/trade', label: 'Trade Desk' },
  { to: '/detect', label: 'Identify' },
  { to: '/data', label: 'Data Explorer' },
  { to: '/health', label: 'Model Health' },
];

function Shell({ children }: { children: React.ReactNode }) {
  const loc = useLocation();
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">RIGCHECK<span> / build comparison</span></span>
        <nav className="nav">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} className={loc.pathname === n.to ? 'active' : ''}>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="topbar-right">
          <ShareButton />
        </div>
      </header>
      <ProvenanceBanner />
      <main className="main">{children}</main>
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

export function App() {
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

  // Builds are first-class objects: saveable and shareable by URL.
  useEffect(() => {
    const s = encodeState(builds, games, resolutions);
    const path = window.location.hash.split('?')[0] || '#/analyser';
    const next = `${path}?s=${s}`;
    if (window.location.hash !== next) {
      window.history.replaceState(null, '', next);
    }
  }, [builds, games, resolutions]);

  const value = { builds, setBuilds, games, setGames, resolutions, setResolutions, data: engineData };

  return (
    <AppCtx.Provider value={value}>
      <HashRouter>
        <Shell>
          <Routes>
            <Route path="/analyser" element={<BuildAnalyser />} />
            <Route path="/matrix" element={<ComparisonMatrix />} />
            <Route path="/upgrade" element={<UpgradeAdvisor />} />
            <Route path="/inventory" element={<InventoryOptimiser />} />
            <Route path="/machine" element={<MachineReport />} />
            <Route path="/trade" element={<TradeDesk />} />
            <Route path="/detect" element={<Detect />} />
            <Route path="/data" element={<DataExplorer />} />
            <Route path="/health" element={<ModelHealth />} />
            <Route path="*" element={<Navigate to="/analyser" replace />} />
          </Routes>
        </Shell>
      </HashRouter>
    </AppCtx.Provider>
  );
}
