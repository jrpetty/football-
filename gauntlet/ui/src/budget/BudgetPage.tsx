/**
 * "My budget": the owner's own spending settings (monthly budget with an optional hard stop, the default run and
 * per-answer limits) and how much this month has cost so far, per day and per run.
 */
import { useState, type MouseEvent } from 'react';
import { useToast, useViewerCaption } from '../context.tsx';
import { Card, ErrorState, FloatingTip, PageHead, SkeletonRows, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { Link, pathOf } from '../router.tsx';
import { CurrencyRate, MoneyLimitPicker } from '../components/SpendLimits.tsx';
import { budgetApi, useBudget, type BudgetItem, type BudgetSettings, type BudgetStatus } from './budgetApi.ts';
import { BudgetMeter, BudgetToneChip, money } from './BudgetParts.tsx';
import './budget.css';

const KIND: Record<BudgetItem['kind'], string> = { run: 'Run', arena: 'Arena', grade: 'Judges', polish: 'Script', other: 'Other' };

function fmtDay(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}

export default function BudgetPage() {
  useViewerCaption('The owner’s monthly budget: how much this month’s tests have cost so far, and the limits new runs start with.');
  const { budget, reload } = useBudget();
  const [err, setErr] = useState<Error | null>(null);
  if (err) return <div className="page"><ErrorState error={err} onRetry={() => (setErr(null), reload())} /></div>;
  if (!budget) return <div className="page"><SkeletonRows rows={8} /></div>;
  return (
    <div className="page budget-page">
      <PageHead
        eyebrow="Lab"
        title="My budget"
        sub="Your own spending limits. Gauntlet adds up what every run, Arena tournament and paid judge call cost this month, and new runs start with your default limits."
        actions={<CurrencyRate />}
      />
      <div className="bud-grid">
        <div className="stack">
          <MonthCard status={budget} />
          <RecentCard status={budget} />
        </div>
        <SettingsCard status={budget} onSaved={reload} />
      </div>
    </div>
  );
}

function MonthCard({ status }: { status: BudgetStatus }) {
  const m = status.settings.monthlyUsd;
  const left = status.remainingUsd;
  return (
    <Card className="bud-month" title={`This month · ${status.month.label}`} desc={`Resets on ${status.month.nextResetLabel} (your computer’s local time)`} tools={<BudgetToneChip status={status} />}>
      <div className="bud-hero">
        <div className="bud-hero-num tnum">
          <b>{money(status.spentUsd)}</b>
          {m !== undefined ? <span> of {money(m)} spent</span> : <span> spent so far</span>}
        </div>
        {m !== undefined && left !== null && (
          <div className={cx('bud-left tnum', status.tone !== 'ok' && `tone-${status.tone}`)}>{left > 0 ? `${money(left)} left` : `${money(-left)} over`}</div>
        )}
      </div>
      {m !== undefined ? (
        <BudgetMeter status={status} lg />
      ) : (
        <p className="muted">No monthly budget yet. Set one on the right (for example £50 a month) to see a meter here and, if you like, stop runs once it is used up.</p>
      )}
      <div className="bud-facts">
        <div>
          <span>Left this month</span>
          <b className="tnum">{left === null ? '—' : money(Math.max(0, left))}</b>
        </div>
        <div>
          <span>Reserved by running jobs</span>
          <b className="tnum">{money(status.committedUsd)}</b>
        </div>
        <div>
          <span>Hard stop</span>
          <b>{status.settings.hardStop ? 'On' : m !== undefined ? 'Off (warnings only)' : 'Off'}</b>
        </div>
        <div>
          <span>Next reset</span>
          <b>{status.month.nextResetLabel}</b>
        </div>
      </div>
      {status.blocked && (
        <div className="bud-note bad" role="alert">
          <Icon.Alert /> <span>The budget is used up: the hard stop blocks new runs and tournaments until {status.month.nextResetLabel}. Raise the monthly budget or turn the hard stop off to carry on now.</span>
        </div>
      )}
      <DayBars status={status} />
    </Card>
  );
}

/** Spending per day of the month: one bar per day, hover for the amount. */
function DayBars({ status }: { status: BudgetStatus }) {
  const [tip, setTip] = useState<{ x: number; y: number; day: string; usd: number } | null>(null);
  const start = new Date(status.month.start);
  const daysIn = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate();
  const byDate = new Map(status.days.map((d) => [d.date, d.spentUsd]));
  const pad = (n: number) => String(n).padStart(2, '0');
  const key = (d: number) => `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(d)}`;
  const max = Math.max(0.01, ...status.days.map((d) => d.spentUsd));
  const today = new Date();
  const isThisMonth = today.getFullYear() === start.getFullYear() && today.getMonth() === start.getMonth();
  const days = Array.from({ length: daysIn }, (_, i) => i + 1);
  return (
    <div className="bud-days">
      <div className="bud-days-head">
        <span className="label">Spending per day</span>
        <span className="muted small tnum">busiest day {money(max)}</span>
      </div>
      <div className="bud-days-plot" onMouseLeave={() => setTip(null)} role="img" aria-label={`Spending per day in ${status.month.label}`}>
        {days.map((d) => {
          const usd = byDate.get(key(d)) ?? 0;
          const future = isThisMonth && d > today.getDate();
          return (
            <div
              key={d}
              className={cx('bud-day', future && 'future', isThisMonth && d === today.getDate() && 'today')}
              onMouseMove={(e: MouseEvent) => setTip({ x: e.clientX, y: e.clientY, day: key(d), usd })}
            >
              {usd > 0 && <span className="bar" style={{ height: `${Math.max(4, (usd / max) * 100)}%` }} />}
            </div>
          );
        })}
      </div>
      <div className="bud-days-axis tnum" aria-hidden="true">
        {days.map((d) => (
          <span key={d}>{d % 7 === 1 ? d : ''}</span>
        ))}
      </div>
      {tip && (
        <FloatingTip x={tip.x} y={tip.y}>
          <b>{new Date(`${tip.day}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })}</b>
          <div className="tnum">{tip.usd > 0 ? `${money(tip.usd)} spent` : 'Nothing spent'}</div>
        </FloatingTip>
      )}
    </div>
  );
}

function RecentCard({ status }: { status: BudgetStatus }) {
  return (
    <Card title="Recent spending" desc="Runs, Arena tournaments and paid judge calls this month. Manual (copy & paste) and Random Baseline contestants cost nothing.">
      {status.items.length === 0 ? (
        <p className="muted">Nothing spent yet this month.</p>
      ) : (
        <ul className="bud-items">
          {status.items.map((i, n) => {
            const to = i.kind === 'run' && i.id ? pathOf('runs', i.id) : i.kind === 'arena' && i.id ? pathOf('arena', i.id) : null;
            const share = status.spentUsd > 0 ? (i.spentUsd / status.spentUsd) * 100 : 0;
            return (
              <li key={`${i.kind}-${i.id ?? n}`}>
                <span className="bud-item-date">{fmtDay(i.at)}</span>
                <span className={cx('bud-kind', `k-${i.kind}`)}>{KIND[i.kind]}</span>
                <span className="bud-item-name">
                  {to ? <Link to={to}>{i.name}</Link> : i.name}
                  {i.active && <span className="badge live">running</span>}
                </span>
                <span className="bud-item-share" aria-hidden="true">
                  <span style={{ width: `${share}%` }} />
                </span>
                <b className="tnum">{money(i.spentUsd)}</b>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

type Draft = { monthlyUsd: number | null; hardStop: boolean; defaultRunUsd: number | null; defaultPerAnswerUsd: number | null };

const draftOf = (s: BudgetSettings): Draft => ({ monthlyUsd: s.monthlyUsd ?? null, hardStop: !!s.hardStop, defaultRunUsd: s.defaultRunUsd ?? null, defaultPerAnswerUsd: s.defaultPerAnswerUsd ?? null });

function SettingsCard({ status, onSaved }: { status: BudgetStatus; onSaved: () => void }) {
  const toast = useToast();
  const [draft, setDraft] = useState<Draft>(() => draftOf(status.settings));
  const [nonce, setNonce] = useState(0);
  const [saving, setSaving] = useState(false);
  const saved = draftOf(status.settings);
  const dirty = JSON.stringify(draft) !== JSON.stringify(saved);
  const set = (p: Partial<Draft>) => setDraft((d) => ({ ...d, ...p }));
  const perTooHigh = draft.defaultPerAnswerUsd !== null && draft.defaultRunUsd !== null && draft.defaultPerAnswerUsd > draft.defaultRunUsd;
  const save = async () => {
    setSaving(true);
    try {
      await budgetApi.save({ ...draft, hardStop: draft.monthlyUsd !== null && draft.hardStop });
      toast.success(draft.monthlyUsd !== null ? `Saved: ${money(draft.monthlyUsd)} a month${draft.hardStop ? ', hard stop on' : ''}` : 'Budget settings saved');
      onSaved();
    } catch (e) {
      toast.error(e as Error, 'Could not save your budget');
    } finally {
      setSaving(false);
    }
  };
  const undo = () => {
    setDraft(saved);
    setNonce((n) => n + 1);
  };
  return (
    <Card
      className="bud-settings"
      title="Your budget settings"
      desc="Type any amount with Custom. Saved on this computer in your Gauntlet settings (kept when you update)."
      foot={
        <div className="row bud-save">
          {dirty && <span className="muted small">Unsaved changes</span>}
          <button type="button" className="btn ghost" onClick={undo} disabled={!dirty || saving}>
            Undo
          </button>
          <button type="button" className="btn primary" onClick={save} disabled={!dirty || saving || perTooHigh}>
            <Icon.Check /> {saving ? 'Saving…' : 'Save budget'}
          </button>
        </div>
      }
    >
      <div className="stack bud-fields" key={nonce}>
        <div className="sl-block">
          <span className="label">Monthly budget</span>
          <MoneyLimitPicker idBase="bud-month" label="Monthly budget" valueUsd={draft.monthlyUsd} onChange={(v) => set({ monthlyUsd: v, ...(v === null ? { hardStop: false } : {}) })} presets={[20, 50, 100, 200]} noneLabel="None" />
          <div className="hint">Everything Gauntlet spends in a calendar month: runs, Arena tournaments, AI judges and script polish.</div>
        </div>
        <div className={cx('bud-hardstop', draft.monthlyUsd === null && 'disabled')}>
          <Switch checked={draft.monthlyUsd !== null && draft.hardStop} onChange={(v) => set({ hardStop: v })} label="Hard stop" disabled={draft.monthlyUsd === null} />
          <div>
            <b>Hard stop</b>
            <div className="hint">
              {draft.monthlyUsd === null
                ? 'Set a monthly budget first.'
                : draft.hardStop
                  ? 'On: once the month’s budget is used up, no run or tournament can start. A new run’s limit is lowered to what is left, so it stops cleanly instead of going over.'
                  : 'Off: you only get warnings when a run could take you over the budget.'}
            </div>
          </div>
        </div>
        <div className="sl-block">
          <span className="label">Default spending limit for a whole run</span>
          <MoneyLimitPicker idBase="bud-run" label="Default whole-run limit" valueUsd={draft.defaultRunUsd} onChange={(v) => set({ defaultRunUsd: v })} presets={[5, 10, 30, 50]} />
          <div className="hint">Pre-selected in New Run and new Arena tournaments. You can still change it for any run.</div>
        </div>
        <div className="sl-block">
          <span className="label">Default per-answer limit</span>
          <MoneyLimitPicker idBase="bud-per" label="Default per-answer limit" valueUsd={draft.defaultPerAnswerUsd} onChange={(v) => set({ defaultPerAnswerUsd: v })} presets={[0.5, 1, 2, 5]} noneLabel="Off" />
          <div className="hint">{perTooHigh ? <span className="bad-text">The per-answer limit cannot be more than the run limit.</span> : 'Optional. Pre-selected in New Run. Off is fairest: a money limit gives cheaper models more room.'}</div>
        </div>
      </div>
    </Card>
  );
}
