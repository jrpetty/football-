/**
 * The sidebar menu. Simple mode (the default): about six task-named items. "Show all tools": every page,
 * grouped into the same sections with a short description on hover. The choice is remembered per browser.
 */
import type { ComponentType } from 'react';
import { Link } from '../router.tsx';
import { cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { SIMPLE_NAV, groupAllTools, pageKeyFor, sectionFor } from './nav.ts';
import { SECTION_ICONS, SimpleIcon } from './icons.tsx';
import { isSimpleMode, setSimpleMode, useSimplePrefs } from './prefs.ts';
import './simple.css';

/** The shape of App.tsx's NAV entries. */
export interface MenuItem {
  to: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  cta?: boolean;
  badge?: 'manual';
  section?: string;
  match: (path: string) => boolean;
}

/** One-line tooltips for the "all tools" menu, by path. */
const HINTS: Record<string, string> = {
  '/': 'Your starting point: next steps and how things stand',
  '/run/new': 'Pick models and tests, see the price, press start',
  '/leaderboard': 'The ranking across all your runs',
  '/runs': 'Every test run you have done',
  '/arena': 'Tournaments: models play games against each other',
  '/versus': 'Two models side by side',
  '/best': 'The winner of every single test',
  '/present': 'Full-screen video slides for a run',
  '/studio': 'Highlights, script, thumbnails and OBS overlays',
  '/gallery': 'The pictures models painted',
  '/inbox': 'Copy & paste tests: questions waiting for a pasted reply',
  '/grading': 'Mark answers that need a person',
  '/review': 'Score answers without knowing which model wrote them',
  '/tests': 'Every test, what it asks and how it is marked',
  '/grade': 'Paste any reply and see its score',
  '/keys': 'Paste API keys so Gauntlet can reach the models',
  '/models': 'Which AI models take part',
  '/costs': 'What a run would cost before you start',
  '/budget': 'Your monthly spending limit',
  '/newmodel': 'Test a brand-new model in five steps',
  '/history': 'How models have changed over time',
  '/challenge': 'Questions written by your viewers',
  '/publish': 'Turn results into a website for viewers',
  '/methodology': 'How scoring works and why it is fair',
  '/guide': 'Plain-English explanations for viewers',
};

export function SidebarNav({ items, path, manualCount }: { items: MenuItem[]; path: string; manualCount: number }) {
  const simple = useSimplePrefs(isSimpleMode);
  const current = sectionFor(pageKeyFor(path));
  const countLabel = (n: number) => (n > 99 ? '99+' : String(n));

  return (
    <>
      {simple ? (
        <nav className="nav nav-simple" aria-label="Main">
          {SIMPLE_NAV.map((s) => {
            const I = SECTION_ICONS[s.icon];
            const active = current?.id === s.id;
            const count = s.id === 'copy' ? manualCount : 0;
            return (
              <Link key={s.id} to={s.to} title={s.blurb} aria-current={active ? 'page' : undefined} aria-label={count ? `${s.label}, ${count} waiting` : undefined}>
                <I />
                {s.label}
                {count > 0 && <span className="nav-count">{countLabel(count)}</span>}
              </Link>
            );
          })}
        </nav>
      ) : (
        <nav className="nav nav-all" aria-label="All tools">
          {groupAllTools(items).map((g) => (
            <div key={g.id} className="nav-group" role="group" aria-label={g.label}>
              <div className="nav-section eyebrow" title={g.blurb}>
                {g.label}
              </div>
              {g.items.map((n) => {
                const I = n.icon;
                const active = n.match(path);
                const count = n.badge === 'manual' ? manualCount : 0;
                return (
                  <Link key={n.to} to={n.to} className={cx(n.cta && 'nav-cta')} title={HINTS[n.to.split('?')[0]!] ?? n.label} aria-current={active ? 'page' : undefined} aria-label={count ? `${n.label}, ${count} waiting` : undefined}>
                    <I />
                    {n.label}
                    {count > 0 && <span className="nav-count">{countLabel(count)}</span>}
                  </Link>
                );
              })}
            </div>
          ))}
        </nav>
      )}
      <div className="nav-mode">
        <button type="button" className="nav-mode-btn" onClick={() => setSimpleMode(!simple)} aria-pressed={!simple} title={simple ? 'Show every page, grouped. Nothing is hidden for good.' : 'Back to the short, simple menu'}>
          {simple ? <SimpleIcon.Grid /> : <Icon.ChevronRight style={{ transform: 'rotate(180deg)' }} />}
          <span>{simple ? 'Show all tools' : 'Simple menu'}</span>
        </button>
        {simple && (
          <div className="nav-about">
            <Link to="/methodology">How scoring works</Link>
            <span aria-hidden="true">·</span>
            <Link to="/guide">Viewer guide</Link>
          </div>
        )}
      </div>
    </>
  );
}
