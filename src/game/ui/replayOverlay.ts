// The broadcast furniture that wraps a replay: a band top and bottom, the
// label, how far through we are, and how to get out. Shared by both
// presentations — a replay looks the same whether you are watching it in 3D
// or from above, and there is no reason for two copies of it to drift.
export function drawReplayOverlay(
  c: CanvasRenderingContext2D,
  w: number,
  h: number,
  label: string,
  progress: number,
) {
  const bar = Math.max(38, h * 0.07)
  c.fillStyle = 'rgba(6,10,18,0.82)'
  c.fillRect(0, 0, w, bar)
  c.fillRect(0, h - bar, w, bar)
  c.fillStyle = '#ffe28a'
  c.font = '700 16px system-ui, sans-serif'
  c.textAlign = 'center'
  c.textBaseline = 'middle'
  c.fillText(label, w / 2, bar / 2)
  c.fillStyle = 'rgba(255,255,255,0.5)'
  c.font = '600 11px system-ui, sans-serif'
  c.fillText('R or ESC to skip', w / 2, h - bar / 2)
  c.fillStyle = 'rgba(255,255,255,0.15)'
  c.fillRect(0, bar - 3, w, 3)
  c.fillStyle = '#ffe28a'
  c.fillRect(0, bar - 3, w * progress, 3)
}
