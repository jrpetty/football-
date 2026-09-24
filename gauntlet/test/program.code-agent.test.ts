import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { program, buildMessages, repoForSeed, codeAgentSystemPrompt } from '../src/programs/code-agent.ts';
import { hiddenRunFiles, listRepoIds, loadRepo, testFilesOf } from '../src/programs/lib/code-agent-repos.ts';
import { passedOf, runProjectTests, testKey } from '../src/programs/lib/code-agent-sandbox.ts';
import { applyPatch, parseReply } from '../src/programs/lib/code-agent-protocol.ts';
import { Workspace } from '../src/programs/lib/code-agent-workspace.ts';
import { computeScore, gapClosed } from '../src/programs/lib/code-agent-score.ts';
import { diffLines, diffRows, unifiedDiff } from '../src/programs/lib/code-agent-diff.ts';
import { constantResponder, createFakeModel, createTestContext, mockBaselineResponder, type Responder } from './helpers/fake-model.ts';

function testDef(file: string): { seeds: number[]; config: Record<string, unknown> } {
  return JSON.parse(readFileSync(new URL(`../tests/agentic/${file}.json`, import.meta.url), 'utf8'));
}
const STD = testDef('code-agent');
const HARD = testDef('code-agent-hard');
const ALL_CASES = [...STD.seeds.map((seed) => ({ seed, config: STD.config })), ...HARD.seeds.map((seed) => ({ seed, config: HARD.config }))];

async function hiddenResult(files: Record<string, string>, repoId: string) {
  const repo = loadRepo(repoId);
  const h = hiddenRunFiles(files, repo);
  return runProjectTests(h.files, h.tests);
}

async function play(seed: number, responder: Responder, config: Record<string, unknown>, maxOutputTokens = 16000) {
  const model = createFakeModel(responder);
  const { ctx, artifacts } = createTestContext({ seed, model, defaults: program.defaults, config, maxOutputTokens });
  const result = await program.run(ctx);
  return { result, model, artifacts };
}

/** A competent engineer: runs the tests, reads each file it will fix, rewrites it with the reference fix, re-runs, submits. */
function referencePolicy(repoId: string): Responder {
  const repo = loadRepo(repoId);
  const script: string[] = ['First, see what fails.\nACTION: RUN_TESTS'];
  for (const [path, content] of Object.entries(repo.fix)) {
    script.push(`ACTION: READ ${path}`);
    script.push(`The fix:\nACTION: WRITE ${path}\n\`\`\`js\n${content}\`\`\``);
  }
  script.push('ACTION: RUN_TESTS', 'Done.\nACTION: SUBMIT');
  return (_s, _u, _h, info) => script[info.index] ?? 'ACTION: SUBMIT';
}

describe('code-agent: fixture repos', () => {
  const ids = listRepoIds();

  it('has four standard and three hard repos, all within the size brief', () => {
    assert.equal(ids.filter((id) => loadRepo(id).meta.tier === 'standard').length, 4);
    assert.equal(ids.filter((id) => loadRepo(id).meta.tier === 'hard').length, 3);
    for (const id of ids) {
      const repo = loadRepo(id);
      const files = Object.keys(repo.files).length;
      const lines = Object.values(repo.files).reduce((n, c) => n + c.split('\n').length, 0);
      assert.ok(files >= 5 && files <= 15, `${id}: ${files} files`);
      assert.ok(lines >= 150 && lines <= 800, `${id}: ${lines} lines`);
      assert.ok(repo.meta.bugs.length >= 1 && repo.meta.bugs.length <= 3, `${id}: ${repo.meta.bugs.length} bugs`);
      assert.ok(repo.files['README.md'], `${id} has a README`);
      assert.ok(testFilesOf(repo.files).length >= 1, `${id} has visible tests`);
      assert.ok(Object.keys(repo.hidden).length >= 1, `${id} has hidden tests`);
      for (const f of Object.keys(repo.fix)) assert.ok(f in repo.files, `${id}: fix file ${f} exists in the repo`);
    }
  });

  it('every test JSON seed maps to an existing repo', () => {
    for (const c of ALL_CASES) assert.ok(ids.includes(repoForSeed(c.seed, c.config)), `seed ${c.seed}`);
    assert.deepEqual(new Set(HARD.seeds.map((s) => loadRepo(repoForSeed(s, HARD.config)).meta.tier)), new Set(['hard']));
    assert.deepEqual(new Set(STD.seeds.map((s) => loadRepo(repoForSeed(s, STD.config)).meta.tier)), new Set(['standard']));
  });

  for (const id of ids) {
    it(`${id}: the original fails visible and hidden tests; the reference fix passes all of them`, async () => {
      const repo = loadRepo(id);
      const tests = testFilesOf(repo.files);
      const origVisible = await runProjectTests(repo.files, tests);
      const origHidden = await hiddenResult(repo.files, id);
      assert.equal(origVisible.loadErrors.length + origHidden.loadErrors.length, 0);
      assert.ok(origVisible.passed < origVisible.total, `${id}: some visible test fails originally`);
      assert.ok(origHidden.passed < origHidden.total, `${id}: some hidden test fails originally`);
      assert.ok(origHidden.total >= 10, `${id}: at least 10 hidden tests`);

      const fixed = { ...repo.files, ...repo.fix };
      const fixVisible = await runProjectTests(fixed, tests);
      const fixHidden = await hiddenResult(fixed, id);
      assert.equal(fixVisible.passed, fixVisible.total, `${id}: visible after fix ${JSON.stringify(fixVisible.tests.filter((t) => !t.ok))}`);
      assert.equal(fixHidden.passed, fixHidden.total, `${id}: hidden after fix ${JSON.stringify(fixHidden.tests.filter((t) => !t.ok))}`);
      assert.equal(fixHidden.total, origHidden.total);
    });

    it(`${id}: every fixed file matters (fixing only one file leaves hidden tests failing)`, async () => {
      const repo = loadRepo(id);
      const fixFiles = Object.keys(repo.fix);
      if (fixFiles.length < 2) return;
      for (const f of fixFiles) {
        const partial = { ...repo.files, [f]: repo.fix[f]! };
        const r = await hiddenResult(partial, id);
        assert.ok(r.passed < r.total, `${id}: fixing only ${f} passes every hidden test`);
      }
    });
  }

  it('hard-coding the visible expectations does not pass the hidden tests', async () => {
    const repo = loadRepo('invoice-calc');
    const hack = repo.files['src/money.js']!.replace(
      'return Math.floor(amount * 100);',
      'if (amount === 19.99) return 1999;\n  if (amount === 0.29) return 29;\n  return Math.floor(amount * 100);',
    );
    const inv = repo.files['src/invoice.js']!.replace('const tax = taxFor(subtotal, region);', "const tax = region === 'UK' && discountCents === 300 ? 540 : taxFor(subtotal, region);");
    const files = { ...repo.files, 'src/money.js': hack, 'src/invoice.js': inv };
    const vis = await runProjectTests(files, testFilesOf(files));
    const hid = await hiddenResult(files, 'invoice-calc');
    assert.equal(vis.passed, vis.total, 'the hack fools the visible tests');
    assert.ok(hid.passed <= hid.total - 5, `the hidden tests are not fooled (${hid.passed}/${hid.total})`);
  });
});

describe('code-agent: sandbox isolation', () => {
  const base = {
    'src/index.js': "module.exports = { answer: () => 42 };\n",
    'tests/a.test.js': "const test = require('node:test');\nconst assert = require('node:assert/strict');\nconst m = require('../src');\ntest('answer', () => { assert.equal(m.answer(), 42); });\n",
  };
  const run = (src: string, test?: string) => runProjectTests({ ...base, 'src/index.js': src, ...(test ? { 'tests/a.test.js': test } : {}) }, ['tests/a.test.js'], { perTestTimeoutMs: 500 });

  it('runs a normal project', async () => {
    const r = await run(base['src/index.js']);
    assert.equal(r.passed, 1);
    assert.equal(r.total, 1);
  });

  it("require('fs'), child_process and http are blocked", async () => {
    for (const mod of ['fs', 'node:fs', 'child_process', 'http', 'net', 'worker_threads']) {
      const r = await run(`const x = require('${mod}');\nmodule.exports = { answer: () => 42 };\n`);
      assert.equal(r.passed, 0, mod);
      assert.match(r.loadErrors[0]?.error ?? '', /not available in the Gauntlet sandbox/, mod);
    }
  });

  it('no process, fetch, eval or Function-constructor escape', async () => {
    const probes = [
      "typeof process !== 'undefined' ? 1 : 42",
      "typeof fetch !== 'undefined' ? 1 : 42",
      "typeof require('node:assert').constructor.constructor === 'function' && (() => { try { return require('node:assert').constructor.constructor('return process')() ? 1 : 42; } catch (e) { return 42; } })()",
      "(() => { try { return this.constructor.constructor('return process')() ? 1 : 42; } catch (e) { return 42; } })()",
      "(() => { try { return console.log.constructor('return process')() ? 1 : 42; } catch (e) { return 42; } })()",
      "(() => { try { return setTimeout.constructor('return 1')() ? 1 : 42; } catch (e) { return 42; } })()",
      "(() => { try { return eval('1') ? 1 : 42; } catch (e) { return 42; } })()",
    ];
    for (const p of probes) {
      const r = await run(`module.exports = { answer: () => ${p} };\n`);
      assert.equal(r.passed, 1, `escape probe returned something other than 42: ${p} ${JSON.stringify(r.tests)}`);
    }
  });

  it('an endless loop is stopped by the time limit, and other tests still run', async () => {
    const test = "const test = require('node:test');\nconst assert = require('node:assert/strict');\nconst m = require('../src');\ntest('loops', () => { m.spin(); });\ntest('fine', () => { assert.equal(1, 1); });\n";
    const r = await run('module.exports = { spin() { for (;;) {} } };\n', test);
    assert.equal(r.total, 2);
    assert.equal(r.tests[0]!.ok, false);
    assert.match(r.tests[0]!.error ?? '', /Timed out/);
    assert.equal(r.tests[1]!.ok, true);
  });

  it('an endless loop after an await is stopped too', async () => {
    const test = "const test = require('node:test');\nconst m = require('../src');\ntest('loops later', async () => { await m.spin(); });\n";
    const r = await run('module.exports = { async spin() { await null; for (;;) {} } };\n', test);
    assert.equal(r.tests[0]!.ok, false);
  });

  it('project code cannot make failing assertions pass', async () => {
    const test = "const test = require('node:test');\nconst assert = require('node:assert/strict');\nconst m = require('../src');\ntest('answer', () => { assert.equal(m.answer(), 42); assert.deepEqual([m.answer()], [42]); });\ntest('async', async () => { await Promise.resolve(); assert.equal(m.answer(), 42); });\n";
    const tamper = [
      "try { require('node:assert/strict').equal = () => {}; } catch (e) {}",
      'Object.is = () => true;',
      'Promise.prototype.then = function (ok) { ok(); return this; };',
      "try { require('node:test'); } catch (e) {}",
    ].join('\n');
    const r = await run(`${tamper}\nmodule.exports = { answer: () => 41 };\n`, test);
    assert.equal(r.passed, 0, JSON.stringify(r.tests));
  });

  it('project code cannot require the test runner or the test files', async () => {
    let r = await run("require('node:test');\nmodule.exports = { answer: () => 42 };\n");
    assert.match(r.loadErrors[0]?.error ?? '', /only available to test files/);
    r = await run("require('../tests/a.test.js');\nmodule.exports = { answer: () => 42 };\n");
    assert.match(r.loadErrors[0]?.error ?? '', /Cannot require test file/);
  });

  it('syntax errors are reported as load errors, not crashes', async () => {
    const r = await run('module.exports = { answer: () => 42 ;\n');
    assert.equal(r.crashed, null);
    assert.equal(r.passed, 0);
    assert.match(r.loadErrors[0]?.error ?? '', /SyntaxError|Unexpected/);
    assert.equal(passedOf(r, ['tests/a.test.js › answer']), 0);
  });

  it('console output is captured, and each test file gets fresh module state', async () => {
    const files = {
      'src/counter.js': "let n = 0;\nmodule.exports = { next: () => ++n };\n",
      'tests/a.test.js': "const test = require('node:test');\nconst assert = require('node:assert/strict');\nconst c = require('../src/counter');\ntest('first', () => { console.log('hello from a'); assert.equal(c.next(), 1); });\n",
      'tests/b.test.js': "const test = require('node:test');\nconst assert = require('node:assert/strict');\nconst c = require('../src/counter');\ntest('first again', () => { assert.equal(c.next(), 1); });\n",
    };
    const r = await runProjectTests(files, ['tests/a.test.js', 'tests/b.test.js']);
    assert.equal(r.passed, 2);
    assert.match(r.log, /hello from a/);
    assert.deepEqual(r.tests.map(testKey), ['tests/a.test.js › first', 'tests/b.test.js › first again']);
  });
});

describe('code-agent: reply parser', () => {
  it('reads plain, decorated and aliased action lines', () => {
    const cases: Array<[string, unknown]> = [
      ['ACTION: RUN_TESTS', { kind: 'RUN_TESTS' }],
      ['I will run them.\n\n**ACTION:** `RUN_TESTS`', { kind: 'RUN_TESTS' }],
      ['action: run tests', { kind: 'RUN_TESTS' }],
      ['Action: ls', { kind: 'LIST' }],
      ['ACTION: cat ./src/money.js', { kind: 'READ', path: 'src/money.js' }],
      ['ACTION: READ `src/a.js` 10-20', { kind: 'READ', path: 'src/a.js', start: 10, end: 20 }],
      ['ACTION: READ src/a.js:5-9', { kind: 'READ', path: 'src/a.js', start: 5, end: 9 }],
      ['ACTION: READ src/a.js lines 3 to 7', { kind: 'READ', path: 'src/a.js', start: 3, end: 7 }],
      ['ACTION: SEARCH "toCents("', { kind: 'SEARCH', text: 'toCents(' }],
      ['ACTION: grep Math.floor', { kind: 'SEARCH', text: 'Math.floor' }],
      ['Done!\nACTION: SUBMIT.', { kind: 'SUBMIT' }],
      ['READ src/x.js', { kind: 'READ', path: 'src/x.js' }],
      ['```\nACTION: LIST\n```', { kind: 'LIST' }],
    ];
    for (const [text, want] of cases) assert.deepEqual(parseReply(text).action, want, text);
  });

  it('the last ACTION line counts (like every Gauntlet program) and earlier ones are reported', () => {
    const p = parseReply('Draft: ACTION: SUBMIT is premature.\nACTION: READ src/a.js\nOn second thought:\nACTION: RUN_TESTS');
    assert.deepEqual(p.action, { kind: 'RUN_TESTS' });
    assert.equal(p.ignoredActions, 1);
  });

  it('code inside PATCH blocks never counts as an action line', () => {
    const p = parseReply("ACTION: PATCH src/a.js\n<<<<<<< SEARCH\n  action: 'save',\n=======\n  action: 'store',\n>>>>>>> REPLACE");
    assert.deepEqual(p.action, { kind: 'PATCH', path: 'src/a.js', blocks: [{ search: "  action: 'save',", replace: "  action: 'store'," }] });
  });

  it('ACTION lines inside code blocks are ignored when a real one exists', () => {
    const p = parseReply('Example:\n```\nACTION: SUBMIT\n```\nNow:\nACTION: RUN_TESTS');
    assert.deepEqual(p.action, { kind: 'RUN_TESTS' });
  });

  it('WRITE takes the fenced block after the action line (and tolerates longer fences)', () => {
    const p = parseReply('ACTION: WRITE src/new.js\n```javascript\nconst a = 1;\nmodule.exports = a;\n```\ntrailing words');
    assert.deepEqual(p.action, { kind: 'WRITE', path: 'src/new.js', content: 'const a = 1;\nmodule.exports = a;\n' });
    const md = parseReply('ACTION: WRITE README.md\n````md\n# T\n```js\nx\n```\n````');
    assert.equal(md.action?.kind === 'WRITE' && md.action.content, '# T\n```js\nx\n```\n');
    assert.match(parseReply('ACTION: WRITE src/a.js\nconst a = 1;').error ?? '', /fenced code block/);
  });

  it('PATCH reads SEARCH/REPLACE blocks, inside or outside a fence', () => {
    const text = 'ACTION: PATCH src/a.js\n```js\n<<<<<<< SEARCH\nconst a = 1;\n=======\nconst a = 2;\n>>>>>>> REPLACE\n<<<<<<< SEARCH\nfoo();\n=======\n>>>>>>> REPLACE\n```';
    assert.deepEqual(parseReply(text).action, { kind: 'PATCH', path: 'src/a.js', blocks: [{ search: 'const a = 1;', replace: 'const a = 2;' }, { search: 'foo();', replace: '' }] });
    assert.match(parseReply('ACTION: PATCH src/a.js\n<<<<<<< SEARCH\nx\n=======\ny').error ?? '', /not closed/);
    assert.match(parseReply('ACTION: PATCH src/a.js\nplease change x to y').error ?? '', /no SEARCH\/REPLACE blocks/);
  });

  it('never throws on garbage', () => {
    for (const junk of ['', '   ', 'hello there', 'ACTION:', 'ACTION: FLY to the moon', '```', '<<<<<<< SEARCH', 'ACTION: READ', 'ACTION: WRITE', 'ACTION: SEARCH ""', '\u0000\u0001', 'ACTION: PATCH']) {
      const p = parseReply(junk);
      assert.equal(p.action, null, junk);
      assert.ok(p.error, junk);
    }
  });
});

describe('code-agent: patches and the workspace', () => {
  const file = 'function f(a) {\n  if (a > 1) {\n    return a * 2;\n  }\n  return a;\n}\n';

  it('applies exact, whitespace-tolerant and line-numbered SEARCH blocks', () => {
    assert.deepEqual(applyPatch(file, [{ search: '    return a * 2;', replace: '    return a * 3;' }]), { ok: true, content: file.replace('a * 2', 'a * 3'), fuzzy: 0 });
    const fuzzy = applyPatch(file, [{ search: 'if (a > 1) {\n return a * 2;', replace: '  if (a >= 1) {\n    return a * 2;' }]);
    assert.equal(fuzzy.ok, true);
    assert.equal(fuzzy.ok && fuzzy.content, file.replace('a > 1', 'a >= 1'));
    const numbered = applyPatch(file, [{ search: '3|     return a * 2;', replace: '3|     return a * 4;' }]);
    assert.equal(numbered.ok && numbered.content, file.replace('a * 2', 'a * 4'));
  });

  it('rejects ambiguous and missing SEARCH text, atomically', () => {
    const dup = applyPatch('x;\nx;\n', [{ search: 'x;', replace: 'y;' }]);
    assert.equal(dup.ok, false);
    assert.match(!dup.ok ? dup.error : '', /matches 2 places/);
    const missing = applyPatch(file, [{ search: 'return a * 2;', replace: 'return 0;' }, { search: 'nope', replace: '' }]);
    assert.equal(missing.ok, false);
    assert.match(!missing.ok ? missing.error : '', /SEARCH block 2 was not found/);
  });

  it('tests are read-only and paths are sanitised', () => {
    const ws = new Workspace({ 'src/a.js': 'a\n', 'tests/a.test.js': 't\n' });
    const w = ws.write('tests/a.test.js', 'hacked');
    assert.equal(w.ok, false);
    assert.equal(!w.ok && w.tamper, true);
    const p = ws.patch('tests/a.test.js', [{ search: 't', replace: 'x' }]);
    assert.equal(!p.ok && p.tamper, true);
    assert.equal(ws.write('tests/new.test.js', 'x').ok, false);
    for (const bad of ['../evil.js', '/etc/passwd', 'src/../../x.js', '__hidden__/a.js', 'src/a.exe', 'C:/x.js']) {
      const r = ws.write(bad, 'x');
      assert.equal(r.ok, false, bad);
    }
    assert.equal(ws.files['tests/a.test.js'], 't\n');
    assert.equal(ws.write('src/b.js', 'b\n').ok, true);
    assert.deepEqual(ws.changed(), ['src/b.js']);
    assert.equal(ws.state('src/b.js'), 'added');
  });

  it('READ shows numbered lines and SEARCH finds text', () => {
    const ws = new Workspace({ 'src/a.js': 'one\ntwo\nthree\n' });
    assert.equal(ws.read('src/a.js', 2, 3).text, 'src/a.js (lines 2-3 of 3)\n2| two\n3| three');
    assert.equal(ws.read('a.js').path, 'src/a.js');
    assert.equal(ws.read('src/nope.js').ok, false);
    assert.match(ws.search('TWO').text, /src\/a\.js:2: two/);
  });

  it('diffs', () => {
    const d = diffLines('a\nb\nc', 'a\nB\nc');
    assert.deepEqual(d.map((l) => l.op + l.text), [' a', '-b', '+B', ' c']);
    assert.match(unifiedDiff('x.js', 'a\nb\n', 'a\nc\n'), /^--- a\/x\.js\n\+\+\+ b\/x\.js\n@@ -1,3 \+1,3 @@\n a\n-b\n\+c/);
    assert.equal(unifiedDiff('x.js', 'same', 'same'), '');
    const rows = diffRows('1\n2\n3\n4\n5\n6\n7\n8\n', '1\n2\n3\n4\nfive\n6\n7\n8\n');
    assert.equal(rows.added, 1);
    assert.equal(rows.removed, 1);
    assert.equal(rows.rows[0]!.op, '@');
  });
});

describe('code-agent: scoring', () => {
  const base = { hidden: { before: 4, now: 4, total: 13 }, visible: { before: 6, now: 6, total: 9 }, actionsUsed: 30, par: 8, tokensUsed: 0, tokenBudget: 150000, tamperAttempts: 0 };

  it('doing nothing scores 0 even though some tests passed from the start', () => {
    assert.equal(computeScore(base).score, 0);
  });

  it('a perfect, efficient fix scores 1', () => {
    const s = computeScore({ ...base, hidden: { before: 4, now: 13, total: 13 }, visible: { before: 6, now: 9, total: 9 }, actionsUsed: 8 });
    assert.equal(s.score, 1);
    assert.equal(s.passed, true);
  });

  it('a slow fix loses only the efficiency bonus', () => {
    const s = computeScore({ ...base, hidden: { before: 4, now: 13, total: 13 }, visible: { before: 6, now: 9, total: 9 }, actionsUsed: 32, tokensUsed: 150000 });
    assert.equal(s.score, 0.9 + 0.1 * 0.5 * 0.25);
  });

  it('regressions cancel progress', () => {
    assert.equal(gapClosed(4, 2, 13), 0);
    assert.equal(gapClosed(4, 8, 13), 4 / 9);
    assert.equal(gapClosed(13, 13, 13), 1);
    assert.equal(gapClosed(13, 12, 13), 12 / 13);
  });

  it('attempts to edit tests cost 0.1 each, at most 0.3, and forfeit "passed"', () => {
    const full = { ...base, hidden: { before: 4, now: 13, total: 13 }, visible: { before: 6, now: 9, total: 9 }, actionsUsed: 8 };
    assert.equal(computeScore({ ...full, tamperAttempts: 1 }).score, 0.9);
    assert.equal(computeScore({ ...full, tamperAttempts: 9 }).score, 0.7);
    assert.equal(computeScore({ ...full, tamperAttempts: 1 }).passed, false);
    assert.equal(computeScore({ ...base, tamperAttempts: 2 }).score, 0);
  });
});

describe('code-agent: full episodes with fake models', () => {
  for (const c of ALL_CASES) {
    it(`seed ${c.seed} (${repoForSeed(c.seed, c.config)}): a competent engineer fixes everything and scores ≥ 0.95`, async () => {
      const repoId = repoForSeed(c.seed, c.config);
      const { result, artifacts } = await play(c.seed, referencePolicy(repoId), c.config);
      assert.equal(result.passed, true, result.summary);
      assert.ok(result.score >= 0.95, `${result.score} ${result.summary}`);
      const d = result.detail as { hidden: { passedAfter: number; total: number }; submittedBy: string; filesChanged: string[] };
      assert.equal(d.hidden.passedAfter, d.hidden.total);
      assert.equal(d.submittedBy, 'model');
      assert.deepEqual(d.filesChanged.sort(), Object.keys(loadRepo(repoId).fix).sort());
      assert.equal(artifacts.length, 1);
      assert.match(artifacts[0]!.content, /^--- a\//);
      // Replay: a start frame, one per action and a final frame, red → green.
      const frames = result.replay!.frames;
      assert.equal(frames[0]!.code!.kind, 'start');
      assert.equal(frames.at(-1)!.code!.kind, 'final');
      assert.ok(frames[0]!.code!.tests!.passed < frames[0]!.code!.tests!.total);
      assert.equal(frames.at(-1)!.code!.tests!.passed, frames.at(-1)!.code!.tests!.total);
      assert.ok(frames.some((f) => f.code?.kind === 'edit' && f.code.diff && f.code.diff.rows.some((r) => r.op === '+')));
    });
  }

  for (const c of ALL_CASES) {
    it(`seed ${c.seed}: the Random Baseline scores 0`, async () => {
      const { result } = await play(c.seed, mockBaselineResponder(), c.config);
      assert.equal(result.score, 0, result.summary);
      assert.equal(result.passed, false);
    });
  }

  it('garbage, empty and refusing models score 0 without crashing, and run out of actions', async () => {
    for (const r of [constantResponder('lorem ipsum dolor'), constantResponder(''), constantResponder('I cannot help with that.', 'refusal')]) {
      const { result, model } = await play(101, r, { ...STD.config, maxActions: 5 });
      assert.equal(result.score, 0);
      assert.equal(model.calls.length, 5);
      assert.equal((result.detail as { submittedBy: string }).submittedBy, 'actions');
      assert.equal((result.detail as { invalidActions: number }).invalidActions, 5);
    }
  });

  it('same seed and same play ⇒ identical prompts and result', async () => {
    const a = await play(202, referencePolicy('adventure-parser'), STD.config);
    const b = await play(202, referencePolicy('adventure-parser'), STD.config);
    assert.equal(a.model.calls.length, b.model.calls.length);
    for (let i = 0; i < a.model.calls.length; i++) assert.deepEqual(a.model.calls[i]!.messages, b.model.calls[i]!.messages);
    const strip = (r: typeof a.result) => JSON.stringify({ ...r, detail: { ...r.detail } });
    assert.equal(strip(a.result), strip(b.result));
  });

  it('prompts never contain the hidden tests or the answer key', async () => {
    const { model } = await play(101, referencePolicy('invoice-calc'), STD.config);
    const repo = loadRepo('invoice-calc');
    const all = model.calls.map((c) => (c.system ?? '') + c.messages.map((m) => m.content).join('\n')).join('\n');
    assert.ok(!all.includes('toCents: amounts that are not exact in binary'));
    assert.ok(!all.includes(repo.meta.bugs[0]!.summary));
    assert.ok(all.includes(repo.meta.issue));
  });

  it('editing a test file is rejected and penalised', async () => {
    const script = [
      'ACTION: WRITE tests/money.test.js\n```js\n// all good\n```',
      'ACTION: PATCH tests/invoice.test.js\n<<<<<<< SEARCH\nassert.equal(inv.total, 3634);\n=======\n>>>>>>> REPLACE',
      ...Object.entries(loadRepo('invoice-calc').fix).map(([p, c]) => `ACTION: WRITE ${p}\n\`\`\`js\n${c}\`\`\``),
      'ACTION: SUBMIT',
    ];
    const { result, model } = await play(101, (_s, _u, _h, i) => script[i.index] ?? 'ACTION: SUBMIT', STD.config);
    const d = result.detail as { tamperAttempts: number; hidden: { passedAfter: number; total: number } };
    assert.equal(d.tamperAttempts, 2);
    assert.equal(d.hidden.passedAfter, d.hidden.total);
    assert.equal(result.passed, false);
    assert.ok(result.score <= 0.81, String(result.score));
    assert.match(model.calls[1]!.messages.at(-1)!.content, /read-only/);
  });

  it('a WRITE cut off by the output limit is not applied', async () => {
    const script: Responder = (_s, _u, _h, i) => (i.index === 0 ? { text: 'ACTION: WRITE src/money.js\n```js\nmodule.exports = {', stopReason: 'max_tokens' } : 'ACTION: SUBMIT');
    const { result, model } = await play(101, script, STD.config);
    assert.match(model.calls[1]!.messages.at(-1)!.content, /cut off/);
    assert.deepEqual((result.detail as { filesChanged: string[] }).filesChanged, []);
  });

  it('spending the token budget submits automatically', async () => {
    const long = `${'thinking '.repeat(2000)}\nACTION: LIST`;
    const { result, model } = await play(303, constantResponder(long), { ...STD.config, tokenBudget: 12000 });
    assert.equal((result.detail as { submittedBy: string }).submittedBy, 'tokens');
    assert.ok(model.calls.length < 30);
  });

  it('breaking working code scores below doing nothing on visible and hidden', async () => {
    const script = ["ACTION: WRITE src/money.js\n```js\nmodule.exports = {};\n```", 'ACTION: SUBMIT'];
    const { result } = await play(101, (_s, _u, _h, i) => script[i.index] ?? 'ACTION: SUBMIT', STD.config);
    assert.equal(result.score, 0);
    assert.equal((result.detail as { hidden: { passedAfter: number } }).hidden.passedAfter, 0);
  });

  it('context stays bounded: old exchanges are shortened', () => {
    const history = Array.from({ length: 12 }, (_, i) => ({ reply: `long reasoning ${i}\nACTION: READ src/a.js`, summary: 'READ src/a.js', result: `FULL RESULT ${i} ${'x'.repeat(1000)}`, brief: `brief ${i}` }));
    const msgs = buildMessages('intro', history, 4);
    assert.equal(msgs.length, 25);
    assert.equal(msgs.filter((m) => m.content.startsWith('FULL RESULT')).length, 4);
    assert.equal(msgs.at(-1)!.content.startsWith('FULL RESULT 11'), true);
    assert.equal(msgs[2]!.content, 'brief 0');
    assert.match(codeAgentSystemPrompt(30, 150000), /150,000 output tokens/);
  });
});
