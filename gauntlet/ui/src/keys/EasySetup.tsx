/**
 * Easy setup for the API Keys page: the first-run welcome, the one "Paste any API key" box, where keys are kept,
 * the "get a key" guides, and the "one key for everything" (OpenRouter) model map.
 */
import { useEffect, useMemo, useRef, useState, type ClipboardEvent } from 'react';
import { useToast } from '../context.tsx';
import { Card, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { Link } from '../router.tsx';
import { detectProvider, parsePastedKeys } from '../../../src/core/key-detect.ts';
import { keysApi, type KeyGuide, type KeyStatus, type PasteResult, type RoutingStatus, type Storage } from './keysApi.ts';

// ───────────────────────────── helpers ─────────────────────────────

/** "Claude", "GPT", "Gemini": the family word of a model label. */
function family(label: string): string {
  return label.split(/[\s-]/)[0] ?? label;
}

function families(models: Array<{ label: string }>): string[] {
  return [...new Set(models.map((m) => family(m.label)))];
}

function readyLine(r: PasteResult): string {
  const n = r.ready.length;
  if (r.providerId === 'openrouter') {
    const fams = families(r.ready);
    return n ? `${n} models ready through OpenRouter (${fams.slice(0, 5).join(', ')}${fams.length > 5 ? '…' : ''})` : 'Ready: add models on the Models page';
  }
  if (!n) return 'No models set up for this company yet (add them on the Models page)';
  const fams = families(r.ready);
  return `${n} ${fams.length === 1 ? `${fams[0]} ` : ''}${n === 1 ? 'model' : 'models'} ready`;
}

const labelFor = (keys: KeyStatus[], id: string | null | undefined) => keys.find((k) => k.providerId === id)?.label ?? id ?? '';


// ───────────────────────────── where things are kept ─────────────────────────────

export function StorageNote({ storage }: { storage: Storage | undefined }) {
  if (!storage) return null;
  return (
    <div className="es-storage" role="note">
      <Icon.Lock />
      <div>
        <b>
          Your keys are saved on this computer in <code className="es-path">{storage.dir}</code>
        </b>
        {storage.portable ? (
          <span> (portable mode: inside the Gauntlet folder, so keep this folder when you update).</span>
        ) : (
          <span> — updating Gauntlet never deletes them. Your runs and settings are kept there too.</span>
        )}
      </div>
    </div>
  );
}

// ───────────────────────────── the paste box ─────────────────────────────

export function PasteBox({ keys, onSaved, big }: { keys: KeyStatus[]; onSaved: (results: PasteResult[]) => void; big?: boolean }) {
  const toast = useToast();
  const [text, setText] = useState('');
  const [reveal, setReveal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<PasteResult[]>([]);
  const [clipNote, setClipNote] = useState<string | null>(null);
  const [choose, setChoose] = useState<{ text: string; masked: string } | null>(null);
  const box = useRef<HTMLTextAreaElement>(null);

  const found = useMemo(() => parsePastedKeys(text), [text]);
  const hint = useMemo(() => {
    if (!found.length) return null;
    if (found.length > 1) return `${found.length} keys found`;
    const d = detectProvider(found[0]!.key);
    return d.providerId ? `Looks like ${/^[AEIOU]/.test(labelFor(keys, d.providerId)) ? 'an' : 'a'} ${labelFor(keys, d.providerId)} key` : 'We’ll ask which company it’s from';
  }, [found, keys]);

  const submit = async (value = text, provider?: string) => {
    if (!value.trim()) {
      box.current?.focus();
      return;
    }
    setBusy(true);
    setClipNote(null);
    try {
      const r = await keysApi.paste(value, provider);
      const unknown = r.results.find((x) => x.needsChoice);
      // Keep only what still needs the owner: an unknown key goes to "Which company?", the rest are done.
      setChoose(unknown ? { text: parsePastedKeys(value).find((p) => p.line === unknown.line)?.key ?? value, masked: unknown.masked } : null);
      setResults((prev) => (provider ? [...prev.filter((x) => !x.needsChoice), ...r.results] : r.results.filter((x) => !x.needsChoice)));
      setText(r.results.some((x) => !x.saved && !x.needsChoice) ? value : '');
      if (r.results.some((x) => x.saved)) onSaved(r.results);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  const fromClipboard = async () => {
    try {
      if (!navigator.clipboard?.readText) throw new Error('unsupported');
      const t = await navigator.clipboard.readText();
      if (!t.trim()) {
        setClipNote('Your clipboard is empty. Copy the key on the company’s website first (use its copy button).');
        return;
      }
      setText(t);
      void submit(t);
    } catch {
      setClipNote('Your browser didn’t let Gauntlet read the clipboard. Click in the box above and press Ctrl+V (or right-click → Paste).');
      box.current?.focus();
    }
  };

  // Pasting is the whole action: check and save straight away.
  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    const t = e.clipboardData.getData('text');
    if (!t.trim() || !parsePastedKeys(t).length) return;
    e.preventDefault();
    const next = text.trim() ? `${text.trim()}\n${t}` : t;
    setText(next);
    void submit(next);
  };

  return (
    <Card className={cx('es-paste', big && 'is-big')}>
      <div className="es-paste-head">
        <h2>
          <Icon.Key /> Paste any API key
        </h2>
        <p>Gauntlet works out which company it’s from, checks it for free and saves it. Several keys (one per line) or lines from a .env file work too.</p>
      </div>
      <form
        className="es-form"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <textarea
          ref={box}
          className={cx('input es-input', !reveal && 'is-hidden')}
          value={text}
          rows={found.length > 1 ? Math.min(6, found.length + 1) : 2}
          onChange={(e) => setText(e.target.value)}
          onPaste={onPaste}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void submit();
            }
          }}
          placeholder="Click here and press Ctrl+V to paste your key"
          autoComplete="off"
          spellCheck={false}
          aria-label="Paste any API key"
          data-testid="paste-box"
        />
        <div className="es-actions">
          <button type="button" className="btn primary lg" onClick={fromClipboard} disabled={busy}>
            <Icon.Copy /> Paste from clipboard
          </button>
          <button type="submit" className="btn lg" disabled={busy || !text.trim()}>
            {busy ? 'Checking…' : 'Check & save'}
          </button>
          <button type="button" className="btn ghost sm" onClick={() => setReveal((v) => !v)} aria-pressed={reveal}>
            {reveal ? <Icon.EyeOff /> : <Icon.Eye />} {reveal ? 'Hide' : 'Show'}
          </button>
          {hint && !busy && <span className="es-hint">{hint}</span>}
          {busy && <span className="es-hint">Checking with the company (free)…</span>}
        </div>
      </form>
      {clipNote && (
        <div className="es-result warn">
          <Icon.Info /> <span>{clipNote}</span>
        </div>
      )}

      {choose && (
        <div className="es-choose" role="group" aria-label="Which company is this key from?">
          <div className="es-choose-q">
            <b>Which company is this key from?</b> <code>{choose.masked}</code>
          </div>
          <div className="es-choose-grid">
            {keys.map((k) => (
              <button key={k.providerId} type="button" className="btn lg es-choice" disabled={busy} onClick={() => void submit(choose.text, k.providerId)}>
                {k.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {results.length > 0 && (
        <div className="es-results" role="status">
          {results.map((r, i) => (
            <PasteOutcome key={`${r.line}-${i}`} r={r} />
          ))}
        </div>
      )}
    </Card>
  );
}

function PasteOutcome({ r }: { r: PasteResult }) {
  if (r.saved && r.ok) {
    return (
      <div className="es-result good big">
        <Icon.Check />
        <div>
          <b>
            {r.label} key works — {readyLine(r)}
          </b>
          <span className="es-sub">
            Saved on this computer <code>{r.masked}</code>. This check was free.
          </span>
          {r.warning && <span className="es-sub warn-text">{r.warning}</span>}
        </div>
      </div>
    );
  }
  if (r.saved) {
    return (
      <div className="es-result warn big">
        <Icon.Alert />
        <div>
          <b>{r.label} key saved, but it isn’t ready yet</b>
          <span className="es-sub">{r.error}</span>
        </div>
      </div>
    );
  }
  return (
    <div className="es-result bad big">
      <Icon.Alert />
      <div>
        <b>{r.label ? `${r.label} key not saved` : 'Key not saved'}</b>
        <span className="es-sub">{r.error}</span>
        {r.warning && <span className="es-sub">{r.warning}</span>}
      </div>
    </div>
  );
}

// ───────────────────────────── first-run welcome ─────────────────────────────

export function Welcome({ keys, guides, storage, onSaved, done }: { keys: KeyStatus[]; guides: KeyGuide[]; storage?: Storage; onSaved: (r: PasteResult[]) => void; done: boolean }) {
  const or = guides.find((g) => g.providerId === 'openrouter');
  return (
    <div className="es-welcome">
      <div className="es-welcome-head">
        <h2>Welcome to Gauntlet</h2>
        <p>Three steps and you’re testing AI models. No typing, no files.</p>
      </div>
      <ol className="es-steps">
        <li className="es-step">
          <span className="es-num">1</span>
          <div className="es-step-body">
            <h3>Get one key</h3>
            <p>
              We recommend <b>OpenRouter</b>: one key for every AI (Claude, GPT, Gemini, Grok, DeepSeek) and one bill. {or?.credit.split('.')[0]}.
            </p>
            {or && (
              <a className="btn primary lg" href={or.url} target="_blank" rel="noreferrer noopener">
                Get an OpenRouter key <Icon.External />
              </a>
            )}{' '}
            <a className="btn ghost" href="#es-guides" onClick={(e) => (e.preventDefault(), document.getElementById('es-guides')?.scrollIntoView({ behavior: 'smooth' }))}>
              Step-by-step help
            </a>
          </div>
        </li>
        <li className="es-step is-wide">
          <span className="es-num">2</span>
          <div className="es-step-body">
            <h3>Paste it here</h3>
            <PasteBox keys={keys} onSaved={onSaved} big />
            <StorageNote storage={storage} />
          </div>
        </li>
        <li className={cx('es-step', !done && 'is-waiting')}>
          <span className="es-num">3</span>
          <div className="es-step-body">
            <h3>Try it</h3>
            <p>Watch a demo with sample results (free, no key needed), or run a real test on two cheap models: it costs pennies, and the exact cost is shown before you start.</p>
            <div className="row wrap" style={{ gap: 10 }}>
              <a className="btn lg" href="?mock=1#/" target="_blank" rel="noreferrer">
                <Icon.Play /> Free demo
              </a>
              <Link className={cx('btn lg', done && 'primary')} to="/run/new?suite=quick&models=cheap" aria-disabled={!done}>
                <Icon.Rocket /> Cheap test run
              </Link>
            </div>
          </div>
        </li>
      </ol>
    </div>
  );
}

// ───────────────────────────── "get a key" guides ─────────────────────────────

export function Guides({ guides, keys }: { guides: KeyGuide[]; keys: KeyStatus[] }) {
  const set = new Set(keys.filter((k) => k.set).map((k) => k.providerId));
  return (
    <section id="es-guides" className="es-guides">
      <h2>How to get a key</h2>
      <p className="muted">Pick one company. Each guide opens that company’s own key page in a new tab; come back here and paste.</p>
      <div className="es-guide-list">
        {guides.map((g) => (
          <details key={g.providerId} className={cx('es-guide', g.recommended && 'is-rec')} open={g.recommended && !set.size}>
            <summary>
              <span className="es-guide-name">
                {g.name}
                {g.recommended && <span className="badge good">Easiest</span>}
                {set.has(g.providerId) && (
                  <span className="badge outline">
                    <Icon.Check /> connected
                  </span>
                )}
              </span>
              <span className="es-guide-blurb">{g.blurb}</span>
            </summary>
            <div className="es-guide-body">
              <ol>
                {g.steps.map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ol>
              <p className="es-credit">
                <Icon.Dollar /> {g.credit}
              </p>
              <div className="row wrap" style={{ gap: 8 }}>
                <a className="btn primary" href={g.url} target="_blank" rel="noreferrer noopener">
                  Open {g.name.split(' (')[0]}’s key page <Icon.External />
                </a>
                {g.creditUrl && (
                  <a className="btn" href={g.creditUrl} target="_blank" rel="noreferrer noopener">
                    Add credit <Icon.External />
                  </a>
                )}
              </div>
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}

// ───────────────────────────── one key for everything ─────────────────────────────

function priceText(p: { inputPerM: number; outputPerM: number } | undefined): string {
  if (!p) return '—';
  const f = (v: number) => (v >= 1 ? `$${v.toFixed(v % 1 ? 2 : 0)}` : `$${v.toFixed(2)}`);
  return `${f(p.inputPerM)} / ${f(p.outputPerM)}`;
}

export function OpenRouterPanel({ reloadKey }: { reloadKey: number }) {
  const toast = useToast();
  const [st, setSt] = useState<RoutingStatus | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let alive = true;
    keysApi
      .openrouter()
      .then((s) => alive && setSt(s))
      .catch(() => alive && setSt(null));
    return () => {
      alive = false;
    };
  }, [reloadKey]);
  if (!st || !st.hasKey) return null;

  const setRouting = async (on: boolean) => {
    setBusy(true);
    try {
      setSt(await keysApi.openrouterRouting(on ? 'auto' : 'off'));
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };
  const refresh = async () => {
    setBusy(true);
    try {
      setSt(await keysApi.openrouterRefresh());
      toast.success('OpenRouter’s model list is up to date');
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };
  const routed = st.rows.filter((r) => r.routed).length;
  const direct = st.rows.filter((r) => r.directKey).length;
  return (
    <Card className="es-or">
      <div className="es-or-head">
        <div>
          <h2>One key for everything: OpenRouter</h2>
          <p className="muted">
            Models whose own company isn’t connected run through OpenRouter. A company’s own key always wins.{' '}
            {st.active ? (
              <b>
                {routed} {routed === 1 ? 'model runs' : 'models run'} via OpenRouter{direct ? `, ${direct} directly` : ''}.
              </b>
            ) : (
              <b>Switched off: only directly connected models can run.</b>
            )}
          </p>
        </div>
        <label className="es-or-switch">
          <Switch checked={st.active} onChange={(v) => void setRouting(v)} disabled={busy} label="Use OpenRouter for models without their own key" />
          <span>Use OpenRouter</span>
        </label>
      </div>
      <div className="table-wrap">
        <table className="table compact es-or-table">
          <thead>
            <tr>
              <th>Model</th>
              <th>OpenRouter model</th>
              <th>How it runs</th>
              <th className="num" title="USD per 1M input / output tokens">OpenRouter price (in / out)</th>
            </tr>
          </thead>
          <tbody>
            {st.rows.map((r) => (
              <tr key={r.id} className={cx(!r.slug && 'is-none')}>
                <td>
                  <b>{r.label}</b>
                  <div className="muted" style={{ fontSize: '0.76rem' }}>
                    {r.vendor}
                  </div>
                </td>
                <td>
                  {r.slug ? (
                    <span className="es-slug">
                      → <code>{r.slug}</code> <Icon.Check />
                    </span>
                  ) : (
                    <span className="muted">not available via OpenRouter</span>
                  )}
                </td>
                <td>
                  {r.directKey ? (
                    <span className="badge good">Direct ({r.providerLabel} key)</span>
                  ) : r.routed ? (
                    <span className="badge info">via OpenRouter</span>
                  ) : (
                    <span className="badge outline">can’t run yet</span>
                  )}
                </td>
                <td className="num tnum">{priceText(r.openRouterPrice)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="es-or-foot">
        <span className="muted">
          Costs use OpenRouter’s listed prices (plus its small fee when you buy credit). Results that ran via OpenRouter are labelled on the run page and the
          Presenter, and kept on their own leaderboard row. For results you publish, a company’s own key is the gold standard.
          {st.catalogFetchedAt ? ` Model list from ${new Date(st.catalogFetchedAt).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} (${st.catalogSize} models).` : ''}
        </span>
        <button type="button" className="btn sm ghost" onClick={refresh} disabled={busy}>
          <Icon.Refresh /> Refresh list
        </button>
      </div>
    </Card>
  );
}
