// The game's two typefaces, as CSS font stacks a canvas can use.
//
// Declared once in fonts.css. A canvas — unlike a page — doesn't wait for a
// face to arrive: draw text before it has loaded and you get the fallback,
// permanently, baked into a texture. So anything that paints text into a
// canvas should `await fontsReady()` first.
export const DISPLAY = '"Big Shoulders Display", "Arial Narrow", Impact, sans-serif'
export const UI = 'Barlow, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif'

const FACES = [
  '700 32px "Big Shoulders Display"',
  '800 32px "Big Shoulders Display"',
  '900 32px "Big Shoulders Display"',
  '500 16px Barlow',
  '600 16px Barlow',
  '700 16px Barlow',
]

let ready: Promise<void> | null = null

export function fontsReady(): Promise<void> {
  if (!ready) {
    // Never let a font hold the game hostage: if the browser cannot load one,
    // carry on with the fallback stack.
    const all = Promise.all(FACES.map((f) => document.fonts.load(f))).then(() => undefined)
    const timeout = new Promise<void>((r) => setTimeout(r, 2500))
    ready = Promise.race([all, timeout]).catch(() => undefined)
  }
  return ready
}
