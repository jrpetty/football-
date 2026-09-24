// Independent re-derivation of every answer key in tests/extraction/frontier.json, written separately from the Python
// generators: integer (cent / minute / gram-free) arithmetic with BigInt rationals, re-applying the document changes by hand.
// Run from this folder: node verify.mjs
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const test = JSON.parse(readFileSync(new URL('../../tests/extraction/frontier.json', import.meta.url), 'utf8'));
const byId = Object.fromEntries(test.cases.map((c) => [c.id, c.expected]));

// half-up rounding of a non-negative rational num/den to an integer
const rhu = (num, den) => { num = BigInt(num); den = BigInt(den); return Number((2n * num + den) / (2n * den)); };
const eur = (cents) => cents / 100; // cents -> JSON number (all keys hold at most 2 decimals)
const addDays = (iso, n) => { const d = new Date(iso + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

function check(id, got) {
  const exp = byId[id];
  assert.deepEqual(Object.keys(got).sort(), Object.keys(exp).sort(), `${id}: key set`);
  for (const k of Object.keys(exp)) {
    const a = JSON.stringify(got[k]), b = JSON.stringify(exp[k]);
    assert.equal(a, b, `${id}.${k}: independent ${a} vs key ${b}`);
  }
  console.log(`${id}: all ${Object.keys(exp).length} top-level fields agree`);
}

// ---------------------------------------------------------------- x01 offsite thread
{
  let g1 = ['Ingrid Solberg', 'Henrik Aas', 'Liv Brekke', 'Tomas Berg', 'Maja Lind', 'Sigrid Haugen', 'Erik Dahl', 'Nora Strand', 'Kari Moen', 'Anders Lie'];
  let g2 = ['Ola Nygard', 'Jonas Vik', 'Silje Bakke', 'Magnus Holm', 'Hanne Eide', 'Petter Rod', 'Ane Fjeld', 'Lars Myhre'];
  let ba = ['Elin Sather'];
  g2 = g2.filter((p) => p !== 'Petter Rod' && p !== 'Jonas Vik'); ba.push('Jonas Vik');           // 12 Aug
  g1 = g1.filter((p) => p !== 'Liv Brekke');                                                   // 14 Aug
  const staff = [...g1, ...g2, ...ba];
  const lateOla = 'Ola Nygard';
  const deluxe = ['Henrik Aas'];
  const nightsOf = (p) => (p === lateOla ? 2 : 3);
  const supN = staff.filter((p) => !deluxe.includes(p)).reduce((s, p) => s + nightsOf(p), 0);
  const dlxN = deluxe.reduce((s, p) => s + nightsOf(p), 0);
  const supRate = rhu(14200 * 90, 100), dlxRate = rhu(17600 * 90, 100);
  const accNet = supRate * supN + dlxRate * dlxN, accVat = rhu(accNet * 6, 100);
  const tax = 200 * (supN + dlxN);
  const meetNet = 95000 + rhu(95000 * 60, 100), meetVat = rhu(meetNet * 23, 100);
  const covers = Math.max(staff.length + 2, 20), dinNet = 5800 * covers, dinVat = rhu(dinNet * 13, 100);
  // pickups: UTC + 1 h + 45 min
  const pick = (h, m) => { const t = h * 60 + m + 105; return `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`; };
  const arrG = [['KL 1206', g1.length, pick(11, 5)], ['TP 1957', g2.filter((p) => p !== lateOla).length, pick(13, 50)], ['BA 478', ba.length, pick(18, 0)]];
  const veh = (n) => (n <= 16 ? 'minibus' : 'coach');
  const price = { minibus: 19000, coach: 31000 };
  const trNet = arrG.reduce((s, g) => s + price[veh(g[1])], 0) + price[veh(staff.length)], trVat = rhu(trNet * 6, 100);
  const total = accNet + accVat + tax + meetNet + meetVat + dinNet + dinVat + trNet + trVat;
  const deposit = rhu(total * 30, 100), nok = rhu(total * 1162, 100);
  check('x01', {
    hotel_name: 'Hotel Castelo da Ria', contract_signed_date: '2026-08-20', discount_applied: true, arrival_date: '2026-10-13', departure_date: '2026-10-16', nights: 3,
    attendee_count: staff.length, superior_rooms: staff.length - 1, deluxe_rooms: 1, room_nights: supN + dlxN, superior_rate_eur: eur(supRate), deluxe_rate_eur: eur(dlxRate),
    accommodation_net_eur: eur(accNet), accommodation_vat_eur: eur(accVat), tourist_tax_eur: eur(tax), meeting_room_net_eur: eur(meetNet), meeting_room_vat_eur: eur(meetVat),
    dinner_date: '2026-10-15', dinner_covers_charged: covers, dinner_net_eur: eur(dinNet), dinner_vat_eur: eur(dinVat),
    arrival_transfers: arrG.map(([flight, passengers, t]) => ({ flight, passengers, vehicle: veh(passengers), pickup_time_local: t })),
    departure_vehicle: veh(staff.length), transfers_net_eur: eur(trNet), transfers_vat_eur: eur(trVat), grand_total_eur: eur(total), deposit_eur: eur(deposit),
    deposit_due_date: addDays('2026-08-20', 7), balance_eur: eur(total - deposit), balance_due_date: addDays('2026-10-13', -21), free_cancellation_until: addDays('2026-10-13', -30),
    grand_total_nok: eur(nok), within_budget: nok <= 12850000, purchase_order: 'PO-HM-26-0481', invoice_email: 'faktura@halvorsen-maritime.no',
    vegetarian_count: ['Nora Strand', 'Kari Moen', 'Anders Lie', 'Elin Sather'].length, gluten_free_count: ['Silje Bakke', 'Elin Sather'].length,
  });
}

// ---------------------------------------------------------------- x02 freight invoice + credit note
{
  // rates in 1/10000 EUR per unit; amounts in cents
  const conv = (cents, rate) => rhu(cents * rate, 10000);
  const lines = [
    ['O1', 1, conv(65000, 1284)], ['O2', 2, conv(236000, 1284)], ['O3', 1, conv(40000, 1284)],
    ['F1', 2, conv(rhu(468000 * 95, 100), 9082)], ['F2', 2, conv(57000, 9082)],
    ['D1', 2, 53000], ['D2', 1, 9500], ['D3', 2, 82000], ['D4', 2, 17000], ['X1', 1, 174840],
  ];
  const fee = Math.max(rhu(174840 * 25, 1000), 4500);
  lines.push(['X2', 1, fee]);
  const z = lines.slice(0, 5).reduce((s, l) => s + l[2], 0), sd = lines.slice(5, 9).reduce((s, l) => s + l[2], 0);
  const rz = rhu(z * 3, 100), rs = rhu(sd * 3, 100);
  const netZ = z - rz, netS = sd - rs + fee, vat = rhu(netS * 21, 100), total = netZ + netS + vat + 174840;
  const orig = 1048775;
  const asIssued = { O3: 5136, D1: 53000 }; // lines whose disputes were refused
  check('x02', {
    invoice_number: 'BF-26-08812', credit_note_number: 'BF-CN-26-0417', invoice_date: '2026-09-14', due_date: addDays('2026-09-14', 30),
    containers: ['MSKU 7734120', 'TGHU 5528907'], usd_rate_final: 0.9082, cny_rate_final: 0.1284, ocean_freight_usd: 4446,
    lines: lines.map(([code, quantity, a]) => ({ code, quantity, amount_eur: eur(a) })), rebate_z_eur: eur(rz), rebate_s_eur: eur(rs),
    net_z_eur: eur(netZ), net_s_eur: eur(netS), vat_eur: eur(vat), disbursements_eur: eur(174840), original_total_eur: eur(orig),
    recalculated_total_eur: eur(total), credit_note_total_eur: eur(orig - total), advance_payment_eur: 2000, amount_payable_eur: eur(total - 200000),
    disputed_lines_unchanged: lines.filter(([c, , a]) => asIssued[c] === a).map(([c]) => c),
  });
}

// ---------------------------------------------------------------- x03 SaaS contract
{
  const idx = (fee, tenthsPct) => rhu(fee * (1000 + tenthsPct), 1000); // whole pounds
  const f26 = idx(21000, 16), f27 = idx(f26, 40), fc = idx(2400, 16);
  const named = 318, userFees = (named - 300) * 58, net = f27 + userFees;
  const downtime = 95 + 27, mins = 43200;
  const availHundredths = Math.floor(((mins - downtime) * 10000) / mins); // floor of percent*100
  const pct = availHundredths < 9900 ? 25 : availHundredths < 9950 ? 10 : availHundredths < 9990 ? 5 : 0;
  check('x03', {
    provider: 'Orbis Analytics Ltd', customer: 'Marrow & Finch Retail plc', effective_date: '2025-04-01', initial_term_end: '2029-03-31',
    platform_fee_history: [['2025-04-01', 18500], ['2025-10-01', 21000], ['2026-04-01', f26], ['2027-04-01', f27]].map(([from, m]) => ({ from, monthly_fee_gbp: m })),
    increase_2026_percent: 1.6, increase_2027_percent: 4, side_letter_binding: false, forecasting_fee_june_2026_gbp: fc, forecasting_last_month: '2026-06',
    included_named_users: 300, june_2027_named_users: named, june_2027_user_fees_gbp: userFees, june_2027_invoice_net_gbp: net,
    june_2027_invoice_vat_gbp: eur(rhu(net * 100 * 20, 100)), june_2027_downtime_minutes: downtime, june_2027_availability_percent: availHundredths / 100,
    june_2027_service_credit_percent: pct, june_2027_service_credit_gbp: eur(rhu(f27 * 100 * pct, 100)), liability_cap_gbp: Math.max(400000, (f27 * 12 * 3) / 2),
    termination_notice_date: '2027-08-12', termination_date: '2027-11-30', etf_months: 16, early_termination_fee_gbp: eur(rhu(f27 * 100 * 40 * 16, 100)),
  });
}

// ---------------------------------------------------------------- x04 air manifest
{
  // lengths in micrometres-free exact rationals: 1 in = 254/100 cm, 1 lb = 45359237/1e8 kg
  const LBn = 45359237n, LBd = 100000000n;
  function rate(hawb, pcs, dg, terms) {
    // gross in 1/1e8 kg, volume in cm^3 * 1e6 (in^3 -> cm^3 factor 16.387064 = 16387064/1e6)
    let g = 0n, v = 0n;
    for (const [l, w, h, du, wt, wu] of pcs) {
      const wtr = BigInt(Math.round(wt * 10)); // weight in tenths
      g += wu === 'lb' ? (wtr * LBn) / 10n : (wtr * LBd) / 10n;
      v += du === 'in' ? BigInt(l * w * h) * 16387064n : BigInt(l * w * h) * 1000000n;
    }
    const gross = rhu(g, LBd / 10n); // tenths of kg
    const volw = rhu(v, 6000n * 100000n); // tenths of kg
    const mx = Math.max(gross, volw);
    const ch = Math.ceil(mx / 5) * 5; // tenths, multiple of 0.5 kg
    const bands = [[0, 680], [450, 510], [1000, 435], [3000, 390], [5000, 355]]; // tenths kg, cents/kg
    const bi = bands.reduce((b, [m], i) => (ch >= m ? i : b), 0);
    const opts = [[rhu(ch * bands[bi][1], 10), ch, bands[bi][1]], ...bands.slice(bi + 1).map(([m, r]) => [rhu(m * r, 10), m, r])];
    opts.sort((a, b) => a[0] - b[0]);
    let [fr, rk, rt] = opts[0];
    if (fr < 9500) { fr = 9500; rk = null; rt = null; }
    const fuel = rhu(ch * 145, 10), sec = rhu(gross * 18, 10), dgf = dg ? 12000 : 0, total = fr + fuel + sec + dgf + 2500;
    return { hawb, pieces: pcs.length, gross_kg: gross / 10, volumetric_kg: volw / 10, chargeable_kg: ch / 10, rated_kg: rk === null ? null : rk / 10,
      applied_rate_usd_per_kg: rt === null ? null : rt / 100, freight_usd: eur(fr), fuel_usd: eur(fuel), security_usd: eur(sec), dg_fee_usd: eur(dgf), hawb_fee_usd: 25,
      total_usd: eur(total), freight_terms: terms, total_chf: terms === 'collect' ? eur(rhu(total * 8050, 10000)) : null, _g: gross, _c: ch, _t: total };
  }
  const rep = (n, x) => Array.from({ length: n }, () => x);
  const rows = [
    rate('NGC-40117', rep(4, [24, 20, 18, 'in', 38, 'lb']), false, 'collect'),
    rate('NGC-40118', [[48, 40, 52, 'in', 412, 'lb'], [48, 40, 52, 'in', 389, 'lb']], false, 'collect'),
    rate('NGC-40119', rep(5, [60, 40, 35, 'cm', 14.2, 'kg']), false, 'prepaid'),
    rate('NGC-40120', rep(3, [50, 50, 40, 'cm', 21.5, 'kg']), true, 'prepaid'),
    rate('NGC-40121', [[76, 30, 31, 'in', 291, 'lb']], false, 'collect'),
    rate('NGC-40123', [[12, 9, 2, 'in', 3.5, 'lb']], false, 'collect'),
  ];
  const dep = Date.UTC(2026, 9, 5, 20 + 5, 15), arr = Date.UTC(2026, 9, 6, 11 - 2, 30);
  const iso = (t) => new Date(t).toISOString().slice(0, 16);
  const strip = ({ _g, _c, _t, ...r }) => r;
  check('x04', {
    mawb: '947-30115842', flight: 'XT 7231', departure_utc: iso(dep), arrival_utc: iso(arr), scheduled_flight_minutes: (arr - dep) / 60000,
    hawbs: rows.map(strip), offloaded_hawbs: ['NGC-40122'], pieces_held_at_origin: 1, total_pieces: rows.reduce((s, r) => s + r.pieces, 0),
    total_gross_kg: rows.reduce((s, r) => s + r._g, 0) / 10, total_chargeable_kg: rows.reduce((s, r) => s + r._c, 0) / 10,
    total_charges_usd: eur(rows.reduce((s, r) => s + r._t, 0)), collect_total_chf: eur(rows.filter((r) => r.total_chf !== null).reduce((s, r) => s + Math.round(r.total_chf * 100), 0)),
  });
}

// ---------------------------------------------------------------- x05 AGM minutes
{
  const E = { 1: 62, 2: 71, 3: 88, 4: 95, 5: 74, 6: 110, 7: 83, 8: 67, 9: 102, 10: 91, 11: 78, 12: 79 };
  const sum = (us) => us.reduce((s, u) => s + E[u], 0);
  // recorded votes, then corrections applied
  const m = {
    1: { type: 'ordinary', for: [1, 2, 3, 4, 6, 9, 11, 12], against: [], present: [1, 2, 3, 4, 6, 9, 11, 12] },
    2: { type: 'ordinary', for: [1, 2, 3, 4, 6, 11, 12], against: [5, 9], present: [1, 2, 3, 4, 5, 6, 9, 11, 12] },
    3: { type: 'special', for: [1, 2, 5, 6, 9, 11, 12], against: [3], present: [1, 2, 3, 5, 6, 9, 11, 12] },
    4: { type: 'ordinary', for: [1, 2, 5, 6, 11], against: [3, 9, 12], present: [1, 2, 3, 5, 6, 9, 11, 12] },
  };
  for (const k of [1, 2, 3, 4]) m[k].present.push(7);                    // (i) Unit 7 represented throughout
  m[1].for.push(7); m[2].for.push(7); m[3].for.push(7); m[4].against.push(7);
  m[3].against = m[3].against.filter((u) => u !== 3); m[3].for.push(3);    // (ii)
  m[4].for = m[4].for.filter((u) => u !== 11);                            // (iii) abstained
  const resolutions = [1, 2, 3, 4].map((k) => {
    const r = m[k], f = sum(r.for), a = sum(r.against), ab = sum(r.present.filter((u) => !r.for.includes(u) && !r.against.includes(u)));
    return { motion: k, type: r.type, for: f, against: a, abstain: ab, passed: r.type === 'ordinary' ? f > a : 3 * f >= 2000 };
  });
  const shares = Object.keys(E).map((u) => ({ unit: +u, amount_gbp: eur(rhu(9734500 * E[u], 1000)) }));
  const s6 = rhu(9734500 * 110, 1000), first = rhu(s6 * 40, 100);
  const ballots = { 1: 'Okafor Ishikawa Petrov', 2: 'Okafor Duval Ishikawa', 3: 'Duval Petrov Whitfield', 5: 'Petrov Ishikawa', 6: 'Ishikawa Okafor Whitfield',
    9: 'Petrov Duval Whitfield', 11: 'Duval Ishikawa Petrov', 12: 'Okafor Whitfield', 7: 'Duval Okafor Whitfield' };
  const t = {};
  for (const [u, b] of Object.entries(ballots)) for (const c of b.split(' ')) t[c] = (t[c] ?? 0) + E[u];
  const names = Object.keys(t).sort();
  check('x05', {
    association: "Harbourview Court Owners' Association", meeting_date: '2026-09-17', quorum_entitlements: sum([1, 3, 4, 6, 8, 9, 11, 2, 12, 7]), resolutions,
    budget_2027_gbp: 185000, unit_9_quarterly_service_charge_gbp: eur(rhu(18500000 * 102, 4000)), special_levy_gbp: 97345, levy_shares: shares,
    levy_sum_of_shares_gbp: eur(shares.reduce((s, x) => s + Math.round(x.amount_gbp * 100), 0)), unit_6_first_instalment_gbp: eur(first), unit_6_second_instalment_gbp: eur(s6 - first),
    director_votes: names.map((c) => ({ candidate: c, votes: t[c] })), directors_elected: [...names].sort((a, b) => t[b] - t[a]).slice(0, 3),
  });
}
console.log('All answer keys independently re-derived.');
