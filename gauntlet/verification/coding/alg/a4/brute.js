function countSplits(digits, limit) {
  function go(i) {
    if (i === digits.length) return 1;
    let t = 0;
    for (let j = i + 1; j <= digits.length; j++) {
      const piece = digits.slice(i, j);
      if (piece[0] === '0') break;
      if (Number(piece) > limit) break;
      t += go(j);
    }
    return t;
  }
  return go(0) % 1000000007;
}
