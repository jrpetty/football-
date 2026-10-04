/**
 * What simple mode adds above every page: the section's tabs (e.g. Results → Leaderboard | Best on each test |
 * Past runs | History) and, on a first visit, a short dismissible guide. Both hide in Broadcast mode.
 */
import { Link } from '../router.tsx';
import { Icon } from '../components/icons.tsx';
import { helpFor } from '../help/pageHelp.ts';
import { tabsFor, type PageKey } from './nav.ts';
import { SimpleIcon } from './icons.tsx';
import { dismissGuide, dismissedGuides, isSimpleMode, useSimplePrefs } from './prefs.ts';

/** Ask the Help panel to open (it listens for this event). */
export function openHelp(): void {
  window.dispatchEvent(new Event('gauntlet:open-help'));
}

export function SectionTabs({ pageKey }: { pageKey: PageKey }) {
  const t = tabsFor(pageKey);
  if (!t) return null;
  return (
    <nav className="section-tabs no-broadcast" aria-label={`${t.section.label} pages`}>
      {t.tabs.map((tab) => (
        <Link key={tab.to} to={tab.to} title={tab.hint} aria-current={tab === t.current ? 'page' : undefined}>
          {tab.label}
        </Link>
      ))}
    </nav>
  );
}

export function PageGuide({ pageKey }: { pageKey: PageKey }) {
  const dismissed = useSimplePrefs(dismissedGuides);
  const help = helpFor(pageKey);
  if (!help.guide || dismissed.has(pageKey)) return null;
  const { text, next } = help.guide;
  return (
    <aside className="page-guide no-broadcast" role="note" aria-label="Tip for this page">
      <span className="pg-icon" aria-hidden="true">
        <SimpleIcon.Lightbulb />
      </span>
      <div className="pg-text">
        <strong>{help.title}</strong>
        <span>{text}</span>
      </div>
      <div className="pg-actions">
        {next && (
          <Link to={next.to} className="btn sm primary" onClick={() => dismissGuide(pageKey)}>
            {next.label} <Icon.ChevronRight />
          </Link>
        )}
        <button type="button" className="btn sm ghost" onClick={openHelp}>
          More help
        </button>
        <button type="button" className="btn sm ghost icon" onClick={() => dismissGuide(pageKey)} aria-label="Hide this tip" title="Got it: hide this tip">
          <Icon.X />
        </button>
      </div>
    </aside>
  );
}

/** Tabs (simple mode only) and the first-visit guide, above the page. */
export function PageChrome({ pageKey }: { pageKey: PageKey }) {
  const simple = useSimplePrefs(isSimpleMode);
  return (
    <div className="page-chrome no-broadcast">
      {simple && <SectionTabs pageKey={pageKey} />}
      <PageGuide pageKey={pageKey} />
    </div>
  );
}
