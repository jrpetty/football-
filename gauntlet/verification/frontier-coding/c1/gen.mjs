// Hidden tests for c1. Every expected value comes from ref.js and is re-checked by the independent Python implementation.
import fs from 'node:fs';
import { load, deepEqual, python, rng } from '../lib.mjs';
const ref = load('./c1/ref.js', 'runProgram');
const P = (lines) => lines.join('\n');
const T = [];
const add = (label, src, input, maxSteps) => T.push({ label, args: [src, input, maxSteps] });

add('basic arithmetic and flags untouched by MOV', P(['MOV R1, 7', 'MOV R2, -3', 'ADD R1, R2', 'MUL R1, 10', 'SUB R1, 41', 'OUT R1', 'MOV R3, R1', 'JE zero', 'OUT 1', 'zero: HALT']), [], 100);
add('sum 1..100 with LOOP', P(['    mov r0, 100', 'top: add r1, r0', '    loop r0, top', '    out r1']), [], 1000);
add('multiplication overflow wraps modulo 2^32 and sets C/V', P([
  'MOV R1, 123456789', 'MUL R1, 987654321', 'OUT R1', 'JB c1', 'OUT 0', 'c1: MOV R2, 46341', 'MUL R2, R2', 'OUT R2', 'JL neg', 'OUT 100', 'neg: MOV R3, -46340',
  'MUL R3, 46341', 'OUT R3', 'JB bad', 'OUT 7', 'bad: MOV R4, 0x7FFFFFFF', 'MUL R4, 0x7FFFFFFF', 'OUT R4', 'MOV R5, -2147483648', 'MUL R5, -1', 'OUT R5', 'JA ab', 'OUT 9', 'ab: MOV R6, 65537', 'MUL R6, 65537', 'OUT R6']), [], 1000);
add('DIV/MOD truncation, signs and INT_MIN / -1', P([
  'MOV R1, -7', 'DIV R1, 2', 'OUT R1', 'MOV R1, -7', 'MOD R1, 2', 'OUT R1', 'MOV R1, 7', 'DIV R1, -2', 'OUT R1', 'MOV R1, 7', 'MOD R1, -2', 'OUT R1',
  'MOV R2, -2147483648', 'DIV R2, -1', 'OUT R2', 'JGE ok1', 'OUT 111', 'ok1: MOV R3, -2147483648', 'MOD R3, -1', 'OUT R3', 'JE ok2', 'OUT 222', 'ok2: MOV R4, 2147483647', 'DIV R4, -2147483648', 'OUT R4', 'MOV R5, -2147483648', 'MOD R5, 7', 'OUT R5']), [], 1000);
add('division by zero stops before the instruction takes effect', P(['MOV R1, 10', 'OUT R1', 'MOV R2, 0', 'DIV R1, R2', 'OUT 99']), [], 100);
add('MOD by zero', P(['IN R1', 'IN R2', 'MOD R1, R2', 'OUT R1']), [5, 0], 100);
add('shift counts are taken modulo 32; SHR vs SAR; carry out', P([
  'MOV R1, 1', 'SHL R1, 33', 'OUT R1', 'MOV R2, -1', 'SHR R2, 1', 'OUT R2', 'MOV R3, -1', 'SAR R3, 5', 'OUT R3', 'MOV R4, 3', 'SHL R4, -1', 'OUT R4', 'JB c', 'OUT 0',
  'c: MOV R5, -8', 'SAR R5, 1', 'OUT R5', 'MOV R6, 5', 'SHR R6, 32', 'OUT R6', 'JAE nc', 'OUT 1', 'nc: MOV R7, 0x80000001', 'SHR R7, 31', 'OUT R7', 'MOV R0, 0x40000000', 'SHL R0, 1', 'JL sgn', 'OUT 2', 'sgn: OUT R0']), [], 1000);
add('shift carry chain decides jumps', P(['MOV R1, 0x80000000', 'SHL R1, 1', 'JE z', 'OUT 1', 'z: JB c', 'OUT 2', 'c: MOV R2, 6', 'SHR R2, 2', 'JB c2', 'OUT 3', 'c2: MOV R3, 5', 'SAR R3, 1', 'JA c3', 'OUT 4', 'c3: OUT R3', 'MOV R4, 0', 'SHL R4, 0', 'JBE c4', 'OUT 5', 'c4: HALT']), [], 100);
add('ADD carry vs overflow', P(['MOV R1, -1', 'ADD R1, 1', 'JB carry', 'OUT 0', 'carry: JE zero', 'OUT 1', 'zero: MOV R2, 2147483647', 'ADD R2, 1', 'OUT R2', 'JL lt', 'OUT 2', 'lt: JB b2', 'OUT 3',
  'b2: MOV R3, -2147483648', 'ADD R3, -2147483648', 'OUT R3', 'JE z3', 'OUT 4', 'z3: JB c3', 'OUT 5', 'c3: JGE g3', 'OUT 6', 'g3: HALT']), [], 100);
add('signed comparison must use V when the subtraction overflows', P(['IN R1', 'IN R2', 'CMP R1, R2', 'JL less', 'OUT 0', 'JMP next', 'less: OUT 1', 'next: CMP R2, R1', 'JG greater', 'OUT 0', 'HALT', 'greater: OUT 1']), [-2147483648, 1], 100);
add('signed comparison 2', P(['IN R1', 'IN R2', 'CMP R1, R2', 'JLE a', 'OUT 10', 'a: JGE b', 'OUT 20', 'b: JG c', 'OUT 30', 'c: JL d', 'OUT 40', 'd: HALT']), [2147483647, -5], 100);
add('unsigned comparisons treat negatives as large', P(['MOV R1, -1', 'CMP R1, 1', 'JA above', 'OUT 0', 'above: MOV R2, 5', 'CMP R2, -5', 'JB below', 'OUT 1', 'below: CMP R2, 5', 'JBE be', 'OUT 2', 'be: JAE ae', 'OUT 3', 'ae: CMP R2, 6', 'JAE bad', 'OUT 4', 'bad: HALT']), [], 100);
add('unsigned maximum of the input', P(['    IN R0', '    MOV R1, R0', 'more: LOOP R0, body', '    OUT R1', '    HALT', 'body: IN R2', '    CMP R2, R1', '    JBE more', '    MOV R1, R2', '    JMP more']), [6, 5, -3, 2000000000, -2000000000, 7], 1000);
add('immediates: hex, signs, wrapping', P(['MOV R1, 0xFFFFFFFF', 'MOV R2, 0x80000000', 'MOV R3, 4294967296', 'MOV R4, -0x10', 'MOV R5, +0X7fffffff', 'MOV R6, 4294967297', 'MOV R7, -2147483649', 'MOV R0, 0x0000000A', 'OUT 0xABCDEF01', 'OUT -4294967295']), [], 100);
add('case-insensitive mnemonics/registers but case-sensitive labels', P(['  mOv r1, 3', 'Loop: out 1', '  JmP loop', 'loop: SUB r1, 1', '  jne Loop', '  Out R1']), [], 100);
add('labels: several per line, label-only lines, label at end', P(['start: a_1: b2:', '', '   ; just a comment', 'MOV R1, 1', 'CMP R1, 1', 'JE finish', 'OUT 5', 'x: y:MOV R2, 2', 'finish:']), [], 100);
add('program without HALT runs off the end; label after last instruction', P(['MOV R1, 1', 'ADD R1, R1', 'OUT R1', 'JMP done', 'OUT 99', 'done:']), [], 100);
add('recursive factorial via CALL/RET, wrapping at 13!', P(['    IN R1', 'next: MOV R0, R1', '    CALL fact', '    OUT R2', '    SUB R1, 1', '    JG next', '    HALT', 'fact: CMP R0, 1', '    JG rec', '    MOV R2, 1', '    RET', 'rec: PUSH R0', '    SUB R0, 1', '    CALL fact', '    POP R0', '    MUL R2, R0', '    RET']), [14], 10000);
add('stack overflow through unbounded recursion', P(['MOV R1, 0', 'f: ADD R1, 1', 'CALL f', 'HALT']), [], 100000);
add('PUSH overflow at exactly 256 values', P(['MOV R1, 300', 'p: PUSH R1', 'LOOP R1, p', 'HALT']), [], 100000);
add('POP on empty stack', P(['MOV R1, 5', 'PUSH 1', 'POP R2', 'POP R1', 'OUT 3']), [], 100);
add('RET with empty stack', P(['OUT 1', 'RET', 'OUT 2']), [], 100);
add('RET to an out-of-range address', P(['PUSH 7', 'RET', 'OUT 5', 'HALT']), [], 100);
add('RET to n halts', P(['PUSH 3', 'RET', 'OUT 5']), [], 100);
add('RET to a computed address', P(['MOV R1, 5', 'PUSH R1', 'RET', 'OUT 1', 'OUT 2', 'OUT 3', 'MOV R2, -1', 'PUSH R2', 'RET']), [], 100);
add('memory bounds', P(['MOV R1, 1000', 'STORE [R1+23], 5', 'LOAD R2, [ R1 + 23 ]', 'OUT R2', 'STORE [1023], 9', 'LOAD R3, [0x3FF]', 'OUT R3', 'LOAD R4, [R1-1001]', 'OUT R4']), [], 100);
add('memory bounds with wrapped absolute address', P(['STORE [4294967297], 42', 'LOAD R1, [1]', 'OUT R1', 'LOAD R2, [0xFFFFFFFF]', 'OUT R2']), [], 100);
add('read until the input runs out', P(['loop: IN R1', 'ADD R2, R1', 'OUT R2', 'JMP loop']), [3, -1, 10], 100);
add('step limit', P(['MOV R1, 0', 'l: ADD R1, 3', 'XOR R2, R1', 'JMP l']), [], 1000);
add('step limit vs running off the end', P(['MOV R1, 1', 'MOV R2, 2', 'MOV R3, 3']), [], 3);
add('step limit reached right before HALT', P(['MOV R1, 1', 'MOV R2, 2', 'HALT']), [], 2);
add('LOOP from 0 wraps around', P(['l: ADD R1, 1', 'LOOP R0, l', 'OUT R1']), [], 5001);
add('syntax: undefined label', P(['MOV R1, 1', 'JMP Done', 'done: HALT']), [], 100);
add('syntax: duplicate label', P(['a: MOV R1, 1', 'b: a: HALT']), [], 100);
add('syntax: wrong operand kind', P(['MOV R1, 1', 'LOAD R2, R1', 'HALT']), [], 100);
add('syntax: register name as label', P(['MOV R1, 1', 'r5: HALT']), [], 100);
add('syntax: bad memory offset', P(['LOAD R1, [R2+0x4]']), [], 100);
add('comments and odd spacing', P(['\tMOV\tR1 ,\t5   ; set: R1', 'OUT R1;no space', '  ; MOV R1, 99', 'x :OUT  -1 ;x: y:', 'HALT ; done']), [], 100);
add('bitwise ops and NOT/NEG flags', P(['MOV R1, 0x0F0F', 'AND R1, 0x00FF', 'OUT R1', 'MOV R2, 0xF0', 'OR R2, 0x0F', 'XOR R2, -1', 'OUT R2', 'NOT R2', 'OUT R2', 'MOV R3, -2147483648', 'NEG R3', 'OUT R3', 'JL x', 'OUT 1', 'x: JB y', 'OUT 2', 'y: MOV R4, 0', 'NEG R4', 'JB z', 'OUT 3', 'z: JE w', 'OUT 4', 'w: HALT']), [], 100);
add('bubble sort in memory', P([
  '      IN R0', '      MOV R1, 0', 'rd:   CMP R1, R0', '      JGE sort', '      IN R2', '      STORE [R1+100], R2', '      ADD R1, 1', '      JMP rd',
  'sort: MOV R1, R0', 'outer: SUB R1, 1', '      JLE print', '      MOV R2, 0', 'inner: CMP R2, R1', '      JGE outer', '      LOAD R3, [R2+100]', '      LOAD R4, [R2+101]',
  '      CMP R3, R4', '      JLE noswap', '      STORE [R2+100], R4', '      STORE [R2+101], R3', 'noswap: ADD R2, 1', '      JMP inner',
  'print: MOV R1, 0', 'pl:   CMP R1, R0', '      JGE end', '      LOAD R3, [R1+100]', '      OUT R3', '      ADD R1, 1', '      JMP pl', 'end:  HALT']), [12, 5, -3, 99, 0, -2147483648, 2147483647, 7, 7, -1, 42, 13, 8], 100000);
add('sieve of Eratosthenes below 1024', P([
  '     MOV R1, 2', 'outer: MOV R2, R1', '     MUL R2, R1', '     CMP R2, 1024', '     JGE count', '     LOAD R3, [R1]', '     CMP R3, 0', '     JNE nxt',
  'mark: STORE [R2], 1', '     ADD R2, R1', '     CMP R2, 1024', '     JL mark', 'nxt: ADD R1, 1', '     JMP outer',
  'count: MOV R1, 2', '     MOV R4, 0', 'c:   LOAD R3, [R1]', '     CMP R3, 0', '     JNE skip', '     ADD R4, 1', '     MOV R5, R1', 'skip: ADD R1, 1', '     CMP R1, 1024', '     JL c', '     OUT R4', '     OUT R5']), [], 1000000);

// ---- integration programs: every instruction with tricky operands, forward-only jumps inside an outer LOOP ----
function integration(seed, iters) {
  const R = rng(seed);
  const SPECIAL = ['0', '1', '-1', '31', '32', '33', '-33', '0x7FFFFFFF', '0x80000000', '0xFFFFFFFF', '-2147483648', '2147483647', '46341', '-46341', '65537', '7', '-7', '3', '0xDEADBEEF', '4294967299'];
  const reg = () => 'R' + R.int(0, 6);           // R7 is the loop counter
  const val = () => (R.chance(0.5) ? reg() : R.pick(SPECIAL));
  const lines = ['      IN R0', '      IN R1', '      IN R2', '      MOV R3, 0x13579BDF', '      MOV R4, -1', '      MOV R5, 2147483647', '      MOV R6, 12345', `      MOV R7, ${iters}`,
    'top:  ADD R0, R7', '      XOR R1, R7', '      SUB R2, R7'];
  let lab = 0;
  const body = [];
  const n = R.int(28, 40);
  for (let i = 0; i < n; i++) {
    const k = R.next();
    if (k < 0.45) body.push(`      ${R.pick(['ADD', 'SUB', 'MUL', 'AND', 'OR', 'XOR', 'SHL', 'SHR', 'SAR', 'ADD', 'SUB', 'MUL'])} ${reg()}, ${val()}`);
    else if (k < 0.53) body.push(`      ${R.pick(['DIV', 'MOD'])} ${reg()}, ${R.pick(['-1', '7', '-3', '0x10', '65536', '-2147483648', '3'])}`);
    else if (k < 0.58) body.push(`      ${R.pick(['NEG', 'NOT'])} ${reg()}`);
    else if (k < 0.78) {
      const a = `f${seed}_${lab++}`;
      const op = R.pick(['CMP', 'CMP', 'ADD', 'SUB', 'MUL', 'SHL', 'SAR', 'NEG']);
      body.push(op === 'NEG' ? `      NEG ${reg()}` : `      ${op} ${reg()}, ${val()}`);
      body.push(`      ${R.pick(['JE', 'JNE', 'JL', 'JLE', 'JG', 'JGE', 'JB', 'JBE', 'JA', 'JAE'])} ${a}`);
      for (let j = R.int(1, 3); j > 0; j--) body.push(`      ${R.pick(['XOR', 'ADD', 'SUB'])} ${reg()}, ${R.pick(SPECIAL)}`);
      body.push(`${a}:`);
    } else if (k < 0.86) { const off = R.int(0, 40) * 17 % 1000; body.push(`      STORE [${off}], ${reg()}`, `      LOAD ${reg()}, [ ${off} ]`); }
    else if (k < 0.92) body.push(`      PUSH ${val()}`, `      PUSH ${reg()}`, `      POP ${reg()}`, `      POP ${reg()}`);
    else body.push(`      CALL sub${seed}`);
  }
  // rolling checksum of R0..R6 kept in memory[1023]
  body.push('      PUSH R1', '      LOAD R1, [1023]', '      MUL R1, 31', '      ADD R1, R0', '      XOR R1, R2', '      ADD R1, R3', '      SUB R1, R4', '      XOR R1, R5', '      ADD R1, R6', '      ADD R1, R7', '      STORE [1023], R1', '      POP R1', '      LOOP R7, top');
  const tail = ['      LOAD R7, [1023]', '      OUT R7', '      OUT R0', '      OUT R1', '      OUT R2', '      OUT R3', '      OUT R4', '      OUT R5', '      OUT R6', '      HALT',
    `sub${seed}: MUL R6, 0x9E3779B1`, '      ADD R6, R3', '      SAR R3, 3', '      XOR R3, R6', '      RET'];
  return [...lines, ...body, ...tail].map((l) => l.replace(/^(\s*)([A-Z]+)(?=\s)/, (m, sp, w) => sp + (R.chance(0.2) ? w.toLowerCase() : w))).join('\n');
}
for (let i = 1; i <= 8; i++) {
  const inputs = [[5, -9, 1000003], [-2147483648, 2147483647, 0], [123456789, -987654321, 42], [1, 2, 3], [0x7654321, -0x1234567, 99991], [-1, -1, -1], [65536, 65535, -65536], [2147483647, 7, -7]][i - 1];
  add(`integration program #${i} (all instructions, tricky operands)`, integration(1000 + i, [3, 50, 200, 1000, 20, 400, 5, 2500][i - 1]), inputs, 2000000);
}
// ---- performance tests (millions of instructions) ----
add('perf: xorshift hash over 400,000 rounds', P([
  '      MOV R1, 2463534242', '      MOV R0, 400000', 'round: MOV R2, R1', '      SHL R2, 13', '      XOR R1, R2', '      MOV R2, R1', '      SHR R2, 17', '      XOR R1, R2',
  '      MOV R2, R1', '      SHL R2, 5', '      XOR R1, R2', '      MOV R3, R1', '      AND R3, 1023', '      LOAD R4, [R3]', '      ADD R4, R1', '      STORE [R3], R4', '      MUL R5, 31', '      ADD R5, R1', '      LOOP R0, round',
  '      OUT R1', '      OUT R5', '      LOAD R6, [512]', '      OUT R6']), [], 10000000);
add('perf: nested loops with signed/unsigned compares', P([
  '      MOV R0, 0', 'i:    MOV R1, 0', 'j:    MOV R2, R0', '      MUL R2, R1', '      CMP R2, 1000000', '      JB small', '      SUB R6, R2', '      JMP cont', 'small: ADD R7, R2', 'cont: ADD R1, 1', '      CMP R1, 1500', '      JL j',
  '      ADD R0, 1', '      CMP R0, 700', '      JL i', '      OUT R6', '      OUT R7']), [], 10000000);
add('perf: recursive Fibonacci with CALL/RET', P(['      IN R0', '      CALL fib', '      OUT R1', '      HALT', 'fib:  CMP R0, 2', '      JGE rec', '      MOV R1, R0', '      RET',
  'rec:  PUSH R0', '      SUB R0, 1', '      CALL fib', '      POP R0', '      PUSH R1', '      SUB R0, 2', '      CALL fib', '      POP R2', '      ADD R1, R2', '      ADD R0, 2', '      RET']), [25], 10000000);
add('perf: step limit hit inside a long computation', P(['l: ADD R1, 7', 'MUL R1, 3', 'MOD R1, 1000003', 'JMP l']), [], 3000001);

// keep 40 tests: drop simpler tests whose rules are covered elsewhere (the integration programs exercise everything at once)
const DROP = new Set(['MOD by zero', 'RET with empty stack', 'signed comparison 2', 'syntax: bad memory offset', 'step limit',
  'basic arithmetic and flags untouched by MOV', 'sum 1..100 with LOOP', 'unsigned maximum of the input', 'program without HALT runs off the end; label after last instruction',
  'POP on empty stack', 'sieve of Eratosthenes below 1024', 'RET to a computed address', 'shift carry chain decides jumps']);
for (let i = T.length - 1; i >= 0; i--) if (DROP.has(T[i].label)) T.splice(i, 1);
if (T.length !== 40) throw new Error('expected 40 tests, got ' + T.length);
const tests = T.map((t) => ({ args: t.args, expected: ref(...t.args) }));
const py = python('./c1/brute.py', T.map((t) => t.args));
let bad = 0;
tests.forEach((t, i) => { if (!deepEqual(t.expected, py[i])) { bad++; console.log('MISMATCH', T[i].label, JSON.stringify(t.expected).slice(0, 300), JSON.stringify(py[i]).slice(0, 300)); } });
T.forEach((t, i) => console.log(String(i + 1).padStart(2), t.label.padEnd(64), tests[i].expected.status.padEnd(15), 'steps', tests[i].expected.steps, 'out', JSON.stringify(tests[i].expected.output).slice(0, 70)));
console.log(bad ? `${bad} MISMATCHES` : `all ${tests.length} tests agree with the Python implementation`);
fs.writeFileSync(new URL('./tests.json', import.meta.url), JSON.stringify({ functionName: 'runProgram', tests }));
fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(T.map((t) => t.label)));
