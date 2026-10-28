// A football radar drawn into a frame, for the EA SPORTS FC demo and tests:
// a translucent dark panel with the pitch's white lines, each player a
// filled dot in a team colour with a dark rim, and the ball a small white dot.
// Pitch metres in, pixels out (see src/core/pitch/radar.ts for reading it).

import { PITCH_LENGTH, PITCH_WIDTH } from '../core/pitch/radar.ts'

export interface RadarScene {
  readonly players: readonly { readonly x: number; readonly y: number; readonly team: 0 | 1 }[]
  readonly ball: { readonly x: number; readonly y: number } | null
}

export interface RadarStyle {
  readonly colours: readonly [readonly [number, number, number], readonly [number, number, number]]
  /** Dot diameter as a share of the pitch's drawn length. */
  readonly dot?: number
  readonly ball?: number
  /** Margin around the pitch inside the box, share of the box. */
  readonly margin?: number
}

/** Draws the radar into the box (pixels) of an RGBA frame. */
export function drawRadar(out: Uint8ClampedArray, stride: number, box: { x: number; y: number; w: number; h: number }, scene: RadarScene, style: RadarStyle): void {
  const set = (x: number, y: number, r: number, g: number, b: number, a = 1) => {
    if (x < box.x || y < box.y || x >= box.x + box.w || y >= box.y + box.h) return
    const o = (Math.round(y) * stride + Math.round(x)) * 4
    out[o] = out[o] * (1 - a) + r * a
    out[o + 1] = out[o + 1] * (1 - a) + g * a
    out[o + 2] = out[o + 2] * (1 - a) + b * a
    out[o + 3] = 255
  }
  // The panel.
  for (let y = box.y; y < box.y + box.h; y++) for (let x = box.x; x < box.x + box.w; x++) set(x, y, 10, 28, 18, 0.62)
  const m = style.margin ?? 0.04
  const px0 = box.x + box.w * m
  const py0 = box.y + box.h * m
  const pw = box.w * (1 - 2 * m)
  const ph = box.h * (1 - 2 * m)
  const X = (x: number) => px0 + (x / PITCH_LENGTH) * pw
  const Y = (y: number) => py0 + (y / PITCH_WIDTH) * ph
  const line = (x0: number, y0: number, x1: number, y1: number) => {
    const n = Math.ceil(Math.hypot(X(x1) - X(x0), Y(y1) - Y(y0))) + 1
    for (let i = 0; i <= n; i++) set(X(x0 + ((x1 - x0) * i) / n), Y(y0 + ((y1 - y0) * i) / n), 225, 232, 228, 0.9)
  }
  const rect = (x0: number, y0: number, x1: number, y1: number) => {
    line(x0, y0, x1, y0)
    line(x1, y0, x1, y1)
    line(x1, y1, x0, y1)
    line(x0, y1, x0, y0)
  }
  rect(0, 0, PITCH_LENGTH, PITCH_WIDTH)
  line(PITCH_LENGTH / 2, 0, PITCH_LENGTH / 2, PITCH_WIDTH)
  const cy = PITCH_WIDTH / 2
  rect(0, cy - 20.16, 16.5, cy + 20.16)
  rect(PITCH_LENGTH - 16.5, cy - 20.16, PITCH_LENGTH, cy + 20.16)
  rect(0, cy - 9.16, 5.5, cy + 9.16)
  rect(PITCH_LENGTH - 5.5, cy - 9.16, PITCH_LENGTH, cy + 9.16)
  for (let a = 0; a < 64; a++) {
    const t0 = (a / 64) * Math.PI * 2
    const t1 = ((a + 1) / 64) * Math.PI * 2
    line(PITCH_LENGTH / 2 + 9.15 * Math.cos(t0), cy + 9.15 * Math.sin(t0), PITCH_LENGTH / 2 + 9.15 * Math.cos(t1), cy + 9.15 * Math.sin(t1))
  }
  const disc = (x: number, y: number, r: number, c: readonly number[], rim: boolean) => {
    const cx = X(x)
    const cyy = Y(y)
    for (let yy = Math.floor(cyy - r - 1); yy <= Math.ceil(cyy + r + 1); yy++)
      for (let xx = Math.floor(cx - r - 1); xx <= Math.ceil(cx + r + 1); xx++) {
        const dd = Math.hypot(xx + 0.5 - cx, yy + 0.5 - cyy)
        if (dd <= r) set(xx, yy, c[0], c[1], c[2])
        else if (rim && dd <= r + 0.9) set(xx, yy, 8, 12, 10, 0.8)
      }
  }
  const dot = ((style.dot ?? 0.032) * pw) / 2
  for (const p of scene.players) disc(p.x, p.y, dot, style.colours[p.team], true)
  if (scene.ball) disc(scene.ball.x, scene.ball.y, ((style.ball ?? 0.018) * pw) / 2, [250, 250, 250], true)
}
