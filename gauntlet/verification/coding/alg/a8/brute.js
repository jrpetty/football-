function unionArea(rects) {
  // small coordinates only: count unit cells
  const seen = new Set();
  for (const [x1, y1, x2, y2] of rects) for (let x = x1; x < x2; x++) for (let y = y1; y < y2; y++) seen.add(x + ',' + y);
  return seen.size;
}
