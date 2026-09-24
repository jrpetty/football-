import { useState } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, navigate, pathOf } from '../router.tsx';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { CategoryChip, ConfirmDialog, CopyButton, DifficultyBadge, ErrorState, HashTag, LoadingPage, PageHead, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { SourceBadge } from './TestsPage.tsx';
import { fmtInt, fmtTokens, prettyJson } from '../format.ts';
import type { ScorerSpec, TestDetail } from '../types.ts';
import { CaseImages, VisionBadge } from '../components/VisionImage.tsx';

export function describeScorer(s: ScorerSpec | undefined): string {
  if (!s) return '—';
  switch (s.type) {
    case 'exact':
      return `Exact match on the FINAL ANSWER line (normalise: ${s.normalize ?? 'trim'}).`;
    case 'number':
      return `Numeric match on the FINAL ANSWER line${s.tolerance ? ` within ${s.relative ? `${s.tolerance * 100}% relative` : `±${s.tolerance}`}` : ' (exact)'}.`;
    case 'choice':
      return 'Multiple choice: the letter on the FINAL ANSWER line.';
    case 'regex':
      return `Regular expression ${s.pattern ? `/${s.pattern}/${s.flags ?? ''}` : '(from each case)'} against ${s.fullText ? 'the full response' : 'the FINAL ANSWER line'}.`;
    case 'contains':
      return `Keyword presence over the full response${s.caseSensitive ? ' (case-sensitive)' : ''}.`;
    case 'constraints':
      return `Machine-checked constraints; ${s.allOrNothing ? 'all must pass' : 'partial credit = fraction satisfied'}.`;
    case 'json':
      return `Structured JSON; ${s.allOrNothing ? 'all-or-nothing: every expected field must be exact' : 'partial credit per expected leaf field'}${s.unorderedArrays ? ', arrays unordered' : ''}${s.numberTolerance ? `, numbers ±${s.numberTolerance}` : ''}.`;
    case 'code-js':
      return `Generated JavaScript is executed against hidden unit tests in a sandbox (timeout ${s.timeoutMs ?? 2000} ms).`;
    case 'judge':
      return `LLM judge panel against a fixed rubric${s.passThreshold ? `; pass ≥ ${s.passThreshold}` : ''}.`;
    case 'judge-classify':
      return `LLM judge panel classifies the reply into ${s.labels.length} labels, each mapped to a score.`;
    case 'artifact':
      return `Extracts a ${s.format.toUpperCase()} artifact, runs ${s.checks?.length ?? 0} automated checks${s.rubric ? `, then a judge (weight ${s.judgeWeight ?? 0.5})` : ''}.`;
    case 'human':
      return 'Scored by humans in Blind Review (0–10).';
  }
}

function ExpectedView({ value }: { value: unknown }) {
  if (value === undefined) return <pre className="code">— (judged / human-scored)</pre>;
  const isConstraints = Array.isArray(value) && value.length > 0 && value.every((v) => v && typeof v === 'object' && 'check' in (v as object));
  if (isConstraints)
    return (
      <ul className="constraint-chips">
        {(value as Array<Record<string, unknown>>).map((c, i) => (
          <li key={i}>
            <b className="mono">{String(c.check)}</b>
            {Object.entries(c)
              .filter(([k]) => k !== 'check')
              .map(([k, v]) => (
                <span key={k}>
                  {k} <code>{Array.isArray(v) ? v.join(', ') : String(v)}</code>
                </span>
              ))}
          </li>
        ))}
      </ul>
    );
  return <pre className="code">{prettyJson(value)}</pre>;
}

export default function TestDetailPage({ testId }: { testId: string }) {
  useViewerCaption('One test up close: what the models are asked, how the answers are checked, and the exact prompts every model receives.', 'Answer keys stay hidden unless revealed');
  const { cat } = useMeta();
  const toast = useToast();
  const state = useAsync<TestDetail>(() => api.test(testId), [testId]);
  const [showKey, setShowKey] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);

  if (state.loading && !state.data) return <LoadingPage />;
  if (state.error && !state.data) return <ErrorState error={state.error} onRetry={state.reload} title="Couldn’t load this test" />;
  const d = state.data;
  if (!d) return null;
  const def = d.definition;
  const sum = d.summary;
  const info = cat(def.category);
  const isProgram = def.kind === 'program';
  const allPrompts = d.rendered.map((r) => `### ${r.caseId}\n${r.system ? `[system]\n${r.system}\n\n` : ''}${r.turns.map((t, i) => (r.turns.length > 1 ? `[turn ${i + 1}]\n${t}` : t)).join('\n\n')}`).join('\n\n');

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteTest(testId);
      toast.success(`Deleted ${def.name}.`);
      navigate('/tests');
    } catch (e) {
      toast.error(e, 'Could not delete');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Link to="/tests">Tests</Link>
            <Icon.ChevronRight style={{ width: 12, height: 12 }} />
            <CategoryChip name={info.name} color={info.color} />
          </span>
        }
        title={def.name}
        sub={def.description}
        actions={
          <>
            <Link to={`/run/new?tests=${encodeURIComponent(testId)}`} className="btn primary">
              <Icon.Rocket /> Run this test
            </Link>
            {!isProgram && (
              <Link to={`/grade?test=${encodeURIComponent(testId)}`} className="btn">
                <Icon.Target /> Grade a reply
              </Link>
            )}
            {!isProgram && (
              <Link to={`/tests/new?from=${encodeURIComponent(testId)}`} className="btn">
                <Icon.Copy /> Duplicate
              </Link>
            )}
            {sum.source === 'custom' && (
              <>
                <Link to={pathOf('tests', testId, 'edit')} className="btn">
                  <Icon.Edit /> Edit
                </Link>
                <button className="btn ghost icon" aria-label="Delete test" title="Delete test" onClick={() => setConfirm(true)}>
                  <Icon.Trash />
                </button>
              </>
            )}
          </>
        }
      />

      {def.hook && (
        <blockquote className="hook-quote">
          <span aria-hidden="true">“</span>
          {def.hook}
          <span aria-hidden="true">”</span>
        </blockquote>
      )}

      <section className="card manifest">
        <div className="manifest-grid">
          <div className="mf">
            <span className="k">Id</span>
            <span className="v mono">{def.id}</span>
          </div>
          <div className="mf">
            <span className="k">Version · hash</span>
            <span className="v row" style={{ gap: 8 }}>
              <span className="mono">v{def.version}</span>
              <HashTag value={sum.hash} label="Test hash" n={10} />
            </span>
          </div>
          <div className="mf">
            <span className="k">Kind · difficulty</span>
            <span className="v row wrap" style={{ gap: 8 }}>
              <span className="badge outline">{isProgram ? 'simulation' : 'prompt'}</span>
              <DifficultyBadge difficulty={def.difficulty} />
            </span>
          </div>
          <div className="mf">
            <span className="k">Source</span>
            <span className="v row wrap" style={{ gap: 8 }}>
              {sum.source === 'builtin' ? 'built-in' : <SourceBadge source={sum.source} />}
              <span className="mono muted" style={{ fontSize: '0.76rem' }}>
                {sum.file}
              </span>
            </span>
          </div>
          <div className="mf">
            <span className="k">Estimate per case</span>
            <span className="v tnum">
              {fmtTokens(sum.estimate?.inputTokens)} in · {fmtTokens(sum.estimate?.outputTokens)} out · {sum.estimate?.calls ?? 1} call{(sum.estimate?.calls ?? 1) === 1 ? '' : 's'}
              {(sum.imageCases ?? 0) > 0 && <span className="muted"> · plus image tokens, priced per vendor</span>}
            </span>
          </div>
          <div className="mf">
            <span className="k">Limits</span>
            <span className="v tnum">
              {fmtInt(def.maxOutputTokens ?? 16000)} max output tokens · {def.timeLimitSec ?? 600} s per case
              {def.kind === 'prompt' && def.answerWithinSec ? ` · ${def.answerWithinSec} s to answer (late = 0)` : ''}
            </span>
          </div>
          {(def.tags?.length ?? 0) > 0 && (
            <div className="mf">
              <span className="k">Tags</span>
              <span className="v chip-list">
                {def.tags!.map((t) => (
                  <span key={t} className="badge">
                    {t}
                  </span>
                ))}
              </span>
            </div>
          )}
          {def.author && (
            <div className="mf">
              <span className="k">Author</span>
              <span className="v">{def.author}</span>
            </div>
          )}
        </div>
      </section>

      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <section className="card">
          <div className="card-head">
            <div className="t">
              <h2>{isProgram ? 'Program' : 'Scorer'}</h2>
              <div className="desc">{isProgram ? d.program?.name ?? (def.kind === 'program' ? def.program : '') : describeScorer(def.kind === 'prompt' ? def.scorer : undefined)}</div>
            </div>
          </div>
          <div className="card-body stack">
            {isProgram && d.program && (
              <>
                <p className="dim">{d.program.description}</p>
                <div className="callout plain">
                  <Icon.Target />
                  <div>
                    <strong>Scoring:</strong> {d.program.scoring}
                  </div>
                </div>
              </>
            )}
            <div className="code-wrap">
              <pre className="code">{prettyJson(def.kind === 'prompt' ? def.scorer : { program: def.program, config: def.config ?? {}, seeds: def.seeds })}</pre>
              <div className="copy">
                <CopyButton text={prettyJson(def.kind === 'prompt' ? def.scorer : { program: def.program, config: def.config, seeds: def.seeds })} iconOnly label="Copy config" />
              </div>
            </div>
          </div>
        </section>
        <section className="card">
          <div className="card-head">
            <div className="t">
              <h2>Reproducibility</h2>
              <div className="desc">What the hash pins down.</div>
            </div>
          </div>
          <div className="card-body">
            <ul className="bullets">
              <li>Every prompt byte, the system prompt and preamble, each expected answer and the scorer config feed the test hash.</li>
              <li>Results record the hash they were produced with. If this test changes, older results are marked stale and excluded from the leaderboard.</li>
              <li>{isProgram ? 'Each seed generates an identical world for every model.' : 'Every model sees exactly these bytes — no per-model prompt tweaks.'}</li>
              {(sum.imageCases ?? 0) > 0 && <li>The bytes of every image are hashed too: replacing or re-rendering a picture changes the hash. Models without image input are skipped on picture cases, not scored as 0.</li>}
              {sum.source === 'private' && <li>This is a held-out test: it lives in the git-ignored tests/private/ folder and is never published, so it cannot leak into training data.</li>}
            </ul>
          </div>
        </section>
      </div>

      <section className="card">
        <div className="card-head">
          <div className="t">
            <h2>
              {isProgram ? 'Seeds' : 'Cases'} · {d.rendered.length}
            </h2>
            <div className="desc">{isProgram ? 'Prompts are generated at run time from each seed.' : 'The exact rendered prompts every model receives.'}</div>
          </div>
          <div className="tools">
            {!isProgram && <CopyButton text={allPrompts} label="Copy all prompts" small={false} />}
            <label className={cx('key-toggle', showKey && 'on')}>
              {showKey ? <Icon.Eye /> : <Icon.EyeOff />}
              <span>Answer key</span>
              <Switch checked={showKey} onChange={setShowKey} label="Show answer key" />
            </label>
          </div>
        </div>
        <div className="card-body stack">
          {!showKey && !isProgram && (
            <div className="muted" style={{ fontSize: '0.8rem' }}>
              <Icon.Lock style={{ width: 12, height: 12, display: 'inline', verticalAlign: '-1px' }} /> Expected answers and auditor notes are hidden by default, since this screen may appear on video.
            </div>
          )}
          {d.rendered.map((r) => (
            <article key={r.caseId} className="case-block">
              <header className="row">
                <span className="mono case-id">{r.caseId}</span>
                <span className="spacer" />
                {r.turns.length > 1 && <span className="badge outline">{r.turns.length} turns</span>}
                {(r.images?.length ?? 0) > 0 && <VisionBadge label={r.images!.length > 1 ? `${r.images!.length} images` : 'Image'} />}
              </header>
              {r.system && (
                <details className="collapse">
                  <summary>System prompt · {r.system.length.toLocaleString()} chars</summary>
                  <div className="inner code-wrap">
                    <pre className="code">{r.system}</pre>
                    <div className="copy">
                      <CopyButton text={r.system} iconOnly label="Copy system prompt" />
                    </div>
                  </div>
                </details>
              )}
              {r.turns.map((turn, i) => (
                <div key={i} className="stack" style={{ gap: 10 }}>
                  <CaseImages images={r.images} turn={i} caption="Sent to the model with this message, before the text. Click to enlarge." />
                  <div className="code-wrap">
                    {r.turns.length > 1 && <div className="mini-title">Turn {i + 1}</div>}
                    <pre className="code">{turn}</pre>
                    <div className="copy">
                      <CopyButton text={turn} iconOnly label={`Copy ${r.turns.length > 1 ? `turn ${i + 1}` : 'prompt'}`} />
                    </div>
                  </div>
                </div>
              ))}
              {r.turns.length === 0 && r.notes && <p className="dim">{r.notes}</p>}
              {showKey && !isProgram && (
                <div className="answer-key">
                  <div className="mini-title">
                    <Icon.Key style={{ width: 12, height: 12 }} /> Expected
                  </div>
                  <ExpectedView value={r.expected} />
                  {r.notes && (
                    <>
                      <div className="mini-title" style={{ marginTop: 10 }}>
                        Auditor notes
                      </div>
                      <p className="dim">{r.notes}</p>
                    </>
                  )}
                </div>
              )}
            </article>
          ))}
        </div>
      </section>

      <ConfirmDialog
        open={confirm}
        danger
        title="Delete this custom test?"
        confirmLabel="Delete test"
        busy={busy}
        onCancel={() => setConfirm(false)}
        onConfirm={remove}
        body={`${def.name} (${def.id}) will be removed from tests/custom/. Existing run results keep their snapshot but it will no longer appear in suites.`}
      />
    </div>
  );
}
