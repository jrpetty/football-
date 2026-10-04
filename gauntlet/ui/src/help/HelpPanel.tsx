/**
 * The floating "?" button on every page and the Help side panel it opens: what the page is for, how to use
 * it step by step, common questions and where to read more. Hidden in Broadcast mode so recordings stay clean.
 */
import { useEffect, useState } from 'react';
import { Drawer } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { Link } from '../router.tsx';
import { useToast } from '../context.tsx';
import { SimpleIcon } from '../simple/icons.tsx';
import { isSimpleMode, resetTips, setSimpleMode, useSimplePrefs } from '../simple/prefs.ts';
import type { PageKey } from '../simple/nav.ts';
import { helpFor } from './pageHelp.ts';

export function HelpButton({ pageKey }: { pageKey: PageKey }) {
  const [open, setOpen] = useState(false);
  const simple = useSimplePrefs(isSimpleMode);
  const toast = useToast();
  const help = helpFor(pageKey);

  useEffect(() => {
    const on = () => setOpen(true);
    window.addEventListener('gauntlet:open-help', on);
    return () => window.removeEventListener('gauntlet:open-help', on);
  }, []);
  // A new page closes the panel.
  useEffect(() => setOpen(false), [pageKey]);

  return (
    <>
      <button type="button" className="help-fab no-broadcast" onClick={() => setOpen(true)} aria-label="Help for this page" title="Help for this page">
        <SimpleIcon.Question />
        <span>Help</span>
      </button>
      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        title={help.title}
        sub="Help for this page"
        width={460}
        foot={
          <div className="help-foot">
            <button
              type="button"
              className="btn sm"
              onClick={() => {
                resetTips();
                toast.success('Tips will show again on every page.');
              }}
            >
              <SimpleIcon.Lightbulb /> Show tips again
            </button>
            <button type="button" className="btn sm ghost" onClick={() => setSimpleMode(!simple)}>
              <SimpleIcon.Grid /> {simple ? 'Show all tools' : 'Simple menu'}
            </button>
          </div>
        }
      >
        <div className="help-panel">
          <p className="help-purpose">{help.purpose}</p>

          {help.steps.length > 0 && (
            <section>
              <h3>How to use it</h3>
              <ol className="help-steps">
                {help.steps.map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ol>
            </section>
          )}

          {help.faqs.length > 0 && (
            <section>
              <h3>Common questions</h3>
              <div className="help-faqs">
                {help.faqs.map((f) => (
                  <details key={f.q}>
                    <summary>{f.q}</summary>
                    <p>{f.a}</p>
                  </details>
                ))}
              </div>
            </section>
          )}

          {(help.links.length > 0 || help.doc) && (
            <section>
              <h3>Go further</h3>
              <ul className="help-links">
                {help.links.map((l) => (
                  <li key={l.to}>
                    <Link to={l.to} onClick={() => setOpen(false)}>
                      {l.label} <Icon.ChevronRight />
                    </Link>
                  </li>
                ))}
              </ul>
              {help.doc && (
                <p className="help-doc">
                  <Icon.Book />
                  <span>
                    The full guide is in your Gauntlet folder: <code>{help.doc.file}</code>, section “{help.doc.section}”.
                  </span>
                </p>
              )}
            </section>
          )}

          <p className="help-tip">
            <kbd>B</kbd> Broadcast mode hides this button, the tips and the checklist, so your recordings stay clean.
          </p>
        </div>
      </Drawer>
    </>
  );
}
