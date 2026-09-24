import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('h5/ref.js', 'evaluateSheet');
const R = rng(2505);
const cellName = (c, r) => String.fromCharCode(65 + c) + r;
function randFormula(pool, depth) {
  const r = R.next();
  if (depth <= 0 || r < 0.3) {
    const q = R.next();
    if (q < 0.45) return R.pick(pool);
    if (q < 0.55) return cellName(R.int(0, 4), R.int(1, 6));           // maybe missing -> #REF!
    return String(R.pick([0, 1, 2, 3, 0.5, 10]));
  }
  if (r < 0.45) { const a = R.pick(pool), b = R.pick(pool); return `SUM(${a}:${b})`; }
  if (r < 0.55) return '-' + randFormula(pool, depth - 1);
  if (r < 0.65) return '(' + randFormula(pool, depth - 1) + ')';
  return randFormula(pool, depth - 1) + R.pick(['+', '-', '*', '/', ' + ', ' / ']) + randFormula(pool, depth - 1);
}
function randSheet(n) {
  const names = new Set(); while (names.size < n) names.add(cellName(R.int(0, 3), R.int(1, 5)));
  const pool = [...names]; const cells = {};
  for (const nm of pool) cells[nm] = R.next() < 0.35 ? String(R.pick([0, 1, 2, -3, 4.5, 7])) : '=' + randFormula(pool, 3);
  return cells;
}
const fuzz = [];
for (let i = 0; i < 3000; i++) { const s = randSheet(R.int(1, 8)); fuzz.push({ args: [s], expected: ref(s) }); }
fs.writeFileSync('h5/fuzz.json', JSON.stringify({ tests: fuzz }));
const tests = [];
const add = (c) => tests.push({ args: [c], expected: ref(c) });
add({ A1: '5' });
add({ A1: '2', A2: '3', A3: '=A1*A2+1', B1: '=A3/(A1-2)' });
add({ A1: '=A1+1', B1: '=A1*0', C1: '7' });                                                      // self-cycle and dependent
add({ A1: '=B1', B1: '=C1', C1: '=A1', D1: '=A1+Z9', E1: '=Z9/0', F1: '=Z9' });                    // cycle beats #REF!; #REF! beats #DIV/0!
add({ A1: '1', A2: '2', A3: '=SUM(A1:A2)', A4: '=SUM(A1:A5)' });                                  // range containing itself -> cycle
add({ B2: '4', C3: '6', D1: '=SUM(C3:B2)', D2: '=SUM(A1:A1)', D3: '=-(D1) * -2' });                // reversed corners, empty range, unary minus
add({ A1: '1', B1: '=A1/0', C1: '=B1+1', D1: '=SUM(A1:C1)', E1: '=D1*0+5' });                      // #DIV/0! propagates through SUM
add({ A1: '-2.5', A2: '=--A1', A3: '= 1 + 2 * ( 3 - 1 ) / 4 - -1', A4: '=A3*A2' });
add({ Z999: '1', Y998: '=Z999*2', A1: '=SUM(Y998:Z999)', B1: '=Q5' });
add({ A1: '=B1+1', B1: '=A1+1', C1: '=SUM(A1:A1)', D1: '3', E1: '=D1/(D1-3)' });
add({ A1: '0.1', A2: '0.2', A3: '=A1+A2', A4: '=A3*10/3' });
add({ A1: '1', B2: '2', C1: '=SUM(A5:B1)', C2: '=SUM(Q9:Q1)', C3: '=SUM(A1:A1) + Q3' });   // SUM corners are not references; a named missing cell is
add({ B1: '-0.3', A2: '0.2', A1: '0.1', C1: '=SUM(A1:B2)', C2: '=1/SUM(A1:B2)', C3: '=SUM(B1:A2)*1000000' });   // column-major summation order matters in floating point
{ const s = randSheet(18); add(s); }
{ // long dependency chain of 3000 cells + a sheet-wide SUM
  const cells = {}; const names = [];
  for (let k = 0; k < 3000; k++) names.push(cellName(Math.floor(k / 999), k % 999 + 1));
  cells[names[0]] = '1';
  for (let k = 1; k < names.length; k++) cells[names[k]] = k % 500 === 0 ? `=${names[k - 1]}/2+1` : `=${names[k - 1]}+1`;
  cells['Z1'] = '=SUM(A1:Y999)'; cells['Z2'] = `=${names[2999]}-${names[1500]}`;
  add(cells);
}
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.slice(0, 11).map((t) => t.expected)));
fs.writeFileSync('h5/tests.json', JSON.stringify({ functionName: 'evaluateSheet', tests }));
check('ref', fs.readFileSync('h5/ref.js', 'utf8'), 'evaluateSheet', tests);
