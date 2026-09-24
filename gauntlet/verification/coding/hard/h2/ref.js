function regexMatch(pattern, text) {
  // ---- parse to AST (recursive descent; patterns in tests are short) ----
  let p = 0;
  function parseAlt() {
    const branches = [parseConcat()];
    while (pattern[p] === '|') { p++; branches.push(parseConcat()); }
    return branches.length === 1 ? branches[0] : { t: 'alt', branches };
  }
  function parseConcat() {
    const items = [];
    while (p < pattern.length && pattern[p] !== '|' && pattern[p] !== ')') {
      let atom = parseAtom();
      const q = pattern[p];
      if (q === '*' || q === '+' || q === '?') { p++; atom = { t: q, a: atom }; }
      items.push(atom);
    }
    return { t: 'cat', items };
  }
  function parseAtom() {
    const c = pattern[p];
    if (c === '(') { p++; const inner = parseAlt(); p++; return inner; }
    if (c === '.') { p++; return { t: 'any' }; }
    if (c === '\\') { p += 2; return { t: 'ch', c: pattern[p - 1] }; }
    if (c === '[') {
      p++; let neg = false; if (pattern[p] === '^') { neg = true; p++; }
      const start = p; while (pattern[p] !== ']') p++;
      const body = pattern.slice(start, p); p++;
      const ranges = [];
      for (let i = 0; i < body.length; i++) {
        if (i + 2 < body.length && body[i + 1] === '-') { ranges.push([body[i], body[i + 2]]); i += 2; }
        else ranges.push([body[i], body[i]]);
      }
      return { t: 'cls', neg, ranges };
    }
    p++; return { t: 'ch', c };
  }
  const ast = parseAlt();
  // ---- compile to NFA: states have {m: matcher|null, out: [], eps: []} ----
  const S = [];
  const ns = () => { S.push({ m: null, next: -1, eps: [] }); return S.length - 1; };
  function build(n) { // returns [start, end]
    if (n.t === 'ch' || n.t === 'any' || n.t === 'cls') { const a = ns(), b = ns(); S[a].m = n; S[a].next = b; return [a, b]; }
    if (n.t === 'cat') { const a = ns(); let cur = a; for (const it of n.items) { const [s, e] = build(it); S[cur].eps.push(s); cur = e; } return [a, cur]; }
    if (n.t === 'alt') { const a = ns(), b = ns(); for (const br of n.branches) { const [s, e] = build(br); S[a].eps.push(s); S[e].eps.push(b); } return [a, b]; }
    const [s, e] = build(n.a); const a = ns(), b = ns();
    S[a].eps.push(s); S[e].eps.push(b);
    if (n.t === '*' || n.t === '?') S[a].eps.push(b);
    if (n.t === '*' || n.t === '+') S[e].eps.push(s);
    return [a, b];
  }
  const [start, accept] = build(ast);
  const matches = (m, ch) => {
    if (m.t === 'any') return true;
    if (m.t === 'ch') return m.c === ch;
    let inside = false; for (const [lo, hi] of m.ranges) if (ch >= lo && ch <= hi) { inside = true; break; }
    return inside !== m.neg;
  };
  const closure = (set) => { const st = [...set]; const seen = new Set(set); while (st.length) { const x = st.pop(); for (const y of S[x].eps) if (!seen.has(y)) { seen.add(y); st.push(y); } } return seen; };
  let cur = closure([start]);
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]; const nxt = [];
    for (const s of cur) if (S[s].m && matches(S[s].m, ch)) nxt.push(S[s].next);
    if (!nxt.length) return false;
    cur = closure(nxt);
  }
  return cur.has(accept);
}
