# E5 - owners' association AGM minutes with entitlement-weighted votes, proxies, arrears, a late arrival, an early
# departure and corrections approved at a later meeting that flip three outcomes.
from decimal import Decimal as D, ROUND_HALF_UP
def r2(x): return x.quantize(D('0.01'), ROUND_HALF_UP)

INSTRUCTIONS = ("Below are the minutes of an owners' association AGM, followed by corrections to those minutes that were approved at a later meeting. "
                "Extract the CORRECTED results of the AGM into JSON that follows the schema exactly.")

DOC = r"""
HARBOURVIEW COURT OWNERS' ASSOCIATION
MINUTES OF THE ANNUAL GENERAL MEETING
held on Thursday 17 September 2026 at 19:00 in the Community Room, Harbourview Court

EXTRACT FROM THE ASSOCIATION'S RULES (reproduced for reference)
R1. Each unit carries the entitlement shown in the schedule below. The entitlements of the twelve units total 1,000.
R2. Quorum: owners present in person or represented by a valid proxy holding in total at least 500 entitlements. Quorum is determined once, at the opening of the meeting.
R3. A proxy is valid only if the proxy form is lodged with the Secretary at least 48 hours before the start of the meeting.
R4. All votes are counted by entitlement. An owner represented by proxy votes through the proxy holder, as directed on the proxy form.
R5. An owner whose service-charge account is more than 60 days in arrears on the day of the meeting may attend and counts towards the quorum, but may not vote on anything.
R6. An ordinary resolution is passed if the entitlements voting for it exceed the entitlements voting against it. Abstentions are disregarded.
R7. A special resolution is passed only if the entitlements voting for it are at least two-thirds of the total entitlements of ALL units, whether represented at the meeting or not.
R8. A special levy is shared among all twelve units in proportion to their entitlements; each unit's share is rounded to the nearest penny (half up). Unless the resolution says otherwise, the first instalment of a unit's share is 40% of that share rounded to the nearest penny (half up), and the second instalment is the rest of the share.
R9. The annual service charge of each unit is the adopted budget multiplied by the unit's entitlement divided by 1,000, and is payable in four equal quarterly instalments.
R10. Directors: each owner entitled to vote may vote for up to as many candidates as there are seats; each vote carries the owner's full entitlement. The seats go to the candidates with the highest totals.

SCHEDULE OF UNITS AND ENTITLEMENTS
Unit 1 - Adaeze Okafor - 62          Unit 7 - Gerard Brennan - 83
Unit 2 - Britt Lindgren - 71         Unit 8 - Helena Salgado - 67
Unit 3 - Claude Duval - 88           Unit 9 - Ivan Petrov - 102
Unit 4 - Denise Moreau - 95          Unit 10 - James Whitfield - 91
Unit 5 - Eva Novak - 74              Unit 11 - Kofi Adeyemi - 78
Unit 6 - Fumiko Ishikawa - 110       Unit 12 - Lucia Castellanos - 79

1. OPENING, ATTENDANCE AND QUORUM
The Chair, Fumiko Ishikawa (Unit 6), opened the meeting at 19:00.
Present in person: Units 1, 3, 4, 6, 8, 9 and 11.
Proxies: The Secretary reported the following proxy forms:
 - Unit 2 (Lindgren): proxy to Adaeze Okafor (Unit 1), lodged Tuesday 15 September at 18:30 - valid.
 - Unit 12 (Castellanos): proxy to the Chair, lodged Saturday 12 September at 11:15 - valid.
 - Unit 7 (Brennan): proxy to the Chair, recorded as lodged Wednesday 16 September at 10:00 - invalid (less than 48 hours before the meeting).
 - Unit 10 (Whitfield): proxy to Claude Duval (Unit 3), lodged Wednesday 16 September at 21:00 - invalid (less than 48 hours before the meeting).
Apologies: Unit 5 (Novak).
The Treasurer reported that the account of Unit 8 (Salgado) is 75 days in arrears; Ms Salgado may attend but may not vote. No other account is more than 60 days in arrears.
The Chair declared the meeting quorate with 752 entitlements present or represented.

2. MOTION 1 - Ordinary resolution: to approve the minutes of the AGM of 18 September 2025.
All owners entitled to vote who were present or represented voted for. For: Units 1, 2, 3, 4, 6, 9, 11, 12 (685). Against: none. Abstentions: none. CARRIED.

19:40 - Eva Novak (Unit 5) arrived and took part in the meeting from this point.

3. MOTION 2 - Ordinary resolution: to adopt the budget for 2027.
The Treasurer presented the draft budget of GBP 188,400. During the discussion she pointed out an arithmetic error in the insurance line (GBP 12,600, not GBP 16,000), which reduces the total to GBP 185,000. The motion was put to adopt the budget of GBP 185,000.
For: Units 1, 2, 3, 4, 6, 11, 12 (583). Against: Units 5, 9 (176). Abstentions: none. CARRIED.

20:10 - Denise Moreau (Unit 4) left the meeting and took no further part in it.

4. MOTION 3 - Special resolution: to approve a special levy for the replacement of the roof.
The board had circulated a proposal for a levy of GBP 104,800. The Chair reported that the contractor's revised quotation had reduced the cost, and the motion was put for a special levy of GBP 97,345, payable in two instalments on 1 December 2026 and 1 March 2027.
For: Units 1, 2, 5, 6, 9, 11, 12 (576). Against: Unit 3 (88). Abstentions: none.
The Chair declared that the resolution had NOT been passed, since the votes for fell short of two-thirds of the total entitlements.

5. ELECTION OF DIRECTORS - three seats.
Candidates: Adaeze Okafor, Claude Duval, Fumiko Ishikawa, Ivan Petrov, James Whitfield.
Ballots cast:
 - Unit 1: Okafor, Ishikawa, Petrov
 - Unit 2 (by proxy): Okafor, Duval, Ishikawa
 - Unit 3: Duval, Petrov, Whitfield
 - Unit 5: Petrov, Ishikawa
 - Unit 6: Ishikawa, Okafor, Whitfield
 - Unit 9: Petrov, Duval, Whitfield
 - Unit 11: Duval, Ishikawa, Petrov
 - Unit 12 (by proxy): Okafor, Whitfield
Ms Salgado (Unit 8) handed in a ballot for Petrov, Ishikawa and Okafor; the Chair ruled it out of order under rule R5 and it was not counted.
Totals as announced: Petrov 404, Ishikawa 395, Whitfield 379, Duval 339, Okafor 322.
Declared elected: Ivan Petrov, Fumiko Ishikawa and James Whitfield.

6. MOTION 4 - Ordinary resolution: to adopt a house rule prohibiting lettings of less than 30 nights.
For: Units 1, 2, 5, 6, 11 (395). Against: Units 3, 9, 12 (269). Abstentions: none. CARRIED.

7. ANY OTHER BUSINESS
The Secretary reminded owners that bicycles may not be stored in the stairwells. The Chair closed the meeting at 21:05.

======================================================================
CORRECTIONS TO THE MINUTES OF THE AGM OF 17 SEPTEMBER 2026
approved by the Extraordinary General Meeting of 15 October 2026

(i) Unit 7's proxy form was in fact lodged on Monday 14 September at 10:00; the Secretary had recorded the date on which she processed it. The proxy was therefore valid and Unit 7 was represented by the Chair throughout the AGM. The directions on the proxy form are to be counted as Unit 7's votes: Motion 1 - for; Motion 2 - for; Motion 3 - for; Election of directors - Duval, Okafor, Whitfield; Motion 4 - against.
(ii) Under Motion 3, Unit 3 voted FOR the resolution; it was wrongly recorded as a vote against.
(iii) Under Motion 4, Unit 11 abstained; it was wrongly recorded as a vote for.
(iv) The quorum figure and all results of the AGM are to be read as recalculated with corrections (i) to (iii). No other part of the minutes is changed.
"""

SCHEMA = r"""
{
  "association": string,
  "meeting_date": "YYYY-MM-DD",
  "quorum_entitlements": number,                  // entitlements present or represented at the opening, as corrected
  "resolutions": [                                // Motions 1 to 4 in order
    { "motion": number,                           // 1, 2, 3 or 4
      "type": "ordinary" | "special",
      "for": number,                              // entitlements
      "against": number,
      "abstain": number,                          // entitlements of owners entitled to vote and present or represented at that vote who voted neither for nor against
      "passed": boolean }
  ],
  "budget_2027_gbp": number,
  "unit_9_quarterly_service_charge_gbp": number,
  "special_levy_gbp": number or null,             // total levy approved, or null if none was approved
  "levy_shares": [                                // one entry per unit in unit order if a levy was approved; empty array otherwise
    { "unit": number, "amount_gbp": number }
  ],
  "levy_sum_of_shares_gbp": number or null,       // sum of the twelve rounded shares, or null if no levy was approved
  "unit_6_first_instalment_gbp": number or null,
  "unit_6_second_instalment_gbp": number or null,
  "director_votes": [                             // every candidate, in alphabetical order of surname
    { "candidate": string,                        // surname only, e.g. "Okafor"
      "votes": number }
  ],
  "directors_elected": [string]                   // surnames, highest vote total first
}
"""

EXTRA_RULES = "Money amounts are JSON numbers in GBP rounded to 2 decimals; entitlements are whole numbers."

ENT = {1: 62, 2: 71, 3: 88, 4: 95, 5: 74, 6: 110, 7: 83, 8: 67, 9: 102, 10: 91, 11: 78, 12: 79}

def expected():
    assert sum(ENT.values()) == 1000
    # Represented at the opening (as corrected): in person 1,3,4,6,8,9,11; valid proxies 2,12 and (corrected) 7. Unit 10's proxy stays invalid, Unit 5 arrives later.
    quorum = sum(ENT[u] for u in (1, 3, 4, 6, 8, 9, 11, 2, 12, 7))
    s = lambda us: sum(ENT[u] for u in us)
    # Eligible voters present at each vote (Unit 8 never votes): M1 before Novak arrives; M2 with Novak and Moreau; M3/elections/M4 after Moreau leaves.
    m1_voters = {1, 2, 3, 4, 6, 9, 11, 12, 7}
    m2_voters = m1_voters | {5}
    later = m2_voters - {4}
    votes = {
        1: ('ordinary', {1, 2, 3, 4, 6, 9, 11, 12, 7}, set(), m1_voters),
        2: ('ordinary', {1, 2, 3, 4, 6, 11, 12, 7}, {5, 9}, m2_voters),
        3: ('special', {1, 2, 5, 6, 9, 11, 12, 3, 7}, set(), later),
        4: ('ordinary', {1, 2, 5, 6}, {3, 9, 12, 7}, later),
    }
    res = []
    for m, (typ, f, a, present) in votes.items():
        assert f | a <= present and not (f & a)
        vf, va, ab = s(f), s(a), s(present - f - a)
        passed = vf > va if typ == 'ordinary' else D(vf) >= D(2000) / 3
        res.append({'motion': m, 'type': typ, 'for': vf, 'against': va, 'abstain': ab, 'passed': passed})
    assert [r['passed'] for r in res] == [True, True, True, False]
    budget = D(185000)
    q9 = r2(budget * ENT[9] / 1000 / 4)
    levy = D(97345)
    shares = {u: r2(levy * e / 1000) for u, e in ENT.items()}
    first6 = r2(shares[6] * D('0.40')); second6 = shares[6] - first6
    ballots = {1: ['Okafor', 'Ishikawa', 'Petrov'], 2: ['Okafor', 'Duval', 'Ishikawa'], 3: ['Duval', 'Petrov', 'Whitfield'], 5: ['Petrov', 'Ishikawa'],
               6: ['Ishikawa', 'Okafor', 'Whitfield'], 9: ['Petrov', 'Duval', 'Whitfield'], 11: ['Duval', 'Ishikawa', 'Petrov'], 12: ['Okafor', 'Whitfield'],
               7: ['Duval', 'Okafor', 'Whitfield']}
    assert set(ballots) == later
    tally = {c: 0 for c in ['Duval', 'Ishikawa', 'Okafor', 'Petrov', 'Whitfield']}
    for u, b in ballots.items():
        for c in b: tally[c] += ENT[u]
    ranked = sorted(tally, key=lambda c: -tally[c])
    assert len(set(tally.values())) == 5
    f = float
    return {
        'association': "Harbourview Court Owners' Association", 'meeting_date': '2026-09-17', 'quorum_entitlements': quorum,
        'resolutions': res, 'budget_2027_gbp': f(budget), 'unit_9_quarterly_service_charge_gbp': f(q9), 'special_levy_gbp': f(levy),
        'levy_shares': [{'unit': u, 'amount_gbp': f(shares[u])} for u in sorted(shares)], 'levy_sum_of_shares_gbp': f(sum(shares.values())),
        'unit_6_first_instalment_gbp': f(first6), 'unit_6_second_instalment_gbp': f(second6),
        'director_votes': [{'candidate': c, 'votes': tally[c]} for c in sorted(tally)], 'directors_elected': ranked[:3],
    }

NOTES = ("Traps: Unit 7's proxy becomes valid (quorum 752 -> 835; Unit 7's directed votes added everywhere); Unit 10's late proxy stays invalid; Unit 8 is in "
         "arrears (counts for quorum, never votes; its ballot is void); Novak (Unit 5) arrives after Motion 1 and is not in the quorum; Moreau (Unit 4) leaves after "
         "Motion 2. Motion 3 needs >= 666.67 of ALL 1,000 entitlements: 576 + 88 (Unit 3 corrected) + 83 (Unit 7) = 747 -> passed; either correction alone is not enough. "
         "Election: Unit 7's ballot (Duval, Okafor, Whitfield) makes Okafor 405 edge out Petrov 404. Motion 4: Unit 11 abstains and Unit 7 votes against -> 317 v 352, fails. "
         "Levy shares 97.345 x entitlement rounded half up; unit 6 first instalment 40% of 10,707.95. Computed in verification/frontier-extraction/e5.py.")

if __name__ == '__main__':
    import json; e = expected(); print(json.dumps(e, indent=1)); print(len(DOC.split()), 'words')
