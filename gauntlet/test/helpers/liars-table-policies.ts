/**
 * Scripted Liar's Table players used by tests and difficulty reports.
 *
 *  - oracle: knows the ground truth; exposes the lie in two questions.
 *  - sweep:  a rigid, non-adaptive detective that reasons ONLY from what it has
 *            asked: it reads every evidence file, asks suspects for their alibi
 *            in list order, then accuses the first suspect whose unhedged claim
 *            at a possible theft slot is contradicted by camera or witness
 *            evidence (otherwise it guesses). It shows how far a fixed routine
 *            gets without adaptive cross-examination.
 *  - examiner: an adaptive, reasonably smart detective. It reads the door log,
 *            CCTV and witness, then asks every suspect not already placed by
 *            evidence where they were at each possible theft slot, checks the
 *            receipts when someone claims a purchase, and accuses as soon as a
 *            claim is contradicted (by evidence, receipts, or a named companion
 *            who says otherwise). Hedged statements are ignored.
 */
import type { Responder } from './fake-model.ts';
import { candidateTheftSlots, claimedCompanions, claimedPurchaseSlots, EVIDENCE_IDS, slotsOf } from '../../src/programs/lib/liars-table-world.ts';
import type { LtWorld } from '../../src/programs/lib/liars-table-world.ts';

export function oraclePolicy(w: LtWorld): Responder {
  const t = slotsOf(w)[w.theftSlot]!;
  const src = w.contradiction.sources[0]!;
  const evidence = ['CCTV', 'WITNESS', 'RECEIPT'].includes(src);
  const script = [
    `ACTION: ASK ${w.culprit} ABOUT ${t}`,
    evidence ? `ACTION: CHECK ${src}` : `ACTION: ASK ${src} ABOUT ${t}`,
    `The claim is contradicted.\nACTION: ACCUSE ${w.culprit} BECAUSE at ${t} ${w.culprit} claimed to be in the ${w.claimedRoom}, but ${evidence ? `the ${src}` : src} shows ${w.culprit} was not there.`,
  ];
  return (_s, _u, _h, info) => script[Math.min(info.index, script.length - 1)]!;
}

/** Suspects whose unhedged claim at a possible theft slot contradicts evidence the player has seen. */
function contradicted(w: LtWorld, askedAlibi: string[], checked: Set<string>): Array<{ name: string; slot: number; source: string }> {
  const out: Array<{ name: string; slot: number; source: string }> = [];
  const inRoom = (room: string, slot: number) => w.suspects.map((s) => s.name).filter((n) => w.truth[n]![slot] === room);
  for (const name of askedAlibi) {
    for (const slot of candidateTheftSlots(w)) {
      if (w.hedged[name]![slot]) continue;
      const room = w.claims[name]![slot]!;
      const cams = [w.roles.cam1, w.roles.cam2].filter((c) => !(w.cctvGap.room === c && w.cctvGap.slot === slot));
      if (checked.has('CCTV') && cams.includes(room) && !inRoom(room, slot).includes(name)) out.push({ name, slot, source: 'CCTV' });
      else if (checked.has('WITNESS') && room === w.roles.witness && !inRoom(room, slot).includes(name)) out.push({ name, slot, source: 'WITNESS' });
    }
  }
  return out;
}

export function sweepPolicy(w: LtWorld, budget: number): Responder {
  const plan = [...EVIDENCE_IDS.map((e) => `CHECK ${e}`), ...w.suspects.map((s) => `ASK ${s.name} ABOUT ALIBI`)].slice(0, budget);
  return (_s, _u, _h, info) => {
    if (info.index < plan.length) return `ACTION: ${plan[info.index]}`;
    const asked = plan.slice(0, info.index);
    const checked = new Set(asked.filter((c) => c.startsWith('CHECK ')).map((c) => c.slice(6)));
    const alibis = asked.filter((c) => c.endsWith('ABOUT ALIBI')).map((c) => c.split(' ')[1]!);
    const hit = contradicted(w, alibis, checked)[0];
    if (hit) {
      const t = slotsOf(w)[hit.slot]!;
      return `ACTION: ACCUSE ${hit.name} BECAUSE at ${t} ${hit.name} claimed the ${w.claims[hit.name]![hit.slot]} but the ${hit.source} shows otherwise`;
    }
    const unasked = w.suspects.map((s) => s.name).find((n) => !alibis.includes(n)) ?? w.suspects[0]!.name;
    return `ACTION: ACCUSE ${unasked} BECAUSE nobody else is contradicted`;
  };
}

export function examinerPolicy(w: LtWorld, budget: number): Responder {
  const names = w.suspects.map((x) => x.name);
  const slots = candidateTheftSlots(w);
  const label = (s: number) => slotsOf(w)[s]!;
  const occupants = (room: string, s: number) => names.filter((n) => w.truth[n]![s] === room);
  const covered = (room: string, s: number, checked: Set<string>) =>
    (checked.has('CCTV') && [w.roles.cam1, w.roles.cam2].includes(room) && !(w.cctvGap.room === room && w.cctvGap.slot === s)) ||
    (checked.has('WITNESS') && room === w.roles.witness);
  const checked = new Set<string>();
  const answers: Array<{ who: string; slot: number }> = [];
  const queue: string[] = ['CHECK DOOR LOG', 'CHECK CCTV', 'CHECK WITNESS'];
  let planned = false;
  let used = 0;

  const findLiar = (): { who: string; slot: number; why: string } | null => {
    for (const { who, slot } of answers) {
      const comp = claimedCompanions(w, who, slot);
      if (comp === null) continue;
      const room = w.claims[who]![slot]!;
      if (covered(room, slot, checked) && !occupants(room, slot).includes(who)) return { who, slot, why: checked.has('CCTV') && room !== w.roles.witness ? 'the CCTV' : 'the witness' };
      if (checked.has('RECEIPT') && claimedPurchaseSlots(w, who).includes(slot) && !w.purchases.some((p) => p.name === who && p.slot === slot)) return { who, slot, why: 'the receipts' };
      for (const c of comp) {
        const other = answers.find((a) => a.who === c && a.slot === slot);
        const theirs = other ? claimedCompanions(w, c, slot) : null;
        if (other && theirs !== null && (w.claims[c]![slot] !== room || !theirs.includes(who))) return { who, slot, why: c };
      }
    }
    return null;
  };

  return () => {
    const liar = findLiar();
    const left = budget - used;
    if (liar || left <= 0 || (planned && queue.length === 0)) {
      if (liar) {
        const room = w.claims[liar.who]![liar.slot]!;
        return `ACTION: ACCUSE ${liar.who} BECAUSE at ${label(liar.slot)} ${liar.who} claimed the ${room}, but ${liar.why} says otherwise`;
      }
      const placedEverywhere = (n: string) => slots.every((s) => answers.some((a) => a.who === n && a.slot === s));
      return `ACTION: ACCUSE ${names.find((n) => !placedEverywhere(n)) ?? names[0]} BECAUSE unaccounted for`;
    }
    if (!planned && queue.length === 0) {
      planned = true;
      for (const s of slots) {
        for (const n of names) {
          const seen = occupants(w.truth[n]![s]!, s).includes(n) && covered(w.truth[n]![s]!, s, checked);
          if (!seen) queue.push(`ASK ${n} ABOUT ${label(s)}`);
        }
      }
    }
    const cmd = queue.shift()!;
    used++;
    if (cmd.startsWith('CHECK ')) checked.add(cmd.slice(6));
    const m = cmd.match(/^ASK (\w+) ABOUT (.+)$/);
    if (m) {
      const slot = slotsOf(w).indexOf(m[2]!);
      answers.push({ who: m[1]!, slot });
      if (!checked.has('RECEIPT') && claimedPurchaseSlots(w, m[1]!).includes(slot)) queue.unshift('CHECK RECEIPT');
    }
    return `ACTION: ${cmd}`;
  };
}
