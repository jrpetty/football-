"""Build a demo warehouse: ingest the sample pricing files, then book a
realistic stock book in against them. Deterministic - same output every run."""
from __future__ import annotations

import datetime as dt
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pricer import stock as stockmod
from pricer.db import reset
from pricer.ingest import ingest_all

TODAY = dt.date(2026, 8, 19)

# (title, share of the pallet) - a realistic mixed lot
CATALOGUE = [
    ("Dell Latitude 5420 i5-1145G7 16GB 512GB",                 8),
    ("HP EliteBook 840 G7 i5-10310U 16GB 256GB",                7),
    ("Lenovo ThinkPad X1 Carbon Gen 9 i7-1165G7 16GB 512GB",    4),
    ("Dell Latitude 7420 i5-1145G7 16GB 512GB",                 5),
    ("Apple MacBook Air M1 8GB 256GB",                          3),
    ("Dell Inspiron 3511 i3-1115G4 8GB 256GB",                  5),
]
GRADE_MIX = [
    ({"chassis": "none",    "screen": "clean",      "keyboard": "none",         "hinges": "pass"}, 96),
    ({"chassis": "light",   "screen": "clean",      "keyboard": "shiny",        "hinges": "pass"}, 90),
    ({"chassis": "visible", "screen": "marks",      "keyboard": "shiny",        "hinges": "pass"}, 85),
    ({"chassis": "visible", "screen": "marks",      "keyboard": "worn_legends", "hinges": "pass"}, 78),
    ({"chassis": "dented",  "screen": "scratches",  "keyboard": "worn_legends", "hinges": "stiff"}, 61),
]


def main():
    rng = random.Random(20260819)
    con = reset()

    print("Ingesting 6 months of demo pricing history ...")
    for key, s in ingest_all(con, Path(__file__).resolve().parent.parent / "data" / "demo").items():
        print(f"  {key:<44} loaded {s['loaded']:>3}  review {s['review']}  excluded {s['excluded']}")

    lots = [
        ("Northgate Asset Recovery", "NAR-2026-118", 4000.0, 120.0, TODAY - dt.timedelta(days=104)),
        ("Corporate Clearance Ltd",  "CCL-8841",     2650.0,  80.0, TODAY - dt.timedelta(days=57)),
        ("Northgate Asset Recovery", "NAR-2026-131", 3100.0, 110.0, TODAY - dt.timedelta(days=19)),
    ]

    total_units = 0
    for supplier, ref, cost, freight, acquired in lots:
        lot_id = stockmod.create_lot(con, supplier, ref, cost, acquired, freight)
        for title, count in CATALOGUE:
            for _ in range(max(1, round(count * rng.uniform(0.5, 1.0)))):
                checks, battery = GRADE_MIX[rng.randrange(len(GRADE_MIX))]
                try:
                    res = stockmod.intake(
                        con, title, checks, lot_id=lot_id,
                        serial=f"SN{rng.randrange(10**7, 10**8)}",
                        battery_health_pct=battery, as_of=acquired,
                        location=f"BIN-{rng.randrange(1, 40):02d}")
                except (ValueError, stockmod.HardStop) as exc:
                    print(f"  skipped {title}: {exc}")
                    continue
                total_units += 1

                unit_id = res["unit_id"]
                age = (TODAY - acquired).days
                # most units move through to listed; a few stall or block on parts
                roll = rng.random()
                if age > 12 and roll < 0.82:
                    ready = acquired + dt.timedelta(days=rng.randrange(3, 12))
                    listed = ready + dt.timedelta(days=rng.randrange(0, 4))
                    # listed shortly after intake, at what it was worth THEN -
                    # which is why aged stock ends up priced above today's market
                    con.execute("""UPDATE units SET ready_at=?, listed_at=?, listed_channel=?,
                                   listed_price=value_at_intake WHERE unit_id=?""",
                                [ready, listed, "own_retail_b2c", unit_id])
                    stockmod.record_event(con, unit_id, "listed", "listed")
                elif roll < 0.92:
                    stockmod.record_event(con, unit_id, "blocked", "awaiting_parts")
                else:
                    stockmod.record_event(con, unit_id, "triage", "triage")

        allocated = stockmod.close_lot(con, lot_id)
        print(f"\n{lot_id}  {supplier}  {ref}  pot £{cost + freight:,.2f} across {len(allocated)} units")
        show = sorted(allocated, key=lambda a: -(a["value_at_intake"] or 0))
        for a in show[:2] + show[-2:]:
            print(f"    {a['unit_id']}  value £{(a['value_at_intake'] or 0):>7.2f}"
                  f"   allocated £{a['cost_allocated']:>7.2f}"
                  f"   (even split would be £{(cost + freight)/len(allocated):.2f})")

    print(f"\nRevaluing {total_units} units against the pricer ...")
    print(" ", stockmod.revalue_all(con, TODAY))


if __name__ == "__main__":
    main()
