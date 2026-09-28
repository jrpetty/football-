// Regression tests for problems found by the second blind audit (docs/AUDIT.md, "Third release: closing the gaps").
import { after, test } from 'node:test';
import assert from 'node:assert/strict';
import { loadTests, caseScorer } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { closeBrowser } from '../src/scoring/browser.ts';
import type { ArtifactRef, ScorerSpec } from '../src/core/types.ts';
import { diedAtRound } from '../src/programs/chain-of-whispers.ts';
import { factPresent } from '../src/programs/lib/chain-of-whispers-story.ts';
import { gradeAnswer, parseAnswers } from '../src/programs/needle-haystack.ts';

const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });
const judgeSays = (score: number) => ({ ids: ['auditor'], ask: async () => [{ judgeId: 'auditor', text: `Reasoning.\nSCORE: ${score}` }] });
const all = loadTests();
after(() => closeBrowser());

function scorerOf(testId: string, caseId: string): { scorer: ScorerSpec; expected: unknown } {
  const t = all.find((x) => x.definition.id === testId)!;
  const d = t.definition;
  if (d.kind !== 'prompt') throw new Error('prompt test expected');
  const c = d.cases.find((x) => x.id === caseId)!;
  return { scorer: caseScorer(d, c), expected: c.expected };
}

async function score(testId: string, caseId: string, response: string, judge: number) {
  const { scorer, expected } = scorerOf(testId, caseId);
  return scoreResponse({ scorer, expected, response, stopReason: 'end', taskText: 'task', judges: judgeSays(judge), saveArtifact: noArtifacts, signal: new AbortController().signal });
}

// The Random Baseline's reply to every One-Shot Games case: an empty canvas that passes the hygiene checks.
const EMPTY_GAME = '```html\n<!doctype html><html><body><canvas id="c" width="300" height="200"></canvas><p>Game</p></body></html>\n```';
// The Random Baseline's reply to the SVG cases: one grey circle.
const GREY_CIRCLE = '```svg\n<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><circle cx="40" cy="44" r="40" fill="gray"/></svg>\n```';

function barChart(quote: '"' | "'"): string {
  const q = quote;
  const bars: Array<[string, number]> = [['Mon', 12], ['Tue', 7], ['Wed', 15], ['Thu', 4], ['Fri', 9]];
  const parts = [`<svg xmlns=${q}http://www.w3.org/2000/svg${q} width=${q}600${q} height=${q}500${q} viewBox=${q}0 0 600 500${q}>`];
  bars.forEach(([day, v], i) => {
    const x = 90 + i * 90;
    parts.push(`<rect x="${x}" y="${450 - v * 20}" width="60" height="${v * 20}" fill="${v === 15 ? '#E4572E' : '#29335C'}"/><text x="${x + 30}" y="470">${day}</text>`);
  });
  parts.push('</svg>');
  return '```svg\n' + parts.join('\n') + '\n```';
}

test('artifact tests: a 0/10 from the judges zeroes the score, so hygiene checks alone earn nothing', async () => {
  for (const caseId of ['g01', 'g02', 'g03']) {
    const r = await score('creative.one-shot-games', caseId, EMPTY_GAME, 0);
    assert.equal(r.score, 0, `one-shot-games ${caseId}: ${r.summary}`);
  }
  for (const caseId of ['v01', 'v02', 'v03', 'v04']) {
    const r = await score('visual.svg-illustration', caseId, GREY_CIRCLE, 0);
    assert.equal(r.score, 0, `svg-illustration ${caseId}: ${r.summary}`);
  }
  // A real attempt the judges rate above zero keeps its check credit.
  const ok = await score('visual.svg-illustration', 'v03', barChart('"'), 6);
  assert.ok((ok.score ?? 0) > 0.6, ok.summary);
});

test('artifact scorer without the opt-in flag keeps the old weighting (stored results stay comparable)', async () => {
  const scorer: ScorerSpec = { type: 'artifact', format: 'svg', checks: [{ check: 'contains', text: '<circle' }], rubric: 'r', judgeWeight: 0.6 };
  const r = await scoreResponse({ scorer, expected: null, response: GREY_CIRCLE, stopReason: 'end', taskText: '', judges: judgeSays(0), saveArtifact: noArtifacts, signal: new AbortController().signal });
  assert.ok(Math.abs((r.score ?? 0) - 0.4) < 1e-9, r.summary);
});

test('SVG viewBox checks accept single-quoted attributes (same XML, same drawing)', async () => {
  const { scorer } = scorerOf('visual.svg-illustration', 'v03');
  assert.equal(scorer.type, 'artifact');
  const contains = (scorer.type === 'artifact' ? scorer.checks ?? [] : []).filter((c) => c.check === 'contains');
  const single = barChart("'");
  const svg = single.slice(single.indexOf('<svg'), single.lastIndexOf('</svg>') + 6).toLowerCase();
  for (const c of contains) if (c.check === 'contains') assert.ok(svg.includes(c.text.toLowerCase()), `single-quoted chart fails contains ${c.text}`);
});

test('chain of whispers: a fact that drops out of a summary but comes back has not died', () => {
  const rounds = [
    { round: 1, facts: ['a'] },
    { round: 2, facts: ['a', 'b'] },
    { round: 3, facts: ['b'] },
    { round: 4, facts: ['a', 'b'] },
    { round: 5, facts: ['b'] },
    { round: 6, facts: ['b'] },
  ];
  assert.equal(diedAtRound(rounds, 'a'), 5);
  assert.equal(diedAtRound(rounds, 'b'), null); // missing only from round 1, present at the end
  assert.equal(diedAtRound(rounds.slice(0, 4), 'a'), null);
});

test('chain of whispers: "a cave … glittering with blue crystals" counts as the crystal-cave fact', () => {
  const cave = { id: 'cave', label: 'crystal cave', canonical: 'They discovered a cave of blue crystals', groups: [['crystal', 'crystals'], ['cave', 'cavern', 'caves']], window: 12 };
  // Written by the auditor while blind-playing the hard chain: the fact is there, 9 words apart.
  assert.ok(factPresent('It was on the way down that they found it: a cave in the mountainside, its walls glittering with blue crystals.', cave));
  assert.ok(!factPresent('They found a cave. Days later, far away, someone sold blue crystals.', cave));
});

test('needle in a haystack: answers given as a Markdown table are read', () => {
  const reply = ['| Q | Answer |', '|---|---|', '| A1 | 143 |', '| A2 | 6,202 |', '| 3 | Duchess of Mists |'].join('\n');
  assert.deepEqual(parseAnswers(reply, 3), ['143', '6,202', 'Duchess of Mists']);
  const needle = { numeric: 143, rejectNumbers: [79], kind: 'aggregate', accept: [], reject: [] } as unknown as Parameters<typeof gradeAnswer>[0];
  assert.equal(gradeAnswer(needle, parseAnswers(reply, 1)[0]!).correct, true);
});
