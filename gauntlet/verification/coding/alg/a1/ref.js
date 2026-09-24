function coverageProfile(intervals) {
  const delta = new Map();
  for (const [s, e, w] of intervals) {
    delta.set(s, (delta.get(s) || 0) + w);
    delta.set(e, (delta.get(e) || 0) - w);
  }
  const xs = [...delta.keys()].sort((a, b) => a - b);
  const out = [];
  let cur = 0;
  for (let i = 0; i < xs.length - 1; i++) {
    cur += delta.get(xs[i]);
    if (cur > 0) {
      const last = out[out.length - 1];
      if (last && last[1] === xs[i] && last[2] === cur) last[1] = xs[i + 1];
      else out.push([xs[i], xs[i + 1], cur]);
    }
  }
  return out;
}
