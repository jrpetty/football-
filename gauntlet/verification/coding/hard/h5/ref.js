function evaluateSheet(cells) {
  const names = Object.keys(cells);
  const ast = {}, deps = {}, missing = {};
  const colOf = (n) => n.charCodeAt(0) - 65, rowOf = (n) => Number(n.slice(1));
  function parse(src) { // returns AST; tokens
    const toks = src.match(/[A-Z][0-9]+|SUM|\d+(?:\.\d+)?|[-+*/():]/g);
    let i = 0;
    const peek = () => toks[i];
    function expr() { let n = term(); while (peek() === '+' || peek() === '-') { const op = toks[i++]; n = { t: 'bin', op, a: n, b: term() }; } return n; }
    function term() { let n = unary(); while (peek() === '*' || peek() === '/') { const op = toks[i++]; n = { t: 'bin', op, a: n, b: unary() }; } return n; }
    function unary() { if (peek() === '-') { i++; return { t: 'neg', a: unary() }; } return primary(); }
    function primary() {
      const tk = toks[i++];
      if (tk === '(') { const n = expr(); i++; return n; }
      if (tk === 'SUM') { i++; const a = toks[i++]; i++; const b = toks[i++]; i++; return { t: 'sum', a, b }; }
      if (/^[A-Z]/.test(tk)) return { t: 'ref', c: tk };
      return { t: 'num', v: Number(tk) };
    }
    return expr();
  }
  for (const n of names) {
    const raw = cells[n];
    deps[n] = new Set(); missing[n] = false;
    if (raw.startsWith('=')) {
      const a = parse(raw.slice(1));
      ast[n] = a;
      const st = [a];
      while (st.length) {
        const x = st.pop();
        if (x.t === 'bin') { st.push(x.a, x.b); } else if (x.t === 'neg') st.push(x.a);
        else if (x.t === 'ref') { if (x.c in cells) deps[n].add(x.c); else missing[n] = true; }
        else if (x.t === 'sum') {
          const c1 = Math.min(colOf(x.a), colOf(x.b)), c2 = Math.max(colOf(x.a), colOf(x.b));
          const r1 = Math.min(rowOf(x.a), rowOf(x.b)), r2 = Math.max(rowOf(x.a), rowOf(x.b));
          x.members = [];
          for (const m of names) { const c = colOf(m), r = rowOf(m); if (c >= c1 && c <= c2 && r >= r1 && r <= r2) { x.members.push(m); deps[n].add(m); } }
          x.members.sort((p, q) => colOf(p) - colOf(q) || rowOf(p) - rowOf(q)); // defined summation order
        }
      }
    } else ast[n] = { t: 'num', v: Number(raw) };
  }
  // Tarjan SCC (iterative)
  const index = {}, low = {}, onStack = {}, stack = [], inCycle = {};
  let idx = 0;
  for (const s of names) {
    if (s in index) continue;
    const work = [[s, [...deps[s]], 0]];
    index[s] = low[s] = idx++; stack.push(s); onStack[s] = true;
    while (work.length) {
      const fr = work[work.length - 1];
      const [v, ds] = fr;
      if (fr[2] < ds.length) {
        const w = ds[fr[2]++];
        if (!(w in index)) { index[w] = low[w] = idx++; stack.push(w); onStack[w] = true; work.push([w, [...deps[w]], 0]); }
        else if (onStack[w]) low[v] = Math.min(low[v], index[w]);
      } else {
        work.pop();
        if (work.length) { const u = work[work.length - 1][0]; low[u] = Math.min(low[u], low[v]); }
        if (low[v] === index[v]) {
          const comp = []; let w;
          do { w = stack.pop(); onStack[w] = false; comp.push(w); } while (w !== v);
          if (comp.length > 1 || deps[v].has(v)) for (const c of comp) inCycle[c] = true;
        }
      }
    }
  }
  // topological evaluation (Kahn) of acyclic part
  const val = {};
  const PRI = { '#CYCLE!': 3, '#REF!': 2, '#DIV/0!': 1 };
  const isErr = (x) => typeof x === 'string';
  const worse = (a, b) => (!a ? b : !b ? a : PRI[a] >= PRI[b] ? a : b);
  for (const n of names) if (inCycle[n]) val[n] = '#CYCLE!';
  const indeg = {}, users = {};
  for (const n of names) users[n] = [];
  for (const n of names) { if (inCycle[n]) continue; indeg[n] = 0; for (const d of deps[n]) { if (!inCycle[d]) { indeg[n]++; users[d].push(n); } } }
  const queue = names.filter((n) => !inCycle[n] && indeg[n] === 0);
  const evalAst = (a) => { // iterative post-order evaluation
    const st = [[a, 0]], vals = [];
    let div0 = false;
    while (st.length) {
      const fr = st[st.length - 1]; const x = fr[0];
      if (x.t === 'num') { vals.push(x.v); st.pop(); continue; }
      if (x.t === 'ref') { vals.push(val[x.c]); st.pop(); continue; }
      if (x.t === 'sum') { let s = 0; for (const m of x.members) s += val[m]; vals.push(s); st.pop(); continue; }
      if (x.t === 'neg') { if (fr[1] === 0) { fr[1] = 1; st.push([x.a, 0]); } else { vals.push(-vals.pop()); st.pop(); } continue; }
      if (fr[1] === 0) { fr[1] = 1; st.push([x.a, 0]); } else if (fr[1] === 1) { fr[1] = 2; st.push([x.b, 0]); } else {
        const b = vals.pop(), aa = vals.pop(); st.pop();
        if (x.op === '+') vals.push(aa + b); else if (x.op === '-') vals.push(aa - b); else if (x.op === '*') vals.push(aa * b);
        else { if (b === 0) { div0 = true; vals.push(0); } else vals.push(aa / b); }
      }
    }
    return div0 ? '#DIV/0!' : vals[0];
  };
  while (queue.length) {
    const n = queue.shift();
    let err = missing[n] ? '#REF!' : null;
    for (const d of deps[n]) if (isErr(val[d])) err = worse(err, val[d]);
    val[n] = err || evalAst(ast[n]);
    if (typeof val[n] === 'number' && Object.is(val[n], -0)) val[n] = 0;
    for (const u of users[n]) if (--indeg[u] === 0) queue.push(u);
  }
  const out = {};
  for (const n of names) out[n] = val[n];
  return out;
}
