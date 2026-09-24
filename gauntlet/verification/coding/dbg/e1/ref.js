function addDecimalStrings(a, b) {
  function parse(s) {
    let sign = 1, i = 0;
    if (s[0] === '+' || s[0] === '-') { if (s[0] === '-') sign = -1; i = 1; }
    const body = s.slice(i);
    const dot = body.indexOf('.');
    const ip = dot < 0 ? body : body.slice(0, dot);
    const fp = dot < 0 ? '' : body.slice(dot + 1);
    return { sign, ip, fp };
  }
  const x = parse(a), y = parse(b);
  const F = Math.max(x.fp.length, y.fp.length);
  const I = Math.max(x.ip.length, y.ip.length);
  const digits = (p) => (p.ip.padStart(I, '0') + p.fp.padEnd(F, '0')).split('').map(Number);
  const dx = digits(x), dy = digits(y);
  const cmp = (p, q) => { for (let k = 0; k < p.length; k++) if (p[k] !== q[k]) return p[k] < q[k] ? -1 : 1; return 0; };
  let res, sign;
  if (x.sign === y.sign) {
    res = new Array(dx.length + 1).fill(0); let carry = 0;
    for (let k = dx.length - 1; k >= 0; k--) { const t = dx[k] + dy[k] + carry; res[k + 1] = t % 10; carry = Math.floor(t / 10); }
    res[0] = carry; sign = x.sign;
  } else {
    const c = cmp(dx, dy);
    if (c === 0) return '0';
    const [big, small] = c > 0 ? [dx, dy] : [dy, dx];
    sign = c > 0 ? x.sign : y.sign;
    res = new Array(big.length).fill(0); let borrow = 0;
    for (let k = big.length - 1; k >= 0; k--) { let t = big[k] - small[k] - borrow; if (t < 0) { t += 10; borrow = 1; } else borrow = 0; res[k] = t; }
  }
  const s = res.join('');
  let ip = s.slice(0, s.length - F).replace(/^0+/, '') || '0';
  let fp = s.slice(s.length - F).replace(/0+$/, '');
  const out = fp ? ip + '.' + fp : ip;
  if (out === '0') return '0';
  return (sign < 0 ? '-' : '') + out;
}
