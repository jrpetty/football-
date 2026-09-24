/**
 * Auto video script: a narration draft built from templates (no API call).
 * Running order and "[Slide N]" cues follow the Presenter exactly; replay cues
 * come from the highlights. Every number is produced by a formatter from
 * stored data — `unverifiedNumbers()` checks a (possibly edited or AI-polished)
 * script against the numbers the run can back up.
 */
import { buildCtx, count, idx, label, money, numberTokens, ordinal, perM, pts, rankedOnTest, standings, type Ctx } from './common.ts';
import { presenterSlides, runningOrder, slideOf } from './slides.ts';
import type { Highlight, ScriptSection, SlideRef, StudioInput, VideoScript } from './types.ts';

const WPM = 150;

function words(line: string): number {
  return line.replace(/\[[^\]]*\]/g, ' ').split(/\s+/).filter(Boolean).length;
}

function section(id: string, title: string, lines: string[]): ScriptSection {
  const clean = lines.filter((l) => l.trim());
  const w = clean.reduce((s, l) => s + words(l), 0);
  return { id, title, lines: clean, estSec: Math.max(3, Math.round((w / WPM) * 60)) };
}

function firstSentence(text: string | undefined, max = 180): string {
  const t = (text ?? '').replace(/\s+/g, ' ').trim();
  if (!t) return '';
  const m = t.match(/^(.+?[.!?])(\s|$)/);
  const s = m ? m[1]! : t;
  return s.length > max ? `${s.slice(0, max - 1).replace(/\s+\S*$/, '')}…` : s;
}

function endStop(s: string): string {
  return /[.!?…"]$/.test(s) ? s : `${s}.`;
}

function perTestWinners(ctx: Ctx, testIds: string[]): Map<string, number> {
  const wins = new Map<string, number>();
  for (const id of testIds) {
    const r = rankedOnTest(ctx, id);
    if (r.length >= 2 && r[0]!.score > r[1]!.score) wins.set(r[0]!.id, (wins.get(r[0]!.id) ?? 0) + 1);
  }
  return wins;
}

export function buildScript(input: StudioInput, highlights: Highlight[], slides: SlideRef[] = presenterSlides(input)): VideoScript {
  const ctx = buildCtx(input);
  const m = input.manifest;
  const order = runningOrder(input);
  const competitors = m.contestants.filter((c) => !ctx.baseline.has(c.id));
  const base = m.contestants.find((c) => ctx.baseline.has(c.id));
  const st = standings(ctx);
  const used = new Set<string>();
  const sections: ScriptSection[] = [];

  // ── Hook ──
  const hook = highlights.find((h) => h.type !== 'big-win') ?? highlights[0];
  const hookLines: string[] = [];
  if (hook) {
    used.add(hook.id);
    hookLines.push(`${hook.cue} ${endStop(hook.title)}`);
  }
  const cap = (s: string) => s.replace(/^./, (c) => c.toUpperCase());
  hookLines.push(
    `${cap(count(competitors.length))} AI ${competitors.length === 1 ? 'model' : 'models'}. ${cap(count(order.length))} ${order.length === 1 ? 'test' : 'tests'}. ${st.length ? 'One winner — and it might not be the one you expect.' : 'Let’s see what happened.'}`,
  );
  sections.push(section('hook', 'Hook (first 10 seconds)', hookLines));

  // ── Intro: contestants and prices ──
  const intro: string[] = [`[Slide ${slideOf(slides, 'title') ?? 1}] Welcome to ${m.name || 'the Gauntlet'}. Here are today’s contestants:`];
  for (const c of competitors) {
    const row = input.leaderboard?.rows.find((r) => r.contestantId === c.id);
    if (ctx.manual.has(c.id)) {
      intro.push(`${c.label} from ${c.vendor}, tested by copy and paste in its chat app — so its speed and cost aren’t comparable.`);
      continue;
    }
    const p = c.pricing;
    const price = p && (p.inputPerM > 0 || p.outputPerM > 0) ? `, priced at ${perM(p.inputPerM)} per million input tokens and ${perM(p.outputPerM)} per million output tokens` : '';
    const spent = row && row.totals?.costUsd > 0 ? `. Its whole run cost ${money(row.totals.costUsd)}` : '';
    intro.push(`${c.label} from ${c.vendor}${price}${spent}.`);
  }
  if (base) intro.push(`And the ${base.label}, which just guesses at random. It costs nothing and shows the floor: anything near it is no better than luck.`);
  const how = slideOf(slides, 'how');
  const repeats = m.settings?.repeats ?? 1;
  intro.push(
    `[Slide ${how ?? 2}] How it works: every model gets exactly the same prompts, in a fresh conversation, and every answer is scored automatically from 0 to 100.${repeats > 1 ? ` Each question was asked ${count(repeats)} times and the attempts are averaged.` : ''}`,
  );
  sections.push(section('intro', 'Intro: the contestants', intro));

  // ── One segment per test, in the Presenter's running order ──
  const half = order.length >= 4 ? Math.floor(order.length / 2) : -1;
  order.forEach((testId, i) => {
    const t = ctx.testById.get(testId)!;
    const lines: string[] = [];
    const explainer = slideOf(slides, 'explainer', testId);
    const what = t.hook?.trim() || firstSentence(t.description);
    lines.push(`[Slide ${explainer}] Test ${count(i + 1)}: ${t.name}. ${what ? endStop(what) : ''}`.trim());
    const plain = t.hook ? firstSentence(t.description) : '';
    if (plain && plain !== what) lines.push(endStop(plain));
    const ranked = rankedOnTest(ctx, testId);
    const result = slideOf(slides, 'result', testId);
    if (!ranked.length) lines.push(`[Slide ${result}] No scored results on this test yet.`);
    else if (ranked.length === 1) lines.push(`[Slide ${result}] ${label(ctx, ranked[0]!.id)} scores ${pts(ranked[0]!.score)}.`);
    else {
      const [a, b] = ranked as [(typeof ranked)[0], (typeof ranked)[0]];
      lines.push(
        pts(a.score) === pts(b.score)
          ? `[Slide ${result}] ${label(ctx, a.id)} and ${label(ctx, b.id)} tie on ${pts(a.score)}.`
          : `[Slide ${result}] ${label(ctx, a.id)} takes it with ${pts(a.score)}, ahead of ${label(ctx, b.id)} on ${pts(b.score)}.`,
      );
      if (ranked.length >= 3) {
        const z = ranked[ranked.length - 1]!;
        lines.push(`Bottom of the table: ${label(ctx, z.id)} with ${pts(z.score)}.`);
      }
    }
    const h = highlights.find((x) => x.testId === testId && x.type !== 'big-win' && !used.has(x.id));
    if (h) {
      used.add(h.id);
      lines.push(`${h.cue} ${endStop(h.title)} ${h.why}`);
    }
    sections.push(section(`test-${testId}`, `Test ${i + 1}: ${t.name}`, lines));

    if (i + 1 === half) {
      const wins = perTestWinners(ctx, order.slice(0, half));
      const leaders = [...wins.entries()].sort((x, y) => y[1] - x[1] || x[0].localeCompare(y[0]));
      const recap: string[] = [];
      if (leaders.length) {
        const [lid, lw] = leaders[0]!;
        recap.push(`[Recap card] Halfway recap: after ${count(half)} tests, ${label(ctx, lid)} has won ${count(lw)} of them.`);
        if (leaders[1]) recap.push(`${label(ctx, leaders[1][0])} has ${count(leaders[1][1])}.`);
        const zero = competitors.filter((c) => !wins.has(c.id)).map((c) => c.label);
        if (zero.length && zero.length < competitors.length) recap.push(`Still waiting for a first win: ${zero.join(', ')}.`);
      } else {
        recap.push(`[Recap card] Halfway recap: after ${count(half)} tests, nobody has a clear lead yet.`);
      }
      recap.push('Now for the second half — and this is where it gets interesting.');
      sections.push(section('recap', 'Mid-video recap', recap));
    }
  });

  // ── Final reveal ──
  const final = slideOf(slides, 'final');
  if (final && st.length) {
    const lines = [`[Slide ${final}] The final standings. The Gauntlet Index is the overall score out of 100, averaged across every category.`];
    for (let place = st.length; place >= 2; place--) {
      const s = st[place - 1]!;
      lines.push(`In ${ordinal(place)} place: ${label(ctx, s.id)}, with ${idx(s.index)}.`);
    }
    const w = st[0]!;
    lines.push(`And the winner: ${label(ctx, w.id)}, with ${idx(w.index)}!`);
    const close = highlights.find((h) => h.type === 'close-race' && !h.testId);
    if (close && !used.has(close.id)) {
      used.add(close.id);
      lines.push(close.why);
    }
    const baseRow = base ? input.leaderboard?.rows.find((r) => r.contestantId === base.id) : undefined;
    if (base && typeof baseRow?.index === 'number') lines.push(`For reference, the ${base.label} scored ${idx(baseRow.index)} by pure guessing.`);
    sections.push(section('final', 'The final reveal', lines));
  }
  const scatter = slideOf(slides, 'scatter');
  if (scatter) {
    const lines = [`[Slide ${scatter}] But is the most expensive model worth it? Here is every model’s score against what it cost to run.`];
    const v = highlights.find((h) => h.type === 'best-value');
    if (v) lines.push(`${endStop(v.title)} ${v.why}`);
    sections.push(section('value', 'Value for money', lines));
  }
  const medals = slideOf(slides, 'medals');
  if (medals) {
    const rows = (input.leaderboard?.rows ?? []).filter((r) => !ctx.baseline.has(r.contestantId) && r.medals.gold > 0);
    rows.sort((a, b) => b.medals.gold - a.medals.gold || a.contestantId.localeCompare(b.contestantId));
    const lines = [`[Slide ${medals}] And the medal table, test by test.`];
    if (rows[0]) lines.push(`${rows[0].label} took the most gold medals: ${count(rows[0].medals.gold)}.`);
    const sweep = highlights.find((h) => h.type === 'clean-sweep' && !h.testId);
    if (sweep) lines.push(sweep.why);
    sections.push(section('medals', 'Medal table', lines));
  }

  // ── Outro ──
  const outro = slideOf(slides, 'outro');
  sections.push(
    section('outro', 'Outro', [
      `[Slide ${outro ?? slides.length}] That’s the Gauntlet. Every prompt, every answer and every score from this run is saved and published, so you can check any number yourself.`,
      'Which model should face the Gauntlet next? Tell me in the comments — and subscribe so you don’t miss the next run.',
    ]),
  );

  const wordCount = sections.reduce((s, x) => s + x.lines.reduce((a, l) => a + words(l), 0), 0);
  return {
    title: `${m.name || m.id} — video script`,
    runId: m.id,
    sections,
    wordCount,
    estSec: sections.reduce((s, x) => s + x.estSec, 0),
  };
}

function fmtDuration(sec: number): string {
  const mm = Math.floor(sec / 60);
  const ss = sec % 60;
  return mm ? `${mm} min ${ss} s` : `${ss} s`;
}

export function scriptToMarkdown(s: VideoScript): string {
  const out = [`# ${s.title}`, '', `_About ${fmtDuration(s.estSec)} of narration · ${s.wordCount} words. Cues in **[brackets]** tell the editor what to show._`, ''];
  for (const sec of s.sections) {
    out.push(`## ${sec.title} (~${sec.estSec} s)`, '');
    for (const l of sec.lines) out.push(l.replace(/\[([^\]]+)\]/g, '**[$1]**'), '');
  }
  return out.join('\n').trimEnd() + '\n';
}

export function scriptToText(s: VideoScript): string {
  const out = [s.title.toUpperCase(), ''];
  for (const sec of s.sections) {
    out.push(sec.title.toUpperCase(), '');
    for (const l of sec.lines) out.push(l, '');
  }
  return out.join('\n').trimEnd() + '\n';
}

// ─────────────────────────────── Number check ───────────────────────────────

/**
 * Every number the run can back up, as the exact text tokens a script may
 * contain: numbers found in stored text (names, hooks, summaries, judge
 * rationales), formatted scores / prices / costs, slide and replay-step
 * numbers, counts, and the numbers shown as evidence behind each highlight.
 */
export function allowedNumbers(input: StudioInput, highlights: Highlight[] = [], slides: SlideRef[] = presenterSlides(input)): Set<string> {
  const ok = new Set<string>(['0', '100']);
  const addText = (t: unknown) => {
    if (typeof t === 'string') for (const n of numberTokens(t)) ok.add(n);
    else if (typeof t === 'number' && Number.isFinite(t)) ok.add(String(t));
  };
  const m = input.manifest;
  addText(m.name);
  addText(m.notes);
  for (const c of m.contestants) {
    addText(c.label);
    addText(c.vendor);
    addText(c.model);
    if (c.pricing) for (const n of numberTokens(`${perM(c.pricing.inputPerM)} ${perM(c.pricing.outputPerM)}`)) ok.add(n);
  }
  for (const t of input.tests) {
    addText(t.name);
    addText(t.hook);
    addText(t.description);
  }
  for (const t of m.tests) addText(t.name);
  const counts = [m.tests.length, m.contestants.length, m.settings?.repeats ?? 1, slides.length];
  for (let i = 1; i <= Math.max(...counts); i++) ok.add(String(i));
  for (const s of slides) ok.add(String(s.n));
  for (const r of input.results) {
    addText(r.summary);
    addText(r.caseId);
    addText(r.scoreDetail?.extracted);
    const e = r.scoreDetail?.expected;
    if (typeof e === 'string' || typeof e === 'number') addText(String(e));
    else if (Array.isArray(e)) for (const x of e) if (typeof x === 'string' || typeof x === 'number') addText(String(x));
    for (const j of r.scoreDetail?.judge ?? []) addText(j.rationale);
    for (const f of r.replay?.frames ?? []) {
      ok.add(String(f.step));
      addText(f.label);
    }
    if (typeof r.score === 'number') ok.add(pts(r.score));
  }
  for (const row of input.leaderboard?.rows ?? []) {
    if (typeof row.index === 'number') for (const n of numberTokens(`${idx(row.index)} ${Math.round(row.index)}`)) ok.add(n);
    if (row.totals?.costUsd > 0) for (const n of numberTokens(money(row.totals.costUsd))) ok.add(n);
    for (const a of Object.values(row.tests ?? {})) if (typeof a?.score === 'number') ok.add(pts(a.score));
    ok.add(String(row.medals?.gold ?? 0));
  }
  const ctx = buildCtx(input);
  for (const mm of ctx.mean.values()) for (const v of mm.values()) ok.add(pts(v));
  for (const h of highlights) {
    for (const e of h.evidence) for (const n of numberTokens(e.display)) ok.add(n);
    if (h.replayStep !== undefined) ok.add(String(h.replayStep));
    for (const r of h.why.match(/"[^"]*"/g) ?? []) addText(r);
  }
  return ok;
}

/** Editor-only metadata (read-time estimates) is not narration and is not checked. */
function narrationOnly(text: string): string {
  return text
    .split('\n')
    .filter((l) => !/^_About .* narration/.test(l.trim()))
    .join('\n')
    .replace(/\(~\d+ s\)/g, '');
}

/** Numbers in `text` that the run's data cannot back up (empty = every number checks out). */
export function unverifiedNumbers(text: string, allowed: Set<string>): string[] {
  return [...new Set(numberTokens(narrationOnly(text)).filter((n) => !allowed.has(n) && !allowed.has(n.replace(/\.0+$/, ''))))];
}
