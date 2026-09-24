# E4 - air-freight consolidation manifest: lb/in -> kg/cm conversions, volumetric weight, weight breaks with the
# break-point rule, a minimum charge, surcharges on different weight bases, warehouse amendments, time zones and CHF collect charges.
import datetime as dt
from decimal import Decimal as D, ROUND_HALF_UP, ROUND_CEILING
def r2(x): return x.quantize(D('0.01'), ROUND_HALF_UP)
def r1(x): return x.quantize(D('0.1'), ROUND_HALF_UP)

INSTRUCTIONS = ("Below are a draft air-freight consolidation manifest and the messages logged against it before the flight. "
                "Produce the FINAL rated manifest for the flight as JSON that follows the schema exactly.")

DOC = r"""
NORTHGATE AIR CONSOLIDATORS INC.
2150 Mannheim Road, Des Plaines, IL 60018, USA

CONSOLIDATION MANIFEST - DRAFT (issued 04 OCT 2026 16:00 CDT)
MAWB: 947-30115842          Carrier: Transalpine Cargo AG
Flight: XT 7231 / 05 OCT 2026   Chicago O'Hare (ORD) to Zurich (ZRH)
Scheduled departure: 05 OCT 2026 18:40 local time (CDT, UTC-5)
Scheduled arrival:   06 OCT 2026 10:05 local time (CEST, UTC+2)

HAWB       Shipper                       Consignee                   Pcs  Packing     Dimensions per piece   Weight per piece    DG  Freight terms
---------  ----------------------------  --------------------------  ---  ----------  ---------------------  ------------------  --  -------------
NGC-40117  Lakeshore Medical Devices     Medipark AG, Basel            4  boxes       24 x 20 x 18 in        38 lb               N   Collect
NGC-40118  Prairie Optics LLC            Optiqa SA, Lausanne           2  pallets     48 x 40 x 52 in        412 lb and 389 lb   N   Collect
NGC-40119  Maple Ridge Instruments Ltd   Labtec GmbH, Zug              6  cartons     60 x 40 x 35 cm        14.2 kg             N   Prepaid
NGC-40120  Voltaris Energy Inc.          Swiss Storage Systems AG      3  fibreboard  50 x 50 x 40 cm        21.5 kg             Y   Prepaid
NGC-40121  Great Lakes Machining Co.     Praezitec AG, Winterthur      1  crate       72 x 30 x 30 in        285 lb              N   Collect
NGC-40122  Harbor Point Apparel          Modehaus Keller, Zurich       8  cartons     20 x 16 x 12 in        22 lb               N   Collect
NGC-40123  Kessler Legal Group           Ruegg & Partner, Zurich       1  envelope    12 x 9 x 2 in          3.5 lb              N   Collect

Remarks:
- NGC-40118: each pallet contains measuring equipment with lithium ion batteries installed (UN3481, packed in compliance with Section II; no Shipper's Declaration for Dangerous Goods required).
- NGC-40120: lithium ion batteries UN3480, Class 9, Shipper's Declaration for Dangerous Goods attached. Cargo aircraft only.

RATING CONDITIONS (Northgate tariff ORD-ZRH, general cargo, valid October 2026)
1. Weight. Piece weights given in lb are converted at 1 lb = 0.45359237 kg. The gross weight of a HAWB is the sum of the weights of its pieces in kg, rounded to 0.1 kg (half up).
2. Volume. Dimensions given in inches are converted at 1 in = 2.54 cm. The volumetric weight of a HAWB is the sum of the volumes of its pieces in cubic centimetres divided by 6,000, rounded to 0.1 kg (half up).
3. Chargeable weight = the greater of the gross weight and the volumetric weight, rounded UP to the next multiple of 0.5 kg (a weight that is already a multiple of 0.5 kg is not changed).
4. Rates (USD per kg): N (under 45 kg) 6.80 | Q45 (45 kg and over) 5.10 | Q100 (100 kg and over) 4.35 | Q300 (300 kg and over) 3.90 | Q500 (500 kg and over) 3.55. Minimum charge M: USD 95.00 per HAWB.
5. Freight. The freight of a HAWB is the LOWEST of: (a) the chargeable weight multiplied by the rate of the band into which the chargeable weight falls; and (b) for each higher band, that band's minimum weight (45, 100, 300 or 500 kg) multiplied by that band's rate. The weight and the rate of the option used are the "rated weight" and the "applied rate". If that lowest amount is less than the minimum charge M, the freight is the minimum charge instead.
6. Surcharges and fees per HAWB: fuel surcharge USD 1.45 per kg of CHARGEABLE weight; security surcharge USD 0.18 per kg of GROSS weight; dangerous goods handling fee USD 120.00 for each HAWB for which a Shipper's Declaration for Dangerous Goods is required; HAWB fee USD 25.00. Every charge is rounded to the cent.
7. Charges of HAWBs with freight terms "Collect" are invoiced to the consignee in Swiss francs at the collect rate of USD 1 = CHF 0.8050, applied to the HAWB's total charges in USD and rounded to the cent. Prepaid charges are invoiced to the shipper in USD.

MESSAGE LOG - XT 7231 / 05 OCT 2026 (all times CDT)
05 OCT 08:20  Warehouse: all cargo for NGC-40117 to NGC-40123 received.
05 OCT 09:12  Warehouse: NGC-40119 - one carton found crushed at receiving inspection. Shipper instructs us to hold that carton at ORD for a survey; the remaining cartons fly as planned.
05 OCT 10:30  Warehouse: NGC-40121 re-measured and re-weighed at acceptance: the crate is actually 76 x 30 x 31 in and weighs 291 lb. The manifest and the rating must reflect the actual dimensions and weight.
05 OCT 11:05  Warehouse: NGC-40117 re-weighed at the customer's request - 38 lb per box confirmed, no change.
05 OCT 11:45  Booking: NGC-40122 OFFLOADED - the shipper's export filing is incomplete. Rebooked on XT 7233 of 07 OCT.
05 OCT 14:20  Booking: NGC-40122 - the export filing has now been completed, but the flight is already closed for acceptance, so NGC-40122 remains on XT 7233 of 07 OCT.
05 OCT 15:02  Booking: NGC-40119 - the shipper asks whether the freight terms can be changed to Collect. Consignee has refused to pay; terms remain Prepaid.
05 OCT 15:30  Operations: XT 7231 delayed by late arrival of the inbound aircraft. New departure 05 OCT 20:15 CDT; new arrival 06 OCT 11:30 CEST.
05 OCT 21:40  Operations: XT 7231 airborne. (Departure and arrival times for the manifest remain those of the 15:30 message.)
"""

SCHEMA = r"""
{
  "mawb": string,
  "flight": string,                                // e.g. "AB 1234"
  "departure_utc": "YYYY-MM-DDTHH:MM",             // departure time to use for the manifest, converted to UTC
  "arrival_utc": "YYYY-MM-DDTHH:MM",               // arrival time to use for the manifest, converted to UTC
  "scheduled_flight_minutes": number,              // minutes between those two instants
  "hawbs": [                                       // one entry per HAWB carried on this flight, in manifest order
    { "hawb": string,
      "pieces": number,                            // pieces carried on this flight
      "gross_kg": number,
      "volumetric_kg": number,
      "chargeable_kg": number,
      "rated_kg": number or null,                  // null if the minimum charge applies
      "applied_rate_usd_per_kg": number or null,   // null if the minimum charge applies
      "freight_usd": number,
      "fuel_usd": number,
      "security_usd": number,
      "dg_fee_usd": number,                        // 0 if not applicable
      "hawb_fee_usd": number,
      "total_usd": number,                         // sum of the five charges above
      "freight_terms": "prepaid" | "collect",
      "total_chf": number or null }                // collect HAWBs only; null for prepaid HAWBs
  ],
  "offloaded_hawbs": [string],                     // HAWBs on the draft manifest that are not carried at all, in manifest order
  "pieces_held_at_origin": number,                 // pieces of HAWBs carried on this flight that stay behind
  "total_pieces": number,                          // over all HAWBs carried
  "total_gross_kg": number,                        // sum of the HAWB gross weights
  "total_chargeable_kg": number,                   // sum of the HAWB chargeable weights
  "total_charges_usd": number,                     // sum of total_usd over all HAWBs carried
  "collect_total_chf": number                      // sum of total_chf over the collect HAWBs
}
"""

EXTRA_RULES = "Weights are JSON numbers in kg with the rounding stated in the rating conditions; money amounts are JSON numbers rounded to 2 decimals."

LB, IN = D('0.45359237'), D('2.54')
BANDS = [(0, D('6.80')), (45, D('5.10')), (100, D('4.35')), (300, D('3.90')), (500, D('3.55'))]

def rate_hawb(hawb, pieces, dg, terms):
    """pieces: list of (l, w, h, dim_unit, weight, weight_unit)"""
    gross = r1(sum(D(str(w)) * (LB if wu == 'lb' else 1) for *_, w, wu in pieces))
    vol = sum(D(l) * D(w) * D(h) * (IN ** 3 if du == 'in' else 1) for l, w, h, du, _, _ in pieces)
    volw = r1(vol / 6000)
    ch = (max(gross, volw) * 2).to_integral_value(ROUND_CEILING) / 2
    bi = max(i for i, (m, _) in enumerate(BANDS) if ch >= m)
    options = [(r2(ch * BANDS[bi][1]), ch, BANDS[bi][1])] + [(r2(D(m) * r), D(m), r) for m, r in BANDS[bi + 1:]]
    freight, rated, rate = min(options)
    if freight < D('95.00'): freight, rated, rate = D('95.00'), None, None
    fuel = r2(ch * D('1.45')); sec = r2(gross * D('0.18')); dgf = D('120.00') if dg else D('0'); fee = D('25.00')
    total = freight + fuel + sec + dgf + fee
    chf = r2(total * D('0.8050')) if terms == 'collect' else None
    f = lambda x: None if x is None else float(x)
    return dict(hawb=hawb, pieces=len(pieces), gross_kg=f(gross), volumetric_kg=f(volw), chargeable_kg=f(ch), rated_kg=f(rated),
                applied_rate_usd_per_kg=f(rate), freight_usd=f(freight), fuel_usd=f(fuel), security_usd=f(sec), dg_fee_usd=f(dgf),
                hawb_fee_usd=f(fee), total_usd=f(total), freight_terms=terms, total_chf=f(chf)), (gross, ch, total, chf)

def expected():
    carried = [
        ('NGC-40117', [(24, 20, 18, 'in', 38, 'lb')] * 4, False, 'collect'),
        ('NGC-40118', [(48, 40, 52, 'in', 412, 'lb'), (48, 40, 52, 'in', 389, 'lb')], False, 'collect'),   # Section II: no DGD -> no DG fee
        ('NGC-40119', [(60, 40, 35, 'cm', '14.2', 'kg')] * 5, False, 'prepaid'),                            # 1 of 6 cartons held
        ('NGC-40120', [(50, 50, 40, 'cm', '21.5', 'kg')] * 3, True, 'prepaid'),
        ('NGC-40121', [(76, 30, 31, 'in', 291, 'lb')], False, 'collect'),                                   # re-measured / re-weighed
        ('NGC-40123', [(12, 9, 2, 'in', '3.5', 'lb')], False, 'collect'),
    ]
    rows, raw = zip(*(rate_hawb(*c) for c in carried))
    # sanity: the notable rating paths
    assert rows[0]['rated_kg'] == 100.0 and rows[0]['chargeable_kg'] == 94.5          # break-point to Q100
    assert rows[5]['rated_kg'] is None and rows[5]['freight_usd'] == 95.0              # minimum charge
    assert rows[2]['chargeable_kg'] == 71.0                                             # gross (71.0) beats volumetric (70.0)
    dep = dt.datetime(2026, 10, 5, 20, 15) + dt.timedelta(hours=5)
    arr = dt.datetime(2026, 10, 6, 11, 30) - dt.timedelta(hours=2)
    mins = int((arr - dep).total_seconds() // 60)
    return {
        'mawb': '947-30115842', 'flight': 'XT 7231', 'departure_utc': dep.strftime('%Y-%m-%dT%H:%M'), 'arrival_utc': arr.strftime('%Y-%m-%dT%H:%M'),
        'scheduled_flight_minutes': mins, 'hawbs': list(rows), 'offloaded_hawbs': ['NGC-40122'], 'pieces_held_at_origin': 1,
        'total_pieces': sum(r['pieces'] for r in rows), 'total_gross_kg': float(sum(g for g, *_ in raw)),
        'total_chargeable_kg': float(sum(c for _, c, *_ in raw)), 'total_charges_usd': float(sum(t for *_, t, _ in raw)),
        'collect_total_chf': float(sum(c for *_, c in raw if c is not None)),
    }

NOTES = ("Traps: NGC-40119 flies 5 of 6 cartons (gross 71.0 kg now beats volumetric 70.0); NGC-40121 rated on the re-measured 76x30x31 in / 291 lb crate; "
         "NGC-40122 offloaded and NOT reinstated; NGC-40117 chargeable 94.5 kg but the break-point rule charges 100 kg x 4.35 = 435.00 < 94.5 x 5.10, while "
         "fuel is still on the 94.5 kg chargeable weight; NGC-40123 falls to the USD 95 minimum (rated weight / rate null); NGC-40118's Section II batteries need "
         "no DGD so no DG fee; NGC-40119 stays prepaid; departure/arrival from the 15:30 delay message (20:15 CDT = 01:15 UTC, 11:30 CEST = 09:30 UTC -> 495 min), "
         "not the 21:40 airborne time. All values computed with Decimal in verification/frontier-extraction/e4.py.")

if __name__ == '__main__':
    import json; e = expected(); print(json.dumps(e, indent=1)); print(len(DOC.split()), 'words')
