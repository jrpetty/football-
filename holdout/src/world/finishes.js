// How a room's walls are finished inside: paint, wallpaper, or a dado of
// tiles, boards or darker paint below with the room's paint above (see
// ROOMS[type].fin). Shared by the street build and first person's additions.

const CAP = '#d9d3c6'

export function finishOf(def) {
  const f = def?.fin || {}
  return { mat: f.paper || 'plaster', dado: f.dado ? { mat: f.dado, h: f.dh || 1.1, col: f.dc || '#e6e4dc' } : null }
}

// One face on one side of a wall, from y0 up to y1 (the floor is at 0.05).
// alongX: the wall runs along x (the face looks along z); s: which side;
// w: its length; (x, z): its centre, already set off the wall's surface;
// mk(key): a material spec ({ mat } or { material }) for the scene.
export function wallFace(b, mk, def, col, alongX, s, w, x, z, y0, y1, ao = 0.1) {
  const F = finishOf(def)
  const box = (key, c, a, top) => {
    if (top - a < 0.004) return
    b.box(alongX ? w : 0.012, top - a, alongX ? 0.012 : w, { ...mk(key), color: c, x, y: (a + top) / 2, z, ao })
  }
  const D = F.dado
  if (!D) return box(F.mat, col, y0, y1)
  const top = 0.05 + D.h
  box(D.mat, D.col, y0, Math.min(y1, top))
  box(F.mat, col, Math.max(y0, top), y1)
  // a rail along the top of the dado
  if (y0 < top && y1 > top) b.box(alongX ? w : 0.03, 0.045, alongX ? 0.03 : w, { ...mk('paint'), color: CAP, x: alongX ? x : x + s * 0.012, y: top, z: alongX ? z + s * 0.012 : z, ao: 0 })
}
