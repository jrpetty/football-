function charAt(pattern, index) {
  let i = 0;
  function parse() {
    let out = '';
    while (i < pattern.length && pattern[i] !== ')') {
      const ch = pattern[i];
      if (ch >= 'a' && ch <= 'z') { out += ch; i++; }
      else { let j = i; while (pattern[j] >= '0' && pattern[j] <= '9') j++; const n = Number(pattern.slice(i, j)); i = j + 1; const inner = parse(); i++; out += inner.repeat(n); }
    }
    return out;
  }
  const s = parse();
  return index < s.length ? s[index] : '';
}
