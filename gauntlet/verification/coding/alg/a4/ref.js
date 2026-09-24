function countSplits(digits, limit) {
  const MOD = 1000000007;
  const n = digits.length;
  const L = String(limit).length;
  const dp = new Array(n + 1).fill(0);
  dp[0] = 1;
  for (let i = 0; i < n; i++) {
    if (dp[i] === 0 || digits[i] === '0') continue;
    let v = 0;
    for (let j = i; j < n && j - i < L; j++) {
      v = v * 10 + (digits.charCodeAt(j) - 48);
      if (v > limit) break;
      dp[j + 1] = (dp[j + 1] + dp[i]) % MOD;
    }
  }
  return dp[n];
}
