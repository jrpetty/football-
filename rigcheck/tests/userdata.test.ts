import { describe, expect, it } from 'vitest';
import {
  USERDATA_VERSION, emptyUserData, exportBundle, mergeUserData, priceCoverageOf, priceOverlay,
  readUserData, toPerfRecords, type UserData,
} from '../src/core/userdata.ts';
import { comparePeers, findPeers } from '../src/core/peers.ts';

const parts = new Set(['nvidia-geforce-rtx-3060-12gb', 'amd-ryzen-5-5600x']);
const games = new Set(['cyberpunk-2077']);
const known = { parts, games };

const bundle = (over: Record<string, unknown> = {}) => ({
  version: USERDATA_VERSION,
  prices: [{ partId: 'nvidia-geforce-rtx-3060-12gb', condition: 'used', gbp: 165, at: '2026-08-01' }],
  measurements: [
    {
      id: 'm1', cpuId: 'amd-ryzen-5-5600x', gpuId: 'nvidia-geforce-rtx-3060-12gb',
      gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 58, low1PctFps: 44,
      at: '2026-08-02',
    },
  ],
  ...over,
});

/* ------------------------------------------------------------ validation -- */

describe('reading an imported bundle', () => {
  it('reads a well-formed one', () => {
    const r = readUserData(bundle(), known);
    expect(r.unreadable).toBe(false);
    expect(r.rejected).toEqual([]);
    expect(r.warnings).toEqual([]);
    expect(r.data.prices).toHaveLength(1);
    expect(r.data.measurements[0].avgFps).toBe(58);
  });

  it('keeps the good rows when one row is bad', () => {
    // The whole point of per-row validation. An import that throws away nine
    // good rows because the tenth had a typo is an import nobody uses twice.
    const r = readUserData(
      bundle({
        prices: [
          { partId: 'nvidia-geforce-rtx-3060-12gb', condition: 'used', gbp: 165, at: '2026-08-01' },
          { partId: 'nvidia-geforce-rtx-3060-12gb', condition: 'refurbished', gbp: 150, at: '2026-08-01' },
          { partId: 'amd-ryzen-5-5600x', condition: 'new', gbp: 120, at: '2026-08-01' },
        ],
      }),
      known,
    );
    expect(r.data.prices).toHaveLength(2);
    expect(r.rejected).toHaveLength(1);
    expect(r.rejected[0]).toMatch(/condition/);
  });

  it('rejects a price no component could cost', () => {
    const r = readUserData(bundle({ prices: [{ partId: 'amd-ryzen-5-5600x', condition: 'new', gbp: 320000 }] }), known);
    expect(r.data.prices).toHaveLength(0);
    expect(r.rejected[0]).toMatch(/plausible price/);
  });

  it('imports a suspiciously high price but names it', () => {
    // Probably 320 pounds typed in pence — but a workstation card really can
    // cost thousands, and the number alone cannot tell those apart. Dropping it
    // would discard a real price; importing it silently would let a hundredfold
    // error rewrite every budget recommendation.
    const r = readUserData(bundle({ prices: [{ partId: 'amd-ryzen-5-5600x', condition: 'new', gbp: 32000 }] }), known);
    expect(r.data.prices).toHaveLength(1);
    expect(r.rejected).toEqual([]);
    expect(r.warnings[0]).toMatch(/320\.00/);
  });

  it('rejects zero, negative and non-numeric prices', () => {
    for (const gbp of [0, -5, '165', null, NaN]) {
      const r = readUserData(bundle({ prices: [{ partId: 'amd-ryzen-5-5600x', condition: 'new', gbp }] }), known);
      expect(r.data.prices, `gbp=${String(gbp)}`).toHaveLength(0);
    }
  });

  it('drops a 1% low that is above the average, and says it did', () => {
    // Arithmetically impossible, so it is a transposed pair of fields. Keeping
    // it would put a nonsense figure in front of somebody labelled as measured.
    const r = readUserData(
      bundle({
        measurements: [{ ...bundle().measurements[0], avgFps: 44, low1PctFps: 58 }],
      }),
      known,
    );
    expect(r.data.measurements[0].avgFps).toBe(44);
    expect(r.data.measurements[0].low1PctFps).toBeUndefined();
    expect(r.rejected[0]).toMatch(/1% low/);
  });

  it('refuses parts and games this catalogue does not have', () => {
    const r = readUserData(
      bundle({
        prices: [{ partId: 'nvidia-geforce-rtx-9090', condition: 'new', gbp: 900 }],
        measurements: [{ ...bundle().measurements[0], gameId: 'half-life-3' }],
      }),
      known,
    );
    expect(r.data.prices).toHaveLength(0);
    expect(r.data.measurements).toHaveLength(0);
    expect(r.rejected.join(' ')).toMatch(/not a part/);
    expect(r.rejected.join(' ')).toMatch(/not a game/);
  });

  it('validates shape alone when no catalogue is supplied', () => {
    const r = readUserData(bundle({ prices: [{ partId: 'anything-at-all', condition: 'new', gbp: 10 }] }));
    expect(r.data.prices).toHaveLength(1);
  });

  it('survives every shape of rubbish without throwing', () => {
    for (const junk of [null, 42, 'a string', [], { prices: 'not an array' }, { measurements: [null, 7] }]) {
      expect(() => readUserData(junk, known)).not.toThrow();
    }
    expect(readUserData(null).unreadable).toBe(true);
    expect(readUserData({ prices: 'not an array' }).data.prices).toEqual([]);
  });

  it('imports what it understands from a newer file rather than refusing it', () => {
    const r = readUserData(bundle({ version: USERDATA_VERSION + 5 }), known);
    expect(r.data.prices).toHaveLength(1);
    expect(r.rejected[0]).toMatch(/version/);
  });

  it('gives an unparseable date the epoch rather than an invalid one', () => {
    const r = readUserData(bundle({ prices: [{ partId: 'amd-ryzen-5-5600x', condition: 'new', gbp: 120, at: 'last tuesday' }] }), known);
    expect(Number.isNaN(Date.parse(r.data.prices[0].at))).toBe(false);
  });
});

/* --------------------------------------------------------------- merging -- */

describe('merging bundles', () => {
  const older: UserData = {
    version: 1,
    prices: [{ partId: 'a', condition: 'used', gbp: 100, at: '2026-01-01' }],
    measurements: [{ id: 'm1', cpuId: 'c', gpuId: 'g', gameId: 'x', resolution: '1080p', avgFps: 60, at: '2026-01-01' }],
  };

  it('treats a second price for the same part as a correction, not a data point', () => {
    const newer: UserData = { ...older, prices: [{ partId: 'a', condition: 'used', gbp: 80, at: '2026-06-01' }] };
    const m = mergeUserData(older, newer);
    expect(m.prices).toHaveLength(1);
    expect(m.prices[0].gbp).toBe(80);
  });

  it('does not let an older price overwrite a newer one', () => {
    const m = mergeUserData({ ...older, prices: [{ partId: 'a', condition: 'used', gbp: 80, at: '2026-06-01' }] }, older);
    expect(m.prices[0].gbp).toBe(80);
  });

  it('keeps new and used prices for the same part apart', () => {
    const m = mergeUserData(older, { ...older, prices: [{ partId: 'a', condition: 'new', gbp: 300, at: '2026-06-01' }] });
    expect(m.prices).toHaveLength(2);
  });

  it('keeps two captures of the same game, because two runs are two runs', () => {
    const second: UserData = {
      ...older,
      measurements: [{ id: 'm2', cpuId: 'c', gpuId: 'g', gameId: 'x', resolution: '1080p', avgFps: 57, at: '2026-06-01' }],
    };
    expect(mergeUserData(older, second).measurements).toHaveLength(2);
  });
});

/* -------------------------------------------------------------- overlays -- */

describe('overlaying user data', () => {
  const data = readUserData(bundle(), known).data;

  it('does not answer a new-condition question with a used-market price', () => {
    // The pricing layer's override tier was one flat map applied to both
    // conditions, so a used figure somebody entered became the "new" price too.
    const o = priceOverlay(data);
    expect(o.used['nvidia-geforce-rtx-3060-12gb']).toBe(165);
    expect(o.new['nvidia-geforce-rtx-3060-12gb']).toBeUndefined();
  });

  it('turns a capture into a record the peer comparison can read', () => {
    const [r] = toPerfRecords(data);
    expect(r.gpuId).toBe('nvidia-geforce-rtx-3060-12gb');
    expect(r.fingerprint.preset).toBe('high');
    expect(r.weight).toBe(1);
    // Stays identifiable as the user's own rather than passing for a population.
    expect(r.provenanceId).toBe('user-capture');
    expect(r.sourceNote).toMatch(/you/i);
  });

  it('defaults a capture with no preset to the reference one rather than dropping it', () => {
    const noPreset = readUserData(bundle({ measurements: [{ ...bundle().measurements[0], preset: undefined }] }), known);
    expect(toPerfRecords(noPreset.data)[0].fingerprint.preset).toBe('high');
  });
});

describe('price coverage', () => {
  const ids = ['a', 'b', 'c', 'd'];

  it('counts what the planner can actually cost', () => {
    const c = priceCoverageOf(ids, ['a'], emptyUserData());
    expect(c.total).toBe(4);
    expect(c.covered).toBe(1);
    expect(c.share).toBeCloseTo(0.25, 5);
  });

  it('credits the user for parts only they have priced', () => {
    const user: UserData = { version: 1, prices: [{ partId: 'b', condition: 'used', gbp: 5, at: '2026-01-01' }], measurements: [] };
    const c = priceCoverageOf(ids, ['a'], user);
    expect(c.covered).toBe(2);
    expect(c.fromUser).toBe(1);
  });

  it('does not double-count a part the seed already had', () => {
    const user: UserData = { version: 1, prices: [{ partId: 'a', condition: 'used', gbp: 5, at: '2026-01-01' }], measurements: [] };
    const c = priceCoverageOf(ids, ['a'], user);
    expect(c.covered).toBe(1);
    expect(c.fromUser).toBe(0);
  });

  it('survives an empty catalogue without dividing by zero', () => {
    expect(priceCoverageOf([], [], emptyUserData()).share).toBe(0);
  });
});

describe('the exported bundle', () => {
  it('round-trips through the importer unchanged', () => {
    const original = readUserData(bundle(), known).data;
    const back = readUserData(JSON.parse(exportBundle(original, '2026-08-22T00:00:00.000Z')), known);
    expect(back.rejected).toEqual([]);
    expect(back.data.prices).toEqual(original.prices);
    expect(back.data.measurements).toEqual(original.measurements);
  });

  it('says plainly that nothing was transmitted', () => {
    expect(exportBundle(emptyUserData(), '2026-08-22T00:00:00.000Z')).toMatch(/left your browser/);
  });
});

/* ----------------------------------------------------- reaching the peers -- */

/**
 * The end of the chain, and the reason the whole lane exists.
 *
 * Peer comparison is written, tested and wired into the health report, and it
 * had never once produced a result: `data/measured/records.json` ships empty,
 * so `findPeers` always searched nothing. These assert that a capture entered
 * through the page comes out the other end as a comparison — that the ids line
 * up, the settings match, and the panel has something to render.
 */
describe('a capture reaching the peer comparison', () => {
  const captured = readUserData(bundle(), known).data;
  const corpus = toPerfRecords(captured);

  const search = () =>
    findPeers(corpus, {
      cpuId: 'amd-ryzen-5-5600x',
      gpuId: 'nvidia-geforce-rtx-3060-12gb',
      gameId: 'cyberpunk-2077',
      resolution: '1440p',
      preset: 'high',
    });

  it('finds the capture for the machine it was taken on', () => {
    const peers = search();
    expect(peers).toHaveLength(1);
    expect(peers[0].avgFps).toBe(58);
    expect(peers[0].exactSettings).toBe(true);
  });

  it('produces a comparison rather than the empty state', () => {
    const c = comparePeers(
      { avgFps: 48, gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' },
      search(),
      [],
    );
    // Before this lane existed, every one of these came back on the "nothing to
    // compare against" tier with no samples and a null median.
    expect(c.tier).toBe('real-peers');
    expect(c.samples).toHaveLength(1);
    expect(c.peerMedian).toBe(58);
    // Still null, and rightly: a percentile computed from one sample says
    // nothing, and the detail text says so too.
    expect(c.percentile).toBeNull();
    expect(c.detail).toMatch(/means very little/);
  });

  it('does not match a different machine, however close', () => {
    // The whole value of a peer is that it is the SAME two parts. A comparison
    // against a different card is not a peer comparison, it is an estimate.
    expect(
      findPeers(corpus, {
        cpuId: 'amd-ryzen-5-5600x',
        gpuId: 'nvidia-geforce-rtx-3070',
        gameId: 'cyberpunk-2077',
        resolution: '1440p',
      }),
    ).toHaveLength(0);
  });

  it('marks a capture taken at other settings as not an exact match', () => {
    const peers = findPeers(corpus, {
      cpuId: 'amd-ryzen-5-5600x',
      gpuId: 'nvidia-geforce-rtx-3060-12gb',
      gameId: 'cyberpunk-2077',
      resolution: '1440p',
      preset: 'ultra',
    });
    expect(peers).toHaveLength(1);
    expect(peers[0].exactSettings).toBe(false);
  });
});
