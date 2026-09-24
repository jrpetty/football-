function rankLeaderboard(entries) {
  const idx = entries.map((e, i) => i);
  idx.sort((a, b) => entries[b][1] - entries[a][1] || entries[a][2] - entries[b][2] || a - b);
  const out = [];
  for (let k = 0; k < idx.length; k++) {
    const e = entries[idx[k]];
    let rank;
    if (k > 0) { const p = entries[idx[k - 1]]; rank = (p[1] === e[1] && p[2] === e[2]) ? out[k - 1][0] : k + 1; } else rank = 1;
    out.push([rank, e[0]]);
  }
  return out;
}
