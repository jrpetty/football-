"""Look at a file you already have and work out how to read it.

Answers the only question that matters when you point this at your own export:
is my data recognised as it is? It reports what each column appears to be, how
it read the dates and prices, which grade words it found, and - the real test -
what proportion of your rows resolve to an actual machine.
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .config import ROOT, cfg
from .match import resolve, verdict
from .parse import BRANDS, parse_title
from .reading import (detect_currency, detect_date_format, read_rows, to_date, to_number)

# Header words that suggest a role, strongest first.
SYNONYMS = {
    "raw_title": ["item title", "lot description", "description", "product name", "item name",
                  "title", "model", "product", "item", "spec", "details", "device", "machine"],
    "price_gross": ["sold for", "unit price", "sale price", "hammer", "price paid", "gross",
                    "sold price", "price", "amount", "value", "total", "net", "cost", "paid"],
    "sold_at": ["date sold", "sale date", "invoice date", "list date", "sold on", "completed",
                "date", "sold", "timestamp", "when"],
    "quantity": ["qty", "quantity", "units", "available", "stock", "count", "pcs", "no."],
    "grade_raw": ["grade", "condition", "cond", "cosmetic", "state", "quality"],
    "serial": ["serial", "service tag", "asset tag", "sn", "mtm", "imei", "asset"],
    "shipping": ["postage", "shipping", "delivery", "freight", "carriage"],
}
# Words that mean a column is NOT the thing its name suggests.
NEGATIVE = {"price_gross": ["qty", "quantity", "units", "count", "vat rate", "discount %"],
            "sold_at": ["update", "created", "modified"],
            "serial": ["invoice", "inv ", "cust", "customer", "order", "account"]}


@dataclass
class Column:
    name: str
    role: str | None = None
    confidence: float = 0.0
    evidence: list[str] = field(default_factory=list)
    sample: list = field(default_factory=list)


@dataclass
class Inspection:
    meta: dict
    columns: list[Column]
    mapping: dict
    currency: str | None
    date_format: str | None
    grades: list[str]
    recognition: dict
    unresolved: list[tuple[str, str]]
    warnings: list[str] = field(default_factory=list)


def _values(rows: list[dict], column: str, limit: int = 200) -> list:
    return [r[column] for r in rows[:limit] if str(r.get(column, "")).strip()]


def _header_score(name: str, role: str) -> tuple[float, str]:
    low = re.sub(r"[^a-z0-9 ]", " ", name.lower()).strip()
    for bad in NEGATIVE.get(role, []):
        if bad in low:
            return 0.0, ""
    for i, word in enumerate(SYNONYMS[role]):
        if low == word:
            return 1.0, f"header is exactly '{word}'"
        if word in low:
            return max(0.55, 0.9 - i * 0.03), f"header contains '{word}'"
    return 0.0, ""


def _value_score(values: list, role: str) -> tuple[float, str]:
    if not values:
        return 0.0, ""
    n = len(values)
    text = [str(v).strip() for v in values]

    if role == "sold_at":
        hits = sum(1 for v in values if to_date(v) is not None)
        return (0.9, f"{hits}/{n} parse as dates") if hits / n > 0.7 else (0.0, "")

    if role == "price_gross":
        nums = [to_number(v) for v in values]
        good = [x for x in nums if x is not None]
        if len(good) / n < 0.7:
            return 0.0, ""
        typical = sorted(good)[len(good) // 2]
        if 15 <= typical <= 8000 and len({round(x) for x in good}) > max(2, n * 0.15):
            return 0.85, f"{len(good)}/{n} numeric, median {typical:.0f}"
        return 0.3, f"{len(good)}/{n} numeric but median {typical:.0f} looks wrong for a price"

    if role == "quantity":
        nums = [to_number(v) for v in values]
        good = [x for x in nums if x is not None]
        if len(good) / n < 0.7:
            return 0.0, ""
        if all(x == int(x) and 0 < x <= 999 for x in good) and max(good) <= 999:
            return 0.6, f"small whole numbers (max {int(max(good))})"
        return 0.0, ""

    if role == "raw_title":
        long_enough = sum(1 for v in text if len(v) >= 15)
        branded = sum(1 for v in text if any(b in v.upper() for b in BRANDS))
        unique = len(set(text)) / n
        if branded / n > 0.4:
            return 0.95, f"{branded}/{n} contain a known laptop brand"
        if long_enough / n > 0.6 and unique > 0.5:
            return 0.5, f"long, mostly distinct text ({unique:.0%} unique)"
        return 0.0, ""

    if role == "grade_raw":
        distinct = Counter(text)
        if 1 < len(distinct) <= 14 and all(len(v) <= 28 for v in text):
            return 0.7, f"{len(distinct)} distinct short values"
        return 0.0, ""

    if role == "serial":
        alnum = sum(1 for v in text if re.fullmatch(r"[A-Za-z0-9\-]{5,20}", v))
        if alnum / n > 0.7 and len(set(text)) / n > 0.9:
            return 0.6, "unique alphanumeric codes"
        return 0.0, ""

    return 0.0, ""


def inspect(path: str | Path, sample_rows: int = 300) -> Inspection:
    rows, meta = read_rows(path)
    warnings: list[str] = []
    if not rows:
        return Inspection(meta, [], {}, None, None, [], {}, [], ["no data rows found"])

    columns: list[Column] = []
    scored: dict[str, list[tuple[float, str, list[str]]]] = {r: [] for r in SYNONYMS}
    for name in meta["columns"]:
        values = _values(rows, name)
        col = Column(name=name, sample=values[:3])
        for role in SYNONYMS:
            h_score, h_why = _header_score(name, role)
            v_score, v_why = _value_score(values, role)
            # a name alone is a guess; a name the values agree with is evidence
            combined = h_score * 0.55 + v_score * 0.45 + (0.15 if h_score and v_score else 0)
            if combined > 0.25:
                scored[role].append((combined, name, [w for w in (h_why, v_why) if w]))
        columns.append(col)

    mapping, taken = {}, set()
    for role in ["raw_title", "price_gross", "sold_at", "grade_raw", "quantity", "serial", "shipping"]:
        best = sorted((c for c in scored[role] if c[1] not in taken), reverse=True)
        if best and best[0][0] >= 0.35:      # a weak guess is worse than no guess
            score, name, why = best[0]
            mapping[role] = name
            taken.add(name)
            for col in columns:
                if col.name == name:
                    col.role, col.confidence, col.evidence = role, round(score, 2), why

    for required in ("raw_title", "price_gross"):
        if required not in mapping:
            warnings.append(f"could not identify a '{required}' column - set it by hand in the profile")

    currency = detect_currency(_values(rows, mapping["price_gross"])) if "price_gross" in mapping else None
    date_format = detect_date_format(_values(rows, mapping["sold_at"])) if "sold_at" in mapping else None
    if date_format in ("%d/%m/%Y", "%m/%d/%Y"):
        warnings.append(f"dates read as {date_format} - check a row where the day exceeds 12 to be sure")

    grades = []
    if "grade_raw" in mapping:
        grades = [g for g, _ in Counter(_values(rows, mapping["grade_raw"], 2000)).most_common(20)]

    recognition, unresolved = _dry_run(rows[:sample_rows], mapping)
    return Inspection(meta, columns, mapping, currency, date_format, grades,
                      recognition, unresolved, warnings)


def _dry_run(rows: list[dict], mapping: dict) -> tuple[dict, list[tuple[str, str]]]:
    """The real test: how many of these rows become a machine we can price?"""
    if "raw_title" not in mapping:
        return {"tested": 0}, []
    counts = Counter()
    misses: list[tuple[str, str]] = []
    for row in rows:
        title = str(row.get(mapping["raw_title"], "")).strip()
        if not title:
            counts["empty title"] += 1
            continue
        spec = parse_title(title)
        config_id, conf, method = resolve(spec)
        if config_id and verdict(conf) != "review":
            counts["resolved"] += 1
            continue
        missing = [f for f in ("model_id", "cpu_id", "ram_gb", "storage_gb")
                   if not getattr(spec, f)]
        reason = ("unknown model" if "model_id" in missing
                  else "unknown CPU" if "cpu_id" in missing
                  else "no RAM/storage in the title" if missing else f"low confidence ({conf})")
        counts[reason] += 1
        if len(misses) < 12:
            misses.append((title[:64], reason))
    tested = sum(counts.values())
    return {"tested": tested, "resolved": counts["resolved"],
            "rate": counts["resolved"] / tested if tested else 0.0,
            "reasons": dict(counts.most_common())}, misses


def propose_profile(insp: Inspection, source_id: str, channel: str = "own_retail_b2c",
                    observation_type: str = "sold", trust: float = 0.8) -> dict:
    """A source profile you can save and reuse, pre-filled from what was found."""
    ladder = cfg()["grades"]["ladder"]
    guess = {}
    for word in insp.grades:
        # "Grade B", "Condition: A", "Class C" all mean the letter
        stripped = re.sub(r"(?i)\b(grade|condition|cond|class)\b\s*:?", "", word).strip()
        upper = re.sub(r"[^A-Z+]", "", stripped.upper())
        if upper in ladder:
            guess[word] = upper
        elif upper in {"A+", "APLUS"}:
            guess[word] = "A_PLUS"
        elif re.search(r"(?i)new|mint|pristine|excellent", word):
            guess[word] = "A_PLUS"
        elif re.search(r"(?i)very good|v\.?good", word):
            guess[word] = "A"
        elif re.search(r"(?i)^good$|used|working", word):
            guess[word] = "B"
        elif re.search(r"(?i)fair|acceptable|poor", word):
            guess[word] = "C"
        elif re.search(r"(?i)spares|parts|faulty|not working|untested|scrap", word):
            guess[word] = "SALVAGE"
        else:
            guess[word] = "REVIEW - map this yourself"

    profile = {
        "id": source_id,
        "label": f"Auto-detected from {Path(insp.meta['path']).name}",
        "channel": channel,
        "currency": insp.currency or cfg()["business"]["base_currency"],
        "observation_type": observation_type,
        "trust": trust,
        "file_glob": f"{re.split(r'[0-9]', Path(insp.meta['path']).stem)[0] or source_id}*"
                     f"{Path(insp.meta['path']).suffix}",
        "price": {"vat_treatment": "CONFIRM: inclusive | exclusive | margin_scheme",
                  "fees": {"rate": 0.0, "fixed": 0.0}},
        "columns": dict(insp.mapping),
        "date_format": insp.date_format or "%Y-%m-%d",
        "grade_map": guess,
        "exclude_if": [
            {"title_matches": r"(?i)\b(job ?lot|bundle|spares|faulty|pallet|mixed)\b"},
            {"price_gross_below": 20},
        ],
    }
    if insp.meta.get("header_row"):
        profile["header_row"] = insp.meta["header_row"]
    return profile


def write_profile(profile: dict, path=None) -> str:
    path = path or ROOT / "sources" / f"{profile['id']}.yml"
    with open(path, "w") as fh:
        fh.write("# Proposed by `pricer inspect`. Check the CONFIRM lines before using.\n")
        yaml.safe_dump(profile, fh, sort_keys=False, default_flow_style=False, allow_unicode=True)
    return str(path)
