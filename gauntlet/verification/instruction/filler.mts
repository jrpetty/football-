// Scores random-filler answers with Gauntlet's real scorer: 5 answers per case from the real Random Baseline
// adapter (src/providers/mock.ts, different contestant ids = different seeds) plus 5 extra filler shapes.
import fs from 'node:fs';
import { createMockAdapter } from '../../src/providers/mock.ts';
import { scoreResponse } from '../../src/scoring/index.ts';
import { renderCase } from '../../src/core/registry.ts';

const file = process.argv[2];
const t = JSON.parse(fs.readFileSync(file, 'utf8'));
const WORDS = 'the of and to in is you that it he was for on are as with his they at be this have from or one had by word but not what all were we when your can said there use an each which she do how their if will up other about out many then them these so some her would make like him into time has look two more write go see number no way could people my than first water been call who oil its now find long down day did get come made may part'.split(' ');
let s = 7;
const rnd = () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; };
const pick = () => WORDS[Math.floor(rnd() * WORDS.length)];
const extra = [
  () => Array.from({ length: 60 }, pick).join(' '),
  () => Array.from({ length: 6 }, () => { const w = Array.from({ length: 9 }, pick).join(' '); return w[0].toUpperCase() + w.slice(1) + '.'; }).join(' '),
  () => Array.from({ length: 8 }, () => Array.from({ length: 7 }, pick).join(' ')).join('\n'),
  () => Array.from({ length: 3 }, () => Array.from({ length: 35 }, pick).join(' ') + '.').join('\n\n'),
  () => Array.from({ length: 25 }, pick).join(' ') + '?',
];
const noJudges = { ids: [], ask: async () => [] };
let grand = 0, n = 0, grandBase = 0, nBase = 0;
for (const c of t.cases) {
  const r = renderCase(t, c);
  const scorer = c.scorer ?? t.scorer;
  const answers: Array<[string, string]> = [];
  for (let k = 0; k < 5; k++) {
    const ad = createMockAdapter({ contestant: { id: 'random-baseline-' + k, model: 'x' } } as any);
    const messages: any[] = [];
    let text = '';
    for (const turn of r.turns) {
      messages.push({ role: 'user', content: turn });
      text = (await ad.complete({ system: r.system, messages, maxOutputTokens: 100 } as any)).text;
      messages.push({ role: 'assistant', content: text });
    }
    answers.push(['baseline', text]);
  }
  for (const f of extra) answers.push(['extra', f()]);
  let tot = 0, totB = 0, mx = 0;
  for (const [kind, a] of answers) {
    const out = await scoreResponse({ scorer, expected: c.expected, response: a, stopReason: 'end', taskText: '', judges: noJudges, saveArtifact: (nm: string) => ({ name: nm, kind: 'text', file: nm, bytes: 0 }), signal: new AbortController().signal } as any);
    tot += out.score ?? 0; mx = Math.max(mx, out.score ?? 0);
    if (kind === 'baseline') totB += out.score ?? 0;
  }
  grand += tot; n += answers.length; grandBase += totB; nBase += 5;
  console.log(c.id, 'baseline avg', (totB / 5).toFixed(3), 'all-filler avg', (tot / answers.length).toFixed(3), 'max', mx.toFixed(2));
}
console.log(`\n${t.id}: Random Baseline average ${(100 * grandBase / nBase).toFixed(1)}%, all filler shapes average ${(100 * grand / n).toFixed(1)}%`);
