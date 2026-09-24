// Compute the prompt examples with each reference; the builder re-verifies them (reference + oracle).
import fs from 'node:fs';
import { load } from './lib.mjs';
const ex = {
  c1: ['runProgram', [
    ['MOV R1, 7\nADD R1, 5\nOUT R1', [], 100],
    ['  IN R0\ntop: ADD R1, R0 ; accumulate\n  LOOP R0, top\n  OUT R1\n  HALT', [4], 100],
    ['l: ADD R1, 1\nJMP l', [], 5],
    ['PUSH 3\nPOP R2\nPOP R1', [], 10],
    ['JMP nowhere', [], 10],
  ]],
  c2: ['coverageArea', [
    [[[[0, 0], [4, 0], [4, 4], [0, 4]], [[2, 2], [6, 2], [6, 6], [2, 6]]], 1],
    [[[[0, 0], [4, 0], [4, 4], [0, 4]], [[2, 2], [6, 2], [6, 6], [2, 6]]], 2],
    [[[[0, 0], [3, 0], [0, 3]]], 1],
    [[[[0, 0], [2, 2], [0, 4], [-2, 2]], [[0, 1], [3, 1], [3, 3], [0, 3]]], 2],
    [[[[0, 0], [2, 0], [0, 2]], [[0, 1], [2, 1], [2, 3], [1, 2], [0, 2]]], 2],
  ]],
  c3: ['palindromeRepeats', [
    [[['abaaba', 1]]],
    [[['a', 5]]],
    [[['ab', 2], ['c', 1], ['ba', 1]]],
    [[['xy', 1]]],
  ]],
  c4: ['minFleetCost', [
    [[0, 0], [[2, 0, 5, 0, 10, 20], [5, 3, 0, 0, 25, 30]], 100, 5],
    [[0, 0], [[2, 0, 5, 0, 10, 20], [5, 3, 0, 0, 22, 30]], 100, 5],
    [[0, 0], [[2, 0, 5, 0, 10, 20], [5, 3, 0, 0, 22, 30]], 100, 1],
    [[7, 7], [], 50, 1],
  ]],
  c5: ['minTowerCost', [
    [[-1, 0, 1], [5, 1, 5], [0, 1, 0], '111'],
    [[-1, 0, 1, 2], [1, 10, 10, 1], [1, 1, 1, 1], '1111'],
    [[2, 2, -1, 2, 3], [4, 4, 9, 1, 2], [0, 0, 0, 1, 2], '11010'],
    [[1, -1, 1], [3, 3, 3], [0, 0, 0], '000'],
  ]],
  c6: ['runExchange', [
    [[['LIMIT', 1, 'SELL', 101, 5], ['LIMIT', 2, 'SELL', 100, 3], ['LIMIT', 3, 'BUY', 101, 4], ['LIMIT', 4, 'BUY', 99, 2]]],
    [[['ICEBERG', 1, 'SELL', 50, 12, 5], ['LIMIT', 2, 'SELL', 50, 2], ['MARKET', 3, 'BUY', 6], ['CANCEL', 2]]],
    [[['LIMIT', 1, 'BUY', 20, 5], ['STOP', 2, 'SELL', 20, 3], ['LIMIT', 3, 'SELL', 20, 1], ['AMEND', 1, 20, 9]]],
  ]],
};
const out = {};
for (const [c, [fn, list]] of Object.entries(ex)) {
  const f = load(`./${c}/ref.js`, fn);
  out[c] = { fn, examples: list.map((args) => [args, f(...args)]) };
  for (const [a, r] of out[c].examples) console.log(c, JSON.stringify(a).slice(0, 110), '=>', JSON.stringify(r));
}
fs.writeFileSync(new URL('./examples.json', import.meta.url), JSON.stringify(out, null, 1));
