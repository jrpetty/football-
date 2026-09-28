/**
 * "My budget" building blocks: the nav icon, the spend-this-month meter and the compact line shown on New Run and
 * the Arena's new-tournament page ("This month: £12.40 of £50 spent · this run up to £8.20").
 */
import type { SVGProps } from 'react';
import { Link } from '../router.tsx';
import { Icon } from '../components/icons.tsx';
import { cx } from '../components/ui.tsx';
import { budgetLine, budgetMoney, floorCents, type BudgetStatus } from '../../../src/budget/budget.ts';
import { displayCurrency } from '../money.ts';
import './budget.css';

/** Budget amounts in the display currency: "£50" for whole amounts, "£12.40" otherwise. */
export const money = (usd: number): string => budgetMoney(usd, displayCurrency());

/** A wallet: the Budget nav item (same 24×24 stroke style as components/icons.tsx). */
export function BudgetIcon(p: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...p}>
      <path d="M19 7V5a2 2 0 0 0-2-2H5a2 2 0 0 0 0 4h14a2 2 0 0 1 2 2v3" />
      <path d="M3 5v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-3" />
      <path d="M21 12h-4a2 2 0 0 0 0 4h4v-4Z" />
    </svg>
  );
}

const TONE_LABEL: Record<BudgetStatus['tone'], string> = { none: 'No monthly budget', ok: 'On track', warn: 'Nearly used up', over: 'Used up' };

/** Spent / budget bar: green, amber from 80 %, red at 100 %, with a tick at 80 %. Always labelled in words too. */
export function BudgetMeter({ status, lg }: { status: BudgetStatus; lg?: boolean }) {
  const m = status.settings.monthlyUsd;
  if (m === undefined) return null;
  const pct = Math.min(100, (status.fraction ?? 0) * 100);
  const committedPct = Math.min(100 - pct, (status.committedUsd / m) * 100);
  return (
    <div className={cx('bud-meter', `tone-${status.tone}`, lg && 'lg')} role="meter" aria-valuemin={0} aria-valuemax={m} aria-valuenow={status.spentUsd} aria-label={`${money(status.spentUsd)} of ${money(m)} spent this month (${TONE_LABEL[status.tone]})`}>
      <div className="bud-track">
        <span className="bud-fill" style={{ width: `${pct}%` }} />
        {committedPct > 0.2 && <span className="bud-committed" style={{ left: `${pct}%`, width: `${committedPct}%` }} title={`${money(status.committedUsd)} reserved by running jobs`} />}
        <span className="bud-tick" style={{ left: '80%' }} aria-hidden="true" />
      </div>
    </div>
  );
}

export function BudgetToneChip({ status }: { status: BudgetStatus }) {
  if (status.tone === 'none') return null;
  return (
    <span className={cx('badge', status.tone === 'ok' ? 'good' : status.tone === 'warn' ? 'warn' : 'bad')}>
      {status.tone === 'ok' ? <Icon.Check /> : <Icon.Alert />}
      {TONE_LABEL[status.tone]}
    </span>
  );
}

/** What the hard stop will do to a run's limit: the new cap when it lowers it, else null. */
export function budgetClamp(status: BudgetStatus | null, capUsd: number | null): number | null {
  if (!status?.settings.hardStop || status.availableUsd === null || status.blocked) return null;
  const left = floorCents(status.availableUsd);
  return capUsd === null || capUsd > left ? left : null;
}

/** A New Run / Arena blocker when the hard stop applies (null otherwise). */
export function budgetBlocker(status: BudgetStatus | null): string | null {
  if (!status?.blocked || status.settings.monthlyUsd === undefined) return null;
  return `Your ${money(status.settings.monthlyUsd)} monthly budget is used up. The hard stop blocks new runs and tournaments until ${status.month.nextResetLabel}; raise the budget or turn the hard stop off on the Budget page.`;
}

/**
 * The compact budget line for New Run and the Arena: the month so far, what this run may spend, and a warning when
 * the run's upper-bound estimate is more than what is left. Owner-only information, so hidden in Broadcast mode.
 */
export function BudgetRunLine({ status, capUsd, upperUsd, what = 'run', showBlocked = true }: { status: BudgetStatus | null; capUsd: number | null; upperUsd?: number | null; what?: 'run' | 'tournament'; /** False when the page lists the block elsewhere (New Run's blockers). */ showBlocked?: boolean }) {
  if (!status) return null;
  const s = status.settings;
  const clamp = budgetClamp(status, capUsd);
  const left = status.availableUsd;
  const effCap = clamp ?? capUsd;
  const over = s.monthlyUsd !== undefined && left !== null && !status.blocked && upperUsd != null && upperUsd > left;
  const stopsInTime = effCap !== null && left !== null && effCap <= left + 0.005;
  return (
    <div className={cx('bud-line', `tone-${status.tone}`)} data-dev>
      <div className="bud-line-head">
        <BudgetIcon className="bud-line-ico" />
        <span className="bud-line-text tnum">{budgetLine(status, displayCurrency(), capUsd, what)}</span>
        <Link to="/budget" className="bud-line-link">
          {s.monthlyUsd === undefined ? 'Set a monthly budget' : 'My budget'} <Icon.ChevronRight />
        </Link>
      </div>
      <BudgetMeter status={status} />
      {showBlocked && status.blocked && s.monthlyUsd !== undefined && (
        <div className="bud-note bad" role="alert">
          <Icon.Alert /> <span>{budgetBlocker(status)}</span>
        </div>
      )}
      {clamp !== null && s.monthlyUsd !== undefined && (
        <div className="bud-note info">
          <Icon.Info /> <span>Limited to {money(clamp)}: what’s left of your {money(s.monthlyUsd)} monthly budget. The {what} stops cleanly if it runs out, keeps every finished result and can be resumed next month.</span>
        </div>
      )}
      {over && s.monthlyUsd !== undefined && left !== null && (
        <div className="bud-note warn">
          <Icon.Alert />
          <span>
            The upper-bound estimate ({money(upperUsd!)}) is more than the {money(left)} left this month.{' '}
            {stopsInTime ? `It stops cleanly at its ${money(effCap!)} limit, so it may not finish this month.` : `Nothing stops it at that point: with the hard stop off and a higher limit, this ${what} could take you over budget.`}
          </span>
        </div>
      )}
    </div>
  );
}
