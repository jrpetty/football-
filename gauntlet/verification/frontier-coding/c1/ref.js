// Reference solution for c1 (register machine). Written in a natural style: pre-assembles once, then dispatches on numeric opcodes.
function runProgram(source, input, maxSteps) {
  const MNEMONICS = {
    MOV: ['r', 'v'], LOAD: ['r', 'm'], STORE: ['m', 'v'], ADD: ['r', 'v'], SUB: ['r', 'v'], MUL: ['r', 'v'], DIV: ['r', 'v'], MOD: ['r', 'v'],
    AND: ['r', 'v'], OR: ['r', 'v'], XOR: ['r', 'v'], SHL: ['r', 'v'], SHR: ['r', 'v'], SAR: ['r', 'v'], NEG: ['r'], NOT: ['r'], CMP: ['r', 'v'],
    JMP: ['L'], JE: ['L'], JNE: ['L'], JL: ['L'], JLE: ['L'], JG: ['L'], JGE: ['L'], JB: ['L'], JBE: ['L'], JA: ['L'], JAE: ['L'],
    LOOP: ['r', 'L'], CALL: ['L'], RET: [], PUSH: ['v'], POP: ['r'], IN: ['r'], OUT: ['v'], HALT: [],
  };
  const OPS = Object.keys(MNEMONICS);
  const syntax = () => ({ status: 'SYNTAX', output: [], steps: 0, registers: [0, 0, 0, 0, 0, 0, 0, 0] });
  const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
  const isReg = (t) => /^[rR][0-7]$/.test(t);
  const wrap = (x) => { // x is an exact integer (Number, |x| < 2^53) or BigInt
    if (typeof x === 'bigint') return Number(BigInt.asIntN(32, x));
    return Number(BigInt.asIntN(32, BigInt(x)));
  };
  function parseImm(t) {
    const m = /^([+-]?)(0[xX][0-9a-fA-F]+|[0-9]+)$/.exec(t);
    if (!m) return null;
    let v = BigInt(m[2].toLowerCase().startsWith('0x') ? m[2] : m[2]);
    if (m[1] === '-') v = -v;
    return Number(BigInt.asIntN(32, v));
  }
  const lines = source.split('\n');
  const instrs = []; // {op, a: [...]}
  const labels = new Map();
  const pendingLabels = [];
  for (const raw of lines) {
    let line = raw;
    const sc = line.indexOf(';');
    if (sc >= 0) line = line.slice(0, sc);
    // peel label definitions
    for (;;) {
      const m = /^[ \t]*([A-Za-z_][A-Za-z0-9_]*)[ \t]*:/.exec(line);
      if (!m) break;
      const name = m[1];
      if (isReg(name)) return syntax();
      if (labels.has(name) || pendingLabels.includes(name)) return syntax();
      pendingLabels.push(name);
      line = line.slice(m[0].length);
    }
    const rest = line.replace(/^[ \t]+|[ \t]+$/g, '');
    if (rest === '') continue;
    const mm = /^([A-Za-z]+)(?:[ \t]+(.*))?$/.exec(rest);
    if (!mm) return syntax();
    const op = mm[1].toUpperCase();
    if (!Object.prototype.hasOwnProperty.call(MNEMONICS, op)) return syntax();
    const kinds = MNEMONICS[op];
    const argText = mm[2] === undefined ? '' : mm[2];
    const parts = argText === '' ? [] : argText.split(',').map((s) => s.replace(/^[ \t]+|[ \t]+$/g, ''));
    if (parts.length !== kinds.length) return syntax();
    const ops = [];
    for (let i = 0; i < kinds.length; i++) {
      const t = parts[i];
      const k = kinds[i];
      if (k === 'r') {
        if (!isReg(t)) return syntax();
        ops.push({ kind: 'r', reg: Number(t[1]) });
      } else if (k === 'v') {
        if (isReg(t)) ops.push({ kind: 'r', reg: Number(t[1]) });
        else {
          const v = parseImm(t);
          if (v === null) return syntax();
          ops.push({ kind: 'i', val: v });
        }
      } else if (k === 'm') {
        const b = /^\[[ \t]*(.*?)[ \t]*\]$/.exec(t);
        if (!b) return syntax();
        const inner = b[1];
        let m2;
        if (isReg(inner)) ops.push({ kind: 'm', reg: Number(inner[1]), off: 0 });
        else if ((m2 = /^([rR][0-7])[ \t]*([+-])[ \t]*([0-9]+)$/.exec(inner))) ops.push({ kind: 'm', reg: Number(m2[1][1]), off: (m2[2] === '-' ? -1 : 1) * Number(m2[3]) });
        else {
          const v = parseImm(inner);
          if (v === null) return syntax();
          ops.push({ kind: 'm', reg: -1, off: v });
        }
      } else { // label
        if (!NAME.test(t) || isReg(t)) return syntax();
        ops.push({ kind: 'L', name: t });
      }
    }
    for (const name of pendingLabels) labels.set(name, instrs.length);
    pendingLabels.length = 0;
    instrs.push({ op: OPS.indexOf(op), ops });
  }
  for (const name of pendingLabels) labels.set(name, instrs.length);
  for (const ins of instrs) for (const o of ins.ops) if (o.kind === 'L') {
    if (!labels.has(o.name)) return syntax();
    o.target = labels.get(o.name);
  }
  // Flatten into typed arrays for speed: opcode, dst reg, src kind(0 reg,1 imm), src value, mem reg, mem off, target
  const n = instrs.length;
  const OPC = new Int32Array(n), A = new Int32Array(n), BK = new Int32Array(n), BV = new Float64Array(n), MR = new Int32Array(n), MO = new Float64Array(n), T = new Int32Array(n);
  for (let i = 0; i < n; i++) {
    const { op, ops } = instrs[i];
    OPC[i] = op;
    for (const o of ops) {
      if (o.kind === 'r' && o === ops[0] && MNEMONICS[OPS[op]][0] === 'r') A[i] = o.reg;
      else if (o.kind === 'r') { BK[i] = 0; BV[i] = o.reg; }
      else if (o.kind === 'i') { BK[i] = 1; BV[i] = o.val; }
      else if (o.kind === 'm') { MR[i] = o.reg; MO[i] = o.off; }
      else if (o.kind === 'L') T[i] = o.target;
    }
  }
  const R = new Int32Array(8);
  const mem = new Int32Array(1024);
  const stack = new Int32Array(256);
  let sp = 0, ip = 0, steps = 0, inp = 0;
  let Z = false, N = false, C = false, V = false;
  const out = [];
  const TWO32 = 4294967296;
  const INT_MIN = -2147483648;
  let status = null;
  const [oMOV, oLOAD, oSTORE, oADD, oSUB, oMUL, oDIV, oMOD, oAND, oOR, oXOR, oSHL, oSHR, oSAR, oNEG, oNOT, oCMP, oJMP, oJE, oJNE, oJL, oJLE, oJG, oJGE, oJB, oJBE, oJA, oJAE, oLOOP, oCALL, oRET, oPUSH, oPOP, oIN, oOUT, oHALT] = OPS.map((_, i) => i);
  while (true) {
    if (ip === n) { status = 'HALT'; break; }
    if (steps === maxSteps) { status = 'LIMIT'; break; }
    const op = OPC[ip];
    const a = A[ip];
    const b = BK[ip] === 0 ? R[BV[ip]] : BV[ip];
    let next = ip + 1;
    switch (op) {
      case oMOV: R[a] = b; break;
      case oLOAD: case oSTORE: {
        const addr = (MR[ip] >= 0 ? R[MR[ip]] : 0) + MO[ip];
        if (addr < 0 || addr > 1023) { status = 'BAD_ADDRESS'; break; }
        if (op === oLOAD) R[a] = mem[addr]; else mem[addr] = b;
        break;
      }
      case oADD: case oSUB: case oCMP: {
        const x = R[a];
        const exact = op === oADD ? x + b : x - b;
        const res = exact | 0;
        Z = res === 0; N = res < 0;
        V = exact !== res;
        const ux = x >>> 0, ub = b >>> 0;
        C = op === oADD ? ux + ub >= TWO32 : ux < ub;
        if (op !== oCMP) R[a] = res;
        break;
      }
      case oNEG: {
        const x = R[a];
        const res = (-x) | 0;
        Z = res === 0; N = res < 0; C = x !== 0; V = x === INT_MIN;
        R[a] = res;
        break;
      }
      case oMUL: {
        const x = R[a];
        const res = Math.imul(x, b);
        const exact = x * b; // if the true product is in range this double is exact; otherwise it is out of range too
        Z = res === 0; N = res < 0;
        C = V = exact !== res;
        R[a] = res;
        break;
      }
      case oDIV: case oMOD: {
        if (b === 0) { status = 'DIV_ZERO'; break; }
        const x = R[a];
        if (op === oDIV) {
          const q = Math.trunc(x / b); // exact enough: |x|,|b| < 2^31, trunc of correctly rounded quotient is exact
          const res = q | 0;
          Z = res === 0; N = res < 0; C = false; V = q !== res;
          R[a] = res;
        } else {
          const res = x % b;
          Z = res === 0; N = res < 0; C = false; V = false;
          R[a] = res | 0;
        }
        break;
      }
      case oAND: case oOR: case oXOR: {
        const x = R[a];
        const res = op === oAND ? x & b : op === oOR ? x | b : x ^ b;
        Z = res === 0; N = res < 0; C = false; V = false;
        R[a] = res;
        break;
      }
      case oNOT: {
        const res = ~R[a];
        Z = res === 0; N = res < 0; C = false; V = false;
        R[a] = res;
        break;
      }
      case oSHL: case oSHR: case oSAR: {
        const x = R[a];
        const s = b & 31;
        let res;
        if (s === 0) { res = x; C = false; }
        else if (op === oSHL) { res = x << s; C = ((x >>> (32 - s)) & 1) === 1; }
        else if (op === oSHR) { res = (x >>> s) | 0; C = ((x >>> (s - 1)) & 1) === 1; }
        else { res = x >> s; C = ((x >>> (s - 1)) & 1) === 1; }
        Z = res === 0; N = res < 0; V = false;
        R[a] = res;
        break;
      }
      case oJMP: next = T[ip]; break;
      case oJE: if (Z) next = T[ip]; break;
      case oJNE: if (!Z) next = T[ip]; break;
      case oJL: if (N !== V) next = T[ip]; break;
      case oJLE: if (Z || N !== V) next = T[ip]; break;
      case oJG: if (!Z && N === V) next = T[ip]; break;
      case oJGE: if (N === V) next = T[ip]; break;
      case oJB: if (C) next = T[ip]; break;
      case oJBE: if (C || Z) next = T[ip]; break;
      case oJA: if (!C && !Z) next = T[ip]; break;
      case oJAE: if (!C) next = T[ip]; break;
      case oLOOP: { const r = (R[a] - 1) | 0; R[a] = r; if (r !== 0) next = T[ip]; break; }
      case oCALL: if (sp === 256) { status = 'STACK_OVERFLOW'; break; } stack[sp++] = ip + 1; next = T[ip]; break;
      case oRET: {
        if (sp === 0) { status = 'STACK_UNDERFLOW'; break; }
        const v = stack[sp - 1];
        if (v < 0 || v > n) { status = 'BAD_JUMP'; break; }
        sp--; next = v; break;
      }
      case oPUSH: if (sp === 256) { status = 'STACK_OVERFLOW'; break; } stack[sp++] = b; break;
      case oPOP: if (sp === 0) { status = 'STACK_UNDERFLOW'; break; } R[a] = stack[--sp]; break;
      case oIN: if (inp >= input.length) { status = 'NO_INPUT'; break; } R[a] = input[inp++] | 0; break;
      case oOUT: out.push(b); break;
      case oHALT: status = 'HALT'; break;
    }
    if (status !== null) { if (status === 'HALT') steps++; break; }
    steps++;
    ip = next;
  }
  return { status, output: out, steps, registers: Array.from(R) };
}
