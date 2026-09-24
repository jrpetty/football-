import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('h4/ref.js', 'simulateCache');
const R = rng(2404);
const randEvents = (n, keys, tmax, vmax) => { const ev = []; let t = 0; for (let i = 0; i < n; i++) { t += R.pick([0, 0, 1, 1, 2, 3]); const k = 'k' + R.int(1, keys); ev.push(R.next() < 0.5 ? ['put', t, k, R.int(0, vmax)] : ['get', t, k]); } return ev; };
const fuzz = [];
for (let i = 0; i < 3000; i++) { const args = [R.int(1, 4), R.int(1, 6), randEvents(R.int(0, 25), 6, 50, 9)]; fuzz.push({ args, expected: ref(...args) }); }
fs.writeFileSync('h4/fuzz.json', JSON.stringify({ tests: fuzz }));
const tests = [];
const add = (c, ttl, ev) => tests.push({ args: [c, ttl, ev], expected: ref(c, ttl, ev) });
add(2, 10, []);
add(2, 10, [['get', 0, 'a']]);
add(2, 10, [['put', 0, 'a', 1], ['put', 1, 'b', 2], ['get', 2, 'a'], ['put', 3, 'c', 3], ['get', 4, 'b'], ['get', 4, 'a'], ['get', 4, 'c']]);          // get refreshes recency
add(2, 5, [['put', 0, 'a', 1], ['get', 4, 'a'], ['get', 5, 'a']]);                                                                                          // expiry is exclusive at t+ttl
add(2, 5, [['put', 0, 'a', 1], ['get', 3, 'a'], ['put', 4, 'b', 2], ['get', 5, 'a'], ['get', 8, 'b'], ['get', 9, 'b']]);                                   // get does not extend ttl
add(2, 5, [['put', 0, 'a', 1], ['put', 3, 'a', 7], ['get', 7, 'a'], ['get', 8, 'a']]);                                                                      // put refreshes ttl and value
add(2, 3, [['put', 0, 'a', 1], ['put', 1, 'b', 2], ['put', 3, 'c', 3], ['get', 3, 'b'], ['get', 3, 'c'], ['get', 3, 'a']]);                               // expired entry frees capacity: b survives
add(1, 100, [['put', 0, 'a', 1], ['put', 0, 'b', 2], ['get', 0, 'a'], ['get', 0, 'b'], ['put', 0, 'b', 3], ['get', 0, 'b']]);
add(3, 1, [['put', 5, 'x', 1], ['get', 5, 'x'], ['get', 6, 'x'], ['put', 6, 'x', 2], ['get', 6, 'x']]);
add(2, 10, [['put', 0, 'a', 1], ['put', 0, 'b', 2], ['get', 0, 'a'], ['put', 0, 'b', 5], ['put', 0, 'c', 3], ['get', 0, 'a'], ['get', 0, 'b'], ['get', 0, 'c']]);   // put on existing key counts as use
add(3, 4, randEvents(60, 6, 0, 99));
add(50, 30, randEvents(1500, 120, 0, 1000));
{ const ev = []; for (let i = 0; i < 4500; i++) ev.push(R.next() < 0.6 ? ['put', i, 'u' + R.int(1, 1500), R.int(0, 9)] : ['get', i, 'u' + R.int(1, 1500)]); add(900, 1200, ev); }
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.slice(0, 10).map((t) => t.expected)));
fs.writeFileSync('h4/tests.json', JSON.stringify({ functionName: 'simulateCache', tests }));
check('ref', fs.readFileSync('h4/ref.js', 'utf8'), 'simulateCache', tests);
