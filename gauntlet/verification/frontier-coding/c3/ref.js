// Reference for c3: palindromic tree (eertree). firstEnd = end index where a node is created; lastEnd propagated along
// suffix links in reverse creation order (links always point to older nodes); occurrence counts propagated the same way. Iterative only.
function palindromeRepeats(parts) {
  let s = '';
  for (const [text, times] of parts) s += text.repeat(times);
  const n = s.length;
  const len = [-1, 0], link = [0, 0], first = [-1, -1], last = [-1, -1], occ = [0, 0];
  const next = [new Map(), new Map()];
  let cur = 1; // node 0: imaginary root (len -1), node 1: empty string (len 0)
  for (let i = 0; i < n; i++) {
    const c = s[i];
    let v = cur;
    while (true) { const L = len[v]; if (i - L - 1 >= 0 && s[i - L - 1] === c) break; v = link[v]; }
    if (next[v].has(c)) { cur = next[v].get(c); last[cur] = i; occ[cur]++; continue; }
    const node = len.length;
    len.push(len[v] + 2); first.push(i); last.push(i); occ.push(1); next.push(new Map());
    if (len[node] === 1) link.push(1);
    else {
      let w = link[v];
      while (true) { const L = len[w]; if (i - L - 1 >= 0 && s[i - L - 1] === c) break; w = link[w]; }
      link.push(next[w].get(c));
    }
    next[v].set(c, node);
    cur = node;
  }
  for (let v = len.length - 1; v >= 2; v--) { const u = link[v]; if (u >= 2) { if (last[v] > last[u]) last[u] = last[v]; occ[u] += occ[v]; } }
  let count = 0, longest = 0, total = 0;
  for (let v = 2; v < len.length; v++) if (last[v] - first[v] >= len[v]) { count++; total += occ[v]; if (len[v] > longest) longest = len[v]; }
  return [count, longest, total];
}
