function longestWindow(nums, maxDistinct, maxSum) {
  const cnt = new Map();
  let best = 0, left = 0, sum = 0;
  for (let right = 0; right < nums.length; right++) {
    const x = nums[right];
    cnt.set(x, (cnt.get(x) || 0) + 1);
    sum += x;
    while (left <= right && (cnt.size > maxDistinct || sum > maxSum)) {
      const y = nums[left++];
      const c = cnt.get(y) - 1;
      if (c === 0) cnt.delete(y); else cnt.set(y, c);
      sum -= y;
    }
    best = Math.max(best, right - left + 1);
  }
  return best;
}
