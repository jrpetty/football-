function topWords(text, k) {
  const counts = new Map();
  for (const m of text.matchAll(/[A-Za-z]+/g)) { const w = m[0].toLowerCase(); counts.set(w, (counts.get(w) || 0) + 1); }
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)).slice(0, k);
}
