export function randTree(R, n, shape) {
  const par = new Array(n).fill(-1); const perm = R.shuffle([...Array(n).keys()]);
  for (let k = 1; k < n; k++) {
    let pk;
    if (shape === 'path') pk = k - 1;
    else if (shape === 'star') pk = R.chance(0.7) ? 0 : R.int(0, k - 1);
    else if (shape === 'cater') pk = R.chance(0.5) ? k - 1 : Math.max(0, k - R.int(1, 3));
    else if (shape === 'deep') pk = R.int(Math.max(0, k - 3), k - 1);
    else pk = R.int(0, k - 1);
    par[perm[k]] = perm[pk];
  }
  return par;
}
