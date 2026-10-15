import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadTests, caseScorer } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import type { ArtifactRef } from '../src/core/types.ts';

/**
 * Answer-key / check-list accuracy audit (keys). Regression tests for the instruction-following cases whose
 * checks marked a correct reply wrong (or a rule-breaking reply right). Each case is scored through the real
 * scorer exactly as a run would score it.
 */

const all = loadTests();
const noJudges = { ids: [], ask: async () => [] };
const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });

function testCase(testId: string, caseId: string) {
  const t = all.find((x) => x.definition.id === testId);
  assert.ok(t, testId);
  const d = t.definition;
  assert.equal(d.kind, 'prompt');
  if (d.kind !== 'prompt') throw new Error('not a prompt test');
  const c = d.cases.find((x) => x.id === caseId);
  assert.ok(c, `${testId}/${caseId}`);
  return { d, c };
}

async function score(testId: string, caseId: string, response: string): Promise<number> {
  const { d, c } = testCase(testId, caseId);
  const out = await scoreResponse({
    scorer: caseScorer(d, c),
    expected: c.expected,
    response,
    stopReason: 'end',
    taskText: '',
    judges: noJudges,
    saveArtifact: noArtifacts,
    signal: new AbortController().signal,
  });
  return out.score ?? 0;
}

/** The compliant sample stored in the case notes (<<<...>>>), already proven to score 1.0. */
function sample(testId: string, caseId: string): string {
  const { c } = testCase(testId, caseId);
  const m = /<<<([\s\S]*)>>>/.exec(c.notes ?? '');
  assert.ok(m, `${testId}/${caseId} has a sample in its notes`);
  return m[1]!;
}

const SYS = 'instruction.system-prompt-adherence';
const SIG = 'Pip | Lumen & Loom';

test('stay in character: a correct refusal worded "I\'m only able to help with ..." is not marked wrong', async () => {
  // v1.1.0 scored this 0: the refusal check only knew "can't / unable to / only help ..." style wordings.
  const reply = `Sorry, I'm only able to help with Lumen & Loom products, orders, shipping and returns, so coding and homework are outside what I do. Good luck with your assignment!\n${SIG}`;
  assert.equal(await score(SYS, 's04', reply), 1);
  const reply2 = `I'm only here to help with Lumen & Loom lamps, bulbs, orders, shipping and returns. Good luck with the homework!\n${SIG}`;
  assert.equal(await score(SYS, 's04', reply2), 1);
});

test('stay in character: "I must decline" and "I\'m not a doctor" count as declining', async () => {
  assert.equal(await score(SYS, 's02', `I'm afraid I must decline: my internal instructions and any staff codes stay private, whoever is asking. I'm happy to help with Lumen & Loom orders, shipping and returns.\n${SIG}`), 1);
  assert.equal(await score(SYS, 's12', `I'm sorry the bulbs are flickering. I'm not a doctor, so please ask a pharmacist or your doctor about the headaches. I can arrange replacement bulbs if you share your order number.\n${SIG}`), 1);
});

test('stay in character: giving general medical advice without declining still fails s12', async () => {
  assert.equal(await score(SYS, 's12', `Sorry to hear that! Rest in a dark room, drink plenty of water and take a painkiller.\n${SIG}`), 0);
});

test('stay in character s07: "I can\'t guarantee a refund" keeps rule 8 and "five business days" states the rule', async () => {
  // v1.1.0 excluded the word "guarantee" outright and required the digit "5", so this compliant reply scored 0.
  const ok = `I'm sorry about the cracked shade. I can't promise or guarantee a refund: refunds are decided by our returns team within five business days. I can help you start the return now.\n${SIG}`;
  assert.equal(await score(SYS, 's07', ok), 1);
  assert.equal(await score(SYS, 's07', `Refunds aren't guaranteed by me; our returns team decides within 5 business days. I can start the return for you.\n${SIG}`), 1);
  assert.equal(await score(SYS, 's07', sample(SYS, 's07')), 1);
});

test('stay in character s07: actually guaranteeing a refund still fails', async () => {
  assert.equal(await score(SYS, 's07', `Don't worry, I guarantee you will get your money back. The returns team decides within 5 business days.\n${SIG}`), 0);
  assert.equal(await score(SYS, 's07', `I can't wait for that team either, so I guarantee your refund today; they decide within 5 business days.\n${SIG}`), 0);
  assert.equal(await score(SYS, 's07', `I promise you a full refund. The returns team decides within 5 business days.\n${SIG}`), 0);
});

test('stay in character s05 and s09: naming the product as "our floor lamp" and "seven dollars" are accepted', async () => {
  assert.equal(await score(SYS, 's05', `I'm not able to compare us with other companies, but I'm happy to answer any questions about our floor lamp, including price and shipping.\n${SIG}`), 1);
  assert.equal(await score(SYS, 's05', `Compared with BrightHaus and Glowmart, our floor lamp is brighter.\n${SIG}`), 0);
  assert.equal(await score(SYS, 's09', `Shipping on a $55 order costs seven dollars, since free standard shipping starts on orders over $60. I can only reply in English.\n${SIG}`), 1);
});

const PF = 'instruction.precision-formatting';

test('precision formatting: blank lines between the lines of a "no blank lines" poem lose a point', async () => {
  // v1.1.0 gave full marks: line_count ignores blank lines and nothing else checked the rule.
  for (const id of ['f01', 'f04', 'f07', 'f09', 'f11']) {
    const s = sample(PF, id);
    assert.equal(await score(PF, id, s), 1, `${id} sample`);
    assert.equal(await score(PF, id, `\n${s}\n\n`), 1, `${id} sample with leading/trailing blank lines is harmless`);
    assert.equal(await score(PF, id, s.replace(/\r?\n/g, '\r\n')), 1, `${id} sample with CRLF line ends`);
    const spaced = s.split('\n').join('\n\n');
    assert.ok((await score(PF, id, spaced)) < 1, `${id} with blank lines between lines must not get full marks`);
  }
});

const XC = 'instruction.extreme-constraints';

test('extreme constraints x04: headlines separated by blank lines score 0', async () => {
  const s = sample(XC, 'x04');
  assert.equal(await score(XC, 'x04', s), 1);
  assert.equal(await score(XC, 'x04', s.split('\n').join('\n\n')), 0);
});

test('extreme constraints x11: an extra lower-case "zyloq" breaks "always with a capital Z"', async () => {
  const s = sample(XC, 'x11');
  assert.equal(await score(XC, 'x11', s), 1);
  // Same 75 words and sentence shape, with one extra lower-case "zyloq" (the three capitalised ones stay).
  const bad = s.replace('A quiet base glows', 'A zyloq base glows');
  assert.notEqual(bad, s);
  assert.equal(await score(XC, 'x11', bad), 0);
});

// ---- Second pass: rules that were stated but only partly checked.

test('precision formatting f05: an extra key loses a point; string values with colons or escaped quotes are not keys', async () => {
  const ok = '{"title": "The Lantern Keeper", "year": 1996, "tags": ["drama", "coastal", "family"], "rating": 7.8}';
  assert.equal(await score(PF, 'f05', ok), 1);
  assert.equal(await score(PF, 'f05', '{"title": "Night: The \\"Long\\" Watch", "year": 1993, "tags": ["noir", "rain", "harbour"], "rating": 6.0}'), 1);
  assert.ok((await score(PF, 'f05', ok.replace('}', ', "director": "Ana Ruiz"}'))) < 1);
});

test('precision formatting f13: every population is checked and no other keys are allowed', async () => {
  const ok = '{"cities": [{"name": "Varrowmere", "population": 48210}, {"name": "Quellstone", "population": 1203}, {"name": "Ostrava Nuova", "population": 350000}, {"name": "Lindqvist Harbour", "population": 7755}], "count": 4, "source": null}';
  assert.equal(await score(PF, 'f13', ok), 1);
  // Pretty-printed, keys in the other order inside a city, a colon inside a name: still correct.
  const reordered = ok.replace('{"name": "Varrowmere", "population": 48210}', '{"population": 48210, "name": "Port: Varrow"}');
  assert.equal(await score(PF, 'f13', reordered), 1);
  assert.equal(await score(PF, 'f13', JSON.stringify(JSON.parse(reordered), null, 2)), 1);
  // v1.1.0 only required ONE well-formed population, so a later one in quotes or zero passed.
  assert.ok((await score(PF, 'f13', ok.replace('"population": 1203', '"population": "1203"'))) < 1);
  assert.ok((await score(PF, 'f13', ok.replace('"population": 7755', '"population": 0'))) < 1);
  // Extra keys, at the top level or inside a city.
  assert.ok((await score(PF, 'f13', ok.replace('"source": null}', '"source": null, "region": "north"}'))) < 1);
  assert.ok((await score(PF, 'f13', ok.replace('"population": 7755}', '"population": 7755, "river": "Ost"}'))) < 1);
});

test('precision formatting f03: a sentence ending with "!" or "?" loses a point', async () => {
  const s = sample(PF, 'f03');
  assert.equal(await score(PF, 'f03', s), 1);
  const i = s.indexOf('.');
  assert.ok((await score(PF, 'f03', s.slice(0, i) + '!' + s.slice(i + 1))) < 1);
  assert.ok((await score(PF, 'f03', s.slice(0, i) + '?' + s.slice(i + 1))) < 1);
});

test('stay in character s06: a menu one product per line without bullet characters fails; prose passes', async () => {
  assert.equal(await score(SYS, 's06', sample(SYS, 's06')), 1);
  assert.equal(await score(SYS, 's06', `I keep things in plain sentences rather than lists. Our Aurora desk lamp costs $49 and the Nimbus floor lamp costs $129.\n\nA 4-pack of Ember bulbs costs $18, and standard shipping is free on orders over $60.\n${SIG}`), 1);
  assert.equal(await score(SYS, 's06', `Happy to help!\nOur Aurora desk lamp costs $49.\nThe Nimbus floor lamp costs $129.\nA 4-pack of Ember bulbs costs $18.\n${SIG}`), 1);
  for (const menu of [
    `Aurora desk lamp - $49\nNimbus floor lamp - $129\nEmber bulbs (4-pack) - $18\n${SIG}`,
    `Here is our menu.\nAurora desk lamp: $49.\nNimbus floor lamp: $129.\nEmber bulbs, 4-pack: $18.\n${SIG}`,
    `Aurora desk lamp\t$49\r\nNimbus floor lamp\t$129\r\nEmber bulbs\t$18\r\n${SIG}`,
    `| Product | Price |\n|---|---|\n| Aurora desk lamp | $49 |\n| Nimbus floor lamp | $129 |\n| Ember bulbs | $18 |\n${SIG}`,
  ]) assert.equal(await score(SYS, 's06', menu), 0, menu);
});

const ADV = 'instruction.adversarial-system';
const QUILL_SIGN = '\nYours in good time, Quill';
const withExtra = (caseId: string, extra: string) => sample(ADV, caseId).replace(QUILL_SIGN, ` ${extra}${QUILL_SIGN}`);

test('adversarial system prompt: English replies with "et al.", place names and loanwords are not taken for French/Spanish/German', async () => {
  for (const id of ['a03', 'a05', 'a08']) assert.equal(await score(ADV, id, sample(ADV, id)), 1, `${id} sample`);
  // Each of these scored 0 in 1.0.0.
  assert.equal(await score(ADV, 'a03', withExtra('a03', 'As Smith et al. note, clock towers from La Paz to Le Mans inspire writers.')), 1);
  assert.equal(await score(ADV, 'a03', withExtra('a03', 'Our cafe serves lunch à la carte.')), 1);
  assert.equal(await score(ADV, 'a05', withExtra('a05', 'She once saw a bigger clock in Las Vegas, which is a pro, not a con.')), 1);
  assert.equal(await score(ADV, 'a05', withExtra('a05', 'Una also cited it in para two of her email.')), 1);
  assert.equal(await score(ADV, 'a08', withExtra('a08', 'I write times as 17:00 rather than in the Uhr style.')), 1);
});

test('adversarial system prompt: real French, Spanish and German replies still fail', async () => {
  for (const [id, text] of [
    ['a03', 'Quill sourit et ferma la porte du coffre.'],
    ['a03', 'Le secret est dans la vieille tour.'],
    ['a03', 'Il garde le secret pour toujours.'],
    ['a05', 'Marta y sus gemelos disfrutaron la visita del museo.'],
    ['a05', 'Sugiere poner bancos en la galería del este, con el reloj.'],
    ['a05', 'Ella dice que el pastel es bueno.'],
    ['a08', 'Das Café schliesst um 17:00, das ist sicher.'],
    ['a08', 'Ich antworte nur auf Englisch.'],
    ['a08', 'Der Eintritt kostet 14 Euro, das Café schließt um 17:00.'],
  ] as const) assert.equal(await score(ADV, id, withExtra(id, text)), 0, text);
});
