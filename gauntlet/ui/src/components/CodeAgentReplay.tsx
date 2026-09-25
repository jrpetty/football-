/**
 * Replay stage for coding-agent programs ("Fix the Bug"): a story strip of
 * every action (read → search → edit → run tests → submit), the repository's
 * file tree with touched files highlighted, a syntax-coloured diff of every
 * edit (or the lines it read), a visible-test bar that turns red → green, how
 * the action and token budgets were spent, and the hidden-test verdict as a
 * big reveal, with a marker when the visible tests missed a bug. Rendered by
 * ReplayPlayer when frames carry `code`. Recorded frames only.
 */
import type { CSSProperties, ReactNode } from 'react';
import type { CodeReplayFrame, ReplayFrame } from '../types.ts';
import { useCountUp } from '../hooks.ts';
import { Icon } from './icons.tsx';
import { cx } from './ui.tsx';
import { ActionStoryStrip, type StoryItem } from './viz/ActionStoryStrip.tsx';
import { highlightLine, langOf } from './viz/CodeSyntax.tsx';
import { TweenNumber } from './viz/TweenNumber.tsx';
import { missedBug, spentActions } from './codeAgentStory.ts';
import '../styles/code-agent.css';
import '../styles/code-agent-visual.css';

type Kind = CodeReplayFrame['kind'];

const KIND_LABEL: Record<Kind, string> = {
  start: 'Start',
  list: 'List files',
  read: 'Read',
  search: 'Search',
  edit: 'Edit',
  tests: 'Run tests',
  submit: 'Submit',
  invalid: 'Invalid',
  rejected: 'Rejected',
  final: 'Hidden tests',
};

/** Short tile word for the story strip. */
const KIND_SHORT: Record<Kind, string> = {
  start: 'Start',
  list: 'List',
  read: 'Read',
  search: 'Search',
  edit: 'Edit',
  tests: 'Tests',
  submit: 'Submit',
  invalid: 'Invalid',
  rejected: 'Blocked',
  final: 'Verdict',
};

const KIND_ICON: Record<Kind, () => ReactNode> = {
  start: () => <Icon.Flag />,
  list: () => <Icon.Layers />,
  read: () => <Icon.Eye />,
  search: () => <Icon.Search />,
  edit: () => <Icon.Edit />,
  tests: () => <Icon.Flask />,
  submit: () => <Icon.Check />,
  invalid: () => <Icon.Alert />,
  rejected: () => <Icon.Lock />,
  final: () => <Icon.Trophy />,
};

/** Colour of each kind in the budget bar (tokens, so both themes work). */
const KIND_COLOR: Partial<Record<Kind, string>> = {
  list: 'var(--seq-2)',
  read: 'var(--accent)',
  search: 'var(--seq-4)',
  edit: 'var(--warn)',
  tests: 'var(--good)',
  submit: 'var(--gold)',
  invalid: 'var(--bad)',
  rejected: 'var(--bad)',
};

/** Heading for frames without an action. */
const HEADLINE: Partial<Record<Kind, string>> = {
  start: 'The repo, before the model touches it',
  final: 'The verdict',
};

const STATE_TAG: Record<string, { t: string; title: string }> = {
  modified: { t: 'M', title: 'changed by the model' },
  added: { t: 'A', title: 'new file' },
  readonly: { t: '🔒', title: 'read-only (tests)' },
};

function groupFiles(files: CodeReplayFrame['files']): Array<{ dir: string; files: CodeReplayFrame['files'] }> {
  const groups = new Map<string, CodeReplayFrame['files']>();
  for (const f of files) {
    const i = f.path.lastIndexOf('/');
    const dir = i < 0 ? '' : f.path.slice(0, i + 1);
    groups.set(dir, [...(groups.get(dir) ?? []), f]);
  }
  return [...groups.entries()].sort(([a], [b]) => (a === '' ? -1 : b === '' ? 1 : a.localeCompare(b))).map(([dir, list]) => ({ dir, files: list }));
}

function FileTree({ code }: { code: CodeReplayFrame }) {
  const touched = new Set(code.touched ?? []);
  return (
    <div className="ca-panel ca-files" aria-label="Repository files">
      <div className="ca-h">Repository</div>
      {groupFiles(code.files).map((g) => (
        <div key={g.dir || '.'} className="ca-dir">
          {g.dir && <div className="ca-dir-name">{g.dir}</div>}
          {g.files.map((f) => {
            const name = g.dir ? f.path.slice(g.dir.length) : f.path;
            const tag = STATE_TAG[f.state];
            return (
              <div key={f.path} className={cx('ca-file', `st-${f.state}`, touched.has(f.path) && 'touched', g.dir && 'nested')} title={`${f.path} · ${f.lines} lines${tag ? ` · ${tag.title}` : ''}`}>
                <span className="ca-fname mono">{name}</span>
                <span className="ca-flines tnum">{f.lines}</span>
                <span className="ca-ftag">{tag?.t ?? ''}</span>
              </div>
            );
          })}
        </div>
      ))}
      <div className="ca-legend">
        <span>
          <i className="ca-dot touched" /> this step
        </span>
        <span>
          <i className="ca-dot modified" /> changed
        </span>
      </div>
    </div>
  );
}

function DiffView({ diff }: { diff: NonNullable<CodeReplayFrame['diff']> }) {
  const lang = langOf(diff.path);
  return (
    <div className="ca-code" aria-label={`Changes to ${diff.path}`}>
      <div className="ca-code-head">
        <span className="mono">{diff.path}</span>
        <span className="ca-diffstat">
          <b className="add">+{diff.added}</b> <b className="del">−{diff.removed}</b>
          <span className="ca-difflegend" aria-hidden="true">
            <i className="add" /> added <i className="del" /> removed
          </span>
        </span>
      </div>
      <div className="ca-lines">
        {diff.rows.map((r, i) =>
          r.op === '@' ? (
            <div key={i} className="ca-line hunk">
              <span className="ca-gut" />
              <span className="ca-sign">⋯</span>
              <span className="ca-txt">{r.text}</span>
            </div>
          ) : (
            <div key={i} className={cx('ca-line', r.op === '+' && 'add', r.op === '-' && 'del')} style={r.op !== ' ' ? ({ ['--li' as string]: i } as CSSProperties) : undefined}>
              <span className="ca-gut tnum">{r.n ?? ''}</span>
              <span className="ca-sign">{r.op === ' ' ? '' : r.op === '-' ? '−' : '+'}</span>
              <span className="ca-txt">{r.text ? highlightLine(r.text, lang) : ' '}</span>
            </div>
          ),
        )}
      </div>
    </div>
  );
}

function ReadView({ view }: { view: NonNullable<CodeReplayFrame['view']> }) {
  const lang = langOf(view.path);
  return (
    <div className="ca-code" aria-label={`Contents of ${view.path}`}>
      <div className="ca-code-head">
        <span className="mono">{view.path}</span>
        <span className="ca-muted">
          lines {view.start}–{view.start + view.lines.length - 1}
        </span>
      </div>
      <div className="ca-lines">
        {view.lines.map((l, i) => (
          <div key={i} className="ca-line">
            <span className="ca-gut tnum">{view.start + i}</span>
            <span className="ca-sign" />
            <span className="ca-txt">{l ? highlightLine(l, lang) : ' '}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function TestList({ tests }: { tests: NonNullable<CodeReplayFrame['tests']> }) {
  const allGreen = tests.total > 0 && tests.passed === tests.total;
  return (
    <div className={cx('ca-testlist', allGreen && 'green')}>
      {allGreen ? (
        <div className="ca-allgreen">✓ All {tests.total} visible tests pass</div>
      ) : (
        <>
          <div className="ca-muted">Failing visible tests</div>
          {tests.failing.map((f) => (
            <div key={f} className="ca-fail">
              <span className="ca-x">✗</span>
              <span>{f.replace(/^.*? › /, '')}</span>
            </div>
          ))}
          {tests.total - tests.passed > tests.failing.length && <div className="ca-muted">…and {tests.total - tests.passed - tests.failing.length} more</div>}
        </>
      )}
    </div>
  );
}

function Meter({ label, used, budget }: { label: string; used: number; budget: number }) {
  const pct = budget > 0 ? Math.min(100, (used / budget) * 100) : 0;
  const low = pct >= 80;
  return (
    <div className="ca-meter">
      <div className="ca-meter-head">
        <span>{label}</span>
        <b className="tnum">
          <TweenNumber value={used} />
          <small> / {budget.toLocaleString('en-US')}</small>
        </b>
      </div>
      <div className="ca-track">
        <div className={cx('ca-fill', low && 'low')} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

/** Test bar: one segment per test; segments turn green one after another when a run fixes them. */
function TestBar({ title, passed, total, note, highlight }: { title: string; passed: number; total: number; note?: string; highlight?: boolean }) {
  const allGreen = total > 0 && passed === total;
  return (
    <div className={cx('ca-bar', allGreen && 'green', highlight && 'flash')} role="img" aria-label={`${title}: ${passed} of ${total} passing`}>
      <div className="ca-bar-head">
        <span>{title}</span>
        {note && <span className="ca-muted">{note}</span>}
      </div>
      <div className="ca-bar-num tnum">
        <b>
          <TweenNumber value={passed} durationMs={700} />
        </b>
        <small> / {total} passing</small>
      </div>
      <div className="ca-segs" style={{ ['--n' as string]: Math.max(1, total) }}>
        {Array.from({ length: total }, (_, i) => (
          <i key={i} className={i < passed ? 'ok' : 'bad'} style={{ transitionDelay: `${i * 45}ms` }} />
        ))}
      </div>
    </div>
  );
}

/** How the action budget was spent so far: one block per action, coloured by kind. */
function BudgetSpend({ frames, upTo, budget, used }: { frames: ReplayFrame[]; upTo: number; budget: number; used: number }) {
  // Actions the counter says were used but that have no frame in this replay are shown hatched.
  const { acts, counts, unseen } = spentActions(frames, upTo, used);
  const cells = Math.max(budget, acts.length + unseen);
  return (
    <div className="ca-spend" aria-label={`Actions used: ${Math.max(used, acts.length)} of ${budget}`}>
      <div className="ca-meter-head">
        <span>Actions used</span>
        <b className="tnum">
          {Math.max(used, acts.length)}
          <small> / {budget}</small>
        </b>
      </div>
      <div className="ca-spend-bar" style={{ ['--n' as string]: cells }}>
        {Array.from({ length: cells }, (_, i) => {
          const f = acts[i];
          if (!f && i < acts.length + unseen) return <i key={i} className="unseen" title="used, but not in this replay" />;
          return <i key={i} className={cx(!f && 'free')} style={f ? { background: KIND_COLOR[f.code!.kind] ?? 'var(--text-3)' } : undefined} title={f ? `${i + 1}. ${f.action ?? KIND_LABEL[f.code!.kind]}` : 'unused'} />;
        })}
      </div>
      <div className="ca-spend-key">
        {[...counts.entries()].map(([k, n]) => (
          <span key={k}>
            <i style={{ background: KIND_COLOR[k] ?? 'var(--text-3)' }} />
            {KIND_SHORT[k]} {n}
          </span>
        ))}
        {unseen > 0 && (
          <span>
            <i className="unseen" />
            not in replay {unseen}
          </span>
        )}
        {!counts.size && !unseen && <span className="ca-muted">No actions yet</span>}
      </div>
    </div>
  );
}

function VerdictReveal({ hidden, missed }: { hidden: NonNullable<CodeReplayFrame['hidden']>; missed: number }) {
  const n = useCountUp(hidden.passed, 1400, 250, hidden.passed);
  const allGreen = hidden.passed === hidden.total;
  const tone = allGreen ? 'good' : hidden.passed > hidden.before ? 'mid' : 'bad';
  const stamp = allGreen ? 'Fixed' : hidden.passed > hidden.before ? 'Partly fixed' : 'Not fixed';
  return (
    <div className={cx('ca-reveal', tone)} role="status">
      <div className="ca-muted">Graded by {hidden.total} hidden tests the model never saw</div>
      <div className="ca-reveal-num tnum">
        {Math.round(n)}
        <small> / {hidden.total}</small>
      </div>
      <div className="ca-reveal-stamp">{stamp}</div>
      <div className="ca-reveal-bars" aria-label={`Hidden tests: ${hidden.before} passed before, ${hidden.passed} after`}>
        <div className="ca-rb">
          <span>Before</span>
          <span className="ca-rb-track">
            <i style={{ width: `${(hidden.before / Math.max(1, hidden.total)) * 100}%` }} />
          </span>
          <b className="tnum">{hidden.before}</b>
        </div>
        <div className="ca-rb after">
          <span>After</span>
          <span className="ca-rb-track">
            <i style={{ width: `${(hidden.passed / Math.max(1, hidden.total)) * 100}%` }} />
          </span>
          <b className="tnum">{hidden.passed}</b>
        </div>
      </div>
      <div className="ca-verdict-sub">{allGreen ? 'Every hidden test passes: the fix is real.' : `${hidden.total - hidden.passed} hidden ${hidden.total - hidden.passed === 1 ? 'test still fails' : 'tests still fail'} (${hidden.before} passed before the model started).`}</div>
      {missed > 0 && <MissedBugFlag n={missed} />}
    </div>
  );
}

function MissedBugFlag({ n }: { n: number }) {
  return (
    <div className="ca-missed" role="note">
      <span className="ca-missed-ico" aria-hidden="true">
        <Icon.Alert />
      </span>
      <span>
        <b>A bug the visible tests didn’t show.</b> Every visible test was green, but {n} hidden {n === 1 ? 'test fails' : 'tests fail'}: the model stopped before the job was done.
      </span>
    </div>
  );
}

function storyItems(frames: ReplayFrame[]): StoryItem[] {
  return frames.map((f) => {
    const c = f.code!;
    const k = c.kind;
    let sub: string | undefined;
    let tone: StoryItem['tone'] = 'neutral';
    if (k === 'tests' && c.tests) {
      sub = `${c.tests.passed}/${c.tests.total}`;
      tone = c.tests.passed === c.tests.total ? 'good' : 'warn';
    } else if (k === 'edit' && c.diff) sub = `+${c.diff.added} −${c.diff.removed}`;
    else if ((k === 'read' || k === 'search') && c.touched?.length) sub = c.touched[0]!.split('/').pop();
    else if (k === 'final' && c.hidden) {
      sub = `${c.hidden.passed}/${c.hidden.total}`;
      tone = c.hidden.passed === c.hidden.total ? 'good' : c.hidden.passed > c.hidden.before ? 'warn' : 'bad';
    } else if (k === 'start' && c.tests) sub = `${c.tests.passed}/${c.tests.total}`;
    if (k === 'invalid' || k === 'rejected') tone = 'bad';
    if (f.tone === 'bad' && tone === 'neutral') tone = 'bad';
    return { icon: KIND_ICON[k](), label: KIND_SHORT[k], sub, tone, title: f.action ?? f.outcome ?? KIND_LABEL[k], flag: k === 'final' && missedBug(c) > 0 };
  });
}

export function CodeAgentStage({ frame, video, frames, index, onSeek }: { frame: ReplayFrame; video?: boolean; frames?: ReplayFrame[]; index?: number; onSeek?: (i: number) => void }) {
  const code = frame.code!;
  const t = code.tests;
  const coded = frames?.filter((f) => f.code) ?? [];
  const at = index ?? 0;
  const missed = missedBug(code);
  let body: ReactNode;
  if (code.kind === 'edit' && code.diff) body = <DiffView diff={code.diff} />;
  else if (code.kind === 'read' && code.view) body = <ReadView view={code.view} />;
  else if (code.kind === 'final' && code.hidden) body = <VerdictReveal hidden={code.hidden} missed={missed} />;
  else if (code.kind === 'start' && t)
    body = (
      <div className="ca-testlist">
        {frame.observation && (
          <blockquote className="ca-issue">
            <span className="ca-muted">Bug report</span>
            <p>{frame.observation}</p>
          </blockquote>
        )}
        <TestList tests={t} />
      </div>
    );
  else if (code.kind === 'tests' && t) body = <TestList tests={t} />;
  else
    body = (
      <div className={cx('ca-note', (code.kind === 'invalid' || code.kind === 'rejected') && 'bad')}>
        <p>{frame.outcome}</p>
        {!!code.touched?.length && code.kind === 'search' && (
          <div className="ca-chips">
            {code.touched.map((p) => (
              <span key={p} className="mono">
                {p}
              </span>
            ))}
          </div>
        )}
      </div>
    );

  return (
    <div className="ca-wrap">
      {coded.length === (frames?.length ?? -1) && coded.length > 1 && <ActionStoryStrip items={storyItems(coded)} current={at} onSeek={onSeek} />}
      <div className={cx('ca-stage', video && 'video')}>
        <FileTree code={code} />
        <div className="ca-panel ca-main" key={frame.step}>
          <div className="ca-action">
            <span className={cx('ca-kind', `k-${code.kind}`)}>
              <span className="ca-kind-ico" aria-hidden="true">
                {KIND_ICON[code.kind]()}
              </span>
              {KIND_LABEL[code.kind]}
            </span>
            <span className={cx('ca-act', frame.action && 'mono')}>{frame.action ?? HEADLINE[code.kind] ?? frame.label ?? ''}</span>
          </div>
          {body}
          {!video && frame.outcome && code.kind !== 'invalid' && code.kind !== 'rejected' && <div className={cx('ca-outcome', frame.tone && `tone-${frame.tone}`)}>{frame.outcome}</div>}
        </div>
        <div className="ca-panel ca-side">
          {t && <TestBar title="Visible tests" passed={t.passed} total={t.total} note={t.ranThisStep ? (code.kind === 'start' ? 'at the start' : 'just ran') : 'last run'} highlight={t.ranThisStep && code.kind === 'tests'} />}
          {code.hidden ? (
            <TestBar title="Hidden tests" passed={code.hidden.passed} total={code.hidden.total} note="final grade" highlight />
          ) : (
            <div className="ca-hidden-wait">Hidden tests: graded after SUBMIT</div>
          )}
          {coded.length > 0 && coded.length === frames?.length ? <BudgetSpend frames={coded} upTo={at} budget={code.actions.budget} used={code.actions.used} /> : <Meter label="Actions" used={code.actions.used} budget={code.actions.budget} />}
          <Meter label="Output tokens" used={code.tokens.used} budget={code.tokens.budget} />
        </div>
      </div>
    </div>
  );
}
