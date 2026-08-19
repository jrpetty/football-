"""L0 - ingestion. A source profile per pricing file you own (design doc s03)."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
from pathlib import Path

from .config import ROOT, sources
from .match import resolve, verdict
from .normalise import normalise
from .parse import parse_title
from .reading import read_rows, to_date, to_number


def _hash(source_id: str, row: dict) -> str:
    return hashlib.sha256(
        (source_id + "|" + json.dumps(row, sort_keys=True, default=str)).encode()
    ).hexdigest()[:32]


def _excluded(row: dict, profile: dict, title: str, gross: float) -> str | None:
    for rule in profile.get("exclude_if") or []:
        if "title_matches" in rule and re.search(rule["title_matches"], title or ""):
            return f"title matched exclusion {rule['title_matches'][:40]}"
        if "price_gross_below" in rule and gross < float(rule["price_gross_below"]):
            return f"price {gross} below floor {rule['price_gross_below']}"
    return None


def ingest_file(con, path: Path, profile: dict, reference_grade: str = "B") -> dict:
    cols = profile["columns"]
    fmt = profile.get("date_format", "%Y-%m-%d")
    stats = {"read": 0, "excluded": 0, "duplicate": 0, "review": 0, "loaded": 0, "skipped_junk": 0}
    batch = path.name

    rows, meta = read_rows(path, profile.get("header_row"))
    stats["skipped_junk"] = meta["skipped_blank"] + meta["skipped_total_rows"]
    if meta.get("junk_rows_above_header"):
        stats["skipped_junk"] += meta["junk_rows_above_header"]

    if True:
        for row in rows:
            stats["read"] += 1
            title = (row.get(cols["raw_title"]) or "").strip()
            gross = to_number(row.get(cols["price_gross"]))
            if gross is None:
                stats["excluded"] += 1
                continue

            if reason := _excluded(row, profile, title, gross):
                stats["excluded"] += 1
                continue

            row_hash = _hash(profile["id"], row)
            if con.execute("SELECT 1 FROM raw_observations WHERE row_hash=?", [row_hash]).fetchone():
                stats["duplicate"] += 1
                continue

            obs_id = con.execute("SELECT nextval('seq_obs')").fetchone()[0]
            con.execute(
                "INSERT INTO raw_observations VALUES (?,?,?,?,?,?)",
                [obs_id, profile["id"], batch, json.dumps(row), row_hash, dt.datetime.now()],
            )

            grade_raw = (row.get(cols.get("grade_raw", "")) or "").strip()
            grade = (profile.get("grade_map") or {}).get(grade_raw)
            spec = parse_title(title)
            config_id, conf, method = resolve(spec)

            if grade == "SALVAGE":
                _queue(con, "grade", "salvage - routes to the parts model, not the working-unit pool", row)
                stats["review"] += 1
                continue
            if grade is None:
                _queue(con, "grade", f"unmapped grade {grade_raw!r} - add it to {profile['id']}.grade_map", row)
                stats["review"] += 1
                continue
            if config_id is None or verdict(conf) == "review":
                _queue(con, "identity", f"{method} (confidence {conf})", row, spec.as_dict())
                stats["review"] += 1
                continue

            con.execute(
                "INSERT INTO matched_listings VALUES (?,?,?,?,?,?)",
                [obs_id, config_id, json.dumps(spec.as_dict()), spec.confidence, conf, method],
            )
            _ensure_config(con, config_id, spec)

            sold_at = to_date(row.get(cols.get("sold_at", "")), fmt)
            qty = int(to_number(row.get(cols.get("quantity", ""))) or 1)
            shipping = to_number(row.get(profile.get("price", {}).get("shipping_column") or "")) or 0.0
            n = normalise(gross, profile, grade, reference_grade, qty, shipping)

            con.execute(
                "INSERT INTO normalised_obs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                [obs_id, profile["id"], config_id, profile.get("observation_type", "sold"),
                 sold_at, qty, n.price_raw, n.price_net, n.price_norm, n.grade_obs,
                 n.channel, n.w_trust, 1.0, n.w_grade, n.w_qty],
            )
            stats["loaded"] += 1
    return stats


def _ensure_config(con, config_id: str, spec) -> None:
    if con.execute("SELECT 1 FROM configs WHERE config_id=?", [config_id]).fetchone():
        return
    con.execute("INSERT INTO configs VALUES (?,?,?,?,?,?,?,?,?)",
                [config_id, spec.model_id, spec.cpu_id, spec.gpu_id, spec.ram_gb,
                 spec.storage_gb, spec.panel, spec.touch, dt.date.today()])


def _queue(con, stage: str, reason: str, payload: dict, candidates: dict | None = None) -> None:
    rid = con.execute("SELECT nextval('seq_review')").fetchone()[0]
    con.execute("INSERT INTO review_queue VALUES (?,?,?,?,?,?,?)",
                [rid, stage, reason, json.dumps(payload), json.dumps(candidates or {}),
                 "open", dt.datetime.now()])


def ingest_all(con, incoming: Path | None = None) -> dict:
    incoming = incoming or ROOT / "data" / "incoming"
    totals: dict[str, dict] = {}
    for source_id, profile in sources().items():
        for path in sorted(incoming.glob(profile.get("file_glob", "*.csv"))):
            if path.suffix.lower() not in {".csv", ".tsv", ".txt", ".xlsx", ".xlsm", ".xltx"}:
                continue
            stats = ingest_file(con, path, profile)
            totals[f"{source_id}:{path.name}"] = stats
    return totals
