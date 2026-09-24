function evaluate(expr) {
  // tokenize
  const toks = [];
  let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === ' ' || c === '\t') { i++; continue; }
    if (c >= '0' && c <= '9') {
      let j = i; while (j < expr.length && expr[j] >= '0' && expr[j] <= '9') j++;
      if (expr[j] === '.') { const k = j + 1; let m = k; while (m < expr.length && expr[m] >= '0' && expr[m] <= '9') m++; if (m === k) return null; j = m; }
      toks.push({ t: 'num', v: Number(expr.slice(i, j)) }); i = j; continue;
    }
    if ('+-*/%^()'.includes(c)) { toks.push({ t: c }); i++; continue; }
    return null;
  }
  if (!toks.length) return null;
  const PREC = { '+': 1, '-': 1, '*': 2, '/': 2, '%': 2, 'u': 3, '^': 4 };
  const out = []; // values
  const ops = [];
  const apply = (op) => {
    if (op === 'u-' || op === 'u+') { if (!out.length) return false; const a = out.pop(); out.push(op === 'u-' ? -a : +a); return true; }
    if (out.length < 2) return false;
    const b = out.pop(), a = out.pop();
    let r;
    switch (op) {
      case '+': r = a + b; break; case '-': r = a - b; break; case '*': r = a * b; break;
      case '/': if (b === 0) return 'err'; r = a / b; break;
      case '%': if (b === 0) return 'err'; r = a % b; break;
      case '^': r = a ** b; break;
    }
    if (!Number.isFinite(r)) return 'err';
    out.push(r); return true;
  };
  const prec = (op) => (op === 'u-' || op === 'u+' ? 3 : PREC[op]);
  let expectOperand = true;
  for (const tk of toks) {
    if (tk.t === 'num') { if (!expectOperand) return null; out.push(tk.v); expectOperand = false; }
    else if (tk.t === '(') { if (!expectOperand) return null; ops.push('('); }
    else if (tk.t === ')') {
      if (expectOperand) return null;
      for (;;) { if (!ops.length) return null; const op = ops.pop(); if (op === '(') break; const r = apply(op); if (r !== true) return null; }
      expectOperand = false;
    } else if (expectOperand) {
      if (tk.t === '-' || tk.t === '+') ops.push('u' + tk.t); else return null;
    } else {
      const p = PREC[tk.t], rightAssoc = tk.t === '^';
      while (ops.length) {
        const top = ops[ops.length - 1];
        if (top === '(') break;
        const tp = prec(top);
        if (tp > p || (tp === p && !rightAssoc)) { const r = apply(ops.pop()); if (r !== true) return null; } else break;
      }
      ops.push(tk.t); expectOperand = true;
    }
  }
  if (expectOperand) return null;
  while (ops.length) { const op = ops.pop(); if (op === '(') return null; const r = apply(op); if (r !== true) return null; }
  return out.length === 1 ? (Object.is(out[0], -0) ? 0 : out[0]) : null;
}
