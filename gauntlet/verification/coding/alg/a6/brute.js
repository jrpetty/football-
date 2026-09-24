function longestWindow(nums, maxDistinct, maxSum) {
  let best = 0;
  for (let i = 0; i < nums.length; i++) {
    const s = new Set(); let sum = 0;
    for (let j = i; j < nums.length; j++) {
      s.add(nums[j]); sum += nums[j];
      if (s.size <= maxDistinct && sum <= maxSum) best = Math.max(best, j - i + 1);
    }
  }
  return best;
}
