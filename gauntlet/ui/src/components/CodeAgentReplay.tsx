/**
 * Replay stage for coding-agent programs ("Fix the Bug"): the repository's
 * file tree with touched files highlighted, the action taken, a diff of every
 * edit (or the lines it read), a visible-test bar that goes red → green, and
 * the action / token budgets. Rendered by ReplayPlayer when frames carry `code`.
 */
import type { ReactNode } from 'react';
import type { CodeReplayFrame, ReplayFrame } from '../types.ts';
import { cx } from './ui.tsx';
import '../styles/code-agent.css';

const KIND_LABEL: Record<CodeReplayFrame['kind'], string> = {
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

/** Heading for frames without an action. */
const HEADLINE: Partial<Record<CodeReplayFrame['kind'], string>> = {
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
              <div
                key={f.path}
                className={cx('ca-file', `st-${f.state}`, touched.has(f.path) && 'touched', g.dir && 'nested')}
                title={`${f.path} · ${f.lines} lines${tag ? ` · ${tag.title}` : ''}`}
              >
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
  return (
    <div className="ca-code" aria-label={`Changes to ${diff.path}`}>
      <div className="ca-code-head">
        <span className="mono">{diff.path}</span>
        <span className="ca-diffstat">
          <b className="add">+{diff.added}</b> <b className="del">−{diff.removed}</b>
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
            <div key={i} className={cx('ca-line', r.op === '+' && 'add', r.op === '-' && 'del')}>
              <span className="ca-gut tnum">{r.n ?? ''}</span>
              <span className="ca-sign">{r.op === ' ' ? '' : r.op === '-' ? '−' : '+'}</span>
              <span className="ca-txt">{r.text || ' '}</span>
            </div>
          ),
        )}
      </div>
    </div>
  );
}

function ReadView({ view }: { view: NonNullable<CodeReplayFrame['view']> }) {
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
            <span className="ca-txt">{l || ' '}</span>
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

function Meter({ label, used, budget, invert }: { label: string; used: number; budget: number; invert?: boolean }) {
  const pct = budget > 0 ? Math.min(100, (used / budget) * 100) : 0;
  const low = pct >= 80;
  return (
    <div className="ca-meter">
      <div className="ca-meter-head">
        <span>{label}</span>
        <b className="tnum">
          {used.toLocaleString('en-US')}
          <small> / {budget.toLocaleString('en-US')}</small>
        </b>
      </div>
      <div className="ca-track">
        <div className={cx('ca-fill', low && !invert && 'low')} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

function TestBar({ title, passed, total, note, highlight }: { title: string; passed: number; total: number; note?: string; highlight?: boolean }) {
  const allGreen = total > 0 && passed === total;
  return (
    <div className={cx('ca-bar', allGreen && 'green', highlight && 'flash')} role="img" aria-label={`${title}: ${passed} of ${total} passing`}>
      <div className="ca-bar-head">
        <span>{title}</span>
        {note && <span className="ca-muted">{note}</span>}
      </div>
      <div className="ca-bar-num tnum">
        <b>{passed}</b>
        <small> / {total} passing</small>
      </div>
      <div className="ca-segs" style={{ ['--n' as string]: Math.max(1, total) }}>
        {Array.from({ length: total }, (_, i) => (
          <i key={i} className={i < passed ? 'ok' : 'bad'} />
        ))}
      </div>
    </div>
  );
}

export function CodeAgentStage({ frame, video }: { frame: ReplayFrame; video?: boolean }) {
  const code = frame.code!;
  const t = code.tests;
  let body: ReactNode;
  if (code.kind === 'edit' && code.diff) body = <DiffView diff={code.diff} />;
  else if (code.kind === 'read' && code.view) body = <ReadView view={code.view} />;
  else if (code.kind === 'final' && code.hidden) {
    const h = code.hidden;
    const allGreen = h.passed === h.total;
    body = (
      <div className={cx('ca-verdict', allGreen ? 'good' : h.passed > h.before ? 'mid' : 'bad')}>
        <div className="ca-muted">Graded by {h.total} hidden tests the model never saw</div>
        <div className="ca-verdict-num tnum">
          {h.passed}
          <small> / {h.total}</small>
        </div>
        <div className="ca-verdict-sub">{allGreen ? 'Every hidden test passes: the fix is real.' : `${h.before} passed before the model started.`}</div>
      </div>
    );
  } else if (code.kind === 'start' && t)
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
      <div className={cx('ca-stage', video && 'video')}>
        <FileTree code={code} />
        <div className="ca-panel ca-main" key={frame.step}>
          <div className="ca-action">
            <span className={cx('ca-kind', `k-${code.kind}`)}>{KIND_LABEL[code.kind]}</span>
            <span className={cx('ca-act', frame.action && 'mono')}>{frame.action ?? HEADLINE[code.kind] ?? frame.label ?? ''}</span>
          </div>
          {body}
          {!video && frame.outcome && code.kind !== 'invalid' && code.kind !== 'rejected' && (
            <div className={cx('ca-outcome', frame.tone && `tone-${frame.tone}`)}>{frame.outcome}</div>
          )}
        </div>
        <div className="ca-panel ca-side">
          {t && (
            <TestBar
              title="Visible tests"
              passed={t.passed}
              total={t.total}
              note={t.ranThisStep ? (code.kind === 'start' ? 'at the start' : 'just ran') : 'last run'}
              highlight={t.ranThisStep && code.kind === 'tests'}
            />
          )}
          {code.hidden ? (
            <TestBar title="Hidden tests" passed={code.hidden.passed} total={code.hidden.total} note="final grade" highlight />
          ) : (
            <div className="ca-hidden-wait">Hidden tests: graded after SUBMIT</div>
          )}
          <Meter label="Actions" used={code.actions.used} budget={code.actions.budget} />
          <Meter label="Output tokens" used={code.tokens.used} budget={code.tokens.budget} />
        </div>
      </div>
    </div>
  );
}
