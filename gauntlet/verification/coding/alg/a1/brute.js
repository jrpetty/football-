function coverageProfile(intervals) {
  if (!intervals.length) return [];
  let lo = Infinity, hi = -Infinity;
  for (const [s, e] of intervals) { lo = Math.min(lo, s); hi = Math.max(hi, e); }
  const cov = [];
  for (let x = lo; x < hi; x++) { let t = 0; for (const [s, e, w] of intervals) if (s <= x && x < e) t += w; cov.push(t); }
  const out = [];
  for (let i = 0; i < cov.length; i++) {
    const x = lo + i;
    if (cov[i] === 0) continue;
    const last = out[out.length - 1];
    if (last && last[1] === x && last[2] === cov[i]) last[1] = x + 1; else out.push([x, x + 1, cov[i]]);
  }
  return out;
}
