/**
 * Highlight finder: scans a finished (or running) run and ranks the moments
 * worth putting in a video — upsets, photo finishes, sweeps, disasters,
 * confident nonsense, the fastest right answer, the priciest wrong one, the
 * best value and the least consistent model.
 *
 * Deterministic and explainable: every pick carries the rule that chose it
 * and the numbers behind it (`evidence`), and its title only quotes those
 * numbers. Works for any test type: simulation-specific signals (death day,
 * bankruptcy, …) are used when present, and every test falls back to generic
 * score-based moments.
 */
import {
  buildCtx,
  caseLink,
  clamp,
  count,
  idx,
  label,
  listPrice,
  money,
  presenterLink,
  pts,
  rankedOnTest,
  secs,
  standings,
  testName,
  times,
  type Ctx,
} from './common.ts';
import { presenterSlides, slideOf } from './slides.ts';
import type { Highlight, HighlightEvidence, SlideRef, StudioInput, StudioResult } from './types.ts';

const ev = (label: string, value: number, display: string): HighlightEvidence => ({ label, value, display });

/** Clip lengths (seconds) by kind of footage. */
const CLIP = { replay: 25, case: 15, stat: 10, reveal: 12 } as const;

interface Env {
  ctx: Ctx;
  slides: SlideRef[];
}

function competitor(ctx: Ctx, id: string): boolean {
  return !ctx.baseline.has(id);
}

function replayStepOf(r: StudioResult, prefer: 'bad' | 'last' = 'bad'): number | undefined {
  const frames = r.replay?.frames ?? [];
  if (!frames.length) return undefined;
  if (prefer === 'bad') {
    for (let i = frames.length - 1; i >= 0; i--) if (frames[i]!.tone === 'bad') return frames[i]!.step;
  }
  return frames[frames.length - 1]!.step;
}

function cueFor(ctx: Ctx, r: { testId: string; contestantId: string; caseId?: string }, step?: number): string {
  const t = testName(ctx, r.testId);
  const who = label(ctx, r.contestantId);
  if (step !== undefined) return `[Replay: ${t}, ${who}, step ${step}]`;
  return `[Case: ${t}, ${who}${r.caseId ? `, ${r.caseId}` : ''}]`;
}

function resultCase(ctx: Ctx, testId: string, contestantId: string, caseId: string): StudioResult | undefined {
  return (ctx.scored.get(testId)?.get(contestantId) ?? []).filter((r) => r.caseId === caseId).sort((a, b) => a.repeat - b.repeat)[0];
}

/** Mean score per case (over repeats) for one contestant on one test. */
function caseMeans(ctx: Ctx, testId: string, contestantId: string): Map<string, number> {
  const acc = new Map<string, { s: number; n: number }>();
  for (const r of ctx.scored.get(testId)?.get(contestantId) ?? []) {
    const a = acc.get(r.caseId) ?? { s: 0, n: 0 };
    a.s += r.score as number;
    a.n += 1;
    acc.set(r.caseId, a);
  }
  return new Map([...acc].map(([k, v]) => [k, v.s / v.n]));
}

// ─────────────────────────────── Detectors ───────────────────────────────

/** A cheaper model beats a pricier one on a test by at least 10 points. */
function upsets(env: Env): Highlight[] {
  const { ctx } = env;
  const out: Highlight[] = [];
  for (const t of ctx.tests) {
    const mm = ctx.mean.get(t.id);
    if (!mm) continue;
    const sp = ctx.spend.get(t.id) ?? new Map<string, number>();
    let best: { h: Highlight; mag: number } | null = null;
    for (const [a, sa] of mm) {
      if (ctx.manual.has(a)) continue;
      for (const [b, sb] of mm) {
        if (a === b || ctx.manual.has(b) || !competitor(ctx, b)) continue;
        const gap = sa - sb;
        if (gap < 0.1) continue;
        const spendA = sp.get(a) ?? 0;
        const spendB = sp.get(b) ?? 0;
        const measured = spendA > 0 && spendB > 0;
        const priceA = measured ? spendA : listPrice(ctx.byId.get(a));
        const priceB = measured ? spendB : listPrice(ctx.byId.get(b));
        const free = priceA === 0 && priceB > 0;
        if (!free && !(priceA > 0 && priceB >= 2 * priceA)) continue;
        const ratio = free ? Infinity : priceB / priceA;
        const mag = gap * 100 * 0.6 + (free ? 30 : Math.log2(ratio) * 5);
        if (best && best.mag >= mag) continue;
        // The case where the underdog's lead was biggest.
        const ca = caseMeans(ctx, t.id, a);
        const cb = caseMeans(ctx, t.id, b);
        let caseId: string | undefined;
        let caseGap = -Infinity;
        for (const [cid, v] of ca) {
          const g = v - (cb.get(cid) ?? 0);
          if (g > caseGap || (g === caseGap && caseId !== undefined && cid < caseId)) {
            caseGap = g;
            caseId = cid;
          }
        }
        const r = caseId ? resultCase(ctx, t.id, a, caseId) : undefined;
        const step = r ? replayStepOf(r, 'last') : undefined;
        const evidence: HighlightEvidence[] = [
          ev(`${label(ctx, a)} score`, sa, pts(sa)),
          ev(`${label(ctx, b)} score`, sb, pts(sb)),
          measured ? ev(`${label(ctx, a)} spent on this test`, spendA, money(spendA)) : ev(`${label(ctx, a)} list price (USD per 1M tokens, blended)`, priceA, money(priceA)),
          measured ? ev(`${label(ctx, b)} spent on this test`, spendB, money(spendB)) : ev(`${label(ctx, b)} list price (USD per 1M tokens, blended)`, priceB, money(priceB)),
        ];
        if (!free) evidence.push(ev('Price ratio', ratio, times(ratio)));
        const baselineWin = ctx.baseline.has(a);
        const why = baselineWin
          ? `The Random Baseline only guesses, yet it scored ${pts(sa)} to ${label(ctx, b)}'s ${pts(sb)}.`
          : free
            ? `${label(ctx, a)} scored ${pts(sa)} to ${label(ctx, b)}'s ${pts(sb)} and cost nothing to run.`
            : `${label(ctx, a)} scored ${pts(sa)} to ${label(ctx, b)}'s ${pts(sb)} while costing ${times(ratio)} less${measured ? ` (${money(spendA)} against ${money(spendB)})` : ' per token'}.`;
        best = {
          mag,
          h: {
            id: `upset:${t.id}:${a}:${b}`,
            type: 'upset',
            title: baselineWin ? `Random guessing beats ${label(ctx, b)} on ${t.name}` : `${label(ctx, a)} beats the pricier ${label(ctx, b)} on ${t.name}`,
            why,
            drama: 0,
            contestantIds: [a, b],
            testId: t.id,
            caseId,
            key: r?.key,
            repeat: r?.repeat,
            replayStep: step,
            link: caseLink(ctx.runId, { testId: t.id, contestantId: a, key: r?.key }, step),
            cue: cueFor(ctx, { testId: t.id, contestantId: a, caseId }, step),
            clipSec: step !== undefined ? CLIP.replay : CLIP.case,
            evidence,
            rule: 'Upset: on one test, a model with a lower spend (or list price when spend is unknown) scored at least 10 points more than a model that costs at least twice as much. The three biggest (score gap and price ratio) are kept.',
          },
        };
      }
    }
    if (best) out.push({ ...best.h, drama: clamp(55 + Math.min(45, best.mag), 0, 100) });
  }
  // The three biggest upsets only (one per test): more would crowd out every other kind of story.
  return out.sort((a, b) => b.drama - a.drama || a.id.localeCompare(b.id)).slice(0, 3);
}

/** Photo finishes: overall (Index within 3 points) and per test (within 3 points). */
function closeRaces(env: Env): Highlight[] {
  const { ctx, slides } = env;
  const out: Highlight[] = [];
  const st = standings(ctx);
  if (st.length >= 2) {
    const [a, b] = st as [(typeof st)[0], (typeof st)[0]];
    const gap = a.index - b.index;
    if (gap <= 3) {
      const row = (id: string) => ctx.input.leaderboard?.rows.find((r) => r.contestantId === id);
      const ciA = row(a.id)?.indexCi95;
      const ciB = row(b.id)?.indexCi95;
      const overlap = !!ciA && !!ciB && ciA[0] <= ciB[1] && ciB[0] <= ciA[1];
      const slide = slideOf(slides, 'final');
      out.push({
        id: `close-race:overall:${a.id}:${b.id}`,
        type: 'close-race',
        title: `Photo finish: ${label(ctx, a.id)} edges ${label(ctx, b.id)} overall`,
        why: `${label(ctx, a.id)} finished on ${idx(a.index)} and ${label(ctx, b.id)} on ${idx(b.index)}: a gap of ${idx(gap)} Index points${overlap ? ', inside the confidence intervals, so it is statistically a tie' : ''}.`,
        drama: clamp(75 + (3 - gap) * 7, 0, 100),
        contestantIds: [a.id, b.id],
        link: presenterLink(ctx.runId, slide),
        cue: slide ? `[Slide ${slide}]` : '[Final standings]',
        clipSec: CLIP.reveal,
        evidence: [ev(`${label(ctx, a.id)} Index`, a.index, idx(a.index)), ev(`${label(ctx, b.id)} Index`, b.index, idx(b.index)), ev('Gap', gap, idx(gap))],
        rule: 'Close race (overall): the top two Gauntlet Index scores are within 3 points.',
      });
    }
  }
  const perTest: Highlight[] = [];
  for (const t of ctx.tests) {
    const ranked = rankedOnTest(ctx, t.id);
    if (ranked.length < 2) continue;
    const [a, b] = ranked as [(typeof ranked)[0], (typeof ranked)[0]];
    const gap = a.score - b.score;
    if (gap > 0.03 || a.score < 0.2) continue;
    const slide = slideOf(slides, 'result', t.id);
    perTest.push({
      id: `close-race:${t.id}:${a.id}:${b.id}`,
      type: 'close-race',
      title: gap === 0 ? `Dead heat on ${t.name}` : `Neck and neck on ${t.name}`,
      why:
        gap === 0
          ? `${label(ctx, a.id)} and ${label(ctx, b.id)} both scored ${pts(a.score)} on ${t.name}.`
          : `${label(ctx, a.id)} scored ${pts(a.score)} and ${label(ctx, b.id)} ${pts(b.score)}: the result could have gone either way.`,
      drama: clamp(Math.round(45 + (0.03 - gap) * 500), 0, 100),
      contestantIds: [a.id, b.id],
      testId: t.id,
      link: presenterLink(ctx.runId, slide),
      cue: slide ? `[Slide ${slide}]` : `[Result: ${t.name}]`,
      clipSec: CLIP.stat,
      evidence: [ev(`${label(ctx, a.id)} score`, a.score, pts(a.score)), ev(`${label(ctx, b.id)} score`, b.score, pts(b.score))],
      rule: 'Close race (test): the top two models on a test are within 3 points of each other.',
    });
  }
  perTest.sort((x, y) => y.drama - x.drama || x.id.localeCompare(y.id));
  return [...out, ...perTest.slice(0, 3)];
}

/** One model wins every test; or is the only one with a perfect score on a test. */
function sweeps(env: Env): Highlight[] {
  const { ctx, slides } = env;
  const out: Highlight[] = [];
  const winners = ctx.tests
    .map((t) => {
      const r = rankedOnTest(ctx, t.id);
      return r.length >= 2 && r[0]!.score > r[1]!.score ? r[0]!.id : null;
    });
  if (ctx.tests.length >= 2 && winners.length === ctx.tests.length && winners.every((w) => w !== null && w === winners[0])) {
    const w = winners[0] as string;
    const slide = slideOf(slides, 'medals') ?? slideOf(slides, 'final');
    out.push({
      id: `clean-sweep:overall:${w}`,
      type: 'clean-sweep',
      title: `Clean sweep: ${label(ctx, w)} wins every test`,
      why: `${label(ctx, w)} took first place on ${ctx.tests.length === 2 ? 'both' : `all ${count(ctx.tests.length)}`} tests — nobody beat it anywhere.`,
      drama: 92,
      contestantIds: [w],
      link: presenterLink(ctx.runId, slide),
      cue: slide ? `[Slide ${slide}]` : '[Medal table]',
      clipSec: CLIP.reveal,
      evidence: [ev('Tests won', ctx.tests.length, String(ctx.tests.length)), ev('Tests in the run', ctx.tests.length, String(ctx.tests.length))],
      rule: 'Clean sweep: the same model has the strictly best score on every test (at least two tests).',
    });
  }
  for (const t of ctx.tests) {
    const g = ctx.scored.get(t.id);
    if (!g) continue;
    const perfect = [...g].filter(([id, list]) => competitor(ctx, id) && list.length > 0 && list.every((r) => r.score === 1)).map(([id]) => id);
    const others = [...g.keys()].filter((id) => competitor(ctx, id) && !perfect.includes(id));
    if (perfect.length !== 1 || others.length < 1) continue;
    const w = perfect[0]!;
    const n = g.get(w)!.length;
    const slide = slideOf(slides, 'result', t.id);
    out.push({
      id: `clean-sweep:${t.id}:${w}`,
      type: 'clean-sweep',
      title: `Flawless: ${label(ctx, w)} aces ${t.name}`,
      why: `${label(ctx, w)} got full marks on every attempt (${n} of ${n}) — the only model to do it.`,
      drama: 58,
      contestantIds: [w],
      testId: t.id,
      link: presenterLink(ctx.runId, slide),
      cue: slide ? `[Slide ${slide}]` : `[Result: ${t.name}]`,
      clipSec: CLIP.stat,
      evidence: [ev('Perfect attempts', n, String(n)), ev('Attempts', n, String(n))],
      rule: 'Perfect test: exactly one model scored 100 on every case and repeat of a test.',
    });
  }
  return out;
}

/** Disasters: early deaths, bankruptcies, wrong accusations, being trapped, lone zeros. */
function catastrophes(env: Env): Highlight[] {
  const { ctx } = env;
  const found: Array<{ h: Omit<Highlight, 'drama'>; severity: number }> = [];
  for (const t of ctx.tests) {
    const g = ctx.scored.get(t.id);
    if (!g) continue;
    for (const [cid, list] of g) {
      if (!competitor(ctx, cid)) continue;
      let bestForPair: { h: Omit<Highlight, 'drama'>; severity: number } | null = null;
      for (const r of list) {
        const d = (r.scoreDetail ?? {}) as Record<string, unknown>;
        const num = (k: string) => (typeof d[k] === 'number' ? (d[k] as number) : null);
        const who = label(ctx, cid);
        let title: string | null = null;
        let why = '';
        let severity = 0;
        const evidence: HighlightEvidence[] = [ev('Score', r.score as number, pts(r.score as number))];
        const deathDay = num('deathDay');
        const maxDays = num('maxDays');
        const bankruptMonth = num('bankruptMonth');
        if (d.alive === false && deathDay !== null) {
          severity = 0.55 + 0.45 * (maxDays ? 1 - deathDay / maxDays : 0.5);
          title = `${who} dies on day ${deathDay} of ${t.name}`;
          why = `${typeof d.causeOfDeath === 'string' ? `Cause of death: ${d.causeOfDeath}. ` : ''}${maxDays ? `It needed to last ${maxDays} days.` : 'Game over.'}`;
          evidence.push(ev('Died on day', deathDay, String(deathDay)));
          if (maxDays) evidence.push(ev('Days to survive', maxDays, String(maxDays)));
        } else if (d.bankrupt === true) {
          const months = num('monthsPlayed');
          severity = 0.6 + 0.4 * (bankruptMonth && months ? 1 - bankruptMonth / Math.max(months, 12) : 0.5);
          title = bankruptMonth !== null ? `${who} goes bankrupt in month ${bankruptMonth}` : `${who} goes bankrupt`;
          why = `It lost all its cash on ${t.name}. ${r.summary}`;
          if (bankruptMonth !== null) evidence.push(ev('Bankrupt in month', bankruptMonth, String(bankruptMonth)));
        } else if (d.correct === false && typeof d.accused === 'string') {
          severity = 0.7;
          title = `${who} accuses the wrong suspect`;
          why = `On ${t.name} it named ${d.accused}${typeof d.culprit === 'string' ? `, but the culprit was ${d.culprit}` : ''}.`;
        } else if (d.escaped === false) {
          const opened = num('locksOpened');
          const total = num('totalLocks');
          severity = 0.5 + 0.4 * (opened !== null && total ? 1 - opened / total : 0.5);
          title = `${who} never escapes`;
          why = `${r.summary}.`;
          if (opened !== null && total) {
            evidence.push(ev('Locks opened', opened, String(opened)), ev('Locks in total', total, String(total)));
          }
        } else if (t.kind === 'program' && (r.score as number) <= 0.05) {
          severity = 0.55;
          title = `${who} crashes out of ${t.name}`;
          why = `${r.summary}.`;
        } else if (t.kind === 'prompt' && r.score === 0) {
          // A lone zero: everyone else nailed this exact case.
          const others = [...g].filter(([id]) => id !== cid && competitor(ctx, id)).map(([, l]) => l.filter((x) => x.caseId === r.caseId));
          const flat = others.flat();
          const otherMean = flat.length ? flat.reduce((s, x) => s + (x.score as number), 0) / flat.length : 0;
          if (others.filter((l) => l.length).length >= 2 && otherMean >= 0.8) {
            severity = 0.1 + 0.1 * otherMean;
            title = `${who} is the only one to fail a question on ${t.name}`;
            why = `Every other model averaged ${pts(otherMean)} on this exact question; ${who} scored zero.`;
            evidence.push(ev('Everyone else (mean)', otherMean, pts(otherMean)));
          }
        }
        if (!title) continue;
        if ((r.score as number) > 0.3) continue;
        const step = replayStepOf(r, 'bad');
        const h: Omit<Highlight, 'drama'> = {
          id: `catastrophe:${t.id}:${cid}`,
          type: 'catastrophe',
          title,
          why: why.replace(/\.\.$/, '.'),
          contestantIds: [cid],
          testId: t.id,
          caseId: r.caseId,
          key: r.key,
          repeat: r.repeat,
          replayStep: step,
          link: caseLink(ctx.runId, r, step),
          cue: cueFor(ctx, r, step),
          clipSec: step !== undefined ? CLIP.replay : CLIP.case,
          evidence,
          rule: 'Catastrophic failure: a simulation ended in death, bankruptcy, a wrong accusation or being trapped (earlier = worse), or a model scored zero on a question everyone else averaged at least 80 on.',
        };
        if (!bestForPair || severity > bestForPair.severity || (severity === bestForPair.severity && r.key < (bestForPair.h.key ?? ''))) bestForPair = { h, severity };
      }
      if (bestForPair) found.push(bestForPair);
    }
  }
  found.sort((a, b) => b.severity - a.severity || a.h.id.localeCompare(b.h.id));
  return found.slice(0, 4).map(({ h, severity }) => ({ ...h, drama: clamp(Math.round(62 + severity * 35), 0, 100) }));
}

/** Judge said the answer was invented / wrong (Honesty tests), or a confident wrong final answer. */
function confidentWrong(env: Env): Highlight[] {
  const { ctx } = env;
  const judged: Array<{ h: Omit<Highlight, 'drama'>; power: number }> = [];
  const exact: Array<{ h: Omit<Highlight, 'drama'>; power: number }> = [];
  for (const t of ctx.tests) {
    const g = ctx.scored.get(t.id);
    if (!g) continue;
    const honesty = t.category === 'honesty';
    for (const [cid, list] of g) {
      if (!competitor(ctx, cid)) continue;
      for (const r of list) {
        if ((r.score as number) > 0.25 || r.status !== 'ok') continue;
        const who = label(ctx, cid);
        const judges = r.scoreDetail?.judge ?? [];
        const bad = judges.filter((j) => /^(hallucinated|wrong)$|fabricat|invent/i.test(j.label ?? ''));
        if (judges.length && bad.length * 2 > judges.length) {
          const hallucinated = bad.some((j) => /halluc|fabricat|invent/i.test(j.label ?? ''));
          const rationale = (bad[0]!.rationale ?? '').replace(/\s+/g, ' ').trim();
          const quote = rationale.length > 170 ? `${rationale.slice(0, 167).replace(/\s+\S*$/, '')}…` : rationale;
          judged.push({
            power: (hallucinated ? 2 : 1) + bad.length * 0.5 + (honesty ? 2 : 0),
            h: {
              id: `confident-wrong:${r.key}`,
              type: 'confident-wrong',
              title: hallucinated ? `${who} falls for a trap on ${t.name}` : `${who} is confidently wrong on ${t.name}`,
              why: `${hallucinated ? 'It answered a trick question as if it were real' : 'It gave a wrong answer with no hedging'}. The judges: "${quote}"`,
              contestantIds: [cid],
              testId: t.id,
              caseId: r.caseId,
              key: r.key,
              repeat: r.repeat,
              link: caseLink(ctx.runId, r),
              cue: cueFor(ctx, r),
              clipSec: CLIP.case,
              evidence: [ev('Judges who flagged it', bad.length, String(bad.length)), ev('Judges', judges.length, String(judges.length)), ev('Score', r.score as number, pts(r.score as number))],
              rule: 'Confident wrong answer: most judges labelled the reply as hallucinated / wrong / fabricated (Honesty tests rank highest).',
            },
          });
          continue;
        }
        const extracted = typeof r.scoreDetail?.extracted === 'string' ? r.scoreDetail.extracted.trim() : '';
        const expected = r.scoreDetail?.expected;
        const expectedText = Array.isArray(expected) ? String(expected[0] ?? '') : typeof expected === 'string' || typeof expected === 'number' ? String(expected) : '';
        if (r.score === 0 && r.scoreDetail?.formatOk === true && extracted && expectedText && extracted.length <= 40 && expectedText.length <= 40) {
          exact.push({
            power: 1,
            h: {
              id: `confident-wrong:${r.key}`,
              type: 'confident-wrong',
              title: `${who} answers "${extracted}" with total confidence`,
              why: `The correct answer to this ${t.name} question was "${expectedText}". No hedging, no partial credit.`,
              contestantIds: [cid],
              testId: t.id,
              caseId: r.caseId,
              key: r.key,
              repeat: r.repeat,
              link: caseLink(ctx.runId, r),
              cue: cueFor(ctx, r),
              clipSec: CLIP.case,
              evidence: [ev('Score', 0, '0')],
              rule: 'Confident wrong answer: a clean, well-formatted final answer that was simply wrong.',
            },
          });
        }
      }
    }
  }
  judged.sort((a, b) => b.power - a.power || a.h.id.localeCompare(b.h.id));
  exact.sort((a, b) => a.h.id.localeCompare(b.h.id));
  // Spread picks across models: at most one per contestant per test.
  const seen = new Set<string>();
  const pick = (list: typeof judged, n: number, base: number) => {
    const out: Highlight[] = [];
    for (const x of list) {
      const k = `${x.h.contestantIds[0]}|${x.h.testId}`;
      if (seen.has(k)) continue;
      seen.add(k);
      out.push({ ...x.h, drama: clamp(Math.round(base + x.power * 5), 0, 100) });
      if (out.length >= n) break;
    }
    return out;
  };
  return [...pick(judged, 3, 50), ...pick(exact, 2, 38)];
}

/** Fastest correct answer on a test compared to the median correct answer. */
function fastest(env: Env): Highlight[] {
  const { ctx } = env;
  const found: Array<{ h: Omit<Highlight, 'drama'>; ratio: number }> = [];
  for (const t of ctx.tests) {
    if (t.kind !== 'prompt') continue;
    const correct = [...(ctx.scored.get(t.id) ?? [])]
      .filter(([id]) => competitor(ctx, id) && !ctx.manual.has(id))
      .flatMap(([, l]) => l)
      .filter((r) => (r.score as number) >= 0.999 && r.metrics?.wallMs > 0);
    if (correct.length < 3 || new Set(correct.map((r) => r.contestantId)).size < 2) continue;
    const sorted = [...correct].sort((a, b) => a.metrics.wallMs - b.metrics.wallMs || a.key.localeCompare(b.key));
    const f = sorted[0]!;
    const median = sorted[Math.floor(sorted.length / 2)]!.metrics.wallMs;
    const ratio = median / f.metrics.wallMs;
    if (ratio < 1.5) continue;
    found.push({
      ratio,
      h: {
        id: `fastest-correct:${t.id}:${f.contestantId}`,
        type: 'fastest-correct',
        title: `${label(ctx, f.contestantId)} nails a ${t.name} question in ${secs(f.metrics.wallMs)}`,
        why: `The typical correct answer took ${secs(median)}; this one was ${times(ratio)} faster and still right.`,
        contestantIds: [f.contestantId],
        testId: t.id,
        caseId: f.caseId,
        key: f.key,
        repeat: f.repeat,
        link: caseLink(ctx.runId, f),
        cue: cueFor(ctx, f),
        clipSec: CLIP.stat,
        evidence: [ev('Time to answer', f.metrics.wallMs, secs(f.metrics.wallMs)), ev('Median correct answer', median, secs(median)), ev('Speed-up', ratio, times(ratio))],
        rule: 'Fastest correct answer: the quickest full-marks answer on a test, at least 1.5× faster than the median full-marks answer (API models only).',
      },
    });
  }
  found.sort((a, b) => b.ratio - a.ratio || a.h.id.localeCompare(b.h.id));
  return found.slice(0, 2).map(({ h, ratio }) => ({ ...h, drama: clamp(Math.round(35 + Math.log2(ratio) * 8), 0, 100) }));
}

/** The most expensive answer that scored (almost) nothing. */
function expensiveWrong(env: Env, claimed: Set<string>): Highlight[] {
  const { ctx } = env;
  const all = ctx.input.results.filter(
    (r) => !claimed.has(r.key) && r.status === 'ok' && typeof r.score === 'number' && r.score <= 0.2 && competitor(ctx, r.contestantId) && !ctx.manual.has(r.contestantId) && (r.metrics?.costUsd ?? 0) > 0 && ctx.testById.has(r.testId),
  );
  all.sort((a, b) => b.metrics.costUsd - a.metrics.costUsd || a.key.localeCompare(b.key));
  const out: Highlight[] = [];
  const seen = new Set<string>();
  for (const r of all) {
    if (seen.has(r.contestantId)) continue;
    seen.add(r.contestantId);
    const cheapestRight = ctx.input.results
      .filter((x) => x.testId === r.testId && x.caseId === r.caseId && x.contestantId !== r.contestantId && (x.score ?? 0) >= 0.999 && !ctx.manual.has(x.contestantId))
      .sort((a, b) => a.metrics.costUsd - b.metrics.costUsd || a.key.localeCompare(b.key))[0];
    const t = testName(ctx, r.testId);
    const step = replayStepOf(r, 'bad');
    const evidence = [ev('Cost of this answer', r.metrics.costUsd, money(r.metrics.costUsd)), ev('Score', r.score as number, pts(r.score as number))];
    let why = `It spent ${money(r.metrics.costUsd)} on this one ${t} case and scored ${pts(r.score as number)}.`;
    let bonus = 0;
    if (cheapestRight) {
      evidence.push(ev(`${label(ctx, cheapestRight.contestantId)} got it right for`, cheapestRight.metrics.costUsd, money(cheapestRight.metrics.costUsd)));
      why += ` ${label(ctx, cheapestRight.contestantId)} got the same case right for ${money(cheapestRight.metrics.costUsd)}.`;
      bonus = 10;
    }
    out.push({
      id: `expensive-wrong:${r.key}`,
      type: 'expensive-wrong',
      title: `The ${money(r.metrics.costUsd)} wrong answer from ${label(ctx, r.contestantId)}`,
      why,
      drama: clamp(Math.round(42 + bonus + Math.min(20, Math.log10(1 + r.metrics.costUsd * 1000) * 6)), 0, 100),
      contestantIds: [r.contestantId],
      testId: r.testId,
      caseId: r.caseId,
      key: r.key,
      repeat: r.repeat,
      replayStep: step,
      link: caseLink(ctx.runId, r, step),
      cue: cueFor(ctx, r, step),
      clipSec: step !== undefined ? CLIP.replay : CLIP.case,
      evidence,
      rule: 'Most expensive wrong answer: the costliest single case that scored 20 or less (API models only; one per model; cases already featured as another moment are skipped).',
    });
    if (out.length >= 2) break;
  }
  return out;
}

/** Most Index points per dollar, against the winner's value. */
function bestValue(env: Env): Highlight[] {
  const { ctx, slides } = env;
  const st = standings(ctx).filter((s) => s.costUsd > 0 && !ctx.manual.has(s.id));
  if (st.length < 2) return [];
  const value = (s: (typeof st)[0]) => s.index / s.costUsd;
  const best = [...st].sort((a, b) => value(b) - value(a) || a.id.localeCompare(b.id))[0]!;
  const top = st[0]!;
  const ppd = (v: number) => (v >= 100 ? String(Math.round(v)) : v.toFixed(1));
  const slide = slideOf(slides, 'scatter');
  const evidence = [ev(`${label(ctx, best.id)} Index`, best.index, idx(best.index)), ev(`${label(ctx, best.id)} total spend`, best.costUsd, money(best.costUsd)), ev('Index points per dollar', value(best), ppd(value(best)))];
  let why: string;
  let drama = 55;
  if (best.id === top.id) {
    why = `The winner is also the best value: ${idx(best.index)} on the Index for ${money(best.costUsd)} in total.`;
  } else {
    const ratio = value(best) / value(top);
    evidence.push(ev(`${label(ctx, top.id)} points per dollar`, value(top), ppd(value(top))), ev('Value ratio', ratio, times(ratio)));
    why = `${label(ctx, best.id)} scored ${idx(best.index)} for ${money(best.costUsd)}; the winner ${label(ctx, top.id)} scored ${idx(top.index)} for ${money(top.costUsd)}. Per dollar, that is ${times(ratio)} the value.`;
    drama = clamp(Math.round(50 + Math.log2(ratio) * 6), 0, 85);
  }
  return [
    {
      id: `best-value:${best.id}`,
      type: 'best-value',
      title: `Best value: ${label(ctx, best.id)} at ${ppd(value(best))} points per dollar`,
      why,
      drama,
      contestantIds: best.id === top.id ? [best.id] : [best.id, top.id],
      link: presenterLink(ctx.runId, slide),
      cue: slide ? `[Slide ${slide}]` : '[Score vs cost]',
      clipSec: CLIP.stat,
      evidence,
      rule: 'Best value: highest Gauntlet Index points per dollar of contestant spend (API models with a cost only).',
    },
  ];
}

/** Same model, same question, very different results across repeats. */
function inconsistency(env: Env): Highlight[] {
  const { ctx } = env;
  if ((ctx.input.manifest.settings?.repeats ?? 1) < 2) return [];
  const found: Array<{ h: Omit<Highlight, 'drama'>; gap: number }> = [];
  for (const t of ctx.tests) {
    for (const [cid, list] of ctx.scored.get(t.id) ?? []) {
      if (!competitor(ctx, cid)) continue;
      const byCase = new Map<string, StudioResult[]>();
      for (const r of list) byCase.set(r.caseId, [...(byCase.get(r.caseId) ?? []), r]);
      let best: { gap: number; hi: StudioResult; lo: StudioResult } | null = null;
      for (const rs of byCase.values()) {
        if (rs.length < 2) continue;
        const sorted = [...rs].sort((a, b) => (b.score as number) - (a.score as number) || a.repeat - b.repeat);
        const hi = sorted[0]!;
        const lo = sorted[sorted.length - 1]!;
        const gap = (hi.score as number) - (lo.score as number);
        if (!best || gap > best.gap || (gap === best.gap && lo.key < best.lo.key)) best = { gap, hi, lo };
      }
      if (!best || best.gap < 0.5) continue;
      const who = label(ctx, cid);
      const step = replayStepOf(best.lo, 'bad');
      found.push({
        gap: best.gap,
        h: {
          id: `inconsistent:${t.id}:${cid}`,
          type: 'inconsistent',
          title: `Same question, different ${who}`,
          why: `On one ${t.name} case, ${who} scored ${pts(best.hi.score as number)} on one attempt and ${pts(best.lo.score as number)} on another — identical prompt, identical settings.`,
          contestantIds: [cid],
          testId: t.id,
          caseId: best.lo.caseId,
          key: best.lo.key,
          repeat: best.lo.repeat,
          replayStep: step,
          link: caseLink(ctx.runId, best.lo, step),
          cue: cueFor(ctx, best.lo, step),
          clipSec: step !== undefined ? CLIP.replay : CLIP.case,
          evidence: [ev('Best attempt', best.hi.score as number, pts(best.hi.score as number)), ev('Worst attempt', best.lo.score as number, pts(best.lo.score as number)), ev('Gap', best.gap, pts(best.gap))],
          rule: 'Inconsistency: the biggest score gap between repeats of the same case by the same model (at least 50 points).',
        },
      });
    }
  }
  found.sort((a, b) => b.gap - a.gap || a.h.id.localeCompare(b.h.id));
  return found.slice(0, 2).map(({ h, gap }) => ({ ...h, drama: clamp(Math.round(40 + gap * 35), 0, 100) }));
}

/** Generic fallback for any test type: who won it and by how much. */
function testWins(env: Env): Highlight[] {
  const { ctx, slides } = env;
  const out: Highlight[] = [];
  for (const t of ctx.tests) {
    const ranked = rankedOnTest(ctx, t.id);
    if (!ranked.length) continue;
    const a = ranked[0]!;
    const b = ranked[1];
    const margin = b ? a.score - b.score : 0;
    if (b && margin <= 0) continue;
    const slide = slideOf(slides, 'result', t.id);
    out.push({
      id: `big-win:${t.id}:${a.id}`,
      type: 'big-win',
      title: b ? `${label(ctx, a.id)} wins ${t.name} by ${pts(margin)} points` : `${label(ctx, a.id)} scores ${pts(a.score)} on ${t.name}`,
      why: b ? `${label(ctx, a.id)} scored ${pts(a.score)}; the runner-up, ${label(ctx, b.id)}, scored ${pts(b.score)}.` : `The only model with a result on this test.`,
      drama: clamp(Math.round(25 + margin * 60), 0, 70),
      contestantIds: b ? [a.id, b.id] : [a.id],
      testId: t.id,
      link: presenterLink(ctx.runId, slide),
      cue: slide ? `[Slide ${slide}]` : `[Result: ${t.name}]`,
      clipSec: CLIP.stat,
      evidence: b ? [ev(`${label(ctx, a.id)} score`, a.score, pts(a.score)), ev(`${label(ctx, b.id)} score`, b.score, pts(b.score)), ev('Margin', margin, pts(margin))] : [ev('Score', a.score, pts(a.score))],
      rule: 'Test winner: the best mean score on a test and its margin over the runner-up (works for every test type).',
    });
  }
  return out;
}

// ─────────────────────────────── Entry point ───────────────────────────────

export function findHighlights(input: StudioInput, slides: SlideRef[] = presenterSlides(input)): Highlight[] {
  const env: Env = { ctx: buildCtx(input), slides };
  const stories = [...catastrophes(env), ...upsets(env), ...closeRaces(env), ...sweeps(env), ...confidentWrong(env), ...fastest(env), ...inconsistency(env)];
  const claimed = new Set(stories.map((h) => h.key).filter((k): k is string => !!k));
  const all = [...stories, ...expensiveWrong(env, claimed), ...bestValue(env), ...testWins(env)];
  // One moment per clip: when two picks point at the same case, keep the more dramatic one.
  const seen = new Set<string>();
  return all
    .map((h) => ({ ...h, drama: Math.round(h.drama) }))
    .sort((a, b) => b.drama - a.drama || a.id.localeCompare(b.id))
    .filter((h) => {
      const k = h.key ?? h.id;
      if (seen.has(k) || seen.has(h.id)) return false;
      seen.add(k).add(h.id);
      return true;
    });
}

export const HIGHLIGHT_LABELS: Record<Highlight['type'], string> = {
  upset: 'Upset',
  'close-race': 'Close race',
  'clean-sweep': 'Clean sweep',
  catastrophe: 'Catastrophe',
  'confident-wrong': 'Confidently wrong',
  'fastest-correct': 'Fastest correct',
  'expensive-wrong': 'Priciest miss',
  'best-value': 'Best value',
  inconsistent: 'Inconsistent',
  'big-win': 'Test winner',
};
