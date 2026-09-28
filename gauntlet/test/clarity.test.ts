/**
 * Viewer clarity: the plain-English helpers behind Broadcast mode
 * (ui/src/components/clarity/plain.ts) — label map, value formatting, case names,
 * score bands and the model-badge monogram / ink colour.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as p from '../ui/src/components/clarity/plain.ts';

test('known score-detail keys get plain labels', () => {
  assert.equal(p.plainKey('checkScore'), 'Automatic checks');
  assert.equal(p.plainKey('judgeScore'), 'Judges’ score');
  assert.equal(p.plainKey('bytes'), 'File size');
  assert.equal(p.plainKey('reasoningTokens'), 'Thinking (tokens)');
});

test('unknown keys are humanised, never shown raw', () => {
  assert.equal(p.plainKey('nightsSurvived'), 'Nights survived');
  assert.equal(p.plainKey('wrong_entries_total'), 'Wrong entries total');
  assert.equal(p.plainKey('two-hop'), 'Two hop');
  assert.equal(p.humanizeKey(''), '');
});

test('values read naturally', () => {
  assert.equal(p.plainValue('checkScore', 0.8), '80 / 100');
  assert.equal(p.plainValue('judgeScore', 1), '100 / 100');
  // a *Score outside 0–1 is not a ratio: left as a number
  assert.equal(p.plainValue('rawScore', 42), '42');
  assert.equal(p.plainValue('bytes', 1640), '1.6 KB');
  assert.equal(p.plainValue('bytes', 300), '300 bytes');
  assert.equal(p.plainValue('judgeDisagreement', true), 'Yes');
  assert.equal(p.plainValue('formatOk', false), 'No');
  assert.equal(p.plainValue('skippedChecks', ['responds_to_input']), 'Responds to input');
  assert.equal(p.plainValue('consoleErrors', []), 'None');
  assert.equal(p.plainValue('x', [{ a: 1 }, { a: 2 }]), '2 items');
  assert.equal(p.plainValue('x', { a: 1, b: 2 }), '2 values');
  assert.equal(p.plainValue('x', null), '—');
  assert.equal(p.plainValue('x', Number.NaN), '—');
  assert.equal(p.plainValue('x', 1234567), (1234567).toLocaleString('en-GB'));
  assert.equal(p.plainValue('x', 0.12345), '0.12');
});

test('case ids become viewer names', () => {
  assert.equal(p.plainCaseName('seed-101'), 'World #101');
  assert.equal(p.plainCaseName('seed_7'), 'World #7');
  assert.equal(p.plainCaseName('c03'), 'Question 3');
  assert.equal(p.plainCaseName('q12'), 'Question 12');
  assert.equal(p.plainCaseName('goat-rope'), 'Goat rope');
  assert.equal(p.plainCaseName('digitSum'), 'Digit sum');
  assert.equal(p.plainAttempt(0), 'Try 1');
  assert.equal(p.plainAttempt(2), 'Try 3');
});

test('score bands match the score-pill thresholds (80 / 40)', () => {
  assert.equal(p.scoreBand(1), 'good');
  assert.equal(p.scoreBand(0.8), 'good');
  assert.equal(p.scoreBand(0.7999), 'partial');
  assert.equal(p.scoreBand(0.4), 'partial');
  assert.equal(p.scoreBand(0.39), 'poor');
  assert.equal(p.scoreBand(0), 'poor');
  assert.equal(p.scoreBand(null), null);
  assert.equal(p.scoreBand(undefined), null);
  assert.equal(p.scoreBand(Number.NaN), null);
  // Same cut-offs as format.ts scoreTone, so pills, matrix and guide agree.
  const fmt = readFileSync(new URL('../ui/src/format.ts', import.meta.url), 'utf8');
  assert.match(fmt, /score >= 0\.8\) return 'good'/);
  assert.match(fmt, /score >= 0\.4\) return 'mid'/);
});

test('monograms are two characters and stable', () => {
  assert.equal(p.monogram('Atlas-4 Ultra'), 'A4');
  assert.equal(p.monogram('Kite 2.5 Reasoner'), 'K2');
  assert.equal(p.monogram('Quill Flash'), 'QF');
  assert.equal(p.monogram('Orbit Chat (web)'), 'OC');
  assert.equal(p.monogram('Kite'), 'KI');
  assert.equal(p.monogram('x'), 'X');
  assert.equal(p.monogram('   '), '?');
  assert.equal(p.monogram('a very-long model name indeed').length, 2);
});

test('monogram ink keeps contrast on any contestant colour', () => {
  assert.equal(p.inkOn('#ffffff'), '#0b0d12');
  assert.equal(p.inkOn('#f2c14e'), '#0b0d12');
  assert.equal(p.inkOn('#000000'), '#ffffff');
  assert.equal(p.inkOn('#1e3a8a'), '#ffffff');
  assert.equal(p.inkOn('#fff'), '#0b0d12');
  assert.equal(p.inkOn('not-a-colour'), '#ffffff');
  assert.equal(p.luminance('zzz'), null);
});

test('Broadcast CSS hides owner-only detail and shows plain labels', () => {
  const css = readFileSync(new URL('../ui/src/styles/clarity.css', import.meta.url), 'utf8');
  assert.match(css, /html\[data-broadcast='on'\] \[data-dev\]/);
  assert.match(css, /html\[data-broadcast='on'\] \.dev-only/);
  assert.match(css, /html\[data-broadcast='on'\] \.plain-only/);
  const main = readFileSync(new URL('../ui/src/main.tsx', import.meta.url), 'utf8');
  assert.match(main, /clarity\.css/);
});
