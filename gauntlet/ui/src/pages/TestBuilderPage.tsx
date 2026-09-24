/** Test Builder — author, validate and save a PromptTest. */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, api } from '../api.ts';
import { useDebounced } from '../hooks.ts';
import { Link, navigate, pathOf, useRoute } from '../router.tsx';
import { useMeta, useToast } from '../context.tsx';
import { Callout, CopyButton, ErrorState, Field, LoadingPage, Modal, PageHead, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { slugify } from '../format.ts';
import type { ArtifactCheck, Constraint, Difficulty, JudgeLabel, PromptTest, PromptTestCase, ScorerSpec, ValidateResult } from '../types.ts';
import { CaseImageEditor, rebaseImages } from '../components/VisionBuilder.tsx';

const FINAL_ANSWER = 'When you are finished, write your final answer on its own line, exactly in the form:\nFINAL ANSWER: <answer>';

type ScorerType = ScorerSpec['type'];
const SCORERS: Array<{ type: ScorerType; label: string; desc: string }> = [
  { type: 'exact', label: 'Exact', desc: 'FINAL ANSWER equals an accepted string' },
  { type: 'number', label: 'Number', desc: 'Numeric answer, optional tolerance' },
  { type: 'choice', label: 'Choice', desc: 'Multiple-choice letter' },
  { type: 'regex', label: 'Regex', desc: 'Pattern match on answer or text' },
  { type: 'contains', label: 'Contains', desc: 'Required / forbidden keywords' },
  { type: 'constraints', label: 'Constraints', desc: 'Machine-checked format rules' },
  { type: 'json', label: 'JSON', desc: 'Field-by-field structured match' },
  { type: 'code-js', label: 'Code (JS)', desc: 'Run code against unit tests' },
  { type: 'judge', label: 'Judge rubric', desc: 'LLM judge panel scores 0–1' },
  { type: 'judge-classify', label: 'Judge classify', desc: 'Judges pick a scored label' },
  { type: 'artifact', label: 'Artifact', desc: 'HTML/SVG build + checks + judge' },
  { type: 'human', label: 'Human', desc: 'Blind human review' },
];

function defaultScorer(type: ScorerType): ScorerSpec {
  switch (type) {
    case 'exact':
      return { type, normalize: 'trim' };
    case 'number':
      return { type, tolerance: 0 };
    case 'choice':
      return { type };
    case 'regex':
      return { type, pattern: '', flags: 'i' };
    case 'contains':
      return { type, all: [], any: [], none: [] };
    case 'constraints':
      return { type, allOrNothing: false };
    case 'json':
      return { type, unorderedArrays: false };
    case 'code-js':
      return { type, timeoutMs: 2000 };
    case 'judge':
      return { type, rubric: '', passThreshold: 0.5 };
    case 'judge-classify':
      return {
        type,
        instructions: '',
        labels: [
          { id: 'correct', description: 'Fully correct', score: 1 },
          { id: 'partial', description: 'Partly correct', score: 0.5 },
          { id: 'wrong', description: 'Wrong or fabricated', score: 0 },
        ],
      };
    case 'artifact':
      return { type, format: 'html', checks: [{ check: 'parses' }, { check: 'no_external_requests' }, { check: 'runs_without_errors' }], rubric: '', judgeWeight: 0.5 };
    case 'human':
      return { type, rubric: '' };
  }
}

// ───────────────────────────── Constraint editor ─────────────────────────────

type FieldKind = 'num' | 'text' | 'bool' | 'list';
const RANGE: Array<[string, FieldKind]> = [
  ['min', 'num'],
  ['max', 'num'],
];
const CONSTRAINT_FIELDS: Record<Constraint['check'], Array<[string, FieldKind]>> = {
  word_count: RANGE,
  sentence_count: RANGE,
  paragraph_count: RANGE,
  line_count: RANGE,
  bullet_count: RANGE,
  include: [
    ['text', 'text'],
    ['caseSensitive', 'bool'],
    ['min', 'num'],
    ['max', 'num'],
  ],
  exclude: [
    ['text', 'text'],
    ['caseSensitive', 'bool'],
  ],
  no_letter: [['letter', 'text']],
  starts_with: [
    ['text', 'text'],
    ['caseSensitive', 'bool'],
  ],
  ends_with: [
    ['text', 'text'],
    ['caseSensitive', 'bool'],
  ],
  all_lowercase: [],
  all_uppercase: [],
  no_commas: [],
  json: [],
  json_keys: [['keys', 'list']],
  regex: [
    ['pattern', 'text'],
    ['flags', 'text'],
    ['shouldMatch', 'bool'],
  ],
  max_word_length: [['max', 'num']],
  acrostic: [['word', 'text']],
  each_line_starts_with: [['text', 'text']],
  title_case_lines: [],
};
const CONSTRAINT_TYPES = Object.keys(CONSTRAINT_FIELDS) as Array<Constraint['check']>;

function newConstraint(check: Constraint['check']): Constraint {
  const base: Record<string, unknown> = { check };
  for (const [k, kind] of CONSTRAINT_FIELDS[check]) {
    if (kind === 'text') base[k] = '';
    if (kind === 'list') base[k] = [];
    if (kind === 'num' && k === 'max' && check === 'max_word_length') base[k] = 12;
  }
  return base as Constraint;
}

function ConstraintEditor({ value, onChange }: { value: unknown; onChange: (v: Constraint[]) => void }) {
  const rows = (Array.isArray(value) ? value : []) as Array<Record<string, unknown> & { check: Constraint['check'] }>;
  const set = (i: number, patch: Record<string, unknown> | null, replace?: Constraint) => {
    const next = rows.map((r, j) => (j === i ? (replace ?? { ...r, ...patch }) : r)) as Constraint[];
    onChange(next);
  };
  return (
    <div className="stack tight">
      {rows.length === 0 && <div className="muted" style={{ fontSize: '0.84rem' }}>No constraints yet.</div>}
      {rows.map((r, i) => (
        <div key={i} className="constraint-row">
          <select className="select sm" style={{ width: 190 }} value={r.check} onChange={(e) => set(i, null, newConstraint(e.target.value as Constraint['check']))} aria-label="Constraint type">
            {CONSTRAINT_TYPES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          {(CONSTRAINT_FIELDS[r.check] ?? []).map(([k, kind]) =>
            kind === 'bool' ? (
              <label key={k} className="check" style={{ fontSize: '0.82rem' }}>
                <input type="checkbox" checked={!!r[k]} onChange={(e) => set(i, { [k]: e.target.checked || undefined })} />
                {k}
              </label>
            ) : (
              <input
                key={k}
                className={cx('input sm', kind === 'num' && 'tnum')}
                style={{ width: kind === 'num' ? 80 : 170 }}
                placeholder={kind === 'list' ? `${k} (comma-separated)` : k}
                aria-label={k}
                type={kind === 'num' ? 'number' : 'text'}
                value={kind === 'list' ? ((r[k] as string[] | undefined) ?? []).join(', ') : ((r[k] as string | number | undefined) ?? '')}
                onChange={(e) => {
                  const v = e.target.value;
                  set(i, { [k]: kind === 'num' ? (v === '' ? undefined : Number(v)) : kind === 'list' ? v.split(',').map((x) => x.trim()).filter(Boolean) : v });
                }}
              />
            ),
          )}
          <span className="spacer" />
          <button type="button" className="btn ghost icon xs" aria-label="Remove constraint" onClick={() => onChange(rows.filter((_, j) => j !== i) as Constraint[])}>
            <Icon.X />
          </button>
        </div>
      ))}
      <div>
        <button type="button" className="btn xs" onClick={() => onChange([...(rows as Constraint[]), newConstraint('word_count')])}>
          <Icon.Plus /> Add constraint
        </button>
      </div>
    </div>
  );
}

// ───────────────────────────── JSON field ─────────────────────────────

function JsonField({ value, onChange, placeholder, rows = 5, label }: { value: unknown; onChange: (v: unknown) => void; placeholder?: string; rows?: number; label: string }) {
  const [text, setText] = useState(() => (value === undefined ? '' : JSON.stringify(value, null, 2)));
  const [err, setErr] = useState<string | null>(null);
  const last = useRef(value);
  useEffect(() => {
    if (value !== last.current) {
      last.current = value;
      setText(value === undefined ? '' : JSON.stringify(value, null, 2));
      setErr(null);
    }
  }, [value]);
  return (
    <div className="stack tight">
      <textarea
        className={cx('textarea mono', err && 'invalid')}
        rows={rows}
        aria-label={label}
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          const t = e.target.value;
          setText(t);
          if (!t.trim()) {
            setErr(null);
            last.current = undefined;
            onChange(undefined);
            return;
          }
          try {
            const v = JSON.parse(t) as unknown;
            setErr(null);
            last.current = v;
            onChange(v);
          } catch (ex) {
            setErr((ex as Error).message);
          }
        }}
      />
      {err && <div className="err" style={{ fontSize: '0.78rem', color: 'var(--bad-text)' }}>Invalid JSON: {err}</div>}
    </div>
  );
}

// ───────────────────────────── code-js unit tests ─────────────────────────────

interface CodeExpected {
  functionName: string;
  tests: Array<{ args: unknown[]; expected: unknown }>;
}

function CodeTestsEditor({ value, onChange }: { value: unknown; onChange: (v: CodeExpected) => void }) {
  const v: CodeExpected = value && typeof value === 'object' && 'tests' in (value as object) ? (value as CodeExpected) : { functionName: '', tests: [] };
  const setTest = (i: number, patch: Partial<CodeExpected['tests'][number]>) => onChange({ ...v, tests: v.tests.map((t, j) => (j === i ? { ...t, ...patch } : t)) });
  return (
    <div className="stack tight">
      <Field label="Function name">
        <input className="input sm mono" value={v.functionName} placeholder="mergeIntervals" onChange={(e) => onChange({ ...v, functionName: e.target.value })} />
      </Field>
      <table className="table compact unit-table">
        <thead>
          <tr>
            <th>#</th>
            <th>Arguments (JSON array)</th>
            <th>Expected return (JSON)</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {v.tests.map((t, i) => (
            <tr key={i}>
              <td className="muted">{i + 1}</td>
              <td>
                <JsonField label={`Arguments for test ${i + 1}`} rows={2} value={t.args} onChange={(a) => setTest(i, { args: Array.isArray(a) ? a : a === undefined ? [] : [a] })} placeholder="[[1,3],[2,6]]" />
              </td>
              <td>
                <JsonField label={`Expected for test ${i + 1}`} rows={2} value={t.expected} onChange={(x) => setTest(i, { expected: x })} placeholder="[[1,6]]" />
              </td>
              <td>
                <button type="button" className="btn ghost icon xs" aria-label={`Remove test ${i + 1}`} onClick={() => onChange({ ...v, tests: v.tests.filter((_, j) => j !== i) })}>
                  <Icon.X />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div>
        <button type="button" className="btn xs" onClick={() => onChange({ ...v, tests: [...v.tests, { args: [], expected: null }] })}>
          <Icon.Plus /> Add unit test
        </button>
      </div>
    </div>
  );
}

// ───────────────────────────── Expected editor ─────────────────────────────

function ExpectedEditor({ scorer, value, onChange }: { scorer: ScorerSpec; value: unknown; onChange: (v: unknown) => void }) {
  switch (scorer.type) {
    case 'exact': {
      const text = Array.isArray(value) ? value.join('\n') : value === undefined ? '' : String(value);
      return (
        <Field label="Accepted answers" hint="One per line — any line counts as correct.">
          <textarea
            className="textarea mono"
            rows={2}
            value={text}
            onChange={(e) => {
              const lines = e.target.value.split('\n');
              const clean = lines.map((l) => l.trim()).filter(Boolean);
              onChange(clean.length === 0 ? undefined : clean.length === 1 && lines.length === 1 ? clean[0] : lines);
            }}
          />
        </Field>
      );
    }
    case 'number':
      return (
        <Field label="Expected number">
          <input className="input tnum" type="number" step="any" value={typeof value === 'number' ? value : value === undefined ? '' : String(value)} onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))} />
        </Field>
      );
    case 'choice':
      return (
        <Field label="Correct option">
          <Seg
            label="Correct option"
            value={String(value ?? '')}
            onChange={(v) => onChange(v)}
            options={['A', 'B', 'C', 'D', 'E', 'F'].map((l) => ({ value: l, label: l }))}
          />
        </Field>
      );
    case 'regex':
      return (
        <Field label="Pattern for this case" hint="Optional — overrides the scorer pattern.">
          <input className="input mono" value={typeof value === 'string' ? value : ''} placeholder="^\\s*42\\s*$" onChange={(e) => onChange(e.target.value || undefined)} />
        </Field>
      );
    case 'contains':
      return (
        <Field label="Keywords for this case (JSON, optional)" hint='e.g. { "all": ["Paris"], "none": ["London"] }'>
          <JsonField label="Contains keywords" value={value} onChange={onChange} rows={3} />
        </Field>
      );
    case 'constraints':
      return (
        <Field label="Constraints">
          <ConstraintEditor value={value} onChange={onChange} />
        </Field>
      );
    case 'json':
      return (
        <Field label="Expected JSON" hint="Partial credit per matching leaf field.">
          <JsonField label="Expected JSON" value={value} onChange={onChange} rows={5} placeholder='{ "vendor": "Acme Ltd", "total": 120.5 }' />
        </Field>
      );
    case 'code-js':
      return <CodeTestsEditor value={value} onChange={onChange} />;
    case 'judge':
      return (
        <Field label="Reference answer (optional)" hint="Shown to the judges, never to the model.">
          <textarea className="textarea" rows={3} value={typeof value === 'string' ? value : value === undefined ? '' : JSON.stringify(value)} onChange={(e) => onChange(e.target.value || undefined)} />
        </Field>
      );
    case 'judge-classify':
      return (
        <Field label="Expected label (optional, for auditing)">
          <select className="select" value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value || undefined)}>
            <option value="">—</option>
            {scorer.labels.map((l) => (
              <option key={l.id} value={l.id}>
                {l.id}
              </option>
            ))}
          </select>
        </Field>
      );
    case 'artifact':
    case 'human':
      return <div className="muted" style={{ fontSize: '0.84rem' }}>No expected value — {scorer.type === 'artifact' ? 'scored by automated checks and the judge rubric.' : 'scored by humans in Blind Review.'}</div>;
  }
}

// ───────────────────────────── Scorer config ─────────────────────────────

const lines = (xs: string[] | undefined) => (xs ?? []).join('\n');
const unlines = (s: string) => s.split('\n').map((x) => x.trim()).filter(Boolean);

const ARTIFACT_CHECKS: Array<ArtifactCheck['check']> = ['parses', 'contains', 'max_bytes', 'no_external_requests', 'runs_without_errors', 'has_canvas_or_svg', 'responds_to_input'];

function ScorerConfig({ s, onChange }: { s: ScorerSpec; onChange: (s: ScorerSpec) => void }) {
  switch (s.type) {
    case 'exact':
      return (
        <Field label="Normalisation" hint="Applied to both the answer and each accepted value.">
          <Seg label="Normalisation" value={s.normalize ?? 'trim'} onChange={(v) => onChange({ ...s, normalize: v })} options={(['none', 'trim', 'lower', 'alnum'] as const).map((v) => ({ value: v, label: v }))} />
        </Field>
      );
    case 'number':
      return (
        <div className="form-grid">
          <Field label="Tolerance">
            <input className="input tnum" type="number" step="any" min={0} value={s.tolerance ?? 0} onChange={(e) => onChange({ ...s, tolerance: Number(e.target.value) })} />
          </Field>
          <label className="check" style={{ alignSelf: 'end', paddingBottom: 8 }}>
            <input type="checkbox" checked={!!s.relative} onChange={(e) => onChange({ ...s, relative: e.target.checked || undefined })} /> Relative (fraction of expected)
          </label>
        </div>
      );
    case 'choice':
      return <p className="muted">The model must answer with a single option letter on the FINAL ANSWER line.</p>;
    case 'regex':
      return (
        <div className="form-grid">
          <Field label="Pattern" hint="Leave empty to use each case’s expected value." className="span-2">
            <input className="input mono" value={s.pattern ?? ''} onChange={(e) => onChange({ ...s, pattern: e.target.value || undefined })} />
          </Field>
          <Field label="Flags">
            <input className="input mono" value={s.flags ?? ''} onChange={(e) => onChange({ ...s, flags: e.target.value || undefined })} />
          </Field>
          <label className="check" style={{ alignSelf: 'end', paddingBottom: 8 }}>
            <input type="checkbox" checked={!!s.fullText} onChange={(e) => onChange({ ...s, fullText: e.target.checked || undefined })} /> Match the full text
          </label>
        </div>
      );
    case 'contains':
      return (
        <div className="form-grid">
          {(['all', 'any', 'none'] as const).map((k) => (
            <Field key={k} label={k === 'all' ? 'Must include all' : k === 'any' ? 'Must include any' : 'Must include none'} hint="One per line">
              <textarea className="textarea mono" rows={3} value={lines(s[k])} onChange={(e) => onChange({ ...s, [k]: unlines(e.target.value) })} />
            </Field>
          ))}
          <label className="check">
            <input type="checkbox" checked={!!s.caseSensitive} onChange={(e) => onChange({ ...s, caseSensitive: e.target.checked || undefined })} /> Case-sensitive
          </label>
        </div>
      );
    case 'constraints':
      return (
        <label className="check">
          <input type="checkbox" checked={!!s.allOrNothing} onChange={(e) => onChange({ ...s, allOrNothing: e.target.checked || undefined })} /> All-or-nothing (otherwise partial credit per constraint). Constraints are set per case below.
        </label>
      );
    case 'json':
      return (
        <div className="form-grid">
          <label className="check">
            <input type="checkbox" checked={!!s.unorderedArrays} onChange={(e) => onChange({ ...s, unorderedArrays: e.target.checked || undefined })} /> Arrays are unordered
          </label>
          <label className="check">
            <input type="checkbox" checked={!!s.allOrNothing} onChange={(e) => onChange({ ...s, allOrNothing: e.target.checked || undefined })} /> All-or-nothing (otherwise partial credit per field)
          </label>
          <Field label="Number tolerance">
            <input className="input tnum" type="number" step="any" min={0} value={s.numberTolerance ?? ''} onChange={(e) => onChange({ ...s, numberTolerance: e.target.value === '' ? undefined : Number(e.target.value) })} />
          </Field>
        </div>
      );
    case 'code-js':
      return (
        <Field label="Timeout per test (ms)">
          <input className="input tnum" type="number" min={100} value={s.timeoutMs ?? 2000} onChange={(e) => onChange({ ...s, timeoutMs: Number(e.target.value) })} />
        </Field>
      );
    case 'judge':
      return (
        <div className="stack">
          <Field label="Rubric" hint="Sent verbatim to every judge. Be specific about what earns full marks.">
            <textarea className="textarea" rows={5} value={s.rubric} onChange={(e) => onChange({ ...s, rubric: e.target.value })} />
          </Field>
          <Field label="Pass threshold (0–1)">
            <input className="input tnum" type="number" step={0.05} min={0} max={1} value={s.passThreshold ?? 0.5} onChange={(e) => onChange({ ...s, passThreshold: Number(e.target.value) })} />
          </Field>
        </div>
      );
    case 'judge-classify':
      return (
        <div className="stack">
          <Field label="Instructions for the judges">
            <textarea className="textarea" rows={3} value={s.instructions} onChange={(e) => onChange({ ...s, instructions: e.target.value })} />
          </Field>
          <table className="table compact">
            <thead>
              <tr>
                <th>Label id</th>
                <th>Description</th>
                <th className="num">Score</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {s.labels.map((l, i) => {
                const set = (patch: Partial<JudgeLabel>) => onChange({ ...s, labels: s.labels.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
                return (
                  <tr key={i}>
                    <td>
                      <input className="input sm mono" value={l.id} onChange={(e) => set({ id: e.target.value })} aria-label="Label id" />
                    </td>
                    <td>
                      <input className="input sm" value={l.description} onChange={(e) => set({ description: e.target.value })} aria-label="Label description" />
                    </td>
                    <td className="num">
                      <input className="input sm tnum" style={{ width: 80 }} type="number" step={0.05} min={0} max={1} value={l.score} onChange={(e) => set({ score: Number(e.target.value) })} aria-label="Label score" />
                    </td>
                    <td>
                      <button type="button" className="btn ghost icon xs" aria-label="Remove label" onClick={() => onChange({ ...s, labels: s.labels.filter((_, j) => j !== i) })}>
                        <Icon.X />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div>
            <button type="button" className="btn xs" onClick={() => onChange({ ...s, labels: [...s.labels, { id: '', description: '', score: 0 }] })}>
              <Icon.Plus /> Add label
            </button>
          </div>
        </div>
      );
    case 'artifact': {
      const checks = s.checks ?? [];
      const has = (c: ArtifactCheck['check']) => checks.find((x) => x.check === c);
      const toggle = (c: ArtifactCheck['check'], on: boolean) => {
        const rest = checks.filter((x) => x.check !== c);
        const item: ArtifactCheck = c === 'contains' ? { check: c, text: '' } : c === 'max_bytes' ? { check: c, bytes: 200000 } : ({ check: c } as ArtifactCheck);
        onChange({ ...s, checks: on ? [...rest, item] : rest });
      };
      return (
        <div className="stack">
          <Field label="Format">
            <Seg label="Artifact format" value={s.format} onChange={(v) => onChange({ ...s, format: v })} options={[{ value: 'html', label: 'HTML' }, { value: 'svg', label: 'SVG' }]} />
          </Field>
          <div className="field">
            <span className="label">Automated checks</span>
            <div className="check-grid">
              {ARTIFACT_CHECKS.map((c) => {
                const item = has(c);
                return (
                  <div key={c} className="row wrap" style={{ gap: 8 }}>
                    <label className="check">
                      <input type="checkbox" checked={!!item} onChange={(e) => toggle(c, e.target.checked)} />
                      <span className="mono" style={{ fontSize: '0.82rem' }}>
                        {c}
                      </span>
                    </label>
                    {item && item.check === 'contains' && (
                      <input className="input sm" style={{ width: 160 }} placeholder="text" value={item.text} onChange={(e) => onChange({ ...s, checks: checks.map((x) => (x.check === 'contains' ? { check: 'contains', text: e.target.value } : x)) })} />
                    )}
                    {item && item.check === 'max_bytes' && (
                      <input className="input sm tnum" style={{ width: 110 }} type="number" value={item.bytes} onChange={(e) => onChange({ ...s, checks: checks.map((x) => (x.check === 'max_bytes' ? { check: 'max_bytes', bytes: Number(e.target.value) } : x)) })} />
                    )}
                  </div>
                );
              })}
            </div>
          </div>
          <Field label="Judge rubric (optional)">
            <textarea className="textarea" rows={3} value={s.rubric ?? ''} onChange={(e) => onChange({ ...s, rubric: e.target.value || undefined })} />
          </Field>
          <Field label="Judge weight (0–1)" hint="Share of the score from the judge; the rest from checks.">
            <input className="input tnum" type="number" step={0.05} min={0} max={1} value={s.judgeWeight ?? 0.5} onChange={(e) => onChange({ ...s, judgeWeight: Number(e.target.value) })} />
          </Field>
        </div>
      );
    }
    case 'human':
      return (
        <Field label="Rubric for human raters" hint="Shown in Blind Review.">
          <textarea className="textarea" rows={4} value={s.rubric} onChange={(e) => onChange({ ...s, rubric: e.target.value })} />
        </Field>
      );
  }
}

// ───────────────────────────── Page ─────────────────────────────

function blankTest(category: string): PromptTest {
  return {
    kind: 'prompt',
    id: '',
    version: '1.0.0',
    name: '',
    category,
    difficulty: 'medium',
    description: '',
    scorer: { type: 'exact', normalize: 'trim' },
    cases: [{ id: 'case-1', prompt: '' }],
  };
}

/** Drop empty optional fields so the saved JSON stays tidy. */
function clean(t: PromptTest): PromptTest {
  const out: Record<string, unknown> = { ...t };
  for (const k of ['system', 'preamble', 'hook', 'author']) if (!String(out[k] ?? '').trim()) delete out[k];
  if (!t.tags?.length) delete out.tags;
  if (!t.maxOutputTokens) delete out.maxOutputTokens;
  if (!t.timeLimitSec) delete out.timeLimitSec;
  if (!t.estimate || (!t.estimate.inputTokens && !t.estimate.outputTokens)) delete out.estimate;
  out.cases = t.cases.map((c) => {
    const cc: Record<string, unknown> = { ...c };
    if (c.turns && c.turns.length) delete cc.prompt;
    else delete cc.turns;
    if (!c.notes?.trim()) delete cc.notes;
    if (c.weight === undefined || c.weight === 1) delete cc.weight;
    if (c.expected === undefined) delete cc.expected;
    if (!c.images?.length) delete cc.images;
    return cc;
  });
  return out as unknown as PromptTest;
}

function Section({ id, n, title, desc, children, tools }: { id: string; n: number; title: string; desc?: ReactNode; children: ReactNode; tools?: ReactNode }) {
  return (
    <section className="card" id={id}>
      <div className="card-head">
        <div className="t">
          <h2>
            {n} · {title}
          </h2>
          {desc && <div className="desc">{desc}</div>}
        </div>
        {tools && <div className="tools">{tools}</div>}
      </div>
      <div className="card-body">{children}</div>
    </section>
  );
}

export default function TestBuilderPage({ editId }: { editId?: string }) {
  const { query } = useRoute();
  const { categories, meta } = useMeta();
  const toast = useToast();
  const from = query.get('from');
  const [draft, setDraft] = useState<PromptTest | null>(editId || from ? null : blankTest(categories[0]?.id ?? 'reasoning'));
  const [loadErr, setLoadErr] = useState<Error | null>(null);
  const [readOnly, setReadOnly] = useState<string | null>(null);
  const [idTouched, setIdTouched] = useState(!!editId);
  const [validation, setValidation] = useState<ValidateResult | null>(null);
  const [validating, setValidating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState('');
  const [importErr, setImportErr] = useState<string | null>(null);
  const originalHash = useRef<string | null>(null);

  useEffect(() => {
    if (!draft && categories.length && !editId && !from) setDraft(blankTest(categories[0].id));
  }, [categories, draft, editId, from]);

  useEffect(() => {
    const id = editId ?? from;
    if (!id) return;
    let alive = true;
    api
      .test(id)
      .then((d) => {
        if (!alive) return;
        if (d.definition.kind !== 'prompt') {
          setReadOnly('Simulation (program) tests are defined in code and can’t be edited in the builder.');
          return;
        }
        if (editId && d.summary.source !== 'custom') {
          setReadOnly('Built-in tests are read-only so published results stay reproducible. Duplicate it to make your own variant.');
          return;
        }
        originalHash.current = editId ? d.summary.hash : null;
        const def = structuredClone(d.definition);
        if (from) {
          // Image paths are relative to the source test's folder; the copy is saved in tests/custom/.
          def.cases = def.cases.map((c) => (c.images?.length ? { ...c, images: rebaseImages(d.summary.file, c.images) } : c));
          def.id = `${def.id}-copy`;
          def.name = `${def.name} (copy)`;
          def.version = '1.0.0';
          setIdTouched(true);
        }
        setDraft(def);
      })
      .catch((e: unknown) => alive && setLoadErr(e instanceof Error ? e : new Error(String(e))));
    return () => {
      alive = false;
    };
  }, [editId, from]);

  // Auto-suggest the id from category + name until the user edits it.
  useEffect(() => {
    if (!draft || idTouched) return;
    const suggestion = draft.name ? `${draft.category}.${slugify(draft.name)}` : '';
    if (suggestion !== draft.id) setDraft({ ...draft, id: suggestion });
  }, [draft, idTouched]);

  const definition = useMemo(() => (draft ? clean(draft) : null), [draft]);
  const json = useMemo(() => (definition ? JSON.stringify(definition, null, 2) : ''), [definition]);
  const debouncedJson = useDebounced(json, 700);

  // Live validation (debounced).
  useEffect(() => {
    if (!debouncedJson) return;
    let alive = true;
    setValidating(true);
    api
      .validateTest(JSON.parse(debouncedJson) as PromptTest)
      .then((v) => alive && setValidation(v))
      .catch(() => alive && setValidation(null))
      .finally(() => alive && setValidating(false));
    return () => {
      alive = false;
    };
  }, [debouncedJson]);

  if (loadErr) return <ErrorState error={loadErr} title="Couldn’t load the test" />;
  if (readOnly)
    return (
      <div className="page">
        <PageHead eyebrow="Test Builder" title="Read-only test" />
        <Callout tone="warn">{readOnly}</Callout>
        <div className="row">
          {editId && (
            <Link to={`/tests/new?from=${encodeURIComponent(editId)}`} className="btn primary">
              <Icon.Copy /> Duplicate instead
            </Link>
          )}
          <Link to={editId ? pathOf('tests', editId) : '/tests'} className="btn">
            Back
          </Link>
        </div>
      </div>
    );
  if (!draft || !definition) return <LoadingPage />;

  const set = (patch: Partial<PromptTest>) => setDraft((d) => (d ? { ...d, ...patch } : d));
  const setCase = (i: number, patch: Partial<PromptTestCase>) => set({ cases: draft.cases.map((c, j) => (j === i ? { ...c, ...patch } : c)) });
  const moveCase = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= draft.cases.length) return;
    const cs = [...draft.cases];
    [cs[i], cs[j]] = [cs[j], cs[i]];
    set({ cases: cs });
  };
  const errors = validation?.errors ?? [];
  const errFor = (prefix: string) => errors.filter((e) => e.startsWith(prefix));

  const save = async () => {
    setSaving(true);
    try {
      const v = await api.validateTest(definition);
      setValidation(v);
      if (!v.ok) {
        toast.error(`${v.errors.length} problem${v.errors.length === 1 ? '' : 's'} to fix before saving.`, 'Validation failed');
        return;
      }
      const saved = editId ? await api.updateTest(editId, definition) : await api.createTest(definition);
      toast.success(`${saved.name} v${saved.version} saved · hash ${saved.hash.slice(0, 8)}`, 'Test saved');
      navigate(pathOf('tests', saved.id));
    } catch (e) {
      if (e instanceof ApiError && Array.isArray(e.details)) setValidation({ ok: false, errors: e.details.map(String) });
      toast.error(e, 'Could not save');
    } finally {
      setSaving(false);
    }
  };

  const doImport = () => {
    try {
      const parsed = JSON.parse(importText) as unknown;
      const def = (parsed && typeof parsed === 'object' && 'definition' in (parsed as object) ? (parsed as { definition: unknown }).definition : parsed) as Partial<PromptTest> & { kind?: string };
      if (!def || typeof def !== 'object') throw new Error('Expected a JSON object');
      if (def.kind && def.kind !== 'prompt') throw new Error('Only prompt tests can be edited in the builder (this is a program test).');
      if (!Array.isArray(def.cases)) throw new Error('Missing "cases" array');
      setDraft({ ...blankTest(categories[0]?.id ?? 'reasoning'), ...(def as PromptTest), kind: 'prompt' });
      setIdTouched(true);
      setImportOpen(false);
      setImportErr(null);
      toast.success('Imported — review the fields, then validate and save.');
    } catch (e) {
      setImportErr((e as Error).message);
    }
  };

  const scrollTo = (err: string) => {
    const m = /^cases\[(\d+)\]/.exec(err);
    const id = m ? `case-${m[1]}` : err.startsWith('scorer') ? 'sec-scorer' : 'sec-identity';
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div className="page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Link to="/tests">Tests</Link> <Icon.ChevronRight style={{ width: 12, height: 12 }} /> {editId ? 'Edit' : from ? 'Duplicate' : 'New'}
          </span>
        }
        title={editId ? `Edit ${draft.name || editId}` : 'Test Builder'}
        sub="Author a prompt test: fixed prompts, a scorer, and cases with expected answers. Every model will receive these exact bytes."
        actions={
          <button className="btn" onClick={() => setImportOpen(true)}>
            <Icon.Upload /> Import JSON
          </button>
        }
      />

      <div className="grid split-8-4 builder">
        <div className="stack loose">
          <Section id="sec-identity" n={1} title="Identity" desc="How the test appears in the library, on the leaderboard and on video.">
            <div className="form-grid">
              <Field label="Category" htmlFor="tb-cat">
                <select id="tb-cat" className="select" value={draft.category} onChange={(e) => set({ category: e.target.value })}>
                  {categories.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Name" htmlFor="tb-name" className="span-2" error={errFor('name')[0]}>
                <input id="tb-name" className="input" value={draft.name} placeholder="River Crossing, Remixed" onChange={(e) => set({ name: e.target.value })} />
              </Field>
              <Field
                label={
                  <>
                    Id
                    {idTouched && !editId && (
                      <button type="button" className="btn ghost xs" onClick={() => setIdTouched(false)}>
                        use suggestion
                      </button>
                    )}
                  </>
                }
                hint={editId ? 'Ids can’t change after saving.' : '<category>.<slug> — auto-suggested from the name.'}
                error={errFor('id')[0]}
                htmlFor="tb-id"
                className="span-2"
              >
                <input
                  id="tb-id"
                  className={cx('input mono', errFor('id').length > 0 && 'invalid')}
                  value={draft.id}
                  disabled={!!editId}
                  placeholder={`${draft.category}.my-test`}
                  onChange={(e) => {
                    setIdTouched(true);
                    set({ id: e.target.value });
                  }}
                />
              </Field>
              <Field label="Version" hint="Semver. Bump when content changes." error={errFor('version')[0]} htmlFor="tb-ver">
                <input id="tb-ver" className="input mono" value={draft.version} onChange={(e) => set({ version: e.target.value })} />
              </Field>
              <Field label="Difficulty" className="span-2">
                <Seg label="Difficulty" value={draft.difficulty} onChange={(v) => set({ difficulty: v as Difficulty })} options={(['easy', 'medium', 'hard', 'extreme'] as const).map((d) => ({ value: d, label: d }))} />
              </Field>
              <Field label="Description" className="span-all" error={errFor('description')[0]} hint="What it measures and why it separates models." htmlFor="tb-desc">
                <textarea id="tb-desc" className="textarea" rows={2} value={draft.description} onChange={(e) => set({ description: e.target.value })} />
              </Field>
              <Field label="Hook" className="span-2" hint="One line for broadcast overlays." htmlFor="tb-hook">
                <input id="tb-hook" className="input" value={draft.hook ?? ''} placeholder="The goat eats the rope. Now what?" onChange={(e) => set({ hook: e.target.value })} />
              </Field>
              <Field label="Tags" hint="Comma-separated" htmlFor="tb-tags">
                <input id="tb-tags" className="input" value={(draft.tags ?? []).join(', ')} onChange={(e) => set({ tags: e.target.value.split(',').map((x) => x.trim()).filter(Boolean) })} />
              </Field>
              <Field label="Author" htmlFor="tb-author">
                <input id="tb-author" className="input" value={draft.author ?? ''} onChange={(e) => set({ author: e.target.value })} />
              </Field>
            </div>
          </Section>

          <Section id="sec-prompting" n={2} title="Prompting" desc="Shared by every case. The preamble is prepended (with a blank line) to each case prompt or first turn.">
            <div className="form-grid">
              <Field label="System prompt" className="span-all" htmlFor="tb-sys">
                <textarea id="tb-sys" className="textarea mono" rows={3} value={draft.system ?? ''} placeholder="You are a careful puzzle solver." onChange={(e) => set({ system: e.target.value })} />
              </Field>
              <Field
                label={
                  <>
                    Preamble
                    {!draft.preamble?.includes('FINAL ANSWER') && (
                      <button type="button" className="btn ghost xs" onClick={() => set({ preamble: draft.preamble ? `${draft.preamble}\n\n${FINAL_ANSWER}` : FINAL_ANSWER })}>
                        + FINAL ANSWER instruction
                      </button>
                    )}
                  </>
                }
                className="span-all"
                hint="Extractive scorers (exact, number, choice, regex) read the FINAL ANSWER line."
                htmlFor="tb-pre"
              >
                <textarea id="tb-pre" className="textarea mono" rows={3} value={draft.preamble ?? ''} onChange={(e) => set({ preamble: e.target.value })} />
              </Field>
              <Field label="Max output tokens" htmlFor="tb-max">
                <input id="tb-max" className="input tnum" type="number" min={1} placeholder={String(meta?.settings.defaultMaxOutputTokens ?? 16000)} value={draft.maxOutputTokens ?? ''} onChange={(e) => set({ maxOutputTokens: e.target.value ? Number(e.target.value) : undefined })} />
              </Field>
              <Field label="Time limit per case (s)" htmlFor="tb-time">
                <input id="tb-time" className="input tnum" type="number" min={1} placeholder={String(meta?.settings.defaultTimeLimitSec ?? 600)} value={draft.timeLimitSec ?? ''} onChange={(e) => set({ timeLimitSec: e.target.value ? Number(e.target.value) : undefined })} />
              </Field>
              <Field label="Estimate: input tokens / case" htmlFor="tb-ein">
                <input
                  id="tb-ein"
                  className="input tnum"
                  type="number"
                  min={0}
                  value={draft.estimate?.inputTokens ?? ''}
                  onChange={(e) => set({ estimate: { inputTokens: Number(e.target.value) || 0, outputTokens: draft.estimate?.outputTokens ?? 0, calls: draft.estimate?.calls } })}
                />
              </Field>
              <Field label="Estimate: output tokens / case" htmlFor="tb-eout">
                <input
                  id="tb-eout"
                  className="input tnum"
                  type="number"
                  min={0}
                  value={draft.estimate?.outputTokens ?? ''}
                  onChange={(e) => set({ estimate: { inputTokens: draft.estimate?.inputTokens ?? 0, outputTokens: Number(e.target.value) || 0, calls: draft.estimate?.calls } })}
                />
              </Field>
            </div>
          </Section>

          <Section id="sec-scorer" n={3} title="Scorer" desc="How each reply becomes a 0–1 score.">
            <div className="scorer-grid" role="radiogroup" aria-label="Scorer type">
              {SCORERS.map((o) => (
                <button key={o.type} type="button" role="radio" aria-checked={draft.scorer.type === o.type} className={cx('scorer-opt', draft.scorer.type === o.type && 'on')} onClick={() => draft.scorer.type !== o.type && set({ scorer: defaultScorer(o.type) })}>
                  <strong>{o.label}</strong>
                  <span>{o.desc}</span>
                </button>
              ))}
            </div>
            <div className="scorer-config">
              <ScorerConfig s={draft.scorer} onChange={(s) => set({ scorer: s })} />
              {errFor('scorer').map((e) => (
                <div key={e} className="err" style={{ color: 'var(--bad-text)', fontSize: '0.8rem' }}>
                  {e}
                </div>
              ))}
            </div>
          </Section>

          <Section
            id="sec-cases"
            n={4}
            title={`Cases · ${draft.cases.length}`}
            desc="Each case is one prompt (or a multi-turn conversation) with its expected answer."
            tools={
              <button type="button" className="btn sm" onClick={() => set({ cases: [...draft.cases, { id: `case-${draft.cases.length + 1}`, prompt: '' }] })}>
                <Icon.Plus /> Add case
              </button>
            }
          >
            <div className="stack">
              {draft.cases.map((c, i) => {
                const multi = !!c.turns;
                const caseErrs = errFor(`cases[${i}]`);
                return (
                  <div key={i} id={`case-${i}`} className={cx('case-edit', caseErrs.length > 0 && 'has-err')}>
                    <div className="row wrap" style={{ gap: 8 }}>
                      <span className="case-n">{i + 1}</span>
                      <input className="input sm mono" style={{ width: 200 }} value={c.id} aria-label={`Case ${i + 1} id`} onChange={(e) => setCase(i, { id: e.target.value })} />
                      <Seg
                        small
                        label="Case mode"
                        value={multi ? 'multi' : 'single'}
                        onChange={(v) => setCase(i, v === 'multi' ? { turns: [c.prompt ?? ''], prompt: undefined } : { prompt: c.turns?.[0] ?? '', turns: undefined })}
                        options={[
                          { value: 'single', label: 'Single prompt' },
                          { value: 'multi', label: 'Multi-turn' },
                        ]}
                      />
                      <span className="spacer" />
                      <button type="button" className="btn ghost icon xs" aria-label="Move case up" disabled={i === 0} onClick={() => moveCase(i, -1)}>
                        <Icon.ArrowUp />
                      </button>
                      <button type="button" className="btn ghost icon xs" aria-label="Move case down" disabled={i === draft.cases.length - 1} onClick={() => moveCase(i, 1)}>
                        <Icon.ArrowDown />
                      </button>
                      <button type="button" className="btn ghost icon xs" aria-label="Duplicate case" onClick={() => set({ cases: [...draft.cases.slice(0, i + 1), { ...structuredClone(c), id: `${c.id}-copy` }, ...draft.cases.slice(i + 1)] })}>
                        <Icon.Copy />
                      </button>
                      <button type="button" className="btn ghost icon xs" aria-label="Remove case" disabled={draft.cases.length === 1} onClick={() => set({ cases: draft.cases.filter((_, j) => j !== i) })}>
                        <Icon.Trash />
                      </button>
                    </div>
                    {multi ? (
                      <div className="stack tight">
                        {(c.turns ?? []).map((t, k) => (
                          <div key={k} className="turn-row">
                            <span className="mini-title" style={{ margin: 0 }}>
                              Turn {k + 1}
                            </span>
                            <textarea className="textarea" rows={2} value={t} aria-label={`Case ${i + 1} turn ${k + 1}`} onChange={(e) => setCase(i, { turns: (c.turns ?? []).map((x, j) => (j === k ? e.target.value : x)) })} />
                            <button type="button" className="btn ghost icon xs" aria-label="Remove turn" disabled={(c.turns ?? []).length === 1} onClick={() => setCase(i, { turns: (c.turns ?? []).filter((_, j) => j !== k) })}>
                              <Icon.X />
                            </button>
                          </div>
                        ))}
                        <div>
                          <button type="button" className="btn xs" onClick={() => setCase(i, { turns: [...(c.turns ?? []), ''] })}>
                            <Icon.Plus /> Add turn
                          </button>
                          <span className="muted" style={{ fontSize: '0.78rem', marginLeft: 10 }}>
                            The model’s replies are kept between turns; the final reply is scored.
                          </span>
                        </div>
                      </div>
                    ) : (
                      <textarea className="textarea" rows={4} value={c.prompt ?? ''} placeholder="The user message for this case…" aria-label={`Case ${i + 1} prompt`} onChange={(e) => setCase(i, { prompt: e.target.value })} />
                    )}
                    <CaseImageEditor testId={draft.id} images={c.images} turns={c.turns?.length ?? 1} onChange={(images) => setCase(i, { images })} />
                    <ExpectedEditor scorer={c.scorer ?? draft.scorer} value={c.expected} onChange={(v) => setCase(i, { expected: v })} />
                    <div className="form-grid">
                      <Field label="Weight">
                        <input className="input sm tnum" type="number" min={0} step={0.5} value={c.weight ?? 1} onChange={(e) => setCase(i, { weight: Number(e.target.value) })} />
                      </Field>
                      <Field label="Auditor notes" hint="How the answer was derived. Never sent to the model." className="span-2">
                        <input className="input sm" value={c.notes ?? ''} onChange={(e) => setCase(i, { notes: e.target.value })} />
                      </Field>
                    </div>
                    {c.scorer && <div className="muted" style={{ fontSize: '0.78rem' }}>This case overrides the scorer ({c.scorer.type}) — edit it in the JSON import.</div>}
                    {caseErrs.map((e) => (
                      <div key={e} style={{ color: 'var(--bad-text)', fontSize: '0.8rem' }}>
                        {e}
                      </div>
                    ))}
                  </div>
                );
              })}
            </div>
          </Section>
        </div>

        <div className="sticky-col stack">
          <Callout tone="info" icon={<Icon.Fingerprint />}>
            <strong>Changing a test changes its hash.</strong> Results recorded with the old hash stop counting toward the leaderboard. That’s intentional: every published score is reproducible with the exact test it was produced
            with.
            {originalHash.current && validation?.hash && validation.hash !== originalHash.current && (
              <div style={{ marginTop: 6 }}>
                Hash will change: <span className="hash">{originalHash.current.slice(0, 8)}</span> → <span className="hash">{validation.hash.slice(0, 8)}</span>
              </div>
            )}
          </Callout>
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>Validate &amp; save</h2>
                <div className="desc">{validating ? 'Checking…' : validation ? (validation.ok ? 'Looks good.' : `${validation.errors.length} problem${validation.errors.length === 1 ? '' : 's'}`) : 'Validates as you type.'}</div>
              </div>
              {validating && <span className="spinner" />}
            </div>
            <div className="card-body stack">
              {validation?.ok && (
                <div className="row" style={{ gap: 8 }}>
                  <span className="badge good">
                    <Icon.Check /> valid
                  </span>
                  {validation.hash && (
                    <>
                      <span className="muted" style={{ fontSize: '0.8rem' }}>
                        hash
                      </span>
                      <span className="hash">{validation.hash.slice(0, 12)}</span>
                    </>
                  )}
                </div>
              )}
              {errors.length > 0 && (
                <ul className="error-list">
                  {errors.map((e) => (
                    <li key={e}>
                      <button type="button" onClick={() => scrollTo(e)}>
                        <Icon.Alert /> {e}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <div className="row">
                <button
                  className="btn"
                  onClick={async () => {
                    setValidating(true);
                    try {
                      setValidation(await api.validateTest(definition));
                    } catch (e) {
                      toast.error(e);
                    } finally {
                      setValidating(false);
                    }
                  }}
                >
                  <Icon.Check /> Validate
                </button>
                <button className="btn primary grow" onClick={save} disabled={saving}>
                  {saving ? 'Saving…' : editId ? 'Save changes' : 'Save test'}
                </button>
              </div>
              <span className="muted" style={{ fontSize: '0.78rem' }}>
                Saves to <span className="mono">tests/custom/{draft.id || '<id>'}.json</span>
                {editId ? ' · the version auto-bumps if content changed' : ''}
              </span>
            </div>
          </section>
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>JSON preview</h2>
              </div>
              <div className="tools">
                <CopyButton text={json} iconOnly label="Copy JSON" />
              </div>
            </div>
            <div className="card-body">
              <pre className="code json-preview">{json}</pre>
            </div>
          </section>
        </div>
      </div>

      <Modal
        open={importOpen}
        onClose={() => setImportOpen(false)}
        title="Import test JSON"
        width={720}
        foot={
          <>
            <button className="btn ghost" onClick={() => setImportOpen(false)}>
              Cancel
            </button>
            <button className="btn primary" onClick={doImport} disabled={!importText.trim()}>
              Import
            </button>
          </>
        }
      >
        <div className="stack">
          <p>Paste a prompt-test definition (or a <span className="mono">{'{ "definition": … }'}</span> wrapper). It replaces the current draft.</p>
          <textarea className={cx('textarea mono', importErr && 'invalid')} rows={14} value={importText} onChange={(e) => setImportText(e.target.value)} aria-label="Test JSON" data-autofocus />
          {importErr && <div style={{ color: 'var(--bad-text)', fontSize: '0.84rem' }}>{importErr}</div>}
        </div>
      </Modal>
    </div>
  );
}
