// Plausible fast solution with common slips: (a*b)|0 for MUL, signed jumps from N only, shift by 0 keeps C.
// "Typical" correct solution in the style a model might write: top-level helpers, objects, string switch.
function toInt32(x) { return typeof x === "bigint" ? Number(BigInt.asIntN(32, x)) : x | 0; }
function parseOperand(tok, kind) {
  tok = tok.trim();
  if (/^r[0-7]$/i.test(tok)) return { t: 'reg', r: +tok[1] };
  if (kind === 'm') {
    const m = /^\[\s*(.*?)\s*\]$/.exec(tok); if (!m) throw 0;
    const inner = m[1];
    if (/^r[0-7]$/i.test(inner)) return { t: 'mem', r: +inner[1], off: 0 };
    const m2 = /^(r[0-7])\s*([+-])\s*(\d+)$/i.exec(inner);
    if (m2) return { t: 'mem', r: +m2[1][1], off: (m2[2] === '-' ? -1 : 1) * Number(m2[3]) };
    return { t: 'mem', r: -1, off: parseImm(inner) };
  }
  return { t: 'imm', v: parseImm(tok) };
}
function parseImm(tok) {
  const m = /^([+-]?)(0x[0-9a-f]+|\d+)$/i.exec(tok); if (!m) throw 0;
  let v = BigInt(m[2]); if (m[1] === '-') v = -v; return Number(BigInt.asIntN(32, v));
}
const SIGS = { MOV: 'rv', LOAD: 'rm', STORE: 'mv', ADD: 'rv', SUB: 'rv', MUL: 'rv', DIV: 'rv', MOD: 'rv', AND: 'rv', OR: 'rv', XOR: 'rv', SHL: 'rv', SHR: 'rv', SAR: 'rv', NEG: 'r', NOT: 'r', CMP: 'rv',
  JMP: 'L', JE: 'L', JNE: 'L', JL: 'L', JLE: 'L', JG: 'L', JGE: 'L', JB: 'L', JBE: 'L', JA: 'L', JAE: 'L', LOOP: 'rL', CALL: 'L', RET: '', PUSH: 'v', POP: 'r', IN: 'r', OUT: 'v', HALT: '' };
function assemble(source) {
  const prog = []; const labels = {}; let pending = [];
  for (let line of source.split('\n')) {
    const c = line.indexOf(';'); if (c >= 0) line = line.slice(0, c);
    let m;
    while ((m = /^\s*([A-Za-z_]\w*)\s*:/.exec(line))) {
      if (/^r[0-7]$/i.test(m[1]) || m[1] in labels || pending.includes(m[1])) throw 0;
      pending.push(m[1]); line = line.slice(m[0].length);
    }
    line = line.trim(); if (!line) continue;
    m = /^([A-Za-z]+)(?:[ \t]+(.*))?$/.exec(line); if (!m) throw 0;
    const op = m[1].toUpperCase(); const sig = SIGS[op]; if (sig === undefined) throw 0;
    const parts = m[2] ? m[2].split(',').map((s) => s.trim()) : [];
    if (parts.length !== sig.length) throw 0;
    const ops = parts.map((p, i) => {
      const k = sig[i];
      if (k === 'L') { if (!/^[A-Za-z_]\w*$/.test(p) || /^r[0-7]$/i.test(p)) throw 0; return { t: 'lab', name: p }; }
      const o = parseOperand(p, k);
      if (k === 'r' && o.t !== 'reg') throw 0;
      if (k === 'v' && o.t === 'mem') throw 0;
      return o;
    });
    for (const l of pending) labels[l] = prog.length; pending = [];
    prog.push({ op, ops });
  }
  for (const l of pending) labels[l] = prog.length;
  for (const ins of prog) for (const o of ins.ops) if (o.t === 'lab') { if (!(o.name in labels)) throw 0; o.target = labels[o.name]; }
  return prog;
}
function runProgram(source, input, maxSteps) {
  let prog;
  try { prog = assemble(source); } catch (e) { return { status: 'SYNTAX', output: [], steps: 0, registers: [0, 0, 0, 0, 0, 0, 0, 0] }; }
  const st = { R: [0, 0, 0, 0, 0, 0, 0, 0], mem: new Array(1024).fill(0), stack: [], Z: false, N: false, C: false, V: false, out: [], inp: 0 };
  const val = (o) => (o.t === 'reg' ? st.R[o.r] : o.v);
  const setZN = (x) => { st.Z = x === 0; st.N = x < 0; };
  let ip = 0, steps = 0; const n = prog.length;
  for (;;) {
    if (ip === n) return fin('HALT');
    if (steps === maxSteps) return fin('LIMIT');
    const { op, ops } = prog[ip];
    let next = ip + 1;
    const a = ops.length && ops[0].t === 'reg' ? st.R[ops[0].r] : 0;
    const b = ops.length > 1 && ops[1].t !== 'lab' && ops[1].t !== 'mem' ? val(ops[1]) : 0;
    switch (op) {
      case 'MOV': st.R[ops[0].r] = b; break;
      case 'LOAD': { const ad = (ops[1].r >= 0 ? st.R[ops[1].r] : 0) + ops[1].off; if (ad < 0 || ad > 1023) return fin('BAD_ADDRESS'); st.R[ops[0].r] = st.mem[ad]; break; }
      case 'STORE': { const ad = (ops[0].r >= 0 ? st.R[ops[0].r] : 0) + ops[0].off; if (ad < 0 || ad > 1023) return fin('BAD_ADDRESS'); st.mem[ad] = val(ops[1]); break; }
      case 'ADD': { const s = a + b; const r = toInt32(s); setZN(r); st.C = (a >>> 0) + (b >>> 0) >= 4294967296; st.V = s !== r; st.R[ops[0].r] = r; break; }
      case 'SUB': case 'CMP': { const d = a - b; const r = toInt32(d); setZN(r); st.C = (a >>> 0) < (b >>> 0); st.V = d !== r; if (op === 'SUB') st.R[ops[0].r] = r; break; }
      case 'NEG': { const r = toInt32(-a); setZN(r); st.C = a !== 0; st.V = a === -2147483648; st.R[ops[0].r] = r; break; }
      case 'MUL': { const r = (a * b) | 0; setZN(r); st.C = st.V = a * b !== r; st.R[ops[0].r] = r; break; }
      case 'DIV': { if (b === 0) return fin('DIV_ZERO'); const q = Math.trunc(a / b); const r = toInt32(q); setZN(r); st.C = false; st.V = q !== r; st.R[ops[0].r] = r; break; }
      case 'MOD': { if (b === 0) return fin('DIV_ZERO'); const r = (a % b) | 0; setZN(r); st.C = false; st.V = false; st.R[ops[0].r] = r; break; }
      case 'AND': case 'OR': case 'XOR': { const r = op === 'AND' ? a & b : op === 'OR' ? a | b : a ^ b; setZN(r); st.C = st.V = false; st.R[ops[0].r] = r; break; }
      case 'NOT': { const r = ~a; setZN(r); st.C = st.V = false; st.R[ops[0].r] = r; break; }
      case 'SHL': case 'SHR': case 'SAR': {
        const k = b & 31; let r = a;
        if (k === 0) {}
        else if (op === 'SHL') { st.C = ((a >>> (32 - k)) & 1) === 1; r = a << k; }
        else { st.C = ((a >>> (k - 1)) & 1) === 1; r = op === 'SHR' ? (a >>> k) | 0 : a >> k; }
        setZN(r); st.V = false; st.R[ops[0].r] = r; break;
      }
      case 'JMP': next = ops[0].target; break;
      case 'JE': if (st.Z) next = ops[0].target; break;
      case 'JNE': if (!st.Z) next = ops[0].target; break;
      case 'JL': if (st.N) next = ops[0].target; break;
      case 'JLE': if (st.Z || st.N) next = ops[0].target; break;
      case 'JG': if (!st.Z && !st.N) next = ops[0].target; break;
      case 'JGE': if (!st.N) next = ops[0].target; break;
      case 'JB': if (st.C) next = ops[0].target; break;
      case 'JBE': if (st.C || st.Z) next = ops[0].target; break;
      case 'JA': if (!st.C && !st.Z) next = ops[0].target; break;
      case 'JAE': if (!st.C) next = ops[0].target; break;
      case 'LOOP': { const r = toInt32(a - 1); st.R[ops[0].r] = r; if (r !== 0) next = ops[1].target; break; }
      case 'CALL': if (st.stack.length >= 256) return fin('STACK_OVERFLOW'); st.stack.push(ip + 1); next = ops[0].target; break;
      case 'RET': { if (!st.stack.length) return fin('STACK_UNDERFLOW'); const x = st.stack[st.stack.length - 1]; if (x < 0 || x > n) return fin('BAD_JUMP'); st.stack.pop(); next = x; break; }
      case 'PUSH': if (st.stack.length >= 256) return fin('STACK_OVERFLOW'); st.stack.push(val(ops[0])); break;
      case 'POP': if (!st.stack.length) return fin('STACK_UNDERFLOW'); st.R[ops[0].r] = st.stack.pop(); break;
      case 'IN': if (st.inp >= input.length) return fin('NO_INPUT'); st.R[ops[0].r] = input[st.inp++]; break;
      case 'OUT': st.out.push(val(ops[0])); break;
      case 'HALT': steps++; return fin('HALT');
    }
    steps++; ip = next;
  }
  function fin(status) { return { status, output: st.out, steps, registers: st.R.slice() }; }
}
