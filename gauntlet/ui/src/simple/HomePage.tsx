/**
 * Home: a friendly start page. A "Getting started" checklist worked out from the real app (keys, runs, what
 * has been opened), big "What do you want to do?" cards, and an at-a-glance row (latest run, money spent
 * this month, what is waiting in the copy & paste inbox). The checklist hides in Broadcast mode.
 */
import type { ComponentType, ReactNode } from 'react';
import { MOCK, api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { Icon } from '../components/icons.tsx';
import { Progress, RunStatusBadge, Skeleton, cx } from '../components/ui.tsx';
import { fmtRelative, pluralize } from '../format.ts';
import { keysApi } from '../keys/keysApi.ts';
import { budgetApi, type BudgetStatus } from '../budget/budgetApi.ts';
import { money } from '../budget/BudgetParts.tsx';
import type { ManualRequest, RunListItem } from '../types.ts';
import { deriveChecklist, type Checklist, type ChecklistStep } from './checklist.ts';
import { SimpleIcon } from './icons.tsx';
import { hasSeen, isChecklistHidden, setChecklistHidden, useSimplePrefs } from './prefs.ts';
import { VersusIcon } from '../versus/VersusIcon.tsx';

interface ActionCard {
  to: string;
  title: string;
  text: string;
  icon: ComponentType<{ className?: string }>;
  tone: string;
}

const CARDS: ActionCard[] = [
  { to: '/run/new', title: 'Test AI models', text: 'Give models the same questions and see who does best.', icon: SimpleIcon.Play, tone: 'c1' },
  { to: '/versus', title: 'Compare two models', text: 'Head to Head: two models side by side, question by question.', icon: VersusIcon, tone: 'c2' },
  { to: '/run/new?copy=1', title: 'Test a chatbot by copy & paste', text: 'No API key? Copy each question into its chat and paste the reply back.', icon: SimpleIcon.Clipboard, tone: 'c3' },
  { to: '/leaderboard', title: 'See the leaderboard', text: 'The overall ranking, with a score out of 100.', icon: Icon.Trophy, tone: 'c4' },
  { to: '/present', title: 'Make a video', text: 'Turn a run into full-screen slides that explain themselves.', icon: Icon.Clapper, tone: 'c5' },
  { to: '/budget', title: 'Check my spending', text: 'What you’ve spent this month, and your monthly limit.', icon: SimpleIcon.Wallet, tone: 'c6' },
];

export default function HomePage() {
  const setup = useAsync(() => keysApi.setup(), []);
  const runs = useAsync<RunListItem[]>(() => api.runs(), []);
  const inbox = useAsync<ManualRequest[]>(() => api.manualQueue(), []);
  const budgetState = useAsync<BudgetStatus>(() => budgetApi.get(), []);
  const budget = budgetState.data;
  const seenResults = useSimplePrefs(() => MOCK || hasSeen('results'));
  const seenPresenter = useSimplePrefs(() => !MOCK && hasSeen('presenter'));
  const hidden = useSimplePrefs(isChecklistHidden);

  const list = deriveChecklist({
    anyKey: setup.data ? setup.data.anyKey : setup.error ? false : null,
    runs: runs.data ?? (runs.error ? [] : null),
    seenResults,
    seenPresenter,
  });
  const settingUp = !list.loading && (!list.steps[0]!.done || !list.steps[1]!.done);
  const showChecklist = !(list.allDone && hidden);

  return (
    <div className="page home-page">
      <header className="home-hero">
        <div className="hh-text">
          <div className="eyebrow">Gauntlet · AI benchmark lab</div>
          <h1>{list.loading ? 'Welcome' : settingUp ? 'Let’s get you set up' : 'Welcome back'}</h1>
          <p className="sub">Gauntlet gives AI models the same questions, marks every answer fairly, and turns the results into slides you can record for a video.</p>
        </div>
      </header>

      {showChecklist && <GettingStarted list={list} />}

      <section aria-labelledby="home-do">
        <h2 id="home-do" className="home-h2">
          What do you want to do?
        </h2>
        <div className="home-cards">
          {CARDS.map((c) => {
            const I = c.icon;
            return (
              <Link key={c.to} to={c.to} className={cx('home-card', c.tone)}>
                <span className="hc-art" aria-hidden="true">
                  <I />
                </span>
                <span className="hc-text">
                  <strong>{c.title}</strong>
                  <span>{c.text}</span>
                </span>
                <Icon.ChevronRight className="hc-go" />
              </Link>
            );
          })}
        </div>
      </section>

      <section aria-labelledby="home-glance">
        <h2 id="home-glance" className="home-h2">
          At a glance
        </h2>
        <div className="home-glance">
          <LatestRun runs={runs.data} loading={runs.loading && !runs.data} />
          <Glance
            icon={<SimpleIcon.Wallet />}
            label={budget ? `Spent in ${budget.month.label}` : 'Spent this month'}
            value={budget ? money(budget.spentUsd) : budgetState.loading ? <Skeleton h={30} w={90} /> : '—'}
            link={{ to: '/budget', label: budget?.settings.monthlyUsd !== undefined ? 'See spending' : 'Set a monthly limit' }}
          >
            {budget && budget.settings.monthlyUsd !== undefined ? (
              <>
                <Progress value={budget.fraction ?? 0} label="Share of this month’s budget used" />
                <span>
                  of your {money(budget.settings.monthlyUsd)} monthly limit
                  {budget.remainingUsd !== null && budget.remainingUsd > 0 ? ` · ${money(budget.remainingUsd)} left` : ''}
                </span>
              </>
            ) : (
              <span>No monthly limit set yet.</span>
            )}
          </Glance>
          <Glance
            icon={<SimpleIcon.Clipboard />}
            label="Copy & paste inbox"
            value={inbox.data ? (inbox.data.length ? pluralize(inbox.data.length, 'question') : 'Nothing waiting') : <Skeleton h={30} w={90} />}
            link={inbox.data?.length ? { to: '/inbox', label: 'Answer them' } : { to: '/run/new?copy=1', label: 'Start a copy & paste test' }}
            tone={inbox.data?.length ? 'live' : undefined}
          >
            <span>{inbox.data?.length ? 'waiting for you to paste a chatbot’s reply.' : 'Questions appear here during a copy & paste test.'}</span>
          </Glance>
        </div>
      </section>
    </div>
  );
}

function GettingStarted({ list }: { list: Checklist }) {
  if (list.loading) {
    return (
      <section className="card home-checklist no-broadcast" aria-busy="true">
        <Skeleton h={120} r={12} />
      </section>
    );
  }
  const next = list.next;
  return (
    <section className="card home-checklist no-broadcast" aria-labelledby="gs-title">
      <div className="gs-head">
        <div>
          <h2 id="gs-title">{list.allDone ? 'You’re all set' : 'Getting started'}</h2>
          <p className="muted">{list.allDone ? 'You’ve done every step. Gauntlet is ready for your next video.' : 'Four quick steps. Your next one is below.'}</p>
        </div>
        <div className="gs-progress">
          <span className="tnum">
            {list.doneCount} of {list.steps.length} done
          </span>
          <Progress value={list.doneCount / list.steps.length} label="Getting started progress" />
        </div>
      </div>

      {next && <NextStep step={next} />}

      <ol className="gs-steps">
        {list.steps.map((s) => (
          <li key={s.id} className={cx(s.done && 'done', next?.id === s.id && 'current')}>
            <span className="gs-mark" aria-hidden="true">
              {s.done ? <Icon.Check /> : s.n}
            </span>
            <span className="gs-title">{s.title}</span>
            <span className="sr-only">{s.done ? '(done)' : next?.id === s.id ? '(next)' : '(to do)'}</span>
            {s.done ? <span className="gs-state">Done</span> : next?.id !== s.id ? <Link to={s.cta.to} className="gs-link">{s.cta.label}</Link> : <span className="gs-state now">Next</span>}
          </li>
        ))}
      </ol>

      {list.allDone && (
        <div className="gs-foot">
          <button type="button" className="btn sm ghost" onClick={() => setChecklistHidden(true)}>
            Hide this list
          </button>
        </div>
      )}
    </section>
  );
}

function NextStep({ step }: { step: ChecklistStep }) {
  return (
    <div className="gs-next">
      <span className="gs-num" aria-hidden="true">
        {step.n}
      </span>
      <div className="gs-next-text">
        <span className="eyebrow">Next step</span>
        <h3>{step.title}</h3>
        <p>{step.why}</p>
      </div>
      <Link to={step.cta.to} className="btn primary lg gs-cta">
        {step.cta.label} <Icon.ChevronRight />
      </Link>
    </div>
  );
}

function Glance({ icon, label, value, children, link, tone }: { icon: ReactNode; label: string; value: ReactNode; children?: ReactNode; link: { to: string; label: string }; tone?: string }) {
  return (
    <div className={cx('card glance', tone)}>
      <div className="gl-head">
        <span className="gl-icon" aria-hidden="true">
          {icon}
        </span>
        <span className="gl-label">{label}</span>
      </div>
      <div className="gl-value">{value}</div>
      <div className="gl-body">{children}</div>
      <Link to={link.to} className="gl-link">
        {link.label} <Icon.ChevronRight />
      </Link>
    </div>
  );
}

function LatestRun({ runs, loading }: { runs: RunListItem[] | undefined; loading: boolean }) {
  const latest = runs && runs.length ? [...runs].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]! : null;
  if (!latest) {
    return (
      <Glance icon={<Icon.History />} label="Latest run" value={loading ? <Skeleton h={30} w={140} /> : 'No runs yet'} link={{ to: '/run/new?suite=quick-check', label: 'Run the 2p Quick Check' }}>
        <span>Your most recent test will show here.</span>
      </Glance>
    );
  }
  const live = latest.status === 'running' || latest.status === 'queued';
  return (
    <Glance
      icon={<Icon.History />}
      label="Latest run"
      value={<span className="gl-name" title={latest.name}>{latest.name || latest.id}</span>}
      link={live ? { to: pathOf('runs', latest.id, 'live'), label: 'Watch it live' } : { to: pathOf('runs', latest.id), label: 'See the results' }}
      tone={live ? 'live' : undefined}
    >
      <span className="row wrap" style={{ gap: 8 }}>
        <RunStatusBadge status={latest.status} />
        <span>
          {pluralize(latest.contestants.length, 'model')} · {fmtRelative(latest.createdAt)}
        </span>
      </span>
      {live && <Progress value={latest.totalJobs ? latest.completedJobs / latest.totalJobs : 0} label="Run progress" />}
    </Glance>
  );
}
