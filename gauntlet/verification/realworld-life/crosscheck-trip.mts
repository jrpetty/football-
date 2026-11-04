/**
 * Cross-check: the app's checker (src/scoring/verifiers/trip.ts) and this folder's solver must agree on every
 * itinerary the timetable allows: same verdict (workable or not) and the same price, for every combination of
 * hotel nights. Run: node verification/realworld-life/crosscheck-trip.mts
 */
import { checkTrip } from '../../src/scoring/verifiers/trip.ts';
import { buildTripCases, evaluate, planAnswer, sequences } from './trip.mts';

export function crosscheckTrip(limitPerCase = 6000): { checked: number; mismatches: string[] } {
  let checked = 0;
  const mismatches: string[] = [];
  for (const c of buildTripCases()) {
    const w = c.world;
    const seqs = sequences(w, {}, 12, 1500);
    const hotelOpts: Array<Array<string | null>> = [[]];
    for (let n = 0; n < w.nights.length; n++) {
      const next: Array<Array<string | null>> = [];
      for (const o of hotelOpts) for (const h of [null, ...w.hotels.map((x) => x.id)]) next.push([...o, h]);
      hotelOpts.splice(0, hotelOpts.length, ...next);
    }
    let n = 0;
    outer: for (const steps of seqs) {
      for (const hotels of hotelOpts) {
        if (++n > limitPerCase) break outer;
        const mine = evaluate(w, steps, hotels);
        const ans = planAnswer(w, { steps, hotels, cost: mine ?? 0 });
        const r = checkTrip(w, ans);
        checked++;
        const ok = r.feasible ? r.value : null;
        if (ok !== mine) mismatches.push(`${c.id}: ${JSON.stringify(ans)} solver=${mine} checker=${ok} ${r.items.filter((i) => !i.passed).map((i) => `${i.label}: ${i.detail}`).join(' | ')}`);
      }
    }
  }
  return { checked, mismatches };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const r = crosscheckTrip();
  console.log(`checked ${r.checked}, mismatches ${r.mismatches.length}`);
  for (const m of r.mismatches.slice(0, 20)) console.log(m);
}
