// Magazines. The rounds in a gun live on the weapon item, so a loaded gun
// stays loaded between runs and in the save; a gun with no item (a stand-in)
// keeps them on whoever holds it. Rounds come out of the camp's ammunition
// when a magazine is loaded, and a shot spends one from the magazine.
// A crossbow's bolts are recovered: it has no reserve to run dry.

// { n: rounds in the gun, cap: what the magazine holds }
export function magOf(h) {
  const st = h.st
  if (!st?.gun || !st.magCap) return { n: 0, cap: 0 }
  const it = st.weaponItem
  const cur = it ? it.mag : h.mag
  // a gun never loaded before (an old save, a fresh find) starts full
  return { n: cur == null ? st.magCap : Math.max(0, Math.min(cur, st.magCap + 1)), cap: st.magCap }
}
export function setMag(h, n) {
  const it = h.st.weaponItem
  if (it) it.mag = n
  else h.mag = n
}
export function reserveOf(h, W) {
  const t = h.st.ammoType
  return t ? Math.floor(W.ammoLeft(t) || 0) : Infinity
}
// Load up to `want` rounds from the reserve; returns how many went in.
export function loadRounds(h, W, want) {
  const m = magOf(h)
  const t = h.st.ammoType
  const n = Math.max(0, Math.min(want, t ? Math.floor(W.ammoLeft(t) || 0) : want))
  if (t && n > 0) W.useAmmo(t, n)
  setMag(h, m.n + n)
  return n
}
// Seconds to reload: a magazine swap (quicker with a round still chambered),
// or a round at a time.
export function reloadTime(st, have, room) {
  if (st.perShell) return 0.3 + st.perShell * room
  return st.reload * (have > 0 ? 0.72 : 1)
}
