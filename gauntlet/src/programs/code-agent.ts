/**
 * Fix the Bug — a coding-agent test.
 *
 * The model is dropped into a small real JavaScript repository whose test
 * suite is failing. Over several turns it explores (LIST / READ / SEARCH),
 * edits (WRITE / PATCH), runs the visible tests (RUN_TESTS) and finally
 * SUBMITs. Its code is then graded by hidden tests that check the same
 * behaviour more thoroughly. All code runs in the sandbox on an in-memory
 * copy of the repo; the model can't touch the tests.
 *
 * Each case (seed) is one fixture repo from src/programs/fixtures/code-agent/.
 * The conversation keeps the last few exchanges verbatim and shortens older
 * ones, so the cost per turn stays bounded.
 */
import type { ChatMessage, CodeReplayFrame, ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import { parseReply, type Action } from './lib/code-agent-protocol.ts';
import { Workspace, formatTestRun } from './lib/code-agent-workspace.ts';
import { hiddenRunFiles, listRepoIds, loadRepo, testFilesOf, type FixtureRepo } from './lib/code-agent-repos.ts';
import { passedOf, runProjectTests, testKey, type ProjectRun } from './lib/code-agent-sandbox.ts';
import { diffRows, unifiedDiff } from './lib/code-agent-diff.ts';
import { computeScore } from './lib/code-agent-score.ts';

export const CODE_AGENT_DEFAULTS = {
  /** Every action counts, valid or not, including SUBMIT. */
  maxActions: 30,
  /** Total output tokens (including reasoning) the model may spend; running out submits automatically. */
  tokenBudget: 150_000,
  /** Exchanges kept verbatim in the conversation; older ones are shortened. */
  keepFull: 6,
  perTestTimeoutMs: 2000,
  /** seed → repo id. Seeds not listed pick from the standard repos by seed. */
  repos: {} as Record<string, string>,
};

interface CodeAgentConfig {
  maxActions: number;
  tokenBudget: number;
  keepFull: number;
  perTestTimeoutMs: number;
  repos: Record<string, string>;
}

function readConfig(config: Record<string, unknown>): CodeAgentConfig {
  const num = (k: string, fallback: number, lo: number, hi: number): number => {
    const v = Number(config[k] ?? fallback);
    return Number.isFinite(v) ? Math.round(Math.min(hi, Math.max(lo, v))) : fallback;
  };
  const repos: Record<string, string> = {};
  if (config.repos && typeof config.repos === 'object') for (const [k, v] of Object.entries(config.repos as Record<string, unknown>)) if (typeof v === 'string') repos[k] = v;
  return {
    maxActions: num('maxActions', CODE_AGENT_DEFAULTS.maxActions, 3, 200),
    tokenBudget: num('tokenBudget', CODE_AGENT_DEFAULTS.tokenBudget, 1000, 5_000_000),
    keepFull: num('keepFull', CODE_AGENT_DEFAULTS.keepFull, 1, 50),
    perTestTimeoutMs: num('perTestTimeoutMs', CODE_AGENT_DEFAULTS.perTestTimeoutMs, 200, 10_000),
    repos,
  };
}

/** Which fixture repo a seed plays. */
export function repoForSeed(seed: number, config: Record<string, unknown>): string {
  const cfg = readConfig(config);
  const named = cfg.repos[String(seed)];
  if (named) return named;
  const pool = listRepoIds().filter((id) => loadRepo(id).meta.tier === 'standard');
  if (!pool.length) throw new Error('No code-agent fixture repos found');
  return pool[seed % pool.length]!;
}

export function codeAgentSystemPrompt(maxActions: number, tokenBudget: number): string {
  return `You are a software engineer fixing bugs in a small JavaScript (Node.js, CommonJS) repository. Its test suite is failing. Find and fix the bugs in the source code so the project behaves as its README describes.

HOW THIS WORKS
- You work through tools, ONE action per reply. After each action you get its result and choose the next one.
- You have ${maxActions} actions and ${groupDigits(tokenBudget)} output tokens (including any reasoning) in total. Every action counts, even an invalid one. When either budget runs out, your work is submitted as it is.
- After you SUBMIT, your code is graded by a larger set of HIDDEN tests that check the same behaviour as the visible tests and the README, more thoroughly. Hard-coding the visible tests' expected values will not work: fix the real bugs.
- Files under tests/ are read-only. The tests are correct; do not try to change or delete them (attempts are rejected and cost points).
- Code runs in a sandbox: plain Node.js CommonJS, no npm packages, no file system, network or child processes. Only project files, node:assert and node:test can be required.
- Score: mostly the share of failing hidden tests you fix (breaking working code counts against you), plus the visible tests, plus a small bonus for using fewer actions and tokens.

ACTIONS — end each reply with exactly one line that starts with ACTION:
ACTION: LIST                        list every file with its line count
ACTION: READ <path>                 show a file with line numbers (up to 250 lines)
ACTION: READ <path> <start>-<end>   show only those lines
ACTION: SEARCH <text>               find lines containing the text (case-insensitive) in all files
ACTION: RUN_TESTS                   run the visible tests and show every failure
ACTION: WRITE <path>                create or replace a whole file: put the COMPLETE new content in one fenced code block right after the ACTION line
ACTION: PATCH <path>                change part of a file with one or more SEARCH/REPLACE blocks right after the ACTION line:
<<<<<<< SEARCH
the exact lines currently in the file
=======
the lines to put there instead
>>>>>>> REPLACE
ACTION: SUBMIT                      finish; your code is graded by the hidden tests

PATCH RULES: copy the SEARCH lines from the file exactly (without the line-number prefix that READ shows); whitespace differences at the start or end of a line are tolerated. The SEARCH text must match exactly one place, so include a few surrounding lines. Several blocks can follow one PATCH; they are applied in order, and if any block fails nothing is changed. Prefer PATCH for small changes; WRITE replaces the whole file.

REPLY FORMAT: think as much as you like, then write the ACTION line last (followed only by the WRITE code block or the PATCH blocks). If a reply contains several ACTION lines, only the last one counts.`;
}

/** 150000 → "150,000" without locale data (identical bytes on every platform). */
function groupDigits(n: number): string {
  return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function introMessage(repo: FixtureRepo, ws: Workspace, cfg: CodeAgentConfig): string {
  const readme = repo.files['README.md'] ?? '';
  return [
    `ISSUE\n${repo.meta.issue}`,
    `REPOSITORY: ${repo.meta.title}\n${ws.list()}`,
    readme ? `README.md\n${readme.trim()}` : '',
    `You have ${cfg.maxActions} actions. Reply with your first action.\n${optionsLine(ws)}`,
  ]
    .filter(Boolean)
    .join('\n\n');
}

/** Backticked example actions (the Random Baseline picks one of these). */
function optionsLine(ws: Workspace): string {
  const reads = ws
    .paths()
    .filter((p) => p.startsWith('src/'))
    .slice(0, 14)
    .map((p) => `\`READ ${p}\``);
  return `Options include: \`RUN_TESTS\`, \`LIST\`, ${reads.join(', ')}, \`SUBMIT\`.`;
}

interface Exchange {
  reply: string;
  /** Short form of the action, e.g. "READ src/money.js". */
  summary: string;
  /** Full result text sent back to the model. */
  result: string;
  /** One-line result used once the exchange is old. */
  brief: string;
}

/** The conversation for the next call: recent exchanges verbatim, older ones shortened. */
export function buildMessages(intro: string, history: readonly Exchange[], keepFull: number): ChatMessage[] {
  const msgs: ChatMessage[] = [{ role: 'user', content: intro }];
  history.forEach((h, i) => {
    const old = i < history.length - keepFull;
    msgs.push({ role: 'assistant', content: old ? `(earlier reply, shortened)\nACTION: ${h.summary}` : h.reply });
    msgs.push({ role: 'user', content: old ? h.brief : h.result });
  });
  return msgs;
}

function summarize(action: Action): string {
  switch (action.kind) {
    case 'READ':
      return action.start !== undefined ? `READ ${action.path} ${action.start}-${action.end ?? action.start}` : `READ ${action.path}`;
    case 'SEARCH':
      return `SEARCH ${action.text}`;
    case 'WRITE':
      return `WRITE ${action.path}`;
    case 'PATCH':
      return `PATCH ${action.path} (${action.blocks.length} block${action.blocks.length === 1 ? '' : 's'})`;
    default:
      return action.kind;
  }
}

function shorten(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}

interface TestSnapshot {
  passed: number;
  total: number;
  failing: string[];
}

function snapshotOf(run: ProjectRun, reference: string[]): TestSnapshot {
  const ok = new Set(run.tests.filter((t) => t.ok).map(testKey));
  const failing = reference.filter((k) => !ok.has(k)).map((k) => k.replace(/^tests\//, ''));
  return { passed: passedOf(run, reference), total: reference.length, failing };
}

export const program: ProgramDefinition = {
  id: 'code-agent',
  name: 'Fix the Bug',
  description:
    'A coding-agent test. The model is dropped into a small real JavaScript repository (5–15 files) with a README, a failing visible test suite and 1–3 planted bugs of realistic kinds: off-by-one, rounding, operator precedence, date logic, shared mutable state, async ordering. ' +
    'Using one text action per turn (LIST, READ, SEARCH, WRITE, PATCH, RUN_TESTS, SUBMIT) under an action and token budget, it must investigate, edit the code and make the tests pass. ' +
    'Everything runs in the sandbox on an in-memory copy of the repo, and the tests are read-only. After SUBMIT, hidden tests that check the same behaviour more thoroughly decide the score, so hard-coding the visible tests does not work.',
  scoring:
    'Score = 0.75 × hidden + 0.15 × visible + 0.10 × efficiency − penalties. hidden (and visible) = share of the tests that failed on the original code which pass now, net of regressions (tests that passed originally and now fail), so doing nothing scores 0. ' +
    'efficiency = hidden × (½ × min(1, par ÷ actions used) + ½ × (1 − output tokens used ÷ token budget)), where par is what a competent engineer needs for that repo. ' +
    'Each attempt to edit a read-only test file costs 0.1 (max 0.3). Running out of actions or tokens submits the code as it is. Passed = every hidden test passes.',
  defaults: CODE_AGENT_DEFAULTS,
  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const repoId = repoForSeed(ctx.seed, ctx.config);
    const repo = loadRepo(repoId);
    const ws = new Workspace(repo.files);
    const visibleFiles = testFilesOf(repo.files);
    const runOpts = { perTestTimeoutMs: cfg.perTestTimeoutMs, signal: ctx.signal };

    // Baselines: what passes on the untouched repo.
    const origVisible = await runProjectTests(repo.files, visibleFiles, runOpts);
    const origHiddenSet = hiddenRunFiles(repo.files, repo);
    const origHidden = await runProjectTests(origHiddenSet.files, origHiddenSet.tests, runOpts);
    if (origVisible.crashed || origHidden.crashed) throw new Error(`Sandbox failed on the original ${repoId} repo: ${origVisible.crashed ?? origHidden.crashed}`);
    const visibleRef = origVisible.tests.map(testKey);
    const hiddenRef = origHidden.tests.map(testKey);
    const visibleBefore = passedOf(origVisible, visibleRef);
    const hiddenBefore = passedOf(origHidden, hiddenRef);

    const system = codeAgentSystemPrompt(cfg.maxActions, cfg.tokenBudget);
    const intro = introMessage(repo, ws, cfg);
    const history: Exchange[] = [];
    const frames: ReplayFrame[] = [];
    const actionLog: Array<{ n: number; action: string; ok: boolean; note?: string }> = [];

    let actions = 0;
    let tokens = 0;
    let tamperAttempts = 0;
    let invalidActions = 0;
    let testRuns = 0;
    let submittedBy: 'model' | 'actions' | 'tokens' = 'actions';
    let lastTests: TestSnapshot = snapshotOf(origVisible, visibleRef);

    const frame = (code: Omit<CodeReplayFrame, 'files' | 'actions' | 'tokens' | 'tests'> & { tests?: CodeReplayFrame['tests'] }, f: Omit<ReplayFrame, 'step' | 'code'>): void => {
      const full: CodeReplayFrame = {
        ...code,
        files: ws.paths().map((path) => ({ path, lines: ws.lineCount(path), state: ws.state(path) })),
        tests: code.tests ?? { ...lastTests, failing: lastTests.failing.slice(0, 6), ranThisStep: false },
        actions: { used: actions, budget: cfg.maxActions },
        tokens: { used: tokens, budget: cfg.tokenBudget },
      };
      frames.push({
        step: frames.length,
        ...f,
        stats: { 'actions left': Math.max(0, cfg.maxActions - actions), 'visible tests': `${full.tests!.passed}/${full.tests!.total}` },
        code: full,
      });
    };

    frame(
      { kind: 'start', tests: { ...lastTests, failing: lastTests.failing.slice(0, 6), ranThisStep: true } },
      { label: 'Start', observation: repo.meta.issue, outcome: `${repo.meta.title}: ${lastTests.passed} of ${lastTests.total} visible tests pass. ${repo.meta.bugs.length} bug${repo.meta.bugs.length === 1 ? '' : 's'} planted.`, tone: 'neutral' },
    );

    while (actions < cfg.maxActions) {
      if (ctx.signal.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
      const reply = await ctx.model.complete({ system, messages: buildMessages(intro, history, cfg.keepFull), maxOutputTokens: ctx.maxOutputTokens, label: `Action ${actions + 1}` });
      actions++;
      tokens += Math.max(0, reply.outputTokens || 0);
      const text = typeof reply.text === 'string' ? reply.text : '';
      const parsed = reply.stopReason === 'refusal' ? { action: null, error: 'The model refused to answer.', line: '', ignoredActions: 0 } : parseReply(text);
      const n = actions;
      let result = '';
      let brief = '';
      let summary = parsed.line || '(no action)';
      let done = false;

      if (!parsed.action) {
        invalidActions++;
        result = `Invalid action: ${parsed.error}`;
        brief = shorten(result, 160);
        actionLog.push({ n, action: summary, ok: false, note: parsed.error ?? undefined });
        frame({ kind: 'invalid' }, { label: `Action ${n}`, action: shorten(parsed.line || '(no action)', 90), outcome: shorten(parsed.error ?? 'Invalid action', 200), tone: 'bad' });
      } else {
        const a = parsed.action;
        summary = summarize(a);
        const truncatedEdit = reply.stopReason === 'max_tokens' && (a.kind === 'WRITE' || a.kind === 'PATCH');
        if (truncatedEdit) {
          invalidActions++;
          result = `Your reply was cut off because it reached the output limit, so the ${a.kind} was not applied. Use PATCH for small changes, or write less reasoning before the ACTION line.`;
          brief = shorten(result, 160);
          actionLog.push({ n, action: summary, ok: false, note: 'cut off' });
          frame({ kind: 'invalid', touched: [a.path] }, { label: `Action ${n}`, action: summary, outcome: 'Reply cut off at the output limit; the edit was not applied.', tone: 'bad' });
        } else if (a.kind === 'LIST') {
          result = ws.list();
          brief = '(file list shown earlier)';
          actionLog.push({ n, action: summary, ok: true });
          frame({ kind: 'list' }, { label: `Action ${n}`, action: 'LIST', outcome: `Listed ${ws.paths().length} files.`, tone: 'neutral' });
        } else if (a.kind === 'READ') {
          const r = ws.read(a.path, a.start, a.end);
          result = r.text;
          brief = r.ok ? `(${r.path} lines ${r.from}-${r.from + r.lines.length - 1} were shown here; READ again if you need them)` : shorten(r.text, 160);
          if (!r.ok) invalidActions++;
          actionLog.push({ n, action: summary, ok: r.ok });
          frame(
            { kind: 'read', touched: r.path ? [r.path] : [], view: r.ok && r.path ? { path: r.path, start: r.from, lines: r.lines.slice(0, 40).map((l) => (l.length > 140 ? `${l.slice(0, 139)}…` : l)) } : undefined },
            { label: `Action ${n}`, action: summary, outcome: r.ok ? `Read ${r.path} (${r.lines.length} lines).` : shorten(r.text, 160), tone: r.ok ? 'neutral' : 'bad' },
          );
        } else if (a.kind === 'SEARCH') {
          const s = ws.search(a.text);
          result = s.text;
          brief = `(search results for "${shorten(a.text, 40)}" were shown here: ${s.hits.length} hit${s.hits.length === 1 ? '' : 's'})`;
          actionLog.push({ n, action: summary, ok: true });
          frame(
            { kind: 'search', touched: [...new Set(s.hits.map((h) => h.path))] },
            { label: `Action ${n}`, action: summary, outcome: s.hits.length ? `${s.hits.length} match${s.hits.length === 1 ? '' : 'es'} in ${new Set(s.hits.map((h) => h.path)).size} file(s).` : 'No matches.', tone: 'neutral' },
          );
        } else if (a.kind === 'WRITE' || a.kind === 'PATCH') {
          const out = a.kind === 'WRITE' ? ws.write(a.path, a.content) : ws.patch(a.path, a.blocks);
          result = out.message;
          brief = shorten(out.message, 200);
          if (!out.ok) {
            if (out.tamper) tamperAttempts++;
            else invalidActions++;
            actionLog.push({ n, action: summary, ok: false, note: out.tamper ? 'tried to edit a test file' : undefined });
            frame({ kind: 'rejected', touched: [out.path] }, { label: `Action ${n}`, action: summary, outcome: shorten(out.message, 200), tone: 'bad' });
          } else {
            const d = diffRows(out.before ?? '', out.after);
            actionLog.push({ n, action: summary, ok: true });
            frame(
              { kind: 'edit', touched: [out.path], diff: { path: out.path, added: d.added, removed: d.removed, rows: d.rows } },
              { label: `Action ${n}`, action: summary, outcome: `${out.before === null ? 'Created' : 'Edited'} ${out.path}: +${d.added} −${d.removed} lines.`, tone: 'neutral' },
            );
          }
        } else if (a.kind === 'RUN_TESTS') {
          const run = await runProjectTests(ws.files, visibleFiles, runOpts);
          testRuns++;
          const prev = lastTests.passed;
          lastTests = snapshotOf(run, visibleRef);
          result = formatTestRun(run);
          brief = `(test run) Visible tests: ${lastTests.passed}/${lastTests.total} passing${lastTests.failing.length ? `; failing: ${shorten(lastTests.failing.slice(0, 4).join(' · '), 200)}` : ''}`;
          actionLog.push({ n, action: summary, ok: true, note: `${lastTests.passed}/${lastTests.total}` });
          frame(
            { kind: 'tests', tests: { ...lastTests, failing: lastTests.failing.slice(0, 6), ranThisStep: true } },
            {
              label: `Action ${n}`,
              action: 'RUN_TESTS',
              outcome: `${lastTests.passed} of ${lastTests.total} visible tests pass${lastTests.passed === lastTests.total ? ' — all green.' : '.'}`,
              tone: lastTests.passed > prev || lastTests.passed === lastTests.total ? 'good' : lastTests.passed < prev ? 'bad' : 'neutral',
            },
          );
        } else if (a.kind === 'SUBMIT') {
          submittedBy = 'model';
          result = 'Submitted.';
          brief = 'Submitted.';
          done = true;
          actionLog.push({ n, action: 'SUBMIT', ok: true });
          frame({ kind: 'submit' }, { label: `Action ${n}`, action: 'SUBMIT', outcome: 'Submitted for grading by the hidden tests.', tone: 'neutral' });
        }
      }

      if (parsed.ignoredActions > 0 && !done) result += `\n\n(Note: one action per reply — only your last ACTION line was used; ${parsed.ignoredActions} earlier one${parsed.ignoredActions === 1 ? ' was' : 's were'} ignored.)`;
      if (done) {
        history.push({ reply: text, summary, result, brief });
        break;
      }
      const left = cfg.maxActions - actions;
      const tokensLeft = Math.max(0, cfg.tokenBudget - tokens);
      const changed = ws.changed();
      const status =
        `Status: ${left} action${left === 1 ? '' : 's'} and ${groupDigits(tokensLeft)} output tokens left · ` +
        `changed files: ${changed.length ? changed.join(', ') : 'none'} · ` +
        (testRuns ? `last test run: ${lastTests.passed}/${lastTests.total} visible tests passing` : 'tests not run yet');
      const full = `Result of action ${n} (${summary}):\n\n${result}\n\n${status}\n${left > 0 ? `Reply with your next action. ${optionsLine(ws)}` : 'No actions left: your work is being submitted.'}`;
      history.push({ reply: text, summary, result: full, brief: `Result of action ${n} (${summary}): ${brief}` });
      if (tokens >= cfg.tokenBudget) {
        submittedBy = 'tokens';
        break;
      }
    }

    // Final grading.
    const finalVisibleRun = await runProjectTests(ws.files, visibleFiles, runOpts);
    const hiddenSet = hiddenRunFiles(ws.files, repo);
    const finalHiddenRun = await runProjectTests(hiddenSet.files, hiddenSet.tests, runOpts);
    const visibleNow = passedOf(finalVisibleRun, visibleRef);
    const hiddenNow = passedOf(finalHiddenRun, hiddenRef);
    const s = computeScore({
      hidden: { before: hiddenBefore, now: hiddenNow, total: hiddenRef.length },
      visible: { before: visibleBefore, now: visibleNow, total: visibleRef.length },
      actionsUsed: actions,
      par: repo.meta.par,
      tokensUsed: tokens,
      tokenBudget: cfg.tokenBudget,
      tamperAttempts,
    });

    lastTests = snapshotOf(finalVisibleRun, visibleRef);
    const hiddenSnap = snapshotOf(finalHiddenRun, hiddenRef);
    const how = submittedBy === 'model' ? 'Submitted' : submittedBy === 'tokens' ? 'Token budget spent — auto-submitted' : 'Out of actions — auto-submitted';
    frame(
      { kind: 'final', tests: { ...lastTests, failing: lastTests.failing.slice(0, 6), ranThisStep: true }, hidden: { passed: hiddenNow, total: hiddenRef.length, before: hiddenBefore } },
      {
        label: 'Hidden tests',
        outcome: `${how}. Hidden tests: ${hiddenNow} of ${hiddenRef.length} pass (${hiddenBefore} passed before). Visible: ${visibleNow}/${visibleRef.length}.`,
        tone: s.passed ? 'good' : hiddenNow > hiddenBefore ? 'neutral' : 'bad',
      },
    );

    const changed = ws.changed();
    const diffText = changed.map((p) => unifiedDiff(p, p in ws.original ? ws.original[p]! : null, ws.files[p]!)).filter(Boolean).join('\n');
    if (diffText) ctx.artifact('changes.diff', 'text', diffText);

    const summary = s.passed
      ? `Fixed it: all ${hiddenRef.length} hidden tests pass · ${actions} actions (par ${repo.meta.par})`
      : `Hidden tests ${hiddenNow}/${hiddenRef.length} (was ${hiddenBefore}) · visible ${visibleNow}/${visibleRef.length} · ${actions} actions${tamperAttempts ? ` · ${tamperAttempts} test-edit attempt${tamperAttempts === 1 ? '' : 's'}` : ''}`;

    return {
      score: s.score,
      passed: s.passed,
      summary,
      detail: {
        items: [
          { label: 'Hidden tests fixed (75%)', passed: s.hidden === 1, score: s.hidden, detail: `${hiddenNow}/${hiddenRef.length} pass (${hiddenBefore} passed before)` },
          { label: 'Visible tests fixed (15%)', passed: s.visible === 1, score: s.visible, detail: `${visibleNow}/${visibleRef.length} pass (${visibleBefore} passed before)` },
          { label: 'Efficiency (10%)', passed: s.efficiency >= 0.5, score: s.efficiency, detail: `${actions} actions (par ${repo.meta.par}), ${groupDigits(tokens)} of ${groupDigits(cfg.tokenBudget)} output tokens` },
          { label: 'Tests left untouched', passed: tamperAttempts === 0, detail: tamperAttempts ? `${tamperAttempts} attempt(s) to edit a test file (−${s.penalty})` : 'no attempts to edit tests' },
        ],
        repo: repoId,
        repoTitle: repo.meta.title,
        tier: repo.meta.tier,
        submittedBy,
        actionsUsed: actions,
        maxActions: cfg.maxActions,
        par: repo.meta.par,
        outputTokensUsed: tokens,
        tokenBudget: cfg.tokenBudget,
        hidden: { passedBefore: hiddenBefore, passedAfter: hiddenNow, total: hiddenRef.length, stillFailing: hiddenSnap.failing },
        visible: { passedBefore: visibleBefore, passedAfter: visibleNow, total: visibleRef.length },
        components: { hidden: s.hidden, visible: s.visible, efficiency: s.efficiency, actionEfficiency: s.actionEfficiency, tokenEfficiency: s.tokenEfficiency, penalty: s.penalty },
        tamperAttempts,
        invalidActions,
        testRuns,
        filesChanged: changed,
        plantedBugs: repo.meta.bugs,
        actionLog,
      },
      replay: { title: `Fix the Bug · ${repo.meta.title}`, frames },
    };
  },
};
