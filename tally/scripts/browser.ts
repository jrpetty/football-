// ---------------------------------------------------------------------------
// Launching Chromium.
//
// Prefers a browser already present on the machine (CI images and this
// development container ship one) over having Playwright download its own,
// falling back to the managed download when there is none.
// ---------------------------------------------------------------------------

import { chromium, type Browser } from 'playwright'
import { existsSync } from 'node:fs'

const CANDIDATES = [
  process.env.CHROMIUM_PATH,
  '/opt/pw-browsers/chromium',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome',
].filter((p): p is string => !!p)

export async function launchChromium(): Promise<Browser> {
  const found = CANDIDATES.find((p) => existsSync(p))
  return await chromium.launch(found ? { executablePath: found } : {})
}
