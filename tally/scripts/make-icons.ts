// ---------------------------------------------------------------------------
// Rasterise the icon SVGs to the PNGs iOS and Android launchers require.
//
// Run with `npm run icons` after changing public/icon.svg. Chromium is already
// a dependency here for the end-to-end test, so it does the rendering rather
// than adding an image library for four files.
// ---------------------------------------------------------------------------

import { launchChromium } from './browser.ts'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const publicDir = join(here, '..', 'public')

const TARGETS = [
  { svg: 'icon.svg', out: 'icon-192.png', size: 192 },
  { svg: 'icon.svg', out: 'icon-512.png', size: 512 },
  { svg: 'icon.svg', out: 'icon-180.png', size: 180 },
  { svg: 'icon-maskable.svg', out: 'icon-maskable-512.png', size: 512 },
]

const browser = await launchChromium()
try {
  for (const target of TARGETS) {
    const svg = readFileSync(join(publicDir, target.svg), 'utf8')
    const page = await browser.newPage({
      viewport: { width: target.size, height: target.size },
      deviceScaleFactor: 1,
    })
    await page.setContent(
      `<style>html,body{margin:0;padding:0}svg{display:block;width:${target.size}px;height:${target.size}px}</style>${svg}`,
    )
    const png = await page.locator('svg').screenshot({ omitBackground: true })
    writeFileSync(join(publicDir, target.out), png)
    await page.close()
    console.log(`${target.out}  ${target.size}x${target.size}  ${png.length} bytes`)
  }
} finally {
  await browser.close()
}
