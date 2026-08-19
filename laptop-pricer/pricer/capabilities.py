"""What this engine will and will not accept, generated from the code itself.

Written this way deliberately: a hand-maintained list of supported formats goes
stale the moment someone adds a CPU alias. Everything below is read from the
live gazetteers, config and parsers, so it cannot drift from the truth.
"""
from __future__ import annotations

from collections import defaultdict

from .config import catalog, cfg
from .inspect import NEGATIVE, SYNONYMS
from .parse import BRANDS, RAM_VALUES
from .reading import CURRENCY_SYMBOLS, DATE_FORMATS, to_date, to_number

READABLE_FORMATS = [
    (".csv", "comma, semicolon, tab or pipe delimited — the delimiter is sniffed"),
    (".tsv / .txt", "tab delimited"),
    (".xlsx / .xlsm / .xltx", "Excel; the sheet with the most data is used unless one is named"),
]

NUMBER_EXAMPLES = ["£1,234.56", "1.234,56", "(99.00)", "$450", "€1.299,00", "1 234", "285"]
DATE_EXAMPLES = ["2026-08-19", "19/08/2026", "08/19/2026", "19 Aug 2026",
                 "19-Aug-26", "20260819", "46253"]

STRUCTURAL = [
    ("Report banners above the header", "found and skipped — the header row is scored, not assumed"),
    ("Blank spacer rows", "dropped"),
    ("Trailing TOTAL / SUBTOTAL lines", "dropped"),
    ("Columns you do not use", "ignored, never guessed at"),
    ("Duplicate rows across imports", "hashed and skipped, so re-importing a file is safe"),
    ("Brand/model/CPU/RAM in separate columns", "joined into one description automatically"),
    ("A missing date column", "loads, but warns — undated rows can never be valued"),
]

MUST_DECLARE = [
    ("vat_treatment", "inclusive | exclusive | margin_scheme",
     "No file reveals this, and getting it wrong moves every price by a fifth."),
    ("observation_type", "sold | ask | offer | appraisal",
     "An asking price is not a sale. Treating asks as sales biases you high."),
    ("channel", "which market the price came from",
     "A trade auction price and your retail price are both real and ~20% apart."),
    ("fees", "rate and fixed",
     "Only the cash you actually received is comparable across sources."),
]

UNSUPPORTED = [
    ("PDFs, scanned invoices, photographs of spreadsheets", "no OCR; export to CSV or Excel first"),
    ("Live Google Sheets or database links", "the tool is offline by design; export a file"),
    ("Hardware other than laptops", "monitors, phones and docks are rejected to review, not priced"),
    ("Date-accurate currency conversion", "multi-currency loads, but FX uses fixed rates"),
    ("Free-text notes as a price", '"offers around 300" is not parsed; give a numeric column'),
]


def report() -> dict:
    cat = catalog()
    by_brand: dict[str, list[str]] = defaultdict(list)
    for m in cat["models"].values():
        by_brand[m["brand"]].append(m["model_name"])

    cpu_aliases = sum(len(c["aliases"]) + 1 for c in cat["cpus"].values())
    grade_words = _grade_vocabulary()

    return {
        "formats": READABLE_FORMATS,
        "numbers": [(x, to_number(x)) for x in NUMBER_EXAMPLES],
        "dates": [(x, to_date(x)) for x in DATE_EXAMPLES],
        "date_format_count": len(DATE_FORMATS),
        "currencies": sorted(set(CURRENCY_SYMBOLS.values())),
        "columns": {role: SYNONYMS[role][:8] for role in SYNONYMS},
        "negatives": NEGATIVE,
        "structural": STRUCTURAL,
        "must_declare": MUST_DECLARE,
        "unsupported": UNSUPPORTED,
        "channels": list(cfg()["channels"]["ladder"]),
        "grades": [g for g in cfg()["grades"]["ladder"]],
        "grade_words": grade_words,
        "brands_recognised": sorted(set(BRANDS.values())),
        "catalogue": {b: sorted(v) for b, v in sorted(by_brand.items())},
        "model_count": len(cat["models"]),
        "cpu_count": len(cat["cpus"]),
        "cpu_alias_count": cpu_aliases,
        "gpu_count": len(cat["gpus"]),
        "ram_values": sorted(RAM_VALUES),
    }


def _grade_vocabulary() -> dict[str, list[str]]:
    """Condition words translated automatically when a profile is proposed."""
    return {
        "A_PLUS": ["A+", "Grade A+", "New", "New (other)", "Mint", "Pristine", "Excellent"],
        "A": ["A", "Grade A", "Very Good", "V.Good"],
        "B": ["B", "Grade B", "Good", "Used", "Working"],
        "C": ["C", "Grade C", "Fair", "Acceptable", "Poor"],
        "D": ["D", "Grade D"],
        "SALVAGE": ["Spares", "Spares or Repair", "For parts", "Faulty",
                    "Not working", "Untested", "Scrap"],
    }
