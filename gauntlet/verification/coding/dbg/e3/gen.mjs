import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('e3/ref.js', 'rankLeaderboard');
const R = rng(1303);
const tests = [];
const add = (e) => tests.push({ args: [e], expected: ref(e) });
add([]);
add([['solo', 10, 5]]);
add([['a', 10, 50], ['b', 20, 60], ['c', 20, 40]]);
add([['a', 5, 9], ['b', 5, 9], ['c', 5, 9]]);                                  // all tied -> all rank 1, input order kept
add([['ann', 7, 30], ['bob', 9, 30], ['cat', 7, 30], ['dan', 7, 25], ['eve', 9, 30]]); // 1,1,3,4,4
add([['neg', -3, 1], ['zero', 0, 100], ['neg2', -3, 0]]);
add([['x', 2.5, 1], ['y', 2.5, 1.5], ['z', 10, 0], ['w', 2.5, 1]]);
add([['Same', 1, 1], ['Same', 1, 1], ['same', 1, 2]]);
add([['q', 3, 9], ['r', 4, 9], ['s', 3, 1], ['t', 4, 9], ['u', 3, 9], ['v', 0, 0]]);
add([['late', 50, 1000000], ['early', 50, 0.5], ['mid', 50, 3]]);                         // duplicate names are separate entries
{ const e = []; for (let i = 0; i < 40; i++) e.push(['p' + i, R.int(0, 5), R.int(0, 3)]); add(e); }
{ const e = []; for (let i = 0; i < 1500; i++) e.push(['u' + i, R.int(0, 60), R.int(0, 10)]); add(e); }
{ const e = []; for (let i = 0; i < 1500; i++) e.push(['v' + i, 100, 7]); add(e); }                  // one giant tie
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.slice(0, 8).map((t) => t.expected)));
fs.writeFileSync('e3/tests.json', JSON.stringify({ functionName: 'rankLeaderboard', tests }));
check('ref', fs.readFileSync('e3/ref.js', 'utf8'), 'rankLeaderboard', tests);
check('dense-rank bug', fs.readFileSync('e3/ref.js', 'utf8').replace(': k + 1;', ': out[k - 1][0] + 1;'), 'rankLeaderboard', tests);
