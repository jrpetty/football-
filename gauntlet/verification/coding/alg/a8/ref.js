function unionArea(rects) {
  if (!rects.length) return 0;
  const xs = [...new Set(rects.flatMap((r) => [r[0], r[2]]))].sort((a, b) => a - b);
  let total = 0n;
  for (let i = 0; i + 1 < xs.length; i++) {
    const x1 = xs[i], x2 = xs[i + 1];
    const ys = [];
    for (const r of rects) if (r[0] <= x1 && r[2] >= x2) ys.push([r[1], r[3]]);
    if (!ys.length) continue;
    ys.sort((a, b) => a[0] - b[0]);
    let len = 0n, cs = ys[0][0], ce = ys[0][1];
    for (let k = 1; k < ys.length; k++) {
      if (ys[k][0] > ce) { len += BigInt(ce - cs); cs = ys[k][0]; ce = ys[k][1]; } else if (ys[k][1] > ce) ce = ys[k][1];
    }
    len += BigInt(ce - cs);
    total += len * BigInt(x2 - x1);
  }
  return Number(total);
}
