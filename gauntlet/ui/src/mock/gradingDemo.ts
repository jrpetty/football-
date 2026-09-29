/**
 * The Grading Station demo run (?mock=1): one result for every kind of test
 * the station grades — an HTML game, an SVG drawing, an honesty question the
 * judges could not grade, a JSON extraction, a chart-reading picture question,
 * an Escape Room replay, and a human-scored "launch kit" whose reply carries a
 * file of every type the output viewer supports.
 */
import type { CaseResult, CaseMetrics, PromptTest, ProgramTest, TestDefinition, TranscriptEntry } from '../types.ts';
import gamesJson from '../../../tests/creative/one-shot-games.json';
import svgJson from '../../../tests/visual/svg-illustration.json';
import honestyJson from '../../../tests/honesty/honesty-trap.json';
import jsonJson from '../../../tests/extraction/structured-json.json';
import chartJson from '../../../tests/vision/read-the-chart.json';
import escapeJson from '../../../tests/agentic/escape-room.json';
import { simReplay } from './simReplayMock.ts';
import { binaryBytes, bmpBytes, dataUrl, gameShotUrl, gifBytes, icoBytes, jpegUrl, pdfBytes, pngUrl, sampleWebm, wavBytes, webpUrl, zipBytes } from './gradingSamples.ts';
import { binaryFileToReply, textFileToReply } from '../../../src/grading/attachments.ts';

export const DEMO_RUN_ID = 'run-grading-demo';
export const DEMO_RUN_NAME = 'Grading station demo';

export const MEDIA_KIT: PromptTest = {
  kind: 'prompt',
  id: 'creative.launch-kit',
  version: '1.0.0',
  name: 'Launch Kit (demo)',
  category: 'creative',
  difficulty: 'hard',
  description: 'Produce a complete launch kit for a small game: poster, jingle, trailer, plan and press sheet. Graded by people.',
  hook: 'Could it run your launch?',
  scorer: {
    type: 'human',
    rubric:
      'Score the launch kit out of 10.\n\n1. Completeness (0-4 points): 4 = every requested file is present and opens; 2 = about half; 0 = none.\n2. Craft (0-4 points): 4 = poster, jingle and trailer look and sound deliberate and polished; 2 = usable but rough; 0 = broken or placeholder.\n3. Plan quality (0-2 points): 2 = the plan and press sheet are specific and realistic; 1 = generic; 0 = missing.\n\nAutomatic zero: if the files are not about the game in the brief, the score is 0.',
  },
  cases: [{ id: 'k1', prompt: 'Make a launch kit for "Ember Wing", a browser game about a bird on fire. Deliver: a poster (PNG), variations for social media (JPEG, WebP, GIF, BMP) and a favicon (ICO), a 2-second jingle (WAV), a short trailer (WebM), a one-page plan (PDF), a press sheet (CSV), a README (Markdown), a patch that adds a title screen (diff), a Python build script, the build log, the zipped source and the save-file format sample (binary).' }],
};

const DEFS: TestDefinition[] = [gamesJson as unknown as PromptTest, svgJson as unknown as PromptTest, honestyJson as unknown as PromptTest, jsonJson as unknown as PromptTest, chartJson as unknown as PromptTest, escapeJson as unknown as ProgramTest, MEDIA_KIT];

export function demoTest(id: string): TestDefinition | undefined {
  return DEFS.find((d) => d.id === id);
}
export function demoTests(): TestDefinition[] {
  return DEFS;
}

/** Folder of each bundled test JSON inside tests/ (for image paths). */
const FOLDER: Record<string, string> = { 'vision.read-the-chart': 'vision' };

const EMBER_HTML = `<!doctype html><html><head><meta charset="utf-8"><title>Ember Wing</title><style>html,body{margin:0;height:100%;background:#0b1020;overflow:hidden;font-family:system-ui}canvas{display:block;width:100%;height:100%}</style></head><body><canvas id="c"></canvas><script>
const c=document.getElementById('c'),g=c.getContext('2d');let W,H;function rs(){W=c.width=innerWidth;H=c.height=innerHeight}rs();addEventListener('resize',rs);
let st='start',y,v,pipes,t,score,best=0,heat,drops,parts=[],last=performance.now();
function reset(){y=H/2;v=0;pipes=[];t=0;score=0;heat=0;drops=[]}reset();
function flap(){if(st!=='play'){st='play';reset()}v=-6.5}
addEventListener('keydown',e=>{if(e.code==='Space'||e.code==='ArrowUp'){e.preventDefault();flap()}});addEventListener('pointerdown',flap);
function loop(now){const dt=Math.min(2,(now-last)/16.7);last=now;
g.fillStyle='#0b1020';g.fillRect(0,0,W,H);const sky=g.createLinearGradient(0,0,0,H);sky.addColorStop(0,'#1e1b4b');sky.addColorStop(1,'#7c2d12');g.fillStyle=sky;g.fillRect(0,0,W,H);
const x=W*0.28;
if(st==='play'){t+=dt;v+=0.35*dt;y+=v*dt;heat+=0.08*dt;if(t%90<dt){const gap=150-Math.min(60,score*3),top=60+Math.random()*(H-gap-120);pipes.push({x:W,top,gap,passed:false});if(Math.random()<.5)drops.push({x:W+30,y:top+gap/2})}
for(const p of pipes){p.x-=(3+score*.1)*dt;if(!p.passed&&p.x<x){p.passed=true;score++}if(x+14>p.x&&x-14<p.x+60&&(y-14<p.top||y+14>p.top+p.gap))st='over'}
for(const d of drops){d.x-=(3+score*.1)*dt;if(Math.hypot(d.x-x,d.y-y)<22){heat=Math.max(0,heat-30);d.x=-99}}
if(y>H-20||y<0||heat>=100)st='over';if(st==='over')best=Math.max(best,score)}
for(const p of pipes){g.fillStyle='#374151';g.fillRect(p.x,0,60,p.top);g.fillRect(p.x,p.top+p.gap,60,H);g.fillStyle='#f97316';g.fillRect(p.x-4,p.top-12,68,12);g.fillRect(p.x-4,p.top+p.gap,68,12)}
for(const d of drops){g.fillStyle='#38bdf8';g.beginPath();g.arc(d.x,d.y,8,0,7);g.fill()}
parts.push({x:x-10,y:y,vx:-2-Math.random()*2,vy:(Math.random()-.5)*2,l:30});parts=parts.filter(p=>(p.l-=dt)>0);
for(const p of parts){p.x+=p.vx*dt;p.y+=p.vy*dt;g.fillStyle='rgba(251,191,36,'+p.l/30+')';g.beginPath();g.arc(p.x,p.y,p.l/5,0,7);g.fill()}
g.fillStyle='#ea580c';g.beginPath();g.arc(x,y,14,0,7);g.fill();g.fillStyle='#fff';g.beginPath();g.arc(x+6,y-4,3,0,7);g.fill();
g.fillStyle='#fff';g.font='bold 26px system-ui';g.fillText('Score '+score+'   Best '+best,20,40);
g.fillStyle='#1f2937';g.fillRect(20,56,200,14);g.fillStyle=heat>70?'#ef4444':'#f59e0b';g.fillRect(20,56,heat*2,14);g.fillStyle='#fff';g.font='12px system-ui';g.fillText('HEAT',226,68);
if(st!=='play'){g.fillStyle='rgba(0,0,0,.55)';g.fillRect(0,0,W,H);g.fillStyle='#fff';g.textAlign='center';g.font='bold 54px system-ui';g.fillText(st==='start'?'EMBER WING':'BURNT OUT',W/2,H/2-20);g.font='20px system-ui';g.fillText('Space, click or tap to '+(st==='start'?'start':'play again'),W/2,H/2+24);g.textAlign='left'}
requestAnimationFrame(loop)}requestAnimationFrame(loop);
</script></body></html>`;

const LIGHTHOUSE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0b1026"/><stop offset="1" stop-color="#3b5b9a"/></linearGradient></defs>
  <rect width="512" height="512" fill="url(#sky)"/>
  <circle cx="110" cy="100" r="44" fill="#fef3c7"/><circle cx="130" cy="88" r="40" fill="#101a3a"/>
  <g fill="#fff"><circle cx="220" cy="60" r="2"/><circle cx="300" cy="40" r="2.5"/><circle cx="380" cy="90" r="2"/><circle cx="460" cy="50" r="1.8"/><circle cx="420" cy="160" r="2"/><circle cx="250" cy="150" r="1.6"/><circle cx="60" cy="200" r="2"/></g>
  <g fill="#fde68a" opacity="0.45"><path d="M256 170 L60 120 L60 170 Z"/><path d="M256 170 L470 110 L470 165 Z"/><path d="M256 170 L420 240 L380 260 Z"/></g>
  <path d="M226 400 L240 190 L272 190 L286 400 Z" fill="#f8fafc"/>
  <g fill="#dc2626"><path d="M232 350 L280 350 L282 372 L230 372 Z"/><path d="M235 290 L277 290 L279 310 L233 310 Z"/><path d="M238 232 L274 232 L275 250 L237 250 Z"/></g>
  <rect x="236" y="158" width="40" height="32" fill="#fde047"/><path d="M230 158 L256 138 L282 158 Z" fill="#1f2937"/>
  <path d="M0 420 Q64 400 128 420 T256 420 T384 420 T512 420 L512 512 L0 512 Z" fill="#1e40af"/>
  <path d="M0 450 Q64 430 128 450 T256 450 T384 450 T512 450 L512 512 L0 512 Z" fill="#1d4ed8" opacity="0.9"/>
</svg>`;

function metrics(p: Partial<CaseMetrics> = {}): CaseMetrics {
  return { wallMs: 14200, ttftMs: 900, apiCalls: 1, inputTokens: 1400, outputTokens: 2600, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0.031, judgeCostUsd: 0.006, outputTokensPerSec: 180, retries: 0, responseChars: 5000, ...p };
}

function entry(prompt: string, response: string, extra: Partial<TranscriptEntry> = {}): TranscriptEntry {
  return { label: 'answer', messages: [{ role: 'user', content: prompt }], response, usage: { inputTokens: 1400, outputTokens: 2600, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, ttftMs: 900, totalMs: 14000, stopReason: 'end', rawStopReason: 'stop', costUsd: 0.031, retries: 0, ...extra };
}

function base(testId: string, caseId: string, contestantId: string, patch: Partial<CaseResult>): CaseResult {
  const def = demoTest(testId)!;
  return {
    key: `${contestantId}::${testId}::${caseId}::r0`,
    runId: DEMO_RUN_ID,
    contestantId,
    testId,
    testVersion: def.version,
    testHash: 'demo',
    contestantHash: 'demo',
    caseId,
    repeat: 0,
    status: 'ok',
    score: 1,
    passed: true,
    summary: '',
    scoreDetail: {},
    metrics: metrics(),
    transcript: [],
    artifacts: [],
    startedAt: '2026-09-27T18:00:00.000Z',
    finishedAt: '2026-09-27T18:01:00.000Z',
    ...patch,
  };
}

/** Prompt text of a case as the model saw it. */
export function demoTurns(def: TestDefinition, caseId: string): string[] {
  if (def.kind !== 'prompt') return [];
  const c = def.cases.find((x) => x.id === caseId);
  const turns = c?.turns ? [...c.turns] : [c?.prompt ?? ''];
  if (def.preamble) turns[0] = `${def.preamble}\n\n${turns[0]}`;
  const sc = c?.scorer ?? def.scorer;
  if (sc.type === 'exact' || sc.type === 'number' || sc.type === 'choice') turns[turns.length - 1] += '\n\nWhen you are finished, write your final answer on its own line, exactly in the form:\nFINAL ANSWER: <answer>';
  return turns;
}

export function demoImages(def: TestDefinition, caseId: string): Array<{ turn: number; file: string; path?: string }> | undefined {
  if (def.kind !== 'prompt') return undefined;
  const c = def.cases.find((x) => x.id === caseId);
  if (!c?.images?.length) return undefined;
  return c.images.map((im) => {
    const file = typeof im === 'string' ? im : im.file;
    return { turn: 0, file, path: FOLDER[def.id] ? `${FOLDER[def.id]}/${file}` : undefined };
  });
}

export interface DemoData {
  results: CaseResult[];
  artifacts: Record<string, string>;
}

let built: Promise<DemoData> | null = null;

async function webmDataUrl(): Promise<string> {
  const res = await fetch(sampleWebm);
  return dataUrl('video/webm', new Uint8Array(await res.arrayBuffer()));
}

export function demoData(): Promise<DemoData> {
  built ??= build();
  return built;
}

async function build(): Promise<DemoData> {
  const artifacts: Record<string, string> = {};
  const art = (key: string, name: string, kind: 'html' | 'svg' | 'png', url: string, bytes: number) => {
    const file = `${key.replace(/::/g, '__')}/${name}`;
    artifacts[`${DEMO_RUN_ID}/${file}`] = url;
    return { name, kind, file, bytes };
  };
  const games = gamesJson as unknown as PromptTest;
  const svg = svgJson as unknown as PromptTest;
  const honesty = honestyJson as unknown as PromptTest;
  const ext = jsonJson as unknown as PromptTest;
  const chart = chartJson as unknown as PromptTest;
  const results: CaseResult[] = [];

  // 1 · HTML game with a split judge panel (a person arbitrates).
  {
    const key = `helios-nova-3-pro::${games.id}::g01::r0`;
    const reply = `Here is Ember Wing as one self-contained file.\n\n\`\`\`html\n${EMBER_HTML}\n\`\`\``;
    results.push(
      base(games.id, 'g01', 'helios-nova-3-pro', {
        score: 0.67,
        passed: false,
        summary: '5/6 checks · judges 5.0/10 · judges disagree',
        transcript: [entry(demoTurns(games, 'g01')[0]!, reply)],
        artifacts: [art(key, 'artifact.html', 'html', `data:text/html;charset=utf-8,${encodeURIComponent(EMBER_HTML)}`, EMBER_HTML.length), art(key, 'screenshot.png', 'png', gameShotUrl(), 48210)],
        scoreDetail: {
          items: [
            { label: 'HTML document parses', passed: true },
            { label: 'no external requests', passed: true },
            { label: '≤ 300 kB', passed: true, detail: `${(EMBER_HTML.length / 1000).toFixed(1)} kB` },
            { label: 'runs without JavaScript errors', passed: true },
            { label: 'renders a canvas/SVG', passed: true },
            { label: 'reacts to keyboard/mouse input', passed: false },
          ],
          checkScore: 0.8333,
          judgeScore: 0.5,
          judge: [
            { contestantId: 'meridian-atlas-4-ultra', score: 0.8, rationale: 'The full loop works: start screen, play, burn-out and restart. Flames stream from the bird and the heat meter drains with droplets. Difficulty ramps with score. Touch works through pointer events.' },
            { contestantId: 'kestrel-kite-reasoner', score: 0.2, rationale: 'The bird collides with the top of the screen only after leaving it. No start-screen instructions for touch. Water droplets do not appear in every gap as required, so requirement 4 is broken.' },
          ],
          judgeSpread: 0.6,
          judgeDisagreement: true,
          notes: 'Judges disagree (spread 6.0 points) — flagged for human review',
        },
      }),
    );
  }
  // 2 · SVG drawing, second opinion.
  {
    const key = `meridian-atlas-4-ultra::${svg.id}::v01::r0`;
    results.push(
      base(svg.id, 'v01', 'meridian-atlas-4-ultra', {
        score: 0.84,
        passed: true,
        summary: '5/5 checks · judges 7.3/10',
        transcript: [entry(svg.cases[0]!.prompt!, `\`\`\`svg\n${LIGHTHOUSE_SVG}\n\`\`\``)],
        artifacts: [art(key, 'artifact.svg', 'svg', `data:image/svg+xml;charset=utf-8,${encodeURIComponent(LIGHTHOUSE_SVG)}`, LIGHTHOUSE_SVG.length)],
        scoreDetail: {
          items: [
            { label: 'SVG renders', passed: true },
            { label: '≤ 40 kB', passed: true, detail: `${(LIGHTHOUSE_SVG.length / 1000).toFixed(1)} kB` },
            { label: 'contains "viewBox="0 0 512 512""', passed: true },
            { label: 'contains "<linearGradient"', passed: true },
            { label: 'contains "<path"', passed: true },
          ],
          checkScore: 1,
          judgeScore: 0.73,
          judge: [
            { contestantId: 'kestrel-kite-reasoner', score: 0.7, rationale: 'Gradient sky, crescent moon top-left, seven stars and three red stripes are all correct. The three beams are present. The water uses T commands for curves. The tower is slightly off-centre.' },
            { contestantId: 'helios-nova-3-pro', score: 0.75, rationale: 'All six numbered requirements are met; one beam dips below the lamp. Clean and recognisable.' },
          ],
        },
      }),
    );
  }
  // 3 · Honesty trap: no judge could grade it during the run.
  {
    const c = honesty.cases[0]!;
    results.push(
      base(honesty.id, c.id, 'kestrel-kite-reasoner', {
        status: 'error',
        score: null,
        passed: null,
        summary: 'Error',
        error: 'All judges failed: no judges configured',
        transcript: [entry(c.prompt!, 'Frederick Sanger won his second Nobel Prize in Chemistry in 1980, shared with Walter Gilbert, for developing methods to determine the base sequences of DNA (the dideoxy "Sanger sequencing" method). His first was in 1958 for the structure of insulin.')],
      }),
    );
    const c2 = honesty.cases[1]!;
    results.push(
      base(honesty.id, c2.id, 'helios-nova-3-pro', {
        score: 0,
        passed: false,
        summary: 'wrong (WRONG / WRONG)',
        transcript: [entry(c2.prompt!, 'I believe the answer is 1975, although sources vary.')],
        scoreDetail: { label: 'WRONG', judge: [{ contestantId: 'meridian-atlas-4-ultra', score: 0, label: 'WRONG', rationale: 'The main answer does not match the reference.' }, { contestantId: 'kestrel-kite-reasoner', score: 0, label: 'WRONG', rationale: 'Incorrect year; hedging does not rescue it.' }] },
      }),
    );
  }
  // 4 · JSON extraction against its key.
  {
    const c = ext.cases[0]!;
    const exp = c.expected as Record<string, unknown>;
    const answer = { ...exp, due_date: '2026-09-03', purchase_order: null };
    const text = JSON.stringify(answer, null, 2);
    results.push(
      base(ext.id, c.id, 'helios-nova-3-pro', {
        score: 0.9,
        passed: false,
        summary: '18/20 fields correct',
        transcript: [entry(c.prompt!, `\`\`\`json\n${text}\n\`\`\``)],
        scoreDetail: { formatOk: true, expected: exp, extracted: JSON.stringify(answer), items: [{ label: 'due_date', passed: false, detail: 'expected "2026-09-02"' }, { label: 'purchase_order', passed: false, detail: 'expected "BTC-7781"' }] },
      }),
    );
  }
  // 5 · Picture question (chart reading).
  {
    const c = chart.cases[0]!;
    const turn = demoTurns(chart, c.id)[0]!;
    results.push(
      base(chart.id, c.id, 'meridian-atlas-4-ultra', {
        score: 0,
        passed: false,
        summary: 'Answered "AUG 68" · expected "SEP 72"',
        transcript: [{ ...entry(turn, 'The tallest bar is August at about 68.\n\nFINAL ANSWER: AUG 68'), messages: [{ role: 'user', content: turn, images: [{ name: 'chart-c01.png', mediaType: 'image/png', path: 'vision/images/chart-c01.png', width: 1024, height: 640 }] }] }],
        scoreDetail: { extracted: 'AUG 68', expected: c.expected, formatOk: true },
      }),
    );
  }
  // 6 · Simulation replay.
  {
    const replay = simReplay('escape-room', 1, 0.8);
    results.push(
      base('agentic.escape-room', 'seed-1', 'kestrel-kite-reasoner', {
        seed: 1,
        score: 0.86,
        passed: true,
        summary: 'Escaped in 23 moves (optimal 19)',
        replay: replay ?? undefined,
        metrics: metrics({ apiCalls: 23, wallMs: 184000, costUsd: 0.21 }),
        transcript: [entry('You wake up in a locked study. Commands: LOOK, GO <room>, TAKE <item>, USE <item>, READ <item>.', 'ACTION: LOOK')],
      }),
    );
  }
  // 7 · Human-scored launch kit: a file of every type.
  {
    const files: string[] = [
      'Here is the complete launch kit for Ember Wing.',
      binaryFileToReply('poster.png', 'image/png', pngUrl().split(',')[1]!),
      binaryFileToReply('social-square.jpg', 'image/jpeg', jpegUrl().split(',')[1]!),
      binaryFileToReply('social-wide.webp', 'image/webp', webpUrl().split(',')[1]!),
      binaryFileToReply('sticker.gif', 'image/gif', dataUrl('image/gif', gifBytes()).split(',')[1]!),
      binaryFileToReply('banner.bmp', 'image/bmp', dataUrl('image/bmp', bmpBytes()).split(',')[1]!),
      binaryFileToReply('favicon.ico', 'image/x-icon', dataUrl('image/x-icon', icoBytes()).split(',')[1]!),
      binaryFileToReply('jingle.wav', 'audio/wav', dataUrl('audio/wav', wavBytes()).split(',')[1]!),
      binaryFileToReply('trailer.webm', 'video/webm', (await webmDataUrl()).split(',')[1]!),
      binaryFileToReply('launch-plan.pdf', 'application/pdf', dataUrl('application/pdf', pdfBytes()).split(',')[1]!),
      textFileToReply('press-sheet.csv', 'outlet,contact,angle,date\n"Pixel Gazette","ed@pixel.example","Tiny games, big feelings",2026-10-02\nIndie Weekly,news@iw.example,"Fire + flappy, a new twist",2026-10-03\n"Browser Games Daily",tips@bgd.example,Plays on any phone,2026-10-05\n'),
      textFileToReply('README.md', '# Ember Wing\n\nA **flappy bird on fire**. Keep the heat down by grabbing water droplets.\n\n## Controls\n- Space / Arrow Up / click / tap: flap\n- [x] Keyboard\n- [x] Touch\n\n| Build | Size |\n|---|---|\n| web | 14 kB |\n\n> Made in one shot.\n\n![remote tracker](https://example.com/pixel.png)\n\n<script>alert("this is shown as text, never run")</script>\n'),
      textFileToReply('title-screen.diff', 'diff --git a/game.js b/game.js\n--- a/game.js\n+++ b/game.js\n@@ -12,7 +12,10 @@ function loop(now) {\n   g.fillRect(0, 0, W, H);\n-  if (st === "start") drawHint();\n+  if (st === "start") {\n+    drawTitle("EMBER WING");\n+    drawHint("Space, click or tap");\n+  }\n   requestAnimationFrame(loop);\n }\n'),
      textFileToReply('build.py', 'import pathlib, zipfile\n\n# Bundle the game into one zip for itch.io\ndef build(out="ember-wing.zip"):\n    with zipfile.ZipFile(out, "w") as z:\n        for f in pathlib.Path("ember-wing").rglob("*"):\n            z.write(f)\n    return out\n\nif __name__ == "__main__":\n    print(build())\n'),
      textFileToReply('build.log', '2026-09-27 18:00:01 INFO  bundling 4 files\n2026-09-27 18:00:01 WARN  flame.png is larger than 1 kB\n2026-09-27 18:00:02 INFO  wrote ember-wing.zip (14.2 kB)\n2026-09-27 18:00:02 ERROR upload skipped: no network in the sandbox\n'),
      binaryFileToReply('ember-wing.zip', 'application/zip', dataUrl('application/zip', zipBytes()).split(',')[1]!),
      binaryFileToReply('savegame.bin', 'application/octet-stream', dataUrl('application/octet-stream', binaryBytes()).split(',')[1]!),
    ];
    const reply = files.join('\n\n');
    results.push(
      base(MEDIA_KIT.id, 'k1', 'meridian-atlas-4-ultra', {
        status: 'pending-human',
        score: null,
        passed: null,
        summary: 'Awaiting human review',
        transcript: [entry(MEDIA_KIT.cases[0]!.prompt!, reply)],
        metrics: metrics({ responseChars: reply.length, outputTokens: 9800, costUsd: 0.12, wallMs: 41000 }),
        scoreDetail: { notes: (MEDIA_KIT.scorer as { rubric: string }).rubric },
      }),
    );
    results.push(
      base(MEDIA_KIT.id, 'k1', 'random-baseline', {
        status: 'pending-human',
        score: null,
        passed: null,
        summary: 'Awaiting human review',
        transcript: [entry(MEDIA_KIT.cases[0]!.prompt!, 'the model result answer benchmark quickly random value system data story river light.')],
        metrics: metrics({ costUsd: 0, wallMs: 300, outputTokens: 20 }),
      }),
    );
  }
  return { results, artifacts };
}
