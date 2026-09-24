// Plausible naive: expand around every centre and track first/last start of every palindrome string.
function palindromeRepeats(parts) {
  let s = ''; for (const [t, k] of parts) s += t.repeat(k);
  const n = s.length; const first = new Map(), last = new Map(), cnt = new Map();
  for (let c = 0; c < 2 * n - 1; c++) {
    let l = Math.floor(c / 2), r = l + (c % 2);
    while (l >= 0 && r < n && s[l] === s[r]) {
      const t = s.slice(l, r + 1);
      if (!first.has(t) || l < first.get(t)) first.set(t, l);
      if (!last.has(t) || l > last.get(t)) last.set(t, l);
      cnt.set(t, (cnt.get(t) || 0) + 1);
      l--; r++;
    }
  }
  let count = 0, longest = 0, total = 0;
  for (const [t, f] of first) if (last.get(t) - f >= t.length) { count++; total += cnt.get(t); longest = Math.max(longest, t.length); }
  return [count, longest, total];
}
