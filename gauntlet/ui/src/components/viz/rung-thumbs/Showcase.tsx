/**
 * Review page for the rung thumbnails (dev only: ui/rung-thumbs.html on the Vite dev server, not in the build):
 * every ladder's L1–L10 strip with its rung labels, at a chosen size and tone. `#/?size=32|48|64|96`,
 * `&tone=broadcast` and `&group=horizon|frontier|business` set the view, so the screenshot script
 * (verification/rung-thumbs-screens.mts) can capture each variant.
 */
import { useEffect, useState } from 'react';
import { LADDERS } from '../../../ladders.ts';
import { setQuery, useRoute } from '../../../router.tsx';
import { RungThumb, hasRungThumb, type RungThumbTone } from './index.tsx';
import './showcase.css';

/** The app's theme switch, standalone: <html data-theme> plus the same saved preference. */
function usePageTheme(): { theme: 'dark' | 'light'; setTheme: (t: 'dark' | 'light') => void } {
  const [theme, setTheme] = useState<'dark' | 'light'>(() => (document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark'));
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('gauntlet.theme', theme);
    } catch {
      /* private mode: the toggle still works for this visit */
    }
  }, [theme]);
  return { theme, setTheme };
}

const SIZES = [32, 48, 64, 96];
const GROUPS: Array<{ title: string; match: (id: string) => boolean }> = [
  { title: 'Horizon', match: (id) => id.startsWith('horizon.') },
  { title: 'Frontier Ladders', match: (id) => !id.startsWith('horizon.') && !id.startsWith('work.') },
  { title: 'Business Ladders', match: (id) => id.startsWith('work.') },
];

export default function RungThumbsShowcase() {
  const { query } = useRoute();
  const { theme, setTheme } = usePageTheme();
  const size = SIZES.includes(Number(query.get('size'))) ? Number(query.get('size')) : 48;
  const tone: RungThumbTone = query.get('tone') === 'broadcast' ? 'broadcast' : 'default';
  const only = query.get('group');
  const tests = LADDERS.tests.filter((t) => hasRungThumb(t.id));
  return (
    <div className="rts-page" data-tone={tone}>
      <header className="rts-head">
        <div>
          <h1>Rung thumbnails</h1>
          <p>
            What each level looks like, drawn from its real parameters. {tests.length} ladders × 10 levels. Hover a picture for its full description. (Dev review page, not part of the app.)
          </p>
        </div>
        <div className="rts-controls">
          {SIZES.map((s) => (
            <button key={s} className={s === size ? 'on' : ''} onClick={() => setQuery({ size: s === 48 ? null : s })}>
              {s}px
            </button>
          ))}
          <button className={tone === 'broadcast' ? 'on' : ''} onClick={() => setQuery({ tone: tone === 'broadcast' ? null : 'broadcast' })}>
            Broadcast
          </button>
          <button onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? 'Light' : 'Dark'} theme</button>
        </div>
      </header>
      {GROUPS.filter((g) => !only || g.title.toLowerCase().startsWith(only)).map((g) => (
        <section key={g.title} className="rts-group" data-area={g.title.startsWith('Business') ? 'business' : undefined}>
          <h2>{g.title}</h2>
          {tests
            .filter((t) => g.match(t.id))
            .map((t) => (
              <div key={t.id} className="rts-ladder">
                <div className="rts-name">
                  <strong>{t.name}</strong>
                  <code>{t.id}</code>
                </div>
                <ol className="rts-strip" style={{ gridTemplateColumns: `repeat(10, minmax(${size}px, 1fr))` }}>
                  {Array.from({ length: 10 }, (_, i) => (
                    <li key={i}>
                      <span className="rts-thumb" style={{ width: size, height: size }}>
                        <RungThumb testId={t.id} level={i + 1} size={size} tone={tone} />
                      </span>
                      <span className="rts-lv">L{i + 1}</span>
                      <span className="rts-rung">{t.rungs?.[i] ?? ''}</span>
                    </li>
                  ))}
                </ol>
              </div>
            ))}
        </section>
      ))}
    </div>
  );
}
