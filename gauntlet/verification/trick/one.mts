// One Presenter screenshot with every reveal step shown. Usage: node one.mts <url> <out.png> [width] [height]
import { chromium } from 'playwright-core';
const [url, out, w, h] = process.argv.slice(2);
const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const p = await b.newPage({ viewport: { width: Number(w ?? 1920), height: Number(h ?? 1080) } });
await p.goto(url!);
await p.waitForTimeout(2500);
await p.keyboard.press('ArrowRight');
await p.keyboard.press('ArrowRight');
await p.waitForTimeout(1500);
await p.screenshot({ path: out! });
await b.close();
