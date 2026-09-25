/**
 * The Liar's Table — visual replay data (ReplayData.sim / ReplayFrame.sim).
 *
 * Each answer is broken down into the facts it states ("Ada was in the
 * Library at 9:00, according to Victor"; "the CCTV shows Silas was NOT in
 * the Gallery at 8:30") so the replay can fill a suspects × time board and
 * highlight a contradiction the moment the model's questions expose it.
 * The facts mirror the answer texts in liars-table-world.ts exactly; none of
 * this is ever shown to a model.
 */
import type { LiarsFact, LiarsSimFrame, LiarsSimWorld } from '../../core/types.ts';
import {
  candidateTheftSlots,
  claimedCompanions,
  claimedPurchaseSlots,
  claimedSightings,
  heardDoorSlots,
  slotsOf,
} from './liars-table-world.ts';
import type { EvidenceId, LtWorld } from './liars-table-world.ts';
import type { LtCommand } from './liars-table-parse.ts';

export function liarsSimWorld(w: LtWorld, budget: number): LiarsSimWorld {
  return {
    kind: 'liars',
    host: w.host,
    venue: w.venue,
    object: w.object,
    objectRoom: w.objectRoom,
    rooms: [w.objectRoom, ...w.rooms],
    bar: w.roles.bar,
    slots: slotsOf(w),
    suspects: w.suspects.map((s) => ({ name: s.name, blurb: s.blurb })),
    budget,
    culprit: w.culprit,
    theftSlot: w.theftSlot,
    claimedRoom: w.claimedRoom,
    truth: Object.fromEntries(Object.entries(w.truth).map(([k, v]) => [k, v.slice(0, w.slotCount)])),
    contradictionSources: w.contradiction.sources.slice(),
  };
}

const names = (w: LtWorld): string[] => w.suspects.map((s) => s.name);

/** Everyone in `room` at `slot` is there; everyone else is not (camera, witness). */
function roomRoll(w: LtWorld, room: string, slot: number, src: string): LiarsFact[] {
  return names(w).map((n) => ({ who: n, slot, room, yes: w.truth[n]![slot] === room, src }));
}

/** A suspect's un-hedged account of one slot: where they were, who was with them, who was not. */
function slotAccount(w: LtWorld, who: string, slot: number): LiarsFact[] {
  const room = w.claims[who]![slot]!;
  if (w.hedged[who]![slot]) return [{ who, slot, room, yes: true, src: who, hedged: true }];
  const comp = claimedCompanions(w, who, slot) ?? [];
  const out: LiarsFact[] = [{ who, slot, room, yes: true, src: who }];
  for (const n of names(w)) if (n !== who) out.push({ who: n, slot, room, yes: comp.includes(n), src: who });
  if (claimedPurchaseSlots(w, who).includes(slot)) out.push({ who, slot, room: w.roles.bar, yes: true, src: who, bought: true });
  return out;
}

function evidenceFacts(w: LtWorld, id: EvidenceId): Pick<LiarsSimFrame, 'facts' | 'door' | 'receipts'> {
  const slots = slotsOf(w).map((_, i) => i);
  switch (id) {
    case 'DOOR LOG':
      return { facts: [], door: candidateTheftSlots(w) };
    case 'WITNESS':
      return { facts: slots.flatMap((s) => roomRoll(w, w.roles.witness, s, 'WITNESS')) };
    case 'CCTV':
      return {
        facts: [w.roles.cam1, w.roles.cam2].flatMap((cam) => slots.filter((s) => !(w.cctvGap.room === cam && w.cctvGap.slot === s)).flatMap((s) => roomRoll(w, cam, s, 'CCTV'))),
      };
    case 'RECEIPT':
      return {
        facts: w.purchases.map((p) => ({ who: p.name, slot: p.slot, room: w.roles.bar, yes: true, src: 'RECEIPT' })),
        receipts: w.purchases.map((p) => ({ who: p.name, slot: p.slot })),
      };
  }
}

/** The facts one valid question reveals, plus the door slots it pins down. */
export function liarsFactsFor(cmd: LtCommand, w: LtWorld): Pick<LiarsSimFrame, 'facts' | 'door' | 'receipts'> {
  if (cmd.kind === 'check') return evidenceFacts(w, cmd.evidence);
  if (cmd.kind !== 'ask') return { facts: [] };
  const who = cmd.suspect;
  const t = cmd.topic;
  const slots = slotsOf(w).map((_, i) => i);
  if (t.kind === 'alibi') {
    return { facts: slots.map((s) => ({ who, slot: s, room: w.claims[who]![s]!, yes: true, src: who, ...(w.hedged[who]![s] ? { hedged: true } : {}) })) };
  }
  if (t.kind === 'time') {
    const heard = heardDoorSlots(w, who).filter((s) => s === t.slot && !w.hedged[who]![s] && w.claims[who]![s] === w.roles.adjacent);
    return { facts: slotAccount(w, who, t.slot), ...(heard.length ? { door: heard } : {}) };
  }
  if (t.kind === 'suspect') {
    const seen = claimedSightings(w, who, t.name);
    const facts: LiarsFact[] = [];
    for (const s of slots) {
      if (w.hedged[who]![s]) continue;
      facts.push({ who: t.name, slot: s, room: w.claims[who]![s]!, yes: seen.includes(s), src: who });
    }
    return { facts };
  }
  // THE <object>: only an innocent next door at the theft heard the door.
  const heard = heardDoorSlots(w, who);
  return heard.length ? { facts: heard.map((s) => ({ who, slot: s, room: w.roles.adjacent, yes: true, src: who })), door: heard } : { facts: [] };
}

/** The frame payload for one question (or an invalid command). */
export function liarsSimFrame(cmd: LtCommand, w: LtWorld, used: number): LiarsSimFrame {
  if (cmd.kind === 'invalid') return { kind: 'liars', used, invalid: true, facts: [] };
  const base = liarsFactsFor(cmd, w);
  if (cmd.kind === 'check') return { kind: 'liars', used, check: cmd.evidence, ...base };
  if (cmd.kind === 'ask') {
    const t = cmd.topic;
    const topic = t.kind === 'alibi' ? 'ALIBI' : t.kind === 'time' ? slotsOf(w)[t.slot]! : t.kind === 'suspect' ? t.name : `THE ${w.objectKey}`;
    return { kind: 'liars', used, ask: { who: cmd.suspect, topic }, ...base };
  }
  return { kind: 'liars', used, facts: [] };
}
