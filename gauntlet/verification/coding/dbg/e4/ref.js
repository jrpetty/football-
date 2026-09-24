function parseCsvRecord(record) {
  const out = [];
  let i = 0;
  const n = record.length;
  for (;;) {
    if (record[i] === '"') {
      let f = ''; i++;
      for (;;) {
        if (i >= n) return null;                       // unterminated
        const c = record[i];
        if (c === '"') {
          if (record[i + 1] === '"') { f += '"'; i += 2; continue; }
          i++; break;
        }
        f += c; i++;
      }
      out.push(f);
      if (i === n) return out;
      if (record[i] !== ',') return null;              // junk after closing quote
      i++;
    } else {
      let j = i;
      while (j < n && record[j] !== ',') { if (record[j] === '"') return null; j++; }
      out.push(record.slice(i, j));
      if (j === n) return out;
      i = j + 1;
    }
  }
}
