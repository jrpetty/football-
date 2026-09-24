function charAt(pattern, index) {
  // Parse iteratively into nodes: {kind:'lit', s} | {kind:'grp', n, kids}
  const root = { kind: 'grp', n: 1n, kids: [] };
  const stack = [root];
  let i = 0;
  while (i < pattern.length) {
    const ch = pattern[i];
    if (ch >= 'a' && ch <= 'z') {
      const top = stack[stack.length - 1].kids; let j = i;
      while (j < pattern.length && pattern[j] >= 'a' && pattern[j] <= 'z') j++;
      top.push({ kind: 'lit', s: pattern.slice(i, j) }); i = j;
    } else if (ch >= '0' && ch <= '9') {
      let j = i; while (pattern[j] >= '0' && pattern[j] <= '9') j++;
      const g = { kind: 'grp', n: BigInt(pattern.slice(i, j)), kids: [] };
      stack[stack.length - 1].kids.push(g); stack.push(g); i = j + 1; // skip '('
    } else { // ')'
      stack.pop(); i++;
    }
  }
  // lengths (post-order, iterative)
  const order = []; const st = [root];
  while (st.length) { const x = st.pop(); order.push(x); if (x.kind === 'grp') for (const k of x.kids) st.push(k); }
  for (let k = order.length - 1; k >= 0; k--) {
    const x = order[k];
    if (x.kind === 'lit') x.len = BigInt(x.s.length);
    else { let s = 0n; for (const c of x.kids) s += c.len; x.unit = s; x.len = s * x.n; }
  }
  let idx = BigInt(index);
  if (idx >= root.len) return '';
  let node = root;
  for (;;) {
    if (node.kind === 'lit') return node.s[Number(idx)];
    idx %= node.unit;
    for (const c of node.kids) { if (idx < c.len) { node = c; break; } idx -= c.len; }
  }
}
