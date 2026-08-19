"""Generate ~6 months of plausible pricing history for the demo warehouse.

Works by inverting the normalisation chain: start from a known reference value,
push it back through time, grade, channel, fees and VAT to produce the gross
price a source would actually have recorded. That also exercises L3 in reverse.
"""
from __future__ import annotations

import csv
import datetime as dt
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pricer.config import ROOT, cfg, channel_multiplier, grade_multiplier, lambda_for, catalog, sources

TODAY = dt.date(2026, 8, 19)
WINDOW_DAYS = 190
OUT = ROOT / "data" / "demo"

# title -> (model_id, reference value today: ex-VAT, grade B, own retail)
CONFIGS = [
    ("Dell Latitude 5420 i5-1145G7 16GB 512GB NVMe 14in FHD",        "dell-latitude-5420", 207.0),
    ("Dell Latitude 5420 i5-1145G7 8GB 256GB",                       "dell-latitude-5420", 186.0),
    ("Dell Latitude 5420 i5-1135G7 16GB 512GB",                      "dell-latitude-5420", 195.0),
    ("Dell Latitude 5420 i5-1145G7 32GB 1TB",                        "dell-latitude-5420", 243.0),
    ("HP EliteBook 840 G7 i5-10310U 16GB 256GB",                     "hp-elitebook-840-g7", 175.0),
    ("HP EliteBook 840 G7 i5-10310U 8GB 256GB",                      "hp-elitebook-840-g7", 165.0),
    ("HP EliteBook 840 G7 i7-1165G7 16GB 512GB",                     "hp-elitebook-840-g7", 214.0),
    ("Lenovo ThinkPad X1 Carbon Gen 9 i7-1165G7 16GB 512GB",         "lenovo-thinkpad-x1c-g9", 405.0),
    ("Lenovo ThinkPad X1 Carbon Gen 9 i7-1185G7 16GB 1TB",           "lenovo-thinkpad-x1c-g9", 468.0),
    ("Lenovo ThinkPad T14 Gen 2 i5-1135G7 16GB 512GB",               "lenovo-thinkpad-t14-g2", 215.0),
    ("Dell Latitude 7420 i5-1145G7 16GB 512GB",                      "dell-latitude-7420", 250.0),
    ("Dell Latitude 7420 i7-1185G7 16GB 512GB",                      "dell-latitude-7420", 288.0),
    ("Apple MacBook Air M1 8GB 256GB",                               "apple-macbook-air-m1", 470.0),
    ("Apple MacBook Air M1 16GB 512GB",                              "apple-macbook-air-m1", 579.0),
    ("Dell Inspiron 3511 i3-1115G4 8GB 256GB 15.6in",                "dell-inspiron-3511", 95.0),
    ("Dell XPS 13 9310 i7-1185G7 16GB 1TB 4K Touch",                 "dell-xps-13-9310", 420.0),
    ("Lenovo Legion 5 15 i7-11800H 16GB 512GB RTX 3060",             "lenovo-legion-5-15", 480.0),
]

GRADES = [("A_PLUS", 0.05), ("A", 0.22), ("B", 0.45), ("C", 0.23), ("D", 0.05)]

SOURCE_LABELS = {
    "ebay_sold_uk": ("Very Good", "Good", "Acceptable"),
}
GRADE_LABELS = {
    "ebay_sold_uk": {"A_PLUS": "New (other)", "A": "Very Good", "B": "Good", "C": "Acceptable", "D": "Acceptable"},
    "own_pos": {"A_PLUS": "A+", "A": "A", "B": "B", "C": "C", "D": "D"},
    "trade_auction": {"A_PLUS": "Grade 1", "A": "Grade 1", "B": "Grade 2", "C": "Grade 3", "D": "Grade 4"},
    "supplier_stocklist": {"A_PLUS": "A", "A": "A", "B": "B", "C": "C", "D": "C"},
}


def pick(rng, weighted):
    r, acc = rng.random(), 0.0
    for value, w in weighted:
        acc += w
        if r <= acc:
            return value
    return weighted[-1][0]


def gross_for(value_ref: float, months_ago: float, lam: float, grade: str,
              profile: dict, rng: random.Random) -> float:
    """Invert the L3 chain to recover the price this source would have shown."""
    value = value_ref * math.exp(lam * months_ago)          # it was worth more back then
    value *= grade_multiplier(grade)
    value /= channel_multiplier(profile["channel"])
    if profile.get("observation_type") == "ask":
        value /= float(profile.get("ask_haircut", 1.0))

    price = profile.get("price", {})
    fees = price.get("fees") or {}
    rate, fixed = float(fees.get("rate", 0.0)), float(fees.get("fixed", 0.0))
    vat = cfg()["business"]["vat_rate"]
    denom = (1 / (1 + vat) if price.get("vat_treatment") == "inclusive" else 1.0) - rate
    gross = (value + fixed) / denom
    return round(gross * math.exp(rng.gauss(0, 0.07)), 2)


def main():
    rng = random.Random(4242)
    OUT.mkdir(parents=True, exist_ok=True)
    profiles = sources()
    rows: dict[str, list[dict]] = {k: [] for k in profiles}
    counts = {"ebay_sold_uk": 190, "own_pos": 95, "trade_auction": 70, "supplier_stocklist": 45}

    for source_id, n in counts.items():
        profile = profiles[source_id]
        for _ in range(n):
            title, model_id, value_ref = CONFIGS[rng.randrange(len(CONFIGS))]
            days_ago = rng.randrange(1, WINDOW_DAYS)
            date = TODAY - dt.timedelta(days=days_ago)
            grade = pick(rng, GRADES)
            lam = lambda_for(catalog()["models"][model_id])
            gross = gross_for(value_ref, days_ago / 30.0, lam, grade, profile, rng)
            qty = rng.randrange(1, 25) if source_id == "supplier_stocklist" else 1
            rows[source_id].append({
                "title": title, "gross": gross, "date": date, "grade": grade, "qty": qty})

    specs = [
        ("ebay_sold_uk", "ebay_sold_demo.csv",
         ["Item Title", "Sold For", "Postage", "Date Sold", "Qty", "Condition"],
         lambda r: [r["title"], f"{r['gross']:.2f}", "0.00", r["date"].isoformat(), r["qty"],
                    GRADE_LABELS["ebay_sold_uk"][r["grade"]]]),
        ("own_pos", "own_pos_demo.csv",
         ["invoice_date", "description", "gross_price", "grade", "serial"],
         lambda r: [r["date"].isoformat(), r["title"], f"{r['gross']:.2f}",
                    GRADE_LABELS["own_pos"][r["grade"]], ""]),
        ("trade_auction", "auction_demo.csv",
         ["SALE DATE", "LOT DESCRIPTION", "HAMMER", "UNITS", "GRADE"],
         lambda r: [r["date"].strftime("%d/%m/%Y"), r["title"].upper(), f"{r['gross']:.2f}",
                    r["qty"], GRADE_LABELS["trade_auction"][r["grade"]]]),
        ("supplier_stocklist", "supplier_demo.csv",
         ["List Date", "Model", "Unit Price", "Available", "Cond"],
         lambda r: [r["date"].isoformat(), r["title"], f"{r['gross']:.2f}", r["qty"],
                    GRADE_LABELS["supplier_stocklist"][r["grade"]]]),
    ]

    for source_id, filename, header, render in specs:
        path = OUT / filename
        with open(path, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(header)
            for r in sorted(rows[source_id], key=lambda r: r["date"]):
                w.writerow(render(r))
        print(f"  {path.relative_to(ROOT)}  {len(rows[source_id])} rows")


if __name__ == "__main__":
    main()
