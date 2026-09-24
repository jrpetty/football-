function normalizePath(path) {
  const abs = path.startsWith('/');
  const out = [];
  for (const seg of path.split('/')) {
    if (seg === '' || seg === '.') continue;
    if (seg === '..') {
      if (out.length && out[out.length - 1] !== '..') out.pop();
      else if (!abs) out.push('..');
      continue;
    }
    out.push(seg);
  }
  if (abs) return '/' + out.join('/');
  return out.length ? out.join('/') : '.';
}
