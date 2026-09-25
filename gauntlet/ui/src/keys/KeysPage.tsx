/** API Keys: paste a provider key, save it, and it's checked for free straight away. No files to edit, no restart. */
import { useState } from 'react';
import { useAsync } from '../hooks.ts';
import { useToast, useViewerCaption } from '../context.tsx';
import { Callout, Card, ErrorState, PageHead, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { Link } from '../router.tsx';
import { keysApi, type KeyCheck, type KeyStatus } from './keysApi.ts';
import './keys.css';

export default function KeysPage() {
  const data = useAsync(() => keysApi.list(), []);
  const [showAll, setShowAll] = useState(false);
  useViewerCaption('Connecting AI companies: each key is checked for free the moment it is saved, and stays on this computer.');

  if (data.error) return <div className="page"><ErrorState error={data.error} onRetry={data.reload} /></div>;
  if (!data.data) return <div className="page"><SkeletonRows rows={8} /></div>;

  const keys = data.data.keys;
  const update = (next: KeyStatus) => data.setData({ ...data.data!, keys: keys.map((k) => (k.providerId === next.providerId ? next : k)) });
  const connected = keys.filter((k) => k.set);
  const used = keys.filter((k) => k.models.length > 0 || k.set);
  const others = keys.filter((k) => !used.includes(k));
  const ready = connected.reduce((n, k) => n + k.models.length, 0);

  return (
    <div className="page keys-page">
      <PageHead
        eyebrow="Setup"
        title="API Keys"
        sub="Paste a key and press Save. Gauntlet checks it straight away (free) and keeps it only on this computer, in the gauntlet/.env file. No restart needed."
      />

      <div className="keys-summary">
        <div className="ks-big">
          <b className="tnum">{connected.length}</b>
          <span>{connected.length === 1 ? 'company connected' : 'companies connected'}</span>
        </div>
        <div className="ks-big">
          <b className="tnum">{ready}</b>
          <span>{ready === 1 ? 'model ready to test' : 'models ready to test'}</span>
        </div>
        <div className="ks-note">
          {connected.length === 0 && <>Start with one company you already use. Anthropic, OpenAI and Google are the most popular.</>}
          {connected.length === 1 && (
            <>
              Add <b>one more company</b> too: the AI judges never mark their own company’s models, so judge-scored tests (Honesty, games, drawings, debates)
              need at least two.
            </>
          )}
          {connected.length >= 2 && (
            <>
              You’re set up. Next: <Link to="/costs">check what a run costs</Link>, then <Link to="/run/new">start one</Link> with a spending cap.
            </>
          )}
        </div>
      </div>

      <div className="keys-grid">
        {used.map((k) => (
          <KeyCard key={k.providerId} k={k} onChange={update} />
        ))}
      </div>

      {others.length > 0 && (
        <div className="keys-more">
          <button type="button" className="btn ghost" onClick={() => setShowAll((v) => !v)} aria-expanded={showAll}>
            {showAll ? 'Hide' : 'Show'} {others.length} more providers ({others.map((k) => k.label).join(', ')})
          </button>
          {showAll && (
            <div className="keys-grid">
              {others.map((k) => (
                <KeyCard key={k.providerId} k={k} onChange={update} />
              ))}
            </div>
          )}
        </div>
      )}

      <Callout tone="plain" icon={<Icon.Lock />}>
        <b>Keep keys secret.</b> Anyone with a key can spend your credit. Gauntlet never shows a full key again after you save it, never sends it
        anywhere except that company’s own API, and only accepts key changes from this computer. Don’t show the <code>.env</code> file on camera. If a key
        leaks, delete it on the company’s site and make a new one.
      </Callout>
    </div>
  );
}

function KeyCard({ k, onChange }: { k: KeyStatus; onChange: (k: KeyStatus) => void }) {
  const toast = useToast();
  const [value, setValue] = useState('');
  const [reveal, setReveal] = useState(false);
  const [editing, setEditing] = useState(!k.set);
  const [busy, setBusy] = useState<null | 'save' | 'test' | 'send' | 'remove'>(null);
  const [check, setCheck] = useState<KeyCheck | null>(null);
  const [warning, setWarning] = useState<string | undefined>();

  const save = async () => {
    setBusy('save');
    setCheck(null);
    try {
      const r = await keysApi.save(k.providerId, value);
      onChange(r.status);
      setCheck(r.saved === false ? { ...r.check, error: `${r.check.error ?? 'The provider rejected this key.'} It was not saved.` } : r.check);
      setWarning(r.warning);
      if (r.saved === false) return; // keep the pasted text so it can be fixed
      setValue('');
      setEditing(false);
      if (r.check.ok) toast.success(`${k.label} key saved and working`);
      else toast.success(`${k.label} key saved (it couldn’t be checked right now)`);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };
  const test = async (send: boolean) => {
    setBusy(send ? 'send' : 'test');
    try {
      setCheck(await keysApi.test(k.providerId, send));
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };
  const remove = async () => {
    if (!window.confirm(`Remove the ${k.label} key from this computer?`)) return;
    setBusy('remove');
    try {
      const r = await keysApi.remove(k.providerId);
      onChange(r.status);
      setCheck(null);
      setWarning(undefined);
      setEditing(true);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };

  const state = !k.set ? 'missing' : check && !check.ok ? 'failing' : 'ok';
  return (
    <Card className={cx('key-card', `is-${state}`)}>
      <div className="kc-head">
        <div>
          <h2>{k.label}</h2>
          <div className="kc-models">
            {k.models.length ? <>Unlocks {k.models.length} {k.models.length === 1 ? 'model' : 'models'}: {k.models.map((m) => m.label).join(', ')}</> : 'No models set up for this provider yet (add them on the Models page).'}
          </div>
        </div>
        {state === 'ok' && <span className="badge good"><Icon.Check /> Connected</span>}
        {state === 'failing' && <span className="badge bad"><Icon.Alert /> Not working</span>}
        {state === 'missing' && <span className="badge outline">Not connected</span>}
      </div>

      {k.set && !editing && (
        <div className="kc-current">
          <code className="kc-mask">{k.masked}</code>
          <span className="kc-src">{k.source === 'system' ? 'set outside Gauntlet (system environment)' : 'saved on this computer'}</span>
          <div className="kc-actions">
            <button type="button" className="btn sm" onClick={() => test(false)} disabled={busy !== null}>
              {busy === 'test' ? 'Checking…' : 'Check key (free)'}
            </button>
            <button type="button" className="btn sm" onClick={() => test(true)} disabled={busy !== null || k.models.length === 0} title="Sends “Reply with one word: pong” to the cheapest model: a fraction of a cent">
              {busy === 'send' ? 'Sending…' : 'Send a test message (< 1¢)'}
            </button>
            <button type="button" className="btn sm ghost" onClick={() => setEditing(true)} disabled={busy !== null}>
              Replace
            </button>
            {k.source === 'file' && (
              <button type="button" className="btn sm ghost danger" onClick={remove} disabled={busy !== null}>
                Remove
              </button>
            )}
          </div>
        </div>
      )}

      {editing && (
        <div className="kc-edit">
          <ol className="kc-steps">
            <li>
              {k.getKeyUrl ? (
                <a className="btn sm primary" href={k.getKeyUrl} target="_blank" rel="noreferrer noopener">
                  Get {/^([AEIOU]|x[A-Z])/.test(k.label) ? 'an' : 'a'} {k.label} key <Icon.External />
                </a>
              ) : (
                <>Create a key on {k.label}’s website.</>
              )}
            </li>
            {k.steps && <li className="kc-how">{k.steps}</li>}
            <li>Copy the key and paste it here:</li>
          </ol>
          <form
            className="kc-form"
            onSubmit={(e) => {
              e.preventDefault();
              if (value.trim()) void save();
            }}
          >
            <input
              className="input tnum"
              type={reveal ? 'text' : 'password'}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={`Paste your ${k.label} key`}
              autoComplete="off"
              spellCheck={false}
              aria-label={`${k.label} API key`}
            />
            <button type="button" className="btn sm ghost" onClick={() => setReveal((v) => !v)} aria-pressed={reveal}>
              {reveal ? 'Hide' : 'Show'}
            </button>
            <button type="submit" className="btn primary" disabled={!value.trim() || busy !== null}>
              {busy === 'save' ? 'Saving & checking…' : 'Save'}
            </button>
            {k.set && (
              <button type="button" className="btn ghost" onClick={() => { setEditing(false); setValue(''); }}>
                Cancel
              </button>
            )}
          </form>
        </div>
      )}

      {warning && <div className="kc-result warn"><Icon.Alert /> {warning}</div>}
      {check && (
        <div className={cx('kc-result', check.ok ? 'good' : 'bad')} role="status">
          {check.ok ? <Icon.Check /> : <Icon.Alert />}
          {check.ok
            ? check.text !== undefined
              ? <>Test message answered: “{check.text.trim().slice(0, 40)}”{check.totalMs ? ` in ${(check.totalMs / 1000).toFixed(1)} s` : ''}{check.costUsd !== undefined ? `, cost $${check.costUsd.toFixed(4)}` : ''}.</>
              : <>Key works. {check.models ? `${check.models} models visible.` : ''} This check was free.</>
            : <>{check.error}</>}
        </div>
      )}
    </Card>
  );
}
