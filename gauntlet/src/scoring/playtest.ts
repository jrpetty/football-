import type { Page } from 'playwright-core';
import { getBrowser } from './browser.ts';
import type { JamGenre, PlaytestFrameInfo, PlaytestSummary } from './game-jam-shared.ts';

/**
 * Genre playtests for The Game Jam (creative.game-jam).
 *
 * The game is opened twice in headless Chromium at 1280×720 on a *fake clock* (Playwright's clock API drives
 * Date, performance.now, timers and requestAnimationFrame), with Math.random seeded:
 *  - "played": a scripted player sends the genre's controls for 30 seconds of game time (plus a six-frame motion strip);
 *  - "untouched": the same page with no input at all.
 * Because time and randomness are fixed, the two runs differ only because of the input, and a re-run of the same
 * file gives the same screenshots: the checks are repeatable. Screenshots are taken at fixed moments, compared
 * pixel by pixel (in the browser, so no image library is needed) and turned into a few plain, generic checks:
 * it draws something, it keeps moving, it reacts to the controls, it survives 15 s, it throws no errors.
 *
 * Nothing here needs specific element ids or a specific art style, so creative interpretations pass as long as
 * the game starts from Enter / Space / a click (which every brief asks for) and changes on screen when played.
 */

/** Full HD, the size the games are judged and recorded at. */
export const VIEWPORT = { width: 1920, height: 1080 };
/** Game time of each screenshot (ms after the page loaded): the title screen, then mid-action moments. */
export const SHOT_TIMES = [500, 3000, 6000, 10000, 14000, 19000, 24000, 30000];
/** The motion strip: six frames 0.1 s apart in the middle of the action, so judges can see animation. */
export const MOTION_TIMES = [16000, 16100, 16200, 16300, 16400, 16500];
const PLAY_MS = 30000;
/** When the scripted player starts sending input. */
export const INPUT_START_MS = 800;
/** A screenshot pair "changed" when at least this share of pixels differs (0.2 % ≈ a small sprite moving). */
export const MOVE_THRESHOLD = 0.002;
/** Input "changed the game" when the played and untouched copies differ in at least this share of pixels (0.5 % of a full-HD screen; with a frozen clock the untouched copy differs by ~0). */
export const REACT_THRESHOLD = 0.005;
/** A frame is "blank" when fewer than this share of pixels differ from its most common colour. */
export const BLANK_THRESHOLD = 0.003;
/** Real-time budget for one step of the page (a longer wait means an endless loop froze it). */
const STEP_TIMEOUT_MS = 10_000;
const LOAD_TIMEOUT_MS = 12_000;
/** The fake clock's start (a fixed date, so games that read the time of day behave the same on every run). */
const CLOCK_START = Date.UTC(2026, 0, 1, 12, 0, 0);
/** Errors thrown by the game's own callbacks before the harness stops running them one by one. */
const MAX_CALLBACK_ERRORS = 25;

/** Deterministic Math.random (same generator as the one-shot game checks). */
const SEED_SCRIPT = `(() => { let s = 1234567; Math.random = () => { s = (s + 0x6D2B79F5) | 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; })();`;

// ───────────────────────────── Scripted players ─────────────────────────────

type Button = 'left' | 'right';
type Act =
  | { k: 'down'; code: string }
  | { k: 'up'; code: string }
  | { k: 'move'; x: number; y: number }
  | { k: 'mdown'; button: Button }
  | { k: 'mup'; button: Button };
interface Step {
  at: number;
  act: Act;
}

/** Collects timed input steps; helpers expand presses, clicks and drags into down/up pairs. */
class Script {
  steps: Step[] = [];
  key(at: number, code: string, hold = 70): this {
    this.steps.push({ at, act: { k: 'down', code } }, { at: at + hold, act: { k: 'up', code } });
    return this;
  }
  keys(at: number, codes: string[], hold = 70): this {
    for (const c of codes) this.key(at, c, hold);
    return this;
  }
  click(at: number, x: number, y: number, button: Button = 'left'): this {
    this.steps.push({ at, act: { k: 'move', x: px(x), y: py(y) } }, { at, act: { k: 'mdown', button } }, { at: at + 60, act: { k: 'mup', button } });
    return this;
  }
  move(at: number, x: number, y: number): this {
    this.steps.push({ at, act: { k: 'move', x: px(x), y: py(y) } });
    return this;
  }
  drag(at: number, from: [number, number], to: [number, number], ms = 300): this {
    this.steps.push({ at, act: { k: 'move', x: px(from[0]), y: py(from[1]) } }, { at, act: { k: 'mdown', button: 'left' } });
    const n = 6;
    for (let i = 1; i <= n; i++) this.move(at + (ms * i) / n, from[0] + ((to[0] - from[0]) * i) / n, from[1] + ((to[1] - from[1]) * i) / n);
    this.steps.push({ at: at + ms + 20, act: { k: 'mup', button: 'left' } });
    return this;
  }
  hold(at: number, code: string, ms: number): this {
    return this.key(at, code, ms);
  }
  sorted(): Step[] {
    // Stable sort keeps a click's move → down → up order at equal times.
    return this.steps.map((s, i) => ({ s, i })).sort((a, b) => a.s.at - b.s.at || a.i - b.i).map((x) => x.s);
  }
}

// Scripts are written in a 1280×720 design space and scaled to the real viewport when played.
const W = 1280;
const H = 720;
const CX = W / 2;
const CY = H / 2;
const SX = VIEWPORT.width / W;
const SY = VIEWPORT.height / H;
const px = (x: number) => Math.round(x * SX);
const py = (y: number) => Math.round(y * SY);

/** Every game is started the same way: Enter (the brief's required start key), a click in the middle, then Space. */
function start(s: Script, at = INPUT_START_MS): Script {
  return s.key(at, 'Enter').click(at + 300, CX, CY).key(at + 550, 'Space');
}

export interface GenreInfo {
  /** Viewer-facing name of the round. */
  label: string;
  /** Short name, e.g. for cabinet marquees. */
  short: string;
  /** What the scripted player does, in plain words (shown in the UI and told to the judges). */
  inputs: string;
  /** The label of the "reacts to input" check. */
  reacts: string;
  /** Key held for the final half second (the "still running" check). */
  liveKey: string;
  script: () => Step[];
}

export const GENRES: Record<JamGenre, GenreInfo> = {
  flappy: {
    label: 'Flappy Bird remake',
    short: 'Flappy',
    inputs: 'Enter, a click and Space to start, then a flap (Space, sometimes a click or Arrow Up) every 0.38 s for 30 seconds',
    reacts: 'flap presses',
    liveKey: 'Space',
    script: () => {
      const s = start(new Script());
      let i = 0;
      for (let t = 2000; t < PLAY_MS - 200; t += 380, i++) {
        if (i % 5 === 4) s.click(t, CX, CY);
        else s.key(t, i % 7 === 3 ? 'ArrowUp' : 'Space', 60);
      }
      return s.sorted();
    },
  },
  rts: {
    label: 'Real-time strategy',
    short: 'RTS',
    inputs:
      'Enter, a click and Space to start, then box-selects with the mouse, right-click move/attack orders across the map, clicks down the right-hand sidebar and on the map (building), arrow-key camera scrolling and screen-edge scrolling',
    reacts: 'box-select, right-click orders and sidebar clicks',
    liveKey: 'ArrowRight',
    script: () => {
      const s = start(new Script());
      const round = (t0: number, k: number) => {
        s.drag(t0, [160 + k * 40, 140], [820 - k * 30, 560], 320);
        s.click(t0 + 500, 980 - k * 60, 220 + k * 50, 'right');
        s.click(t0 + 800, 700, 470, 'right');
        for (let j = 0; j < 4; j++) s.click(t0 + 1100 + j * 160, W - 90, 170 + j * 70 + (k % 2) * 35);
        s.click(t0 + 1850, 420 + k * 30, 360, 'left');
        s.click(t0 + 2050, 470 + k * 30, 400, 'left');
        s.hold(t0 + 2250, k % 2 ? 'ArrowLeft' : 'ArrowRight', 350);
        s.hold(t0 + 2650, k % 2 ? 'ArrowUp' : 'ArrowDown', 300);
      };
      let k = 0;
      for (let t = 1900; t < PLAY_MS - 3300; t += 3200, k++) round(t, k);
      // Screen-edge scrolling, then one last select-and-order.
      s.move(PLAY_MS - 2600, W - 3, CY).move(PLAY_MS - 1900, CX, H - 3).move(PLAY_MS - 1300, CX, CY);
      s.drag(PLAY_MS - 1100, [200, 150], [900, 600], 250).click(PLAY_MS - 500, 1000, 300, 'right');
      return s.sorted();
    },
  },
  rpg: {
    label: 'Top-down action RPG',
    short: 'RPG',
    inputs:
      'Enter, a click and Space to start, then walking with the arrow keys and WASD in all four directions, attacking (Space, J and Z) and interacting (E, Enter) between moves',
    reacts: 'walking (arrows/WASD) and attacking',
    liveKey: 'ArrowRight',
    script: () => {
      const s = start(new Script());
      const moves: Array<[string, number]> = [
        ['ArrowRight', 700],
        ['KeyS', 550],
        ['ArrowLeft', 500],
        ['KeyW', 650],
        ['KeyD', 600],
        ['ArrowDown', 700],
        ['KeyA', 450],
        ['ArrowUp', 500],
      ];
      const attacks = ['Space', 'KeyJ', 'KeyZ', 'KeyE', 'Space', 'Enter', 'KeyJ', 'KeyZ'];
      let t = 1900;
      let i = 0;
      while (t < PLAY_MS - 900) {
        const [code, ms] = moves[i % moves.length]!;
        s.hold(t, code, ms);
        t += ms + 60;
        s.key(t, attacks[i % attacks.length]!, 80);
        t += 200;
        i++;
      }
      return s.sorted();
    },
  },
  zombie: {
    label: 'Zombie survival',
    short: 'Zombies',
    inputs:
      'Enter, a click and Space to start, then moving with WASD while the mouse sweeps around the player aiming, bursts of left-click shooting, R to reload and E to interact',
    reacts: 'WASD movement, mouse aim and shooting',
    liveKey: 'KeyW',
    script: () => {
      const s = start(new Script());
      const walk: Array<[string, number]> = [
        ['KeyW', 800],
        ['KeyD', 700],
        ['KeyS', 600],
        ['KeyA', 750],
      ];
      let t = 1900;
      let i = 0;
      while (t < PLAY_MS - 900) {
        const [code, ms] = walk[i % walk.length]!;
        s.hold(t, code, ms);
        // Sweep the aim around the middle of the screen while walking.
        for (let j = 0; j < 4; j++) {
          const a = (i * 4 + j) * 0.9;
          s.move(t + (ms * j) / 4, Math.round(CX + Math.cos(a) * 260), Math.round(CY + Math.sin(a) * 180));
        }
        s.steps.push({ at: t + ms * 0.4, act: { k: 'mdown', button: 'left' } }, { at: t + ms * 0.4 + 260, act: { k: 'mup', button: 'left' } });
        t += ms + 80;
        if (i % 3 === 2) {
          s.key(t, 'KeyR', 80);
          t += 150;
        }
        if (i % 4 === 3) {
          s.key(t, 'KeyE', 80);
          t += 150;
        }
        i++;
      }
      return s.sorted();
    },
  },
  racing: {
    label: 'Racing',
    short: 'Racing',
    inputs:
      'Enter, a click and Space to start, then holding accelerate (Arrow Up + W) for the rest of the 30 seconds while steering left and right in turn (arrows + A/D), with a tap of Shift now and then',
    reacts: 'accelerating and steering',
    liveKey: 'ArrowUp',
    script: () => {
      const s = start(new Script());
      s.steps.push({ at: 1900, act: { k: 'down', code: 'ArrowUp' } }, { at: 1900, act: { k: 'down', code: 'KeyW' } });
      let t = 2600;
      let i = 0;
      while (t < PLAY_MS - 700) {
        const left = i % 2 === 0;
        const ms = 380 + (i % 3) * 120;
        s.hold(t, left ? 'ArrowLeft' : 'ArrowRight', ms).hold(t, left ? 'KeyA' : 'KeyD', ms);
        if (i % 4 === 3) s.key(t + ms + 40, 'ShiftLeft', 120);
        t += ms + 320;
        i++;
      }
      s.steps.push({ at: PLAY_MS - 100, act: { k: 'up', code: 'ArrowUp' } }, { at: PLAY_MS - 100, act: { k: 'up', code: 'KeyW' } });
      return s.sorted();
    },
  },
};

// ───────────────────────────── Running a session ─────────────────────────────

class Hung extends Error {
  readonly atMs: number;
  readonly phase: string;
  constructor(atMs: number, phase: string) {
    super(`page stopped responding (${phase})`);
    this.atMs = atMs;
    this.phase = phase;
  }
}

async function within<T>(p: Promise<T>, ms: number, atMs: number, phase: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([p, new Promise<T>((_, reject) => (timer = setTimeout(() => reject(new Hung(atMs, phase)), ms)))]);
  } finally {
    clearTimeout(timer);
  }
}

interface Session {
  /** Screenshots (SHOT_TIMES and, in the played run, MOTION_TIMES); `jpg` is a high-quality copy for the judges. */
  shots: Array<{ t: number; png: Buffer; jpg?: Buffer }>;
  /** One extra frame 0.5 s after the last screenshot (with input in the played run), for the "still running" check. */
  tail: Buffer | null;
  loadErrors: string[];
  playErrors: string[];
  consoleErrors: string[];
  externalRequests: string[];
  hasCanvasOrSvg: boolean;
  hung: { atMs: number; phase: string } | null;
  inputs: number;
}

/** Console messages that only repeat what the external-request check already reports. */
const isBlockedResourceNoise = (text: string) => /Failed to load resource|net::ERR_FAILED|ERR_BLOCKED/i.test(text);

async function session(html: string, steps: Step[] | null, liveKey?: string): Promise<Session> {
  const browser = await getBrowser();
  if (!browser) throw new Error('no browser');
  const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1, javaScriptEnabled: true });
  const out: Session = { shots: [], tail: null, loadErrors: [], playErrors: [], consoleErrors: [], externalRequests: [], hasCanvasOrSvg: false, hung: null, inputs: 0 };
  let now = 0;
  let playing = false;
  const note = (msg: string) => (playing ? out.playErrors : out.loadErrors).push(msg.slice(0, 300));
  try {
    const page: Page = await context.newPage();
    page.on('pageerror', (e) => note(e.message || String(e)));
    page.on('console', (m) => {
      if (m.type() !== 'error') return;
      const text = m.text();
      out.consoleErrors.push(text.slice(0, 300));
      if (!isBlockedResourceNoise(text)) note(`console: ${text}`);
    });
    page.on('crash', () => note('the page crashed'));
    page.on('dialog', (d) => void d.dismiss().catch(() => {}));
    await page.route('**/*', (route) => {
      const url = route.request().url();
      if (url.startsWith('data:') || url.startsWith('blob:') || url === 'about:blank') return route.continue();
      out.externalRequests.push(url);
      return route.abort();
    });
    await page.clock.install({ time: CLOCK_START - 1000 });
    // Freeze time: from here on the game's clock moves only when the harness advances it, so loading speed and
    // machine speed never change what is on screen at a given moment.
    await page.clock.pauseAt(CLOCK_START);
    await page.addInitScript(SEED_SCRIPT);
    await within(page.setContent(html, { waitUntil: 'load', timeout: LOAD_TIMEOUT_MS }), LOAD_TIMEOUT_MS + 2000, 0, 'loading').catch((e: Error) => {
      if (e instanceof Hung) throw e;
      note(`load: ${e.message.split('\n')[0]}`);
    });
    // An exception thrown inside one of the game's timers or frame callbacks surfaces from runFor (a real browser
    // would log it and carry on): record it as a page error, find out how far the fake clock got, and keep going.
    // A game that throws on every frame stops being advanced after MAX_CALLBACK_ERRORS (its picture is frozen anyway).
    let callbackErrors = 0;
    const advance = async (to: number, phase: string) => {
      while (to > now) {
        if (callbackErrors >= MAX_CALLBACK_ERRORS) {
          await within(page.clock.fastForward(to - now).catch(() => {}), STEP_TIMEOUT_MS, now, phase);
          now = to;
          return;
        }
        try {
          await within(page.clock.runFor(to - now), STEP_TIMEOUT_MS, now, phase);
          now = to;
        } catch (e) {
          if (e instanceof Hung) throw e;
          callbackErrors++;
          note((e as Error).message.replace(/^clock\.runFor:\s*/, '').split('\n')[0]!);
          const at = (await within(page.evaluate('Date.now()') as Promise<number>, STEP_TIMEOUT_MS, now, phase)) - CLOCK_START;
          now = Math.min(to, Math.max(now + 1, at));
        }
      }
    };
    // Time is frozen while shooting, so the picture must be stable; a capture can still catch the compositor one
    // frame behind the canvas, so shoot until two consecutive captures agree (repeatable screenshots).
    const capture = () => within(page.screenshot({ type: 'png', timeout: STEP_TIMEOUT_MS }), STEP_TIMEOUT_MS + 1000, now, 'drawing a frame');
    const shoot = async () => {
      let prev = await capture();
      for (let i = 0; i < 3; i++) {
        const next = await capture();
        if (next.equals(prev)) return next;
        prev = next;
      }
      return prev;
    };
    const shotQueue = [...new Set([...SHOT_TIMES, ...(steps ? MOTION_TIMES : [])])].sort((a, b) => a - b);
    const actions = steps ?? [];
    let ai = 0;
    const perform = async (a: Act) => {
      const k = page.keyboard;
      const m = page.mouse;
      if (a.k === 'down') await k.down(a.code);
      else if (a.k === 'up') await k.up(a.code);
      else if (a.k === 'move') await m.move(a.x, a.y);
      else if (a.k === 'mdown') await m.down({ button: a.button });
      else await m.up({ button: a.button });
      out.inputs++;
    };
    while (shotQueue.length || ai < actions.length) {
      const nextShot = shotQueue[0] ?? Infinity;
      const nextAct = actions[ai]?.at ?? Infinity;
      if (nextShot <= nextAct) {
        await advance(nextShot, 'playing');
        if (nextShot >= 3000 && !out.hasCanvasOrSvg) {
          out.hasCanvasOrSvg = await within(
            page.evaluate(`Array.from(document.querySelectorAll('canvas, svg')).some((el) => { const r = el.getBoundingClientRect(); return r.width >= 50 && r.height >= 50; })`) as Promise<boolean>,
            STEP_TIMEOUT_MS,
            now,
            'checking the page',
          );
        }
        const png = await shoot();
        // Time is frozen, so a JPEG taken now shows exactly the same moment (smaller for the judges' image limits).
        const jpg = steps && SHOT_TIMES.includes(nextShot) ? await within(page.screenshot({ type: 'jpeg', quality: 90, timeout: STEP_TIMEOUT_MS }), STEP_TIMEOUT_MS + 1000, now, 'drawing a frame') : undefined;
        out.shots.push({ t: nextShot, png, jpg });
        shotQueue.shift();
      } else {
        await advance(nextAct, 'playing');
        if (!playing && steps) playing = true;
        await within(perform(actions[ai]!.act), STEP_TIMEOUT_MS, now, 'reacting to input');
        ai++;
      }
      if (!steps && now >= INPUT_START_MS) playing = true;
    }
    // Liveness: half a second more (the played copy holds its main control), then one more frame.
    if (steps && liveKey) await within(page.keyboard.down(liveKey), STEP_TIMEOUT_MS, now, 'reacting to input');
    await advance(now + 400, 'playing');
    if (steps && liveKey) await within(page.keyboard.up(liveKey), STEP_TIMEOUT_MS, now, 'reacting to input');
    await advance(now + 100, 'playing');
    out.tail = await shoot();
  } catch (e) {
    if (e instanceof Hung) out.hung = { atMs: e.atMs, phase: e.phase };
    else note(`harness: ${(e as Error).message.split('\n')[0]}`);
  } finally {
    await Promise.race([context.close(), new Promise((r) => setTimeout(r, 8000))]).catch(() => {});
  }
  return out;
}

// ───────────────────────────── Pixel analysis ─────────────────────────────

interface Analysis {
  /** Share of pixels that differ from the frame's most common colour (0 = one flat colour). */
  detail: number[];
  /** Share of changed pixels for each requested pair. */
  diffs: number[];
}

/** Compare screenshots in a scratch page: images are scaled to 320×180 and compared channel by channel. */
async function analyse(pngs: Buffer[], pairs: Array<[number, number]>): Promise<Analysis> {
  const browser = await getBrowser();
  if (!browser) throw new Error('no browser');
  const context = await browser.newContext({ viewport: { width: 400, height: 300 } });
  try {
    const page = await context.newPage();
    // A string expression (the engine's tsconfig has no DOM types); the arguments are inlined as JSON.
    const arg = JSON.stringify({ list: pngs.map((b) => b.toString('base64')), pairs });
    return (await page.evaluate(
      `(async ({ list, pairs }) => {
        const W = 320, H = 180;
        const c = document.createElement('canvas'); c.width = W; c.height = H;
        const x = c.getContext('2d', { willReadFrequently: true });
        const px = [];
        for (const b64 of list) {
          const img = new Image();
          img.src = 'data:image/png;base64,' + b64;
          await img.decode();
          x.clearRect(0, 0, W, H);
          x.drawImage(img, 0, 0, W, H);
          px.push(x.getImageData(0, 0, W, H).data);
        }
        const n = W * H;
        const detail = px.map((d) => {
          const counts = new Map();
          let best = 0, mode = 0;
          for (let i = 0; i < d.length; i += 4) {
            const k = ((d[i] >> 4) << 8) | ((d[i + 1] >> 4) << 4) | (d[i + 2] >> 4);
            const v = (counts.get(k) || 0) + 1; counts.set(k, v);
            if (v > best) { best = v; mode = k; }
          }
          const mr = ((mode >> 8) & 15) * 16 + 8, mg = ((mode >> 4) & 15) * 16 + 8, mb = (mode & 15) * 16 + 8;
          let off = 0;
          for (let i = 0; i < d.length; i += 4) if (Math.max(Math.abs(d[i] - mr), Math.abs(d[i + 1] - mg), Math.abs(d[i + 2] - mb)) > 24) off++;
          return off / n;
        });
        const diffs = pairs.map(([a, b]) => {
          const A = px[a], B = px[b];
          if (!A || !B) return 0;
          let changed = 0;
          for (let i = 0; i < A.length; i += 4) if (Math.max(Math.abs(A[i] - B[i]), Math.abs(A[i + 1] - B[i + 1]), Math.abs(A[i + 2] - B[i + 2])) > 24) changed++;
          return changed / n;
        });
        return { detail, diffs };
      })(${arg})`,
    )) as Analysis;
  } finally {
    await context.close().catch(() => {});
  }
}

/** Artifact name of the motion strip. */
/** Size of the motion strip image. */
export const MOTION_SIZE = { width: 1920, height: 720 };

export const MOTION_NAME = 'playtest-motion.png';

/** Six frames as one 3×2 contact sheet, 1920×720 (each frame 640×360, labelled +0.0 s … +0.5 s). */
async function contactSheet(pngs: Buffer[]): Promise<{ png: Buffer; jpg: Buffer } | null> {
  const browser = await getBrowser();
  if (!browser) return null;
  const context = await browser.newContext({ viewport: { width: 400, height: 300 } });
  try {
    const page = await context.newPage();
    const arg = JSON.stringify(pngs.map((b) => b.toString('base64')));
    const [png, jpg] = (await page.evaluate(
      `(async (list) => {
        const c = document.createElement('canvas'); c.width = 1920; c.height = 720;
        const x = c.getContext('2d');
        x.fillStyle = '#000'; x.fillRect(0, 0, 1920, 720);
        for (let i = 0; i < list.length; i++) {
          const img = new Image(); img.src = 'data:image/png;base64,' + list[i]; await img.decode();
          const cx = (i % 3) * 640, cy = Math.floor(i / 3) * 360;
          x.drawImage(img, cx, cy, 640, 360);
          x.fillStyle = 'rgba(0,0,0,0.7)'; x.fillRect(cx + 8, cy + 8, 104, 34);
          x.fillStyle = '#fff'; x.font = 'bold 22px sans-serif'; x.fillText('+' + (i / 10).toFixed(1) + ' s', cx + 18, cy + 33);
        }
        return [c.toDataURL('image/png').split(',')[1], c.toDataURL('image/jpeg', 0.9).split(',')[1]];
      })(${arg})`,
    )) as [string, string];
    return { png: Buffer.from(png, 'base64'), jpg: Buffer.from(jpg, 'base64') };
  } catch {
    return null;
  } finally {
    await context.close().catch(() => {});
  }
}

// ───────────────────────────── The playtest ─────────────────────────────

export interface PlaytestResult {
  summary: PlaytestSummary;
  /** Screenshots of the played run, in SHOT_TIMES order (missing when the page froze first); `jpg` = judge copy. */
  frames: Array<{ t: number; png: Buffer; jpg?: Buffer }>;
  /** The motion strip as one 3×2 contact sheet (six frames 0.1 s apart), when all six were captured. */
  motion: { png: Buffer; jpg: Buffer } | null;
  loadErrors: string[];
  playErrors: string[];
  consoleErrors: string[];
  externalRequests: string[];
  hasCanvasOrSvg: boolean;
}

const pct = (x: number) => `${x >= 0.1 ? Math.round(x * 100) : (x * 100).toFixed(1)}%`;
const secs = (ms: number) => `${ms % 1000 ? (ms / 1000).toFixed(1) : ms / 1000} s`;

/** Frame caption for viewers, e.g. "Start screen · 0.5 s". */
export function frameLabel(t: number): string {
  return t <= INPUT_START_MS ? `Before any input · ${secs(t)}` : secs(t);
}

/**
 * Play an HTML game for its genre and measure what happened. Returns null when no headless browser is available
 * (the checks are then reported as skipped, exactly like the other browser checks).
 */
export async function runPlaytest(html: string, genre: JamGenre): Promise<PlaytestResult | null> {
  if (!(await getBrowser())) return null;
  const g = GENRES[genre];
  const played = await session(html, g.script(), g.liveKey);
  const idle = played.hung && played.hung.atMs === 0 ? null : await session(html, null);
  const frames = played.shots.filter((f) => SHOT_TIMES.includes(f.t));
  const motionShots = played.shots.filter((f) => MOTION_TIMES.includes(f.t));
  const idleShots = (idle?.shots ?? []).filter((f) => SHOT_TIMES.includes(f.t));
  // Pairs: consecutive played frames, played vs untouched at the same moment, and last frame vs tail.
  const list: Buffer[] = [...frames.map((f) => f.png), ...idleShots.map((f) => f.png), ...(played.tail ? [played.tail] : [])];
  const consecutive: Array<[number, number]> = frames.slice(1).map((_, i) => [i, i + 1]);
  const vsIdle: Array<[number, number]> = frames.map((f, i) => [i, idleShots.findIndex((s) => s.t === f.t)]).filter((p) => p[1] >= 0).map(([a, b]) => [a, frames.length + b]);
  const tailPair: Array<[number, number]> = played.tail && frames.length ? [[frames.length - 1, list.length - 1]] : [];
  // Motion strip frames go last, compared pairwise (how much moves in a tenth of a second).
  const motionStart = list.length;
  list.push(...motionShots.map((f) => f.png));
  const motionPairs: Array<[number, number]> = motionShots.slice(1).map((_, i) => [motionStart + i, motionStart + i + 1]);
  const a = list.length ? await analyse(list, [...consecutive, ...vsIdle, ...tailPair, ...motionPairs]) : { detail: [], diffs: [] };
  const motionDiffs = motionPairs.length ? a.diffs.slice(a.diffs.length - motionPairs.length) : [];
  if (motionPairs.length) a.diffs = a.diffs.slice(0, a.diffs.length - motionPairs.length);
  const motion = motionShots.length === MOTION_TIMES.length ? await contactSheet(motionShots.map((f) => f.png)) : null;
  const change = a.diffs.slice(0, consecutive.length);
  const react = a.diffs.slice(consecutive.length, consecutive.length + vsIdle.length);
  const tailChange = tailPair.length ? a.diffs[a.diffs.length - 1]! : null;
  const infos: PlaytestFrameInfo[] = frames.map((f, i) => {
    const vi = vsIdle.findIndex((p) => p[0] === i);
    return {
      t: f.t,
      name: frameName(f.t),
      label: frameLabel(f.t),
      detail: round4(a.detail[i] ?? 0),
      blank: (a.detail[i] ?? 0) < BLANK_THRESHOLD,
      changed: i === 0 ? null : round4(change[i - 1] ?? 0),
      vsIdle: vi >= 0 ? round4(react[vi] ?? 0) : null,
    };
  });
  const hung = played.hung ?? idle?.hung ?? null;
  const nonBlank = infos.filter((f) => !f.blank).length;
  const moving = change.filter((c) => c >= MOVE_THRESHOLD).length;
  const inPlay = infos.filter((f) => f.t > INPUT_START_MS && f.vsIdle !== null);
  const bestReact = inPlay.reduce((m, f) => Math.max(m, f.vsIdle ?? 0), 0);
  const lastChange = change.length ? change[change.length - 1]! : 0;
  const alive = !hung && frames.length === SHOT_TIMES.length && (lastChange >= MOVE_THRESHOLD || (tailChange ?? 0) >= MOVE_THRESHOLD / 2);
  const summary: PlaytestSummary = {
    genre,
    inputs: g.inputs,
    motion: motion
      ? { name: MOTION_NAME, times: [...MOTION_TIMES], changed: round4(motionDiffs.reduce((x, y) => x + y, 0) / Math.max(1, motionDiffs.length)) }
      : null,
    viewport: { ...VIEWPORT },
    seconds: PLAY_MS / 1000,
    frames: infos,
    hung,
    inputsSent: played.inputs,
    drawsPicture: { passed: nonBlank >= 3, detail: `${nonBlank} of ${SHOT_TIMES.length} screenshots show a picture` },
    keepsMoving: { passed: moving >= 2, detail: `the picture changed between ${moving} of ${change.length || SHOT_TIMES.length - 1} screenshot pairs` },
    reacts: {
      passed: bestReact >= REACT_THRESHOLD,
      detail: `${g.reacts}: ${inPlay.length ? `up to ${pct(bestReact)} of the screen differs from an untouched copy` : 'not measured (the page froze)'}`,
    },
    stillRunning: {
      passed: alive,
      detail: hung ? `the page stopped responding at ${secs(hung.atMs)} of game time (${hung.phase}: probably an endless loop)` : alive ? 'still animating at the end of the playtest' : frames.length < SHOT_TIMES.length ? 'screenshots stopped early' : 'the screen stopped changing before the end',
    },
    noPlayErrors: { passed: played.playErrors.length === 0 && !hung, detail: played.playErrors[0] ?? (hung ? 'the page froze' : undefined) },
  };
  return {
    summary,
    frames,
    loadErrors: [...new Set([...played.loadErrors, ...(idle ? idle.loadErrors.concat(idle.playErrors) : [])])],
    playErrors: [...new Set(played.playErrors)],
    consoleErrors: [...new Set([...played.consoleErrors, ...(idle?.consoleErrors ?? [])])],
    externalRequests: [...new Set([...played.externalRequests, ...(idle?.externalRequests ?? [])])],
    hasCanvasOrSvg: played.hasCanvasOrSvg || Boolean(idle?.hasCanvasOrSvg),
    motion,
  };
}

/** Artifact file name of a playtest frame (Windows-safe). */
export function frameName(t: number): string {
  return `playtest-${String(t).padStart(5, '0')}ms.png`;
}

function round4(n: number): number {
  return Math.round(n * 10000) / 10000;
}
