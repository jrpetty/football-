/** Publish — export the public leaderboard website and set the channel branding. */
import { useEffect, useState } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { useToast, useViewerCaption } from '../context.tsx';
import { Callout, Card, CopyButton, ErrorState, Field, PageHead, SkeletonRows, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtBytes } from '../format.ts';
import { channelApi, previewUrl, zipUrl, type PublishResult, type SiteConfig } from './channelApi.ts';
import './channel.css';

type Draft = Omit<SiteConfig, 'suites'>;

const FIELDS: Array<{ k: keyof Draft; label: string; hint: string; placeholder?: string; type?: string }> = [
  { k: 'channelName', label: 'Channel name', hint: 'Shown in the header and every page title.' },
  { k: 'tagline', label: 'Tagline', hint: 'One line under the name on the front page.' },
  { k: 'youtubeUrl', label: 'YouTube channel link', hint: 'Adds a red “YouTube” button to every page.', placeholder: 'https://www.youtube.com/@yourchannel' },
  { k: 'logo', label: 'Logo', hint: 'A .png/.jpg/.svg file in the gauntlet folder (e.g. config/logo.png) or an https:// link. Empty = Gauntlet mark.', placeholder: 'config/logo.png' },
  { k: 'siteUrl', label: 'Website address (once online)', hint: 'Optional. Shown in the footer.', placeholder: 'https://yourname.github.io/leaderboard' },
  { k: 'submissionFormUrl', label: 'Viewer challenge form', hint: 'Optional. Adds a “Submit a question” page linking to your Google Form.', placeholder: 'https://forms.gle/…' },
  { k: 'footerNote', label: 'Footer note', hint: 'Optional small print, e.g. “Independent and unsponsored.”' },
];

export default function PublishPage() {
  const toast = useToast();
  const data = useAsync(() => Promise.all([channelApi.site(), api.suites()]), []);
  const [draft, setDraft] = useState<SiteConfig | null>(null);
  const [suites, setSuites] = useState<string[]>([]);
  const [zip, setZip] = useState(false);
  const [busy, setBusy] = useState<'save' | 'publish' | null>(null);
  const [result, setResult] = useState<PublishResult | null>(null);
  useEffect(() => {
    if (data.data && !draft) {
      setDraft(data.data[0]);
      setSuites(data.data[0].suites.filter((s) => data.data![1].some((x) => x.id === s)));
    }
  }, [data.data, draft]);
  useViewerCaption('Exporting the public leaderboard website: plain files anyone can host for free, with every result, test description and price, and nothing that is kept private.');

  if (data.error) return <div className="page"><ErrorState error={data.error} onRetry={data.reload} /></div>;
  if (!data.data || !draft) return <div className="page"><SkeletonRows rows={10} /></div>;
  const allSuites = data.data[1];
  const saved = data.data[0];
  const dirty = JSON.stringify({ ...draft, suites }) !== JSON.stringify(saved);

  const save = async () => {
    setBusy('save');
    try {
      const next = await channelApi.saveSite({ ...draft, suites });
      data.setData([next, allSuites]);
      setDraft(next);
      toast.success('Site settings saved to config/site.json');
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };
  const publish = async () => {
    setBusy('publish');
    try {
      if (dirty) {
        const next = await channelApi.saveSite({ ...draft, suites });
        data.setData([next, allSuites]);
      }
      const r = await channelApi.publish(suites, zip);
      setResult(r);
      toast.success(`${r.pages.length}+ pages written`, 'Website exported');
    } catch (e) {
      toast.error(e, 'Export failed');
    } finally {
      setBusy(null);
    }
  };
  const toggleSuite = (id: string) => setSuites((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  return (
    <div className="page ch-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Upload style={{ width: 14, height: 14 }} /> Channel
          </span>
        }
        title="Publish the leaderboard"
        sub="Turn your results into a fast, mobile-friendly website you can put online for free: no server, no coding."
        actions={
          <button className="btn primary" onClick={() => void publish()} disabled={busy !== null || suites.length === 0}>
            <Icon.Upload /> {busy === 'publish' ? 'Exporting…' : 'Publish website'}
          </button>
        }
      />

      {result && (
        <Card className="ch-result" title={<span className="row" style={{ gap: 8 }}><Icon.Check /> Website ready</span>} desc={`Exported ${new Date(result.generatedAt).toLocaleString('en-GB')}`}>
          <div className="stats">
            <div className="stat">
              <span className="k">Pages</span>
              <span className="v">{result.pages.length}</span>
              <span className="s">{result.files} files · {fmtBytes(result.bytes)}</span>
            </div>
            {result.suites.map((s) => (
              <div className="stat" key={s.id}>
                <span className="k">{s.name}</span>
                <span className="v">{s.models} models</span>
                <span className="s">{s.tests} tests</span>
              </div>
            ))}
            <div className="stat">
              <span className="k">Kept private</span>
              <span className="v">{result.hiddenTests + result.withheldPrompts}</span>
              <span className="s">{result.hiddenTests} held-out · {result.withheldPrompts} prompts withheld</span>
            </div>
          </div>
          <div className="ch-folder">
            <span className="label">Folder</span>
            <code>{result.outDir}</code>
            <CopyButton text={result.outDir} label="Copy path" />
          </div>
          {result.zipPath && (
            <div className="ch-folder">
              <span className="label">Zip</span>
              <code>{result.zipPath}</code>
              <CopyButton text={result.zipPath} label="Copy path" />
            </div>
          )}
          <div className="row wrap" style={{ gap: 10, marginTop: 12 }}>
            <a className="btn primary" href={previewUrl()} target="_blank" rel="noreferrer">
              <Icon.External /> Preview the site
            </a>
            <a className="btn" href={zipUrl(suites)} download>
              <Icon.Download /> Download as .zip
            </a>
          </div>
          {result.warnings.length > 0 && (
            <ul className="ch-warnings">
              {result.warnings.map((w) => (
                <li key={w}>
                  <Icon.Alert /> {w}
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}

      <div className="grid split-7-5">
        <Card title="Channel branding" desc="Saved in config/site.json. Leave anything blank that you don’t need." tools={<button className="btn sm" disabled={!dirty || busy !== null} onClick={() => void save()}>{busy === 'save' ? 'Saving…' : 'Save'}</button>}>
          <div className="form-grid ch-form">
            {FIELDS.map((f) => (
              <Field key={f.k} label={f.label} hint={f.hint}>
                <input className="input" value={String(draft[f.k] ?? '')} placeholder={f.placeholder} onChange={(e) => setDraft({ ...draft, [f.k]: e.target.value })} />
              </Field>
            ))}
            <Field label="Accent colour" hint="Links, buttons and highlights.">
              <span className="row" style={{ gap: 8 }}>
                <input type="color" className="ch-color" value={draft.accentColor} onChange={(e) => setDraft({ ...draft, accentColor: e.target.value.toUpperCase() })} aria-label="Pick accent colour" />
                <input className="input" value={draft.accentColor} onChange={(e) => setDraft({ ...draft, accentColor: e.target.value })} style={{ maxWidth: 130 }} />
              </span>
            </Field>
          </div>
        </Card>

        <div className="stack">
          <Card title="What goes on the site" desc="Pick the leaderboards to publish. Each gets its own tab.">
            <div className="chip-list">
              {allSuites.map((s) => (
                <button key={s.id} type="button" className={cx('toggle-chip', suites.includes(s.id) && 'on')} aria-pressed={suites.includes(s.id)} onClick={() => toggleSuite(s.id)}>
                  <span className="sw" style={{ background: 'var(--accent)' }} />
                  {s.name}
                </button>
              ))}
            </div>
            <div className="row" style={{ gap: 10, marginTop: 14 }}>
              <Switch checked={zip} onChange={setZip} label="Also make a .zip" />
              <span>Also make a .zip (handy for Netlify Drop or email)</span>
            </div>
            <ul className="ch-list">
              <li>Leaderboards with confidence intervals, category heatmap, medals and score vs cost</li>
              <li>A page for every model and every test (with an example prompt)</li>
              <li>Model history, methodology, fingerprints and verified prices</li>
            </ul>
          </Card>
          <Callout tone="plain" icon={<Icon.Lock />}>
            <strong>Never published:</strong> held-out tests in <code>tests/private/</code> (listed only as “Held-out test 1…”), answer keys, case notes, model notes and API settings. Tests or suites with <code>"publishPrompts": false</code> show no example prompt.
          </Callout>
          <Card title="Put it online for free" desc="Step-by-step guide for Windows: docs/PUBLISHING.md">
            <ol className="ch-list">
              <li>
                <strong>Netlify Drop</strong> (2 minutes): open app.netlify.com/drop and drag the <code>site</code> folder onto the page.
              </li>
              <li>
                <strong>GitHub Pages</strong>: create a repository, upload the contents of the <code>site</code> folder, then Settings → Pages → “Deploy from a branch”.
              </li>
            </ol>
          </Card>
        </div>
      </div>
    </div>
  );
}
