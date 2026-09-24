// Cross-check c1/ref.js against the independent Python implementation (c1/brute.py) on random programs.
import { rng, load, deepEqual, python } from '../lib.mjs';
const ref = load('./c1/ref.js', 'runProgram');
const R = rng(Number(process.argv[2] || 1));
const N = Number(process.argv[3] || 4000);
const OPS2 = ['MOV', 'ADD', 'SUB', 'MUL', 'DIV', 'MOD', 'AND', 'OR', 'XOR', 'SHL', 'SHR', 'SAR', 'CMP'];
const JUMPS = ['JMP', 'JE', 'JNE', 'JL', 'JLE', 'JG', 'JGE', 'JB', 'JBE', 'JA', 'JAE'];
const SPECIAL = [0, 1, -1, 2, 31, 32, 33, 63, -2147483648, 2147483647, -2147483647, 65536, 46341, -46341, 1023, 1024, 7];
function caseify(s) { return s.split('').map((c) => (R.chance(0.3) ? (R.chance(0.5) ? c.toLowerCase() : c.toUpperCase()) : c)).join(''); }
function ws() { return R.pick(['', ' ', '  ', '\t', ' \t ']); }
function reg() { return caseify('R' + R.int(0, 7)); }
function immText(v) {
  const r = R.next();
  if (r < 0.15) { const u = v < 0 ? v + 4294967296 : v; return (R.chance(0.5) ? '0x' : '0X') + (R.chance(0.5) ? u.toString(16) : u.toString(16).toUpperCase()); }
  if (r < 0.2) return (v >= 0 ? '+' : '') + String(v);
  if (r < 0.25 && v >= 0) return String(v + 4294967296);
  if (r < 0.28) return '-0x' + Math.abs(v).toString(16);
  return String(v);
}
function value() { return R.chance(0.45) ? reg() : immText(R.chance(0.5) ? R.pick(SPECIAL) : R.int(-40, 40)); }
function mem() {
  const r = R.next();
  if (r < 0.4) return '[' + ws() + reg() + ws() + ']';
  if (r < 0.8) return '[' + ws() + reg() + ws() + R.pick(['+', '-']) + ws() + R.int(0, 12) + ws() + ']';
  return '[' + ws() + immText(R.pick([0, 1, 5, 1023, 1024, -1, 100])) + ws() + ']';
}
function bad() {
  return R.pick(['MOVE R1, 2', 'MOV R8, 1', 'MOV 5, R1', 'LOAD R1, R2', 'ADD R1', 'ADD R1, 2, 3', 'JMP nowhere', 'MOV R1, 0x', 'MOV R1, 5x', 'STORE [R1+0x1], 2',
    'PUSH', 'RET R1', 'LOAD R1, [R9]', 'MOV R1,, 2', 'OUT 1 2', 'r3: HALT', 'MOVR1, 2', 'LOAD R1, [R1 + -2]', 'JMP R1', 'NEG 5', 'x x: HALT', 'MOV R1, --5', 'MOV R1, 1_0']);
}
function program() {
  const len = R.int(1, 14);
  const labels = ['a', 'b', 'loop', 'Loop', 'end', '_x1'];
  const used = new Set();
  const lines = [];
  for (let i = 0; i < len; i++) {
    let line = '';
    while (R.chance(0.25)) {
      const l = R.pick(labels);
      if (used.has(l) && !R.chance(0.03)) continue;
      used.add(l);
      line += ws() + l + ws() + ':' + ws();
    }
    const k = R.next();
    let ins;
    if (k < 0.03) ins = bad();
    else if (k < 0.07) ins = '';
    else if (k < 0.45) ins = R.pick(OPS2) + ' ' + ws() + reg() + ws() + ',' + ws() + value();
    else if (k < 0.55) ins = R.pick(JUMPS) + ' ' + ws() + R.pick(labels);
    else if (k < 0.6) ins = 'LOOP ' + reg() + ',' + ws() + R.pick(labels);
    else if (k < 0.65) ins = 'LOAD ' + reg() + ', ' + mem();
    else if (k < 0.7) ins = 'STORE ' + mem() + ', ' + value();
    else if (k < 0.74) ins = 'CALL ' + R.pick(labels);
    else if (k < 0.77) ins = 'RET';
    else if (k < 0.81) ins = 'PUSH ' + value();
    else if (k < 0.84) ins = 'POP ' + reg();
    else if (k < 0.87) ins = 'IN ' + reg();
    else if (k < 0.93) ins = 'OUT ' + value();
    else if (k < 0.96) ins = R.pick(['NEG ', 'NOT ']) + reg();
    else ins = 'HALT';
    line += caseify(ins.split(' ')[0]) + ins.slice(ins.split(' ')[0].length);
    if (R.chance(0.1)) line += ws() + '; comment: MOV R1, 2';
    lines.push(line);
  }
  // make most label references resolvable
  for (const l of labels) if (!used.has(l) && R.chance(0.85)) lines.push(l + ':');
  return lines.join('\n');
}
const cases = [];
for (let i = 0; i < N; i++) {
  const input = Array.from({ length: R.int(0, 4) }, () => (R.chance(0.3) ? R.pick(SPECIAL) : R.int(-50, 50)));
  cases.push([program(), input, R.int(1, 400)]);
}
const py = python('./c1/brute.py', cases);
let bad2 = 0; const statuses = {};
for (let i = 0; i < N; i++) {
  const r = ref(...cases[i]);
  statuses[r.status] = (statuses[r.status] || 0) + 1;
  if (!deepEqual(r, py[i])) { bad2++; if (bad2 < 4) console.log('MISMATCH', JSON.stringify(cases[i]), '\nref', JSON.stringify(r), '\npy ', JSON.stringify(py[i])); }
}
console.log(`c1 fuzz: ${N - bad2}/${N} agree; statuses`, JSON.stringify(statuses));
