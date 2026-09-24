import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import {
  program,
  buildStory,
  cleanOutput,
  readConfig,
  truncateWords,
} from '../src/programs/chain-of-whispers.ts';
import { factPresent, generateStory, STORY_IDS, survivingFacts } from '../src/programs/lib/chain-of-whispers-story.ts';
import type { FactSpec, SourceStory } from '../src/programs/lib/chain-of-whispers-story.ts';
import { containsPhrase, countWords, normalizeText } from '../src/programs/lib/needle-haystack-normalize.ts';
import {
  constantResponder,
  createFakeModel,
  createTestContext,
  mockBaselineResponder,
  randomBacktickResponder,
} from './helpers/fake-model.ts';
import type { Responder } from './helpers/fake-model.ts';

function storyFor(seed: number, config: Record<string, unknown> = {}): SourceStory {
  return buildStory({ rng: createRng(seed) }, readConfig({ ...program.defaults, ...config }));
}

async function play(seed: number, responder: Responder, config: Record<string, unknown> = {}) {
  const model = createFakeModel(responder);
  const { ctx, artifacts } = createTestContext({ seed, model, config, defaults: program.defaults });
  const result = await program.run(ctx);
  return { result, model, artifacts };
}

const isSummaryStep = (userText: string) => userText.startsWith('Summarise');

/** Keeps every fact: a canonical-fact summary, then the original story as the "expansion". */
function oracle(story: SourceStory, drop: (round: number) => string[] = () => []): Responder {
  return (_s, userText, _h, info) => {
    const dropped = new Set(drop(info.index + 1));
    if (isSummaryStep(userText)) {
      return story.facts.filter((f) => !dropped.has(f.id) && factPresent(userText, f)).map((f) => `${f.canonical}.`).join(' ');
    }
    // Rebuild the story from the summary it was given, so dropped facts stay dropped.
    const kept = story.facts.filter((f) => factPresent(userText, f));
    const filler = ' The town talked about it for a long time afterwards, and the details were retold at every winter fireside.';
    let text = kept.map((f) => `${f.canonical}.`).join(' ');
    while (countWords(text) < 320) text += filler;
    return text;
  };
}

type Detail = {
  survived: number;
  penalty: { total: number; overLimit: number; tooShort: number };
  rounds: Array<{ kind: string; words: number; truncated: boolean; facts: number; lost: string[] }>;
  facts: Array<{ id: string; survived: boolean; diedAtRound: number | null }>;
};

test('chain-of-whispers: every generated story has 12 facts, all present, 400–500 words', () => {
  for (const id of STORY_IDS) {
    for (let seed = 1; seed <= 60; seed++) {
      const s = generateStory(createRng(seed), id);
      assert.equal(s.facts.length, 12);
      assert.deepEqual(survivingFacts(s.text, s.facts), s.facts.map((f) => f.id), `${id} seed ${seed}`);
      const words = countWords(s.text);
      assert.ok(words >= 400 && words <= 500, `${id} seed ${seed}: ${words} words`);
      // The canonical summary fits the 100-word limit and keeps every fact.
      const canon = s.facts.map((f) => `${f.canonical}.`).join(' ');
      assert.ok(countWords(canon) <= 100);
      assert.equal(survivingFacts(canon, s.facts).length, 12);
    }
  }
});

test('chain-of-whispers: determinism — same seed, same prompts and result; seeds differ', async () => {
  const s = storyFor(101);
  const a = await play(101, oracle(s));
  const b = await play(101, oracle(s));
  assert.deepEqual(a.model.calls.map((c) => c.messages), b.model.calls.map((c) => c.messages));
  assert.deepEqual(a.result, b.result);
  const texts = new Set([101, 202, 303, 404].map((seed) => storyFor(seed).text));
  assert.equal(texts.size, 4);
});

test('chain-of-whispers: oracle keeps 12/12 through 3 cycles (6 fresh-context calls)', async () => {
  for (const seed of [101, 202, 303]) {
    const s = storyFor(seed);
    const { result, model, artifacts } = await play(seed, oracle(s));
    assert.equal(result.score, 1, JSON.stringify(result.detail));
    assert.equal(result.passed, true);
    assert.equal(result.summary, '12/12 facts survived 3 cycles');
    assert.equal(model.calls.length, 6);
    for (const c of model.calls) assert.equal(c.messages.length, 1, 'fresh context every call');
    assert.ok(model.calls[0]!.messages[0]!.content.includes(s.text), 'first call sees the source');
    assert.ok(!model.calls[1]!.messages[0]!.content.includes(s.text), 'later calls only see the previous output');
    assert.equal(artifacts[0]!.name, 'whispers.txt');
    assert.deepEqual(result.replay!.series![0]!.points.map((p) => p.y), [12, 12, 12, 12, 12, 12, 12]);
  }
});

test('chain-of-whispers: decay curve and summary when facts die along the way', async () => {
  const s = storyFor(202);
  // Facts that no other canonical sentence also carries (so dropping one really removes it).
  const canonWithout = (id: string) => s.facts.filter((f) => f.id !== id).map((f) => `${f.canonical}.`).join(' ');
  const independent = s.facts.filter((f) => !factPresent(canonWithout(f.id), f)).map((f) => f.id);
  const [f1, f2, f3] = independent;
  const { result } = await play(202, oracle(s, (round) => (round === 1 ? [f1!] : round === 3 ? [f1!, f2!, f3!] : [])));
  assert.equal(result.summary, '9/12 facts survived 3 cycles');
  assert.equal(result.score, 0.75);
  assert.deepEqual(result.replay!.series![0]!.points.map((p) => p.y), [12, 11, 11, 9, 9, 9, 9]);
  const d = result.detail as unknown as Detail;
  assert.equal(d.facts.find((f) => f.id === f1)!.diedAtRound, 1);
  assert.equal(d.facts.find((f) => f.id === f2)!.diedAtRound, 3);
  assert.ok(result.replay!.frames[1]!.outcome!.startsWith('Lost: '));
});

test('chain-of-whispers: garbage, random-backtick and refusals score 0 without crashing', async () => {
  for (const r of [constantResponder('lorem ipsum dolor sit amet'), randomBacktickResponder(3)]) {
    const { result, model } = await play(101, r);
    assert.equal(result.score, 0);
    assert.equal(model.calls.length, 6);
  }
  const s = storyFor(303);
  const good = oracle(s);
  const { result, model } = await play(303, (sys, u, h, info) => (info.index === 2 ? { text: '', stopReason: 'refusal' } : good(sys, u, h, info)));
  assert.equal(model.calls.length, 3, 'chain stops once broken');
  assert.equal(result.score, 0);
  assert.equal(result.summary, 'Chain broke in cycle 2 (summary) · 0/12 facts');
  assert.deepEqual(result.replay!.series![0]!.points.map((p) => p.y), [12, 12, 12, 0, 0, 0, 0]);
});

test('chain-of-whispers: the real Random Baseline (filler prose) scores ~0', async () => {
  for (const seed of [404, 505, 606]) {
    const { result } = await play(seed, mockBaselineResponder());
    assert.ok(result.score <= 0.1, `seed ${seed}: ${result.score}`);
  }
});

test('chain-of-whispers: model errors propagate', async () => {
  await assert.rejects(play(101, () => Promise.reject(new Error('timeout'))), /timeout/);
});

test('chain-of-whispers: output over the limit is truncated before the fact check and penalised', async () => {
  const s = storyFor(101);
  const padding = Array.from({ length: 120 }, () => 'word').join(' ');
  const good = oracle(s);
  // The summary buries every fact after 120 filler words: all of it is cut away.
  const { result } = await play(101, (sys, u, h, info) => (isSummaryStep(u) ? `${padding}. ${good(sys, u, h, info)}` : good(sys, u, h, info)));
  const d = result.detail as unknown as Detail;
  assert.equal(d.rounds[0]!.truncated, true);
  assert.equal(d.rounds[0]!.facts, 0);
  assert.equal(d.penalty.overLimit, 3);
  assert.equal(result.score, 0);
});

test('chain-of-whispers: not expanding (returning the summary as the story) is penalised', async () => {
  const s = storyFor(202);
  const canon = s.facts.map((f) => `${f.canonical}.`).join(' ');
  const { result } = await play(202, () => canon);
  const d = result.detail as unknown as Detail;
  assert.equal(d.survived, 12);
  assert.equal(d.penalty.tooShort, 3);
  assert.equal(result.score, 0.88);
});

test('chain-of-whispers: bare keyword stuffing does not count as a surviving fact', () => {
  const s = generateStory(createRng(5), 'lighthouse');
  const keeper = s.facts.find((f) => f.id === 'keeper')!;
  const passengers = s.facts.find((f) => f.id === 'passengers')!;
  const surname = keeper.groups[0]![0]!;
  const count = passengers.groups[0]![0]!;
  assert.equal(factPresent(`${surname}. Lighthouse keeper.`, keeper), false, 'name and role in different sentences');
  assert.equal(factPresent(`${count}. Passengers.`, passengers), false);
  assert.equal(factPresent(`${surname} ${Array.from({ length: 20 }, () => 'and').join(' ')} lighthouse`, keeper), false, 'outside the window');
  assert.equal(factPresent(`The lighthouse keeper, ${surname}, never slept.`, keeper), true);
});

test('chain-of-whispers: fact matching handles number words, dates and paraphrase', () => {
  const f = (groups: string[][], window: number): FactSpec => ({ id: 'x', label: 'x', canonical: 'x', groups, window });
  const passengers = f([['37'], ['passengers', 'people']], 4);
  assert.ok(factPresent('The ship carried thirty-seven passengers.', passengers));
  assert.ok(factPresent('All 37 of the people aboard lived.', passengers));
  assert.ok(!factPresent('The ship carried 38 passengers.', passengers));
  const date = f([['14'], ['march']], 3);
  for (const t of ['on 14 March 1893', 'on March 14th', 'the 14th of March', 'on the fourteenth of March']) assert.ok(factPresent(t, date), t);
  assert.ok(!factPresent('On 14 April, in March', f([['14'], ['march']], 1)));
  const renamed = f([['renamed', 'name'], ['marisol light']], 10);
  assert.ok(factPresent("The tower now bears her name: Marisol's Light.", renamed));
  const value = f([['4800'], ['crowns']], 2);
  assert.ok(factPresent('insured for 4,800 crowns', value));
  assert.ok(factPresent('insured for four thousand eight hundred crowns', value));
});

test('chain-of-whispers: prompts are self-contained, end with the format and never reveal the key', async () => {
  const s = storyFor(101);
  const { model } = await play(101, oracle(s));
  for (const c of model.calls) {
    const p = c.messages[0]!.content;
    assert.ok(!p.includes('`'), 'no backticks');
    assert.match(p, /\n\nReply with only the (summary|story): [^\n]*\d+( words)?\.$/);
    // Outside the quoted text, the instructions never name a fact's keywords.
    const instructions = normalizeText(p.replace(/"""[\s\S]*"""/, ' '));
    for (const fct of s.facts) {
      for (const alias of fct.groups.flat()) {
        if (!/^(m|name|named|led|people|day|days)$/.test(alias)) assert.ok(!containsPhrase(instructions, alias), `"${alias}" leaked`);
      }
    }
    assert.ok(!/\bfacts?\b|\bchecked\b|\bkeywords?\b|\bscor/i.test(instructions));
  }
});

test('chain-of-whispers: helpers', () => {
  assert.equal(truncateWords('one two, three — four five', 3), 'one two, three');
  assert.equal(truncateWords('short text', 10), 'short text');
  assert.equal(countWords(truncateWords('a '.repeat(300), 100)), 100);
  assert.equal(cleanOutput('```\nThe story.\n```'), 'The story.');
  assert.equal(cleanOutput('**Summary:** Marisol saved them.'), 'Marisol saved them.');
  assert.deepEqual(readConfig({ cycles: 99, story: 'museum' }), {
    cycles: 3, summaryWords: 100, storyWords: 450, storyMaxWords: 500, storyMinWords: 300, story: 'museum',
  });
});
