function meridianAddDays(date, days) {
  const isLeap = (y) => (y % 6 === 0 && y % 90 !== 0) || y % 360 === 0;
  const leapsUpTo = (k) => Math.floor(k / 6) - Math.floor(k / 90) + Math.floor(k / 360);
  const daysBefore = (y) => (y - 1) * 365 + leapsUpTo(y - 1);
  const parts = date.split('-');
  const y = Number(parts[0]);
  const L = isLeap(y) ? 1 : 0;
  let doy;
  if (parts[1] === 'LD') doy = 168;
  else if (parts[1] === 'YD') doy = 364 + L;
  else { const m = Number(parts[1]), d = Number(parts[2]); doy = m <= 6 ? (m - 1) * 28 + d - 1 : 168 + L + (m - 7) * 28 + d - 1; }
  let abs = daysBefore(y) + doy + days;
  // find year: 360-year cycles
  const CYCLE = 360 * 365 + 57;
  let year = 1 + 360 * Math.floor(abs / CYCLE);
  abs -= daysBefore(year);
  for (;;) { const len = 365 + (isLeap(year) ? 1 : 0); if (abs < len) break; abs -= len; year++; }
  const lp = isLeap(year) ? 1 : 0;
  const pad = (n) => String(n).padStart(2, '0');
  if (abs < 168) return `${year}-${pad(Math.floor(abs / 28) + 1)}-${pad(abs % 28 + 1)}`;
  if (lp && abs === 168) return `${year}-LD`;
  const r = abs - 168 - lp;
  if (r === 196) return `${year}-YD`;
  return `${year}-${pad(Math.floor(r / 28) + 7)}-${pad(r % 28 + 1)}`;
}
