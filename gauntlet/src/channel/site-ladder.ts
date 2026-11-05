/**
 * The public site's difficulty-ladder pictures, as static SVG (no script): a small ten-step staircase for a height
 * in a table cell, and "the climb" for a ladder test's page — ten steps, each harder than the last, every model a
 * coloured token standing on the step of its height (src/core/ladders.ts), the step above the leader marked as the
 * wall nobody climbed to. Only the recorded heights are drawn.
 */
import { esc } from './site-charts.ts';

/** "Kite 2.5 Reasoner" → "K2.5", "Sable Large" → "SL" (as on the app's tokens). */
export function siteInitials(label: string): string {
  const words = label.replace(/[()]/g, ' ').split(/[\s-]+/).filter(Boolean);
  const pick = words.find((w) => /\d/.test(w));
  const first = words[0]?.[0]?.toUpperCase() ?? '?';
  return pick ? `${first}${pick.replace(/[^\d.]/g, '').slice(0, 3)}` : words.slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');
}

/** A ten-step staircase for one height (a mean like 4.2 fills that step partly), the model as a dot on its top step. */
export function miniStairsSvg(height: number | null, levels = 10, color = 'var(--accent)'): string {
  const W = 7;
  const H = 26;
  const stepH = (i: number) => 4 + ((i + 1) / levels) * (H - 10);
  const steps: string[] = [];
  for (let i = 0; i < levels; i++) {
    const h = stepH(i);
    const fill = height === null ? 0 : Math.max(0, Math.min(1, height - i));
    steps.push(`<rect x="${i * W + 0.5}" y="${(H - h).toFixed(1)}" width="${W - 1.5}" height="${h.toFixed(1)}" rx="1" class="st"/>`);
    if (fill > 0) steps.push(`<rect x="${i * W + 0.5}" y="${(H - h * fill).toFixed(1)}" width="${W - 1.5}" height="${(h * fill).toFixed(1)}" rx="1" fill="${esc(color)}"/>`);
  }
  const whole = height === null ? 0 : Math.floor(height + 1e-9);
  if (whole > 0) steps.push(`<circle cx="${((whole - 1) * W + (W - 1) / 2 + 0.25).toFixed(2)}" cy="${(H - stepH(whole - 1) - 3.2).toFixed(1)}" r="2.6" fill="${esc(color)}"/>`);
  const label = height === null ? 'not run' : `height ${height} of ${levels}`;
  return `<svg class="stairs" viewBox="0 0 ${levels * W} ${H}" role="img" aria-label="${esc(label)}">${steps.join('')}</svg>`;
}

export interface ClimbModel {
  label: string;
  color: string;
  height: number;
  baseline?: boolean;
}

/** The climb for one ladder test: a staircase of `levels` steps with every model's token on the step of its height. */
export function climbSvg(models: readonly ClimbModel[], levels = 10): string {
  const stepW = 54;
  const rise = 17;
  const tok = 11;
  const real = models.filter((m) => !m.baseline);
  const top = Math.max(0, ...real.map((m) => m.height));
  const wall = real.length && top < levels ? top + 1 : null;
  const by = new Map<number, ClimbModel[]>();
  for (const m of models) by.set(m.height, [...(by.get(m.height) ?? []), m]);
  const stack = Math.max(1, ...[...by.values()].map((g) => g.length));
  const W = 20 + (levels + 1) * stepW;
  const baseY = 30 + levels * rise + stack * (tok * 2 + 3) + 8;
  const H = baseY + 22;
  const x0 = (i: number) => 10 + i * stepW;
  const topY = (i: number) => baseY - 6 - i * rise;
  const out: string[] = [];
  for (let i = 0; i <= levels; i++) {
    const cls = i === 0 ? 'ground' : i === wall ? 'wall' : wall !== null && i > wall ? 'beyond' : 'step';
    out.push(`<rect class="${cls}" x="${x0(i) + 1}" y="${topY(i)}" width="${stepW - 2}" height="${baseY - topY(i)}" rx="3"/>`);
    out.push(`<text class="lv" x="${x0(i) + stepW / 2}" y="${baseY + 15}">${i === 0 ? 'Start' : `L${i}`}</text>`);
  }
  if (wall !== null) out.push(`<text class="wall-t" x="${x0(wall) + stepW / 2}" y="${topY(wall) - 6}">the wall</text>`);
  const flagX = x0(levels) + stepW / 2;
  const flagTop = topY(levels) - (by.get(levels)?.length ?? 0) * (tok * 2 + 3) - 30;
  out.push(`<path class="flag${top >= levels ? ' reached' : ''}" d="M${flagX} ${flagTop + 26}v-24h14l-4 5 4 5h-14"/>`);
  for (const [lv, group] of by) {
    group.forEach((m, k) => {
      const cx = x0(lv) + stepW / 2;
      const cy = topY(lv) - tok - 2 - k * (tok * 2 + 3);
      out.push(`<g><title>${esc(m.baseline ? 'Random guessing' : m.label)}: level ${m.height} of ${levels}</title><circle cx="${cx}" cy="${cy}" r="${tok}" fill="${esc(m.baseline ? 'var(--surface-2)' : m.color)}" class="${m.baseline ? 'tok base' : 'tok'}"/><text class="tok-t" x="${cx}" y="${cy + 3.2}">${esc(m.baseline ? '?' : siteInitials(m.label))}</text></g>`);
    });
  }
  const alt = real.length ? real.map((m) => `${m.label} reached level ${m.height} of ${levels}`).join('; ') : 'No results yet';
  return `<svg class="climb" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(`The climb: ${alt}`)}">${out.join('')}</svg>`;
}

/** The climb with its key: who is which token, and each model's height. */
export function climbBlock(models: readonly ClimbModel[], levels = 10): string {
  const sorted = [...models].sort((a, b) => Number(!!a.baseline) - Number(!!b.baseline) || b.height - a.height);
  const key = sorted
    .map((m) => `<li><i style="background:${esc(m.baseline ? 'var(--muted)' : m.color)}"></i>${esc(m.baseline ? 'Random guessing' : m.label)} <b>${m.height === 0 ? (m.baseline ? 'never left the ground' : 'L0') : `L${m.height}`}</b></li>`)
    .join('');
  return `<div class="climb-wrap"><div class="scroll">${climbSvg(models, levels)}</div><ul class="climb-key">${key}</ul></div>`;
}
