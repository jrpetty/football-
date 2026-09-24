# E1 - offsite planning email thread. Every derived field is computed below from the facts in the thread.
import datetime as dt
from decimal import Decimal as D, ROUND_HALF_UP
def r2(x): return x.quantize(D('0.01'), ROUND_HALF_UP)

INSTRUCTIONS = "Read the email thread below and extract the FINAL agreed arrangements for the offsite into JSON that follows the schema exactly."

DOC = r"""
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Maja Lind <maja.lind@halvorsen-maritime.no>
Date: Mon 3 Aug 2026 09:12 (UTC+02:00)
Subject: Leadership offsite October 2026 - request for proposal

Dear Mr Meireles,

Halvorsen Maritime AS is planning its annual leadership offsite, and a colleague at the Aveiro tourism office recommended Hotel Castelo da Ria to us. We are looking at the following:

- Arrival Tuesday 13 October 2026, departure Friday 16 October 2026 (three nights).
- 18 single-occupancy rooms, all in the Superior category.
- A meeting room for our working sessions on Wednesday 14 October (full day).
- One private group dinner, provisionally on Wednesday 14 October.
- Transfers from Porto airport (OPO) to the hotel on the arrival day and back to the airport on the departure day.

Could you send us a proposal with rates, taxes and payment terms? We would like to have everything signed before the end of August, because our budget committee meets on 1 September.

Best regards,
Ingrid Solberg
Head of People & Culture
Halvorsen Maritime AS, Bergen

----------------------------------------------------------------------
From: Duarte Meireles <duarte.meireles@castelodaria.pt>
To: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Cc: Maja Lind <maja.lind@halvorsen-maritime.no>
Date: Tue 4 Aug 2026 10:40 (UTC+01:00)
Subject: RE: Leadership offsite October 2026 - request for proposal

Dear Ms Solberg,

Thank you for thinking of us. I am pleased to confirm availability for your dates and to propose the following. All prices are in euros.

Accommodation (per room per night, breakfast included):
- Superior room, single occupancy: EUR 142.00
- Deluxe room, single occupancy: EUR 176.00
Accommodation is subject to VAT at 6%. The municipal tourist tax of Aveiro, EUR 2.00 per guest per night, is charged in addition; it is not subject to VAT and no discount of any kind applies to it.

Meeting room - Sala Ria (theatre style for up to 40 people): EUR 950.00 per full day, subject to VAT at 23%. Two coffee breaks and still water are included in the day rate.

Private dinner in our Restaurant Moliceiro: EUR 58.00 per cover for the three-course menu with wine pairing, subject to VAT at 13%. Please note that private dining requires a minimum of 20 covers; if fewer guests attend, 20 covers are charged.

Transfers between OPO and the hotel, per one-way trip:
- Minibus (up to 16 passengers): EUR 190.00
- Coach (17 to 30 passengers): EUR 310.00
Transfers are subject to VAT at 6%.

Commercial conditions:
1. We offer a 10% discount on the accommodation rates (not on any other service) if the signed contract reaches us no later than Friday 21 August 2026.
2. A deposit of 30% of the total contract value is due within 7 days of the date of signature. The balance is due no later than 21 days before the arrival date.
3. Cancellation of the whole group is free of charge until 30 days before the arrival date; after that, the cancellation scale in the contract applies.
4. VAT is calculated separately for each service (accommodation, meeting room, dinner, transfers) on the net total of that service and rounded to the nearest cent.

As soon as you confirm the final room list I will prepare the contract. May I also suggest a guided moliceiro boat tour on the Ria de Aveiro? It is very popular with groups in October, although I have not included it in this proposal.

Kind regards,
Duarte Meireles
Group Sales Manager
Hotel Castelo da Ria, Aveiro, Portugal

----------------------------------------------------------------------
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Maja Lind <maja.lind@halvorsen-maritime.no>; Tomas Berg <tomas.berg@halvorsen-maritime.no>
Date: Wed 5 Aug 2026 09:47 (UTC+02:00)
Subject: RE: RE: Leadership offsite October 2026 - request for proposal

Dear Duarte (if I may),

Thank you, this looks very good. A few changes after discussing with our CEO:

1. Our CEO and our CFO should have Deluxe rooms, so please make it 16 Superior rooms and 2 Deluxe rooms (18 rooms in total, as before).
2. We would like the meeting room on Thursday 15 October as well as on Wednesday 14 October.
3. Please move the dinner to Thursday 15 October - Wednesday evening will be free time.

We will skip the boat tour this year, but thank you for the suggestion. I am copying Tomas Berg from our finance team, who will handle the contract and the payments.

Best,
Ingrid

----------------------------------------------------------------------
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Date: Wed 5 Aug 2026 10:14 (UTC+02:00)
Subject: RE: RE: Leadership offsite October 2026 - request for proposal

Duarte, one more thing: please add an extra night for the two Deluxe guests - they will arrive on Monday 12 October.

Ingrid

----------------------------------------------------------------------
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Date: Wed 5 Aug 2026 10:31 (UTC+02:00)
Subject: RE: RE: Leadership offsite October 2026 - request for proposal

Duarte, please disregard my email of 10:14 - it was sent in error (I mixed this up with another event). The CEO and the CFO arrive with everyone else on 13 October.

Apologies for the confusion,
Ingrid

----------------------------------------------------------------------
From: Duarte Meireles <duarte.meireles@castelodaria.pt>
To: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Cc: Maja Lind <maja.lind@halvorsen-maritime.no>; Tomas Berg <tomas.berg@halvorsen-maritime.no>
Date: Wed 5 Aug 2026 15:02 (UTC+01:00)
Subject: RE: Leadership offsite October 2026 - request for proposal

Dear Ingrid,

Noted on the rooms and on the dinner date (Thursday 15 October), and no problem about the 10:14 message.

Regarding the meeting room on Thursday 15 October: Sala Ria is booked by another client from 13:00 that day, so on Thursday we can only offer it as a half day (08:30 to 13:00). A half day is charged at 60% of the full-day rate. Wednesday 14 October remains a full day. Please let me know if this works for you.

Kind regards,
Duarte

----------------------------------------------------------------------
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Date: Wed 5 Aug 2026 16:20 (UTC+02:00)
Subject: RE: Leadership offsite October 2026 - request for proposal

That works - a full day on Wednesday and the half day on Thursday morning, both in Sala Ria. Thank you!

Ingrid

----------------------------------------------------------------------
From: Tomas Berg <tomas.berg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Date: Thu 6 Aug 2026 08:05 (UTC+02:00)
Subject: Offsite 13-16 October - invoicing details

Hello Duarte,

I look after the finance side of the offsite. Please address all invoices to:

Halvorsen Maritime AS
Attn: Accounts Payable
Strandkaien 14, 5013 Bergen, Norway
Org. no. 912 448 305

and send them by email to faktura@halvorsen-maritime.no (not to me personally), quoting our purchase order number PO-HM-26-0418.

Our internal budget for the offsite is NOK 140,000 including everything the hotel will charge us. For budget purposes we convert at EUR 1 = NOK 11.62. Could you also confirm whether the tourist tax is part of the contract value on which the 30% deposit is calculated?

Kind regards,
Tomas Berg
Financial Controller

----------------------------------------------------------------------
From: Duarte Meireles <duarte.meireles@castelodaria.pt>
To: Tomas Berg <tomas.berg@halvorsen-maritime.no>
Cc: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Date: Thu 6 Aug 2026 11:20 (UTC+01:00)
Subject: RE: Offsite 13-16 October - invoicing details

Dear Tomas,

Thank you, the invoicing details are noted. Yes: the total contract value includes all services, their VAT and the tourist tax, and the deposit is 30% of that total, rounded to the nearest cent. The balance is simply the total minus the deposit.

A note on the transfers so that you can check the numbers later. On the arrival day our driver meets each arriving flight separately: we send one vehicle per flight, a minibus if the group on that flight is 16 people or fewer and a coach if it is larger, and the pickup is scheduled 45 minutes after the flight's scheduled landing time (Portuguese local time). On the departure day we send a single vehicle for the whole group, leaving the hotel at 09:30.

Best regards,
Duarte

----------------------------------------------------------------------
From: Tomas Berg <tomas.berg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Date: Fri 7 Aug 2026 13:48 (UTC+02:00)
Subject: RE: Offsite 13-16 October - invoicing details

Duarte, a correction to my email of yesterday: our purchase order number is PO-HM-26-0481 - I transposed the last two digits. Please use this number on the contract and on every invoice.

Tomas

----------------------------------------------------------------------
From: Maja Lind <maja.lind@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Date: Mon 10 Aug 2026 14:30 (UTC+02:00)
Subject: Offsite rooming list and flights

Hi Duarte,

Here is our rooming list with the flights. All flight times below are copied from our travel agency's booking tool, which shows them in UTC. As far as I understand, Portugal will be on UTC+1 during the whole offsite (summer time only ends on 25 October).

Flight group 1 - KL 1206 Amsterdam to Porto, scheduled landing 11:05 UTC:
Ingrid Solberg, Henrik Aas (CEO, Deluxe), Liv Brekke (CFO, Deluxe), Tomas Berg, Maja Lind, Sigrid Haugen, Erik Dahl, Nora Strand, Kari Moen, Anders Lie - 10 people.

Flight group 2 - TP 1957 Oslo to Porto, scheduled landing 13:50 UTC:
Ola Nygard, Jonas Vik, Silje Bakke, Magnus Holm, Hanne Eide, Petter Rod, Ane Fjeld, Lars Myhre - 8 people.

Everyone else is in a Superior room, and everybody arrives on 13 October and leaves on 16 October.

Dietary requirements: vegetarian - Nora Strand, Hanne Eide and Anders Lie; gluten-free - Silje Bakke. No other allergies were reported.

Best wishes,
Maja Lind
Executive Assistant

----------------------------------------------------------------------
From: Duarte Meireles <duarte.meireles@castelodaria.pt>
To: Maja Lind <maja.lind@halvorsen-maritime.no>
Date: Mon 10 Aug 2026 16:02 (UTC+01:00)
Subject: Automatic reply: Offsite rooming list and flights

I am out of the office until Tuesday 11 August with limited access to email. For urgent group requests please contact our reservations team at groups@castelodaria.pt.

----------------------------------------------------------------------
From: Maja Lind <maja.lind@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Date: Wed 12 Aug 2026 09:02 (UTC+02:00)
Subject: RE: Offsite rooming list and flights

Hi Duarte,

Two updates to the list:

- Petter Rod has had to withdraw, so he will not travel.
- Elin Sather from our London office will join us instead. She flies BA 478 London Heathrow to Porto, scheduled landing 17:20 UTC, and needs a Superior room. Jonas Vik will now travel via London and take the same BA 478 flight as Elin, so he is no longer on TP 1957.

Elin is vegetarian and also needs gluten-free meals.

Thanks,
Maja

----------------------------------------------------------------------
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Maja Lind <maja.lind@halvorsen-maritime.no>; Tomas Berg <tomas.berg@halvorsen-maritime.no>
Date: Fri 14 Aug 2026 16:45 (UTC+02:00)
Subject: RE: Offsite rooming list and flights

Duarte,

Unfortunately Liv Brekke cannot join us after all because of a board meeting. Please release her Deluxe room; we do not need a replacement room.

Also, Ola Nygard will arrive one day late, on Wednesday 14 October. He will take a taxi from the airport, so he needs no arrival transfer, and he only needs his room from the 14th (he leaves with everyone else on the 16th).

Everything else stays as it is.

Ingrid

----------------------------------------------------------------------
From: Maja Lind <maja.lind@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Date: Mon 17 Aug 2026 10:10 (UTC+02:00)
Subject: RE: Offsite rooming list and flights

Hi Duarte, a small correction to my list of 10 August: it is Kari Moen, not Hanne Eide, who is vegetarian. Hanne has no dietary requirements.

Maja

----------------------------------------------------------------------
From: Duarte Meireles <duarte.meireles@castelodaria.pt>
To: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Cc: Maja Lind <maja.lind@halvorsen-maritime.no>; Tomas Berg <tomas.berg@halvorsen-maritime.no>
Date: Tue 18 Aug 2026 12:00 (UTC+01:00)
Subject: RE: Offsite rooming list and flights

Dear all,

All changes are noted. One piece of news from our side: British Airways has retimed BA 478 for the season, and on 13 October it is now scheduled to land at 18:00 UTC instead of 17:20 UTC. Our pickup for that flight will follow the new time, in line with the 45-minute rule.

For the dinner on Thursday 15 October I have assumed that everyone staying at the hotel will attend. Please tell me if you expect anyone else.

I attach the draft contract with all of the above. The contract is dated the day it is signed by you.

Kind regards,
Duarte

----------------------------------------------------------------------
From: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Tomas Berg <tomas.berg@halvorsen-maritime.no>
Date: Wed 19 Aug 2026 11:37 (UTC+02:00)
Subject: RE: Offsite rooming list and flights

Dear Duarte,

Thank you for the draft. Two guests from our customer Vestfjord Shipping will join us for the dinner on the 15th; they are not staying at the hotel and do not need transfers. Apart from that, the draft is correct and Tomas will send you the signed contract tomorrow.

Best,
Ingrid

----------------------------------------------------------------------
From: Tomas Berg <tomas.berg@halvorsen-maritime.no>
To: Duarte Meireles <duarte.meireles@castelodaria.pt>
Cc: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Date: Thu 20 Aug 2026 16:05 (UTC+02:00)
Subject: Signed contract - Halvorsen Maritime offsite

Hello Duarte,

Please find attached the contract, updated with the two dinner guests and signed today by Henrik Aas on behalf of Halvorsen Maritime AS. We will pay the deposit by bank transfer before the deadline.

Kind regards,
Tomas

----------------------------------------------------------------------
From: Duarte Meireles <duarte.meireles@castelodaria.pt>
To: Tomas Berg <tomas.berg@halvorsen-maritime.no>
Cc: Ingrid Solberg <ingrid.solberg@halvorsen-maritime.no>
Date: Thu 20 Aug 2026 17:30 (UTC+01:00)
Subject: RE: Signed contract - Halvorsen Maritime offsite

Dear Tomas,

Received with thanks. The signed contract reached us today, so the 10% early-signature discount applies to all room nights. You will receive the deposit invoice shortly; the deposit, the balance, their due dates and the free-cancellation deadline are exactly as set out in my proposal of 4 August.

We look forward to welcoming your team in Aveiro.

Kind regards,
Duarte Meireles
"""

SCHEMA = r"""
{
  "hotel_name": string,
  "contract_signed_date": "YYYY-MM-DD",
  "discount_applied": boolean,               // whether the 10% early-signature discount applies
  "arrival_date": "YYYY-MM-DD",              // the group's arrival date
  "departure_date": "YYYY-MM-DD",
  "nights": number,                          // nights between the group's arrival and departure dates
  "attendee_count": number,                  // Halvorsen Maritime staff attending (excluding external dinner guests)
  "superior_rooms": number,                  // rooms booked in each category
  "deluxe_rooms": number,
  "room_nights": number,                     // total room nights over all rooms
  "superior_rate_eur": number,               // final net rate per room per night, after any discount
  "deluxe_rate_eur": number,
  "accommodation_net_eur": number,           // all room nights, net of VAT, after any discount
  "accommodation_vat_eur": number,
  "tourist_tax_eur": number,
  "meeting_room_net_eur": number,
  "meeting_room_vat_eur": number,
  "dinner_date": "YYYY-MM-DD",
  "dinner_covers_charged": number,
  "dinner_net_eur": number,
  "dinner_vat_eur": number,
  "arrival_transfers": [                     // one entry per arrival-day transfer, ordered by pickup time
    { "flight": string,                      // flight number exactly as written, e.g. "XX 1234"
      "passengers": number,
      "vehicle": "minibus" | "coach",
      "pickup_time_local": "HH:MM" }         // Portuguese local time
  ],
  "departure_vehicle": "minibus" | "coach",
  "transfers_net_eur": number,               // all arrival and departure transfers, net of VAT
  "transfers_vat_eur": number,
  "grand_total_eur": number,                 // total contract value: all services, their VAT and the tourist tax
  "deposit_eur": number,
  "deposit_due_date": "YYYY-MM-DD",          // last day on which the deposit is due
  "balance_eur": number,
  "balance_due_date": "YYYY-MM-DD",          // last day on which the balance is due
  "free_cancellation_until": "YYYY-MM-DD",   // last day on which the whole group can cancel free of charge
  "grand_total_nok": number,                 // grand_total_eur converted at the stated budget rate, rounded to 2 decimals
  "within_budget": boolean,                  // grand_total_nok is at most the stated budget
  "purchase_order": string,
  "invoice_email": string,
  "vegetarian_count": number,                // attendees needing vegetarian meals
  "gluten_free_count": number                // attendees needing gluten-free meals
}
"""

EXTRA_RULES = ("All money amounts are in the unit named by the field, as JSON numbers rounded to exactly 2 decimals (half up) where the documents do not already give them to the cent. "
               "\"X days before\" a date means that date minus X calendar days; \"within 7 days of\" a date means that date plus 7 calendar days.")

def expected():
    arrival = dt.date(2026, 10, 13); departure = dt.date(2026, 10, 16); nights = (departure - arrival).days
    staff = ['Ingrid Solberg', 'Henrik Aas', 'Tomas Berg', 'Maja Lind', 'Sigrid Haugen', 'Erik Dahl', 'Nora Strand', 'Kari Moen', 'Anders Lie',  # KL 1206 (Liv Brekke withdrew)
             'Ola Nygard', 'Silje Bakke', 'Magnus Holm', 'Hanne Eide', 'Ane Fjeld', 'Lars Myhre',  # TP 1957 list (Petter withdrew, Jonas moved)
             'Jonas Vik', 'Elin Sather']
    assert len(staff) == len(set(staff)) == 17
    deluxe = ['Henrik Aas']
    nights_of = {p: nights for p in staff}; nights_of['Ola Nygard'] = 2          # arrives 14 Oct
    sup_nights = sum(v for p, v in nights_of.items() if p not in deluxe); dlx_nights = sum(nights_of[p] for p in deluxe)
    disc = D('0.90'); sup_rate = r2(D('142.00') * disc); dlx_rate = r2(D('176.00') * disc)
    acc_net = sup_rate * sup_nights + dlx_rate * dlx_nights; acc_vat = r2(acc_net * D('0.06'))
    tax = D('2.00') * (sup_nights + dlx_nights)
    meet_net = D('950.00') + r2(D('950.00') * D('0.60')); meet_vat = r2(meet_net * D('0.23'))
    covers = max(len(staff) + 2, 20); din_net = D('58.00') * covers; din_vat = r2(din_net * D('0.13'))
    def pickup(utc_hh, utc_mm):
        t = dt.datetime(2026, 10, 13, utc_hh, utc_mm) + dt.timedelta(hours=1) + dt.timedelta(minutes=45)
        return t.strftime('%H:%M')
    groups = [('KL 1206', 9, pickup(11, 5)), ('TP 1957', 6 - 1, pickup(13, 50)), ('BA 478', 2, pickup(18, 0))]
    assert sum(g[1] for g in groups) == len(staff) - 1  # Ola takes a taxi
    veh = lambda n: 'minibus' if n <= 16 else 'coach'
    arr = [{'flight': f, 'passengers': n, 'vehicle': veh(n), 'pickup_time_local': t} for f, n, t in sorted(groups, key=lambda g: g[2])]
    dep_vehicle = veh(len(staff))
    price = {'minibus': D('190.00'), 'coach': D('310.00')}
    tr_net = sum(price[a['vehicle']] for a in arr) + price[dep_vehicle]; tr_vat = r2(tr_net * D('0.06'))
    total = acc_net + acc_vat + tax + meet_net + meet_vat + din_net + din_vat + tr_net + tr_vat
    deposit = r2(total * D('0.30')); balance = total - deposit
    signed = dt.date(2026, 8, 20)
    nok = r2(total * D('11.62'))
    veg = {'Nora Strand', 'Kari Moen', 'Anders Lie', 'Elin Sather'}; gf = {'Silje Bakke', 'Elin Sather'}
    assert veg <= set(staff) and gf <= set(staff)
    f = float
    return {
        'hotel_name': 'Hotel Castelo da Ria', 'contract_signed_date': signed.isoformat(), 'discount_applied': True,
        'arrival_date': arrival.isoformat(), 'departure_date': departure.isoformat(), 'nights': nights,
        'attendee_count': len(staff), 'superior_rooms': len(staff) - len(deluxe), 'deluxe_rooms': len(deluxe), 'room_nights': sup_nights + dlx_nights,
        'superior_rate_eur': f(sup_rate), 'deluxe_rate_eur': f(dlx_rate), 'accommodation_net_eur': f(acc_net), 'accommodation_vat_eur': f(acc_vat),
        'tourist_tax_eur': f(tax), 'meeting_room_net_eur': f(meet_net), 'meeting_room_vat_eur': f(meet_vat),
        'dinner_date': '2026-10-15', 'dinner_covers_charged': covers, 'dinner_net_eur': f(din_net), 'dinner_vat_eur': f(din_vat),
        'arrival_transfers': arr, 'departure_vehicle': dep_vehicle, 'transfers_net_eur': f(tr_net), 'transfers_vat_eur': f(tr_vat),
        'grand_total_eur': f(total), 'deposit_eur': f(deposit), 'deposit_due_date': (signed + dt.timedelta(days=7)).isoformat(),
        'balance_eur': f(balance), 'balance_due_date': (arrival - dt.timedelta(days=21)).isoformat(),
        'free_cancellation_until': (arrival - dt.timedelta(days=30)).isoformat(),
        'grand_total_nok': f(nok), 'within_budget': nok <= D('140000'),
        'purchase_order': 'PO-HM-26-0481', 'invoice_email': 'faktura@halvorsen-maritime.no',
        'vegetarian_count': len(veg), 'gluten_free_count': len(gf),
    }

NOTES = ("Traps: the 10:14 extra-night email is retracted at 10:31; Liv Brekke (Deluxe) and Petter Rod withdraw, Elin Sather joins, Jonas Vik moves to BA 478, "
         "Ola Nygard arrives a day late by taxi (2 room nights, no transfer); Thursday meeting room is a half day at 60%; dinner moved to 15 Oct with 17 staff + 2 guests "
         "but a 20-cover minimum; PO number corrected 0418 -> 0481; vegetarian list corrected (Kari not Hanne) plus Elin; BA 478 retimed 17:20 -> 18:00 UTC; "
         "booking-tool times are UTC and Portugal is UTC+1, pickup = landing + 45 min; 17 people on departure day need a coach; VAT per service rounded to the cent; "
         "tourist tax not discounted and not subject to VAT; deposit = 30% of the grand total rounded to the cent, due signature + 7 days; balance due arrival - 21 days; "
         "free cancellation until arrival - 30 days. All derived values computed with Decimal in extraction/e1.py.")

if __name__ == '__main__':
    import json; e = expected(); print(json.dumps(e, indent=1)); print(len(DOC.split()), 'words')
