/**
 * Manual Inbox — copy each pending prompt into any chatbot, paste the reply
 * back. Polls GET /api/manual every 2 s.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.ts';
import { copyText, useAsync, useInterval } from '../hooks.ts';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { useToast } from '../context.tsx';
import { Callout, Empty, ErrorState, ModelChip, PageHead, Seg, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtInt, fmtRelative } from '../format.ts';
import type { ContestantView, ManualReply, ManualRequest, RunListItem } from '../types.ts';

interface Draft {
  text: string;
  inputTokens: string;
  outputTokens: string;
  reasoningTokens: string;
  costUsd: string;
}
const EMPTY: Draft = { text: '', inputTokens: '', outputTokens: '', reasoningTokens: '', costUsd: '' };
const DRAFT_KEY = 'gauntlet.inbox.drafts';

function loadDrafts(): Record<string, Draft> {
  try {
    return JSON.parse(window.localStorage.getItem(DRAFT_KEY) ?? '{}') as Record<string, Draft>;
  } catch {
    return {};
  }
}
function saveDrafts(d: Record<string, Draft>) {
  try {
    window.localStorage.setItem(DRAFT_KEY, JSON.stringify(d));
  } catch {
    /* ignore */
  }
}

const num = (s: string): number | undefined => {
  if (s.trim() === '') return undefined;
  const n = Number(s);
  return Number.isFinite(n) && n >= 0 ? n : undefined;
};
const badNum = (s: string) => s.trim() !== '' && num(s) === undefined;

function RequestCard({
  req,
  color,
  runName,
  open,
  onOpen,
  draft,
  onDraft,
  onDone,
  autoFocus,
}: {
  req: ManualRequest;
  color?: string;
  runName?: string;
  open: boolean;
  onOpen: () => void;
  draft: Draft;
  onDraft: (d: Draft) => void;
  onDone: (id: string) => void;
  autoFocus: boolean;
}) {
  const toast = useToast();
  const [view, setView] = useState<'new' | 'same'>('new');
  const [copied, setCopied] = useState(false);
  const [failing, setFailing] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const copyRef = useRef<HTMLButtonElement>(null);
  const prompt = view === 'same' && req.isContinuation ? req.latestUserMessage : req.combinedPrompt;

  useEffect(() => {
    if (open && autoFocus) copyRef.current?.focus({ preventScroll: false });
  }, [open, autoFocus]);

  const invalid = badNum(draft.inputTokens) || badNum(draft.outputTokens) || badNum(draft.reasoningTokens) || badNum(draft.costUsd);

  const submit = async () => {
    if (!draft.text.trim()) {
      toast.error('Paste the model’s full reply first.', 'Nothing to submit');
      return;
    }
    if (invalid) {
      toast.error('Token counts and cost must be non-negative numbers (or blank).');
      return;
    }
    setBusy(true);
    try {
      const reply: ManualReply = { text: draft.text, inputTokens: num(draft.inputTokens), outputTokens: num(draft.outputTokens), reasoningTokens: num(draft.reasoningTokens), costUsd: num(draft.costUsd) };
      await api.manualSubmit(req.id, reply);
      toast.success(`${req.contestantLabel} · ${req.testName} — graded exactly like an API reply.`, 'Reply recorded');
      onDone(req.id);
    } catch (e) {
      toast.error(e, 'Could not submit');
    } finally {
      setBusy(false);
    }
  };

  const fail = async () => {
    setBusy(true);
    try {
      await api.manualFail(req.id, reason.trim() || undefined);
      toast.info('Recorded as an error for this case.', 'Marked failed');
      onDone(req.id);
    } catch (e) {
      toast.error(e, 'Could not mark failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <article className={cx('card inbox-card', open && 'open')} style={{ ['--c' as string]: color ?? 'var(--accent)' }} aria-label={`${req.contestantLabel}: ${req.testName} ${req.caseId}`}>
      <button type="button" className="inbox-head" onClick={onOpen} aria-expanded={open}>
        <ModelChip label={req.contestantLabel} color={color} pill />
        <span className="inbox-test">
          <strong>{req.testName}</strong>
          <span className="muted"> · {req.caseId}</span>
        </span>
        <span className="badge accent">{req.label}</span>
        {req.isContinuation && <span className="badge outline">multi-turn</span>}
        <span className="spacer" />
        <span className="muted nowrap" style={{ fontSize: '0.8rem' }} title={new Date(req.createdAt).toLocaleString()}>
          waiting {fmtRelative(req.createdAt).replace(' ago', '')}
        </span>
        <Icon.ChevronDown className={cx('chev', open && 'up')} />
      </button>

      {open && (
        <div className="inbox-body">
          <div className="inbox-steps">
            <div className="step">
              <span className="n">1</span>
              <div className="stack tight" style={{ minWidth: 0, flex: 1 }}>
                <div className="row wrap" style={{ gap: 10 }}>
                  <strong className="step-title">{view === 'same' && req.isContinuation ? 'Continue the SAME chat' : 'Paste into a NEW chat'}</strong>
                  {req.isContinuation && (
                    <Seg
                      small
                      label="Which prompt to copy"
                      value={view}
                      onChange={setView}
                      options={[
                        { value: 'new', label: 'New chat · full conversation', title: 'Includes the system prompt and every earlier turn' },
                        { value: 'same', label: 'Same chat · latest message', title: 'Only the newest user message — use when the earlier turns are already in that chat' },
                      ]}
                    />
                  )}
                  <span className="spacer" />
                  <span className="muted tnum" style={{ fontSize: '0.76rem' }}>
                    {fmtInt(prompt.length)} chars
                  </span>
                </div>
                <div className="prompt-box">
                  <pre>{prompt}</pre>
                  <button
                    ref={copyRef}
                    type="button"
                    className={cx('btn copy-big', copied ? 'copied' : 'primary')}
                    onClick={async () => {
                      if (await copyText(prompt)) {
                        setCopied(true);
                        window.setTimeout(() => setCopied(false), 1600);
                      } else toast.error('Clipboard unavailable — select the text and copy manually.');
                    }}
                  >
                    {copied ? <Icon.Check /> : <Icon.Copy />} {copied ? 'Copied' : 'Copy prompt'}
                  </button>
                </div>
              </div>
            </div>
            <div className="step">
              <span className="n">2</span>
              <div className="stack tight" style={{ minWidth: 0, flex: 1 }}>
                <label className="step-title" htmlFor={`reply-${req.id}`}>
                  Paste the model’s full reply
                </label>
                <textarea
                  id={`reply-${req.id}`}
                  className="textarea reply-box"
                  placeholder="Paste the complete reply exactly as the chatbot wrote it…"
                  value={draft.text}
                  onChange={(e) => onDraft({ ...draft, text: e.target.value })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                      e.preventDefault();
                      void submit();
                    }
                  }}
                />
                <details className="collapse">
                  <summary>Usage &amp; cost (optional) — leave blank if unknown</summary>
                  <div className="inner">
                    <div className="usage-grid">
                      {(
                        [
                          ['inputTokens', 'Input tokens'],
                          ['outputTokens', 'Output tokens'],
                          ['reasoningTokens', 'Reasoning tokens'],
                          ['costUsd', 'Cost (USD)'],
                        ] as const
                      ).map(([k, label]) => (
                        <label key={k} className="field">
                          <span className="label">{label}</span>
                          <input className={cx('input sm tnum', badNum(draft[k]) && 'invalid')} inputMode="decimal" placeholder="unknown" value={draft[k]} onChange={(e) => onDraft({ ...draft, [k]: e.target.value })} />
                        </label>
                      ))}
                    </div>
                    <p className="muted" style={{ fontSize: '0.78rem', marginTop: 8 }}>
                      Blank tokens are estimated from text length; blank cost is recorded as $0. Only fill these in if the chat app shows real numbers.
                    </p>
                  </div>
                </details>
              </div>
            </div>
          </div>
          <div className="inbox-actions">
            {failing ? (
              <div className="row wrap" style={{ flex: 1, gap: 8 }}>
                <input className="input sm" style={{ flex: 1, minWidth: 200 }} placeholder="Reason (e.g. the app refused to load, chat crashed)" value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Failure reason" autoFocus />
                <button className="btn sm ghost" onClick={() => setFailing(false)}>
                  Cancel
                </button>
                <button className="btn sm danger" onClick={fail} disabled={busy}>
                  Record as failed
                </button>
              </div>
            ) : (
              <>
                <button className="btn sm ghost" onClick={() => setFailing(true)}>
                  <Icon.Alert /> Mark failed…
                </button>
                <span className="spacer" />
                {runName && (
                  <Link to={pathOf('runs', req.runId, 'live')} className="muted" style={{ fontSize: '0.8rem' }}>
                    {runName}
                  </Link>
                )}
                <span className="muted hide-mobile" style={{ fontSize: '0.78rem' }}>
                  <kbd>Ctrl</kbd>/<kbd>⌘</kbd> + <kbd>Enter</kbd>
                </span>
                <button className="btn primary" onClick={submit} disabled={busy || !draft.text.trim()}>
                  <Icon.Check /> Submit reply
                </button>
              </>
            )}
          </div>
        </div>
      )}
    </article>
  );
}

export default function InboxPage() {
  const { query } = useRoute();
  const runFilter = query.get('run') ?? '';
  const modelFilter = query.get('model') ?? '';
  const [list, setList] = useState<ManualRequest[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [openId, setOpenId] = useState<string | null>(null); // 'none' = all collapsed
  const [drafts, setDrafts] = useState<Record<string, Draft>>(loadDrafts);
  const [justDone, setJustDone] = useState(0);
  const meta = useAsync<[ContestantView[], RunListItem[]]>(() => Promise.all([api.contestants().catch(() => []), api.runs().catch(() => [])]), []);
  const colors = useMemo(() => new Map((meta.data?.[0] ?? []).map((c) => [c.id, c.color])), [meta.data]);
  const runNames = useMemo(() => new Map((meta.data?.[1] ?? []).map((r) => [r.id, r.name])), [meta.data]);

  const poll = useCallback(async () => {
    try {
      const l = await api.manualQueue();
      setList([...l].sort((a, b) => a.createdAt.localeCompare(b.createdAt)));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e : new Error(String(e)));
    }
  }, []);
  useEffect(() => {
    void poll();
  }, [poll]);
  useInterval(() => void poll(), 2000);

  const filtered = useMemo(() => (list ?? []).filter((r) => (!runFilter || r.runId === runFilter) && (!modelFilter || r.contestantId === modelFilter)), [list, runFilter, modelFilter]);
  const runsInList = useMemo(() => [...new Set((list ?? []).map((r) => r.runId))], [list]);
  const modelsInList = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of list ?? []) m.set(r.contestantId, r.contestantLabel);
    return [...m.entries()];
  }, [list]);

  // Keep one card open: the chosen one, else the oldest.
  const effectiveOpen = openId === 'none' ? null : filtered.some((r) => r.id === openId) ? openId : filtered[0]?.id ?? null;

  const setDraft = (id: string, d: Draft) =>
    setDrafts((all) => {
      const next = { ...all, [id]: d };
      saveDrafts(next);
      return next;
    });
  const done = (id: string) => {
    setList((l) => (l ?? []).filter((r) => r.id !== id));
    setDrafts((all) => {
      const next = { ...all };
      delete next[id];
      saveDrafts(next);
      return next;
    });
    setOpenId(null);
    setJustDone((n) => n + 1);
  };

  return (
    <div className="page inbox-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Inbox style={{ width: 14, height: 14 }} /> Manual models
          </span>
        }
        title={
          <span className="row" style={{ gap: 12 }}>
            Manual Inbox {list && <span className={cx('badge lg', filtered.length ? 'live' : 'good')}>{filtered.length ? `${filtered.length} waiting` : 'all caught up'}</span>}
          </span>
        }
        sub="Test any model you can chat with — web apps, older models, anything without an API. Copy each prompt into the chat, paste the reply back, and it is graded exactly like an API reply."
        actions={
          <div className="row wrap" style={{ gap: 8 }}>
            <select className="select" style={{ width: 220 }} value={runFilter} onChange={(e) => setQuery({ run: e.target.value || null })} aria-label="Filter by run">
              <option value="">All runs</option>
              {runsInList.map((id) => (
                <option key={id} value={id}>
                  {runNames.get(id) ?? id}
                </option>
              ))}
            </select>
            <select className="select" style={{ width: 190 }} value={modelFilter} onChange={(e) => setQuery({ model: e.target.value || null })} aria-label="Filter by model">
              <option value="">All models</option>
              {modelsInList.map(([id, label]) => (
                <option key={id} value={id}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        }
      />

      <Callout tone="warn" icon={<Icon.Lock />}>
        <strong>Fair-play rules:</strong> each prompt must be pasted <strong>exactly as shown</strong>. Don’t add instructions, don’t regenerate, don’t edit the reply. Use a fresh chat unless the card says to continue the same one, and turn off tools such as web search or code execution.
      </Callout>

      {error && !list ? (
        <ErrorState error={error} onRetry={poll} title="Couldn’t load the inbox" />
      ) : !list ? (
        <div className="card pad">
          <SkeletonRows rows={4} h={52} />
        </div>
      ) : filtered.length === 0 ? (
        <div className="card">
          <Empty
            icon={<Icon.Inbox />}
            title={list.length ? 'Nothing matches these filters' : justDone ? 'Inbox zero — nice work' : 'No replies waiting'}
            actions={
              list.length ? (
                <button className="btn" onClick={() => setQuery({ run: null, model: null })}>
                  Clear filters
                </button>
              ) : (
                <>
                  <Link to="/models" className="btn">
                    <Icon.Cpu /> Add a manual model
                  </Link>
                  <Link to="/run/new" className="btn primary">
                    <Icon.Rocket /> Start a run
                  </Link>
                </>
              )
            }
          >
            {list.length
              ? 'Other requests are waiting in other runs or models.'
              : 'Prompts appear here while a run includes a model whose provider is “Manual (copy & paste)”. This page checks for new ones every 2 seconds.'}
          </Empty>
        </div>
      ) : (
        <div className="stack">
          {filtered.map((r, i) => (
            <RequestCard
              key={r.id}
              req={r}
              color={colors.get(r.contestantId)}
              runName={runNames.get(r.runId)}
              open={r.id === effectiveOpen}
              onOpen={() => setOpenId(r.id === effectiveOpen ? 'none' : r.id)}
              draft={drafts[r.id] ?? EMPTY}
              onDraft={(d) => setDraft(r.id, d)}
              onDone={done}
              autoFocus={justDone > 0 && i === 0}
            />
          ))}
        </div>
      )}
    </div>
  );
}
