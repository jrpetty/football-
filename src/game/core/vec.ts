// A tiny 2D vector library. All gameplay lives on the pitch plane (x, y);
// the ball adds a separate scalar height (z) handled in the ball module.
// Vectors are plain objects so they serialise cheaply and stay allocation-light.

export interface Vec2 {
  x: number
  y: number
}

export const vec = (x = 0, y = 0): Vec2 => ({ x, y })

export const clone = (a: Vec2): Vec2 => ({ x: a.x, y: a.y })

export const add = (a: Vec2, b: Vec2): Vec2 => ({ x: a.x + b.x, y: a.y + b.y })

export const sub = (a: Vec2, b: Vec2): Vec2 => ({ x: a.x - b.x, y: a.y - b.y })

export const scale = (a: Vec2, s: number): Vec2 => ({ x: a.x * s, y: a.y * s })

export const mulAdd = (a: Vec2, b: Vec2, s: number): Vec2 => ({
  x: a.x + b.x * s,
  y: a.y + b.y * s,
})

export const dot = (a: Vec2, b: Vec2): number => a.x * b.x + a.y * b.y

// z-component of the 3D cross product — useful for "which side" tests and spin.
export const cross = (a: Vec2, b: Vec2): number => a.x * b.y - a.y * b.x

export const len = (a: Vec2): number => Math.hypot(a.x, a.y)

export const len2 = (a: Vec2): number => a.x * a.x + a.y * a.y

export const dist = (a: Vec2, b: Vec2): number => Math.hypot(a.x - b.x, a.y - b.y)

export const dist2 = (a: Vec2, b: Vec2): number => {
  const dx = a.x - b.x
  const dy = a.y - b.y
  return dx * dx + dy * dy
}

export const normalize = (a: Vec2): Vec2 => {
  const l = Math.hypot(a.x, a.y)
  return l > 1e-9 ? { x: a.x / l, y: a.y / l } : { x: 0, y: 0 }
}

// Return a unit vector pointing from a toward b (zero if coincident).
export const dir = (from: Vec2, to: Vec2): Vec2 => normalize(sub(to, from))

// Rotate 90° counter-clockwise — the left-hand perpendicular.
export const perp = (a: Vec2): Vec2 => ({ x: -a.y, y: a.x })

export const rotate = (a: Vec2, rad: number): Vec2 => {
  const c = Math.cos(rad)
  const s = Math.sin(rad)
  return { x: a.x * c - a.y * s, y: a.x * s + a.y * c }
}

export const fromAngle = (rad: number, l = 1): Vec2 => ({
  x: Math.cos(rad) * l,
  y: Math.sin(rad) * l,
})

export const angle = (a: Vec2): number => Math.atan2(a.y, a.x)

// Clamp a vector's magnitude to at most `max`.
export const limit = (a: Vec2, max: number): Vec2 => {
  const l = Math.hypot(a.x, a.y)
  if (l <= max || l < 1e-9) return { x: a.x, y: a.y }
  const s = max / l
  return { x: a.x * s, y: a.y * s }
}

export const lerpVec = (a: Vec2, b: Vec2, t: number): Vec2 => ({
  x: a.x + (b.x - a.x) * t,
  y: a.y + (b.y - a.y) * t,
})
