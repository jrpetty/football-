"""U1-U5 - the stock book. Units are serialised and unique: quantity is always
one. Status is derived from an append-only event log, never overwritten
(design doc s08-s11)."""
from __future__ import annotations

import datetime as dt
import json
import math
from dataclasses import dataclass

from .commercial import parts_value, price
from .config import catalog, cfg, grade_rank, lambda_for
from .estimate import estimate
from .match import config_key
from .parse import parse_title

OPEN_STATUSES = ("acquired", "in_transit", "received", "triage", "refurb",
                 "awaiting_parts", "qc", "ready", "listed", "reserved")


class HardStop(Exception):
    """A condition that makes the machine parts-only whatever it looks like."""


# ---------- grading ----------

def compute_grade(checks: dict, battery_health_pct: int | None = None) -> tuple[str, list[str]]:
    """Grade is COMPUTED from the checklist, never typed. The unit takes the
    worst band any single check triggers (design doc s09)."""
    grading = cfg()["grading"]
    for flag, message in grading["hard_stops"].items():
        if checks.get(flag):
            raise HardStop(f"{flag}: {message}")

    grades, defects = [], []
    for check, mapping in grading["checks"].items():
        answer = checks.get(check)
        if answer is None:
            continue
        if answer not in mapping:
            raise ValueError(f"unknown answer {answer!r} for check {check!r}")
        grades.append(mapping[answer])

    for name in grading["functional"]:
        if checks.get(name) is False:
            defects.append(name)

    grade = min(grades, key=grade_rank) if grades else "B"

    if battery_health_pct is not None:
        for band in grading["battery_health_bands"]:
            if battery_health_pct >= band["min_pct"]:
                if band.get("grade_cap") and grade_rank(grade) > grade_rank(band["grade_cap"]):
                    grade = band["grade_cap"]
                if band.get("defect"):
                    defects.append(band["defect"])
                break
    return grade, defects


# ---------- events ----------

def record_event(con, unit_id: str, event_type: str, to_status: str | None = None,
                 actor: str = "system", payload: dict | None = None,
                 occurred_at: dt.datetime | None = None) -> None:
    row = con.execute("SELECT status FROM units WHERE unit_id=?", [unit_id]).fetchone()
    from_status = row[0] if row else None
    eid = con.execute("SELECT nextval('seq_event')").fetchone()[0]
    con.execute("INSERT INTO unit_events VALUES (?,?,?,?,?,?,?,?)",
                [eid, unit_id, event_type, from_status, to_status, actor,
                 json.dumps(payload or {}), occurred_at or dt.datetime.now()])
    if to_status:
        con.execute("UPDATE units SET status=? WHERE unit_id=?", [to_status, unit_id])


# ---------- intake (U1) ----------

def create_lot(con, supplier: str, reference: str, total_cost: float,
               acquired_at: dt.date | None = None, freight: float = 0.0, fees: float = 0.0) -> str:
    n = con.execute("SELECT nextval('seq_lot')").fetchone()[0]
    lot_id = f"LOT-{n:04d}"
    con.execute("INSERT INTO purchase_lots VALUES (?,?,?,?,?,?,?,?,?)",
                [lot_id, supplier, reference, acquired_at or dt.date.today(),
                 total_cost, freight, fees, "value_weighted", None])
    return lot_id


def intake(con, title: str, checks: dict, lot_id: str | None = None,
           serial: str | None = None, service_tag: str | None = None,
           battery_health_pct: int | None = None, has_charger: bool = True,
           as_of: dt.date | None = None, location: str = "GOODS-IN") -> dict:
    """Book a physical machine in. Identity via L1-L2, grade via the checklist,
    value via L4 - so the operator sees what it is worth before committing."""
    as_of = as_of or dt.date.today()
    spec = parse_title(title)
    if not (spec.model_id and spec.cpu_id and spec.ram_gb and spec.storage_gb):
        raise ValueError(f"cannot resolve a configuration from {title!r} - missing "
                         f"{[f for f in ('model_id','cpu_id','ram_gb','storage_gb') if not getattr(spec,f)]}")

    grade, defects = compute_grade(checks, battery_health_pct)
    cid = config_key(spec.model_id, spec.cpu_id, spec.ram_gb, spec.storage_gb)

    con.execute("INSERT INTO configs VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                [cid, spec.model_id, spec.cpu_id, spec.gpu_id, spec.ram_gb,
                 spec.storage_gb, spec.panel, spec.touch, as_of])

    est = estimate(con, spec.model_id, spec.cpu_id, spec.ram_gb, spec.storage_gb, grade, as_of)
    cfg_row = {"cpu_id": spec.cpu_id, "ram_gb": spec.ram_gb,
               "storage_gb": spec.storage_gb, "panel": spec.panel}
    value = None
    quote = None
    if est.value is not None:
        quote = price(est.value, spec.model_id, cfg_row, grade,
                      confidence=est.confidence, band=est.band)
        value = quote.market_value_ex_vat

    n = con.execute("SELECT nextval('seq_unit')").fetchone()[0]
    unit_id = f"U-{n:04d}"
    con.execute("""INSERT INTO units (unit_id, config_id, serial, service_tag, lot_id, status,
                   grade_intake, grade_current, battery_health_pct, has_charger,
                   value_at_intake, value_current, revalued_at, location, acquired_at, notes)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                [unit_id, cid, serial, service_tag, lot_id, "received", grade, grade,
                 battery_health_pct, has_charger, value, value, as_of, location, as_of,
                 json.dumps({"title": title, "defects": defects})])
    record_event(con, unit_id, "intake", "received", payload={"grade": grade, "defects": defects})

    return {"unit_id": unit_id, "config_id": cid, "grade": grade, "defects": defects,
            "value": value, "estimate": est, "pricing": quote}


def close_lot(con, lot_id: str) -> list[dict]:
    """Allocate lot cost pro-rata to each unit's market value at intake.

    Splitting evenly makes the expensive machine look like a triumph and the
    cheap one like a disaster, when you simply bought a mixed pallet
    (design doc s09).
    """
    lot = con.execute("""SELECT total_cost, freight, fees FROM purchase_lots
                         WHERE lot_id=?""", [lot_id]).fetchone()
    if not lot:
        raise KeyError(lot_id)
    pot = float(lot[0]) + float(lot[1] or 0) + float(lot[2] or 0)

    units = con.execute("""SELECT unit_id, value_at_intake FROM units
                           WHERE lot_id=? ORDER BY unit_id""", [lot_id]).fetchall()
    if not units:
        return []

    # Two passes so the shares always sum back to the pot: reserve an even split
    # for units we could not value, then distribute the remainder pro-rata.
    even = pot / len(units)
    unvalued = [u for u, v in units if not v]
    remaining = pot - even * len(unvalued)
    total_value = sum(float(v) for _, v in units if v)

    out = []
    for unit_id, value in units:
        if total_value and value:
            share = remaining * float(value) / total_value
        else:
            share = even
        con.execute("UPDATE units SET cost_allocated=? WHERE unit_id=?", [round(share, 2), unit_id])
        record_event(con, unit_id, "cost_allocated", payload={"lot": lot_id, "share": round(share, 2)})
        out.append({"unit_id": unit_id, "value_at_intake": value, "cost_allocated": round(share, 2)})

    con.execute("UPDATE purchase_lots SET closed_at=? WHERE lot_id=?", [dt.datetime.now(), lot_id])
    return out


# ---------- revaluation and ageing (U3) ----------

def cost_landed(row: dict) -> float:
    return round(float(row.get("cost_allocated") or 0) + float(row.get("cost_parts") or 0)
                 + float(row.get("cost_labour") or 0), 2)


def revalue_all(con, as_of: dt.date | None = None) -> dict:
    """Nightly. Carry held stock at market, not at cost - the thing generic
    inventory software cannot do (design doc s10)."""
    as_of = as_of or dt.date.today()
    rows = con.execute(f"""
        SELECT u.unit_id, u.config_id, u.grade_current, c.model_id, c.cpu_id, c.ram_gb, c.storage_gb
        FROM units u JOIN configs c USING (config_id)
        WHERE u.status IN {OPEN_STATUSES}
    """).fetchall()
    updated, unpriced = 0, 0
    for unit_id, _cid, grade, model_id, cpu_id, ram, storage in rows:
        est = estimate(con, model_id, cpu_id, ram, storage, grade, as_of)
        if est.value is None:
            unpriced += 1
            continue
        p = price(est.value, model_id,
                  {"cpu_id": cpu_id, "ram_gb": ram, "storage_gb": storage},
                  grade, confidence=est.confidence, band=est.band)
        con.execute("UPDATE units SET value_current=?, revalued_at=? WHERE unit_id=?",
                    [p.market_value_ex_vat, as_of, unit_id])
        updated += 1
    return {"revalued": updated, "unpriced": unpriced, "as_of": str(as_of)}


@dataclass
class UnitEconomics:
    unit_id: str
    config_id: str
    model_id: str
    grade: str
    status: str
    days_held: int
    days_listed: int | None
    listed_price: float | None
    cost_landed: float
    value_at_intake: float | None
    value_current: float | None
    decay_to_date: float | None
    burn_per_month: float | None
    margin_abs: float | None
    margin_pct: float | None
    days_to_floor: float | None
    parts_value: float
    signal: str
    action: str


def economics(con, as_of: dt.date | None = None) -> list[UnitEconomics]:
    as_of = as_of or dt.date.today()
    policy = cfg()["stock"]
    rows = con.execute(f"""
        SELECT u.unit_id, u.config_id, u.grade_current, u.status, u.acquired_at, u.listed_at,
               u.listed_price, u.cost_allocated, u.cost_parts, u.cost_labour,
               u.value_at_intake, u.value_current,
               c.model_id, c.cpu_id, c.ram_gb, c.storage_gb, c.panel
        FROM units u JOIN configs c USING (config_id)
        WHERE u.status IN {OPEN_STATUSES} ORDER BY u.unit_id
    """).fetchall()

    out = []
    for (unit_id, cid, grade, status, acquired, listed, listed_price, c_alloc, c_parts, c_lab,
         v_intake, v_now, model_id, cpu_id, ram, storage, panel) in rows:
        model = catalog()["models"][model_id]
        landed = cost_landed({"cost_allocated": c_alloc, "cost_parts": c_parts, "cost_labour": c_lab})
        days_held = (as_of - acquired).days if acquired else 0
        days_listed = (as_of - listed).days if listed else None
        lam = lambda_for(model)
        salvage = parts_value(model, {"cpu_id": cpu_id, "ram_gb": ram,
                                      "storage_gb": storage, "panel": panel}, grade)

        decay = burn = margin_abs = margin_pct = days_to_floor = None
        if v_now:
            v_now = float(v_now)
            decay = round(float(v_intake) - v_now, 2) if v_intake else None
            burn = round(v_now * lam, 2)
            margin_abs = round(v_now - landed, 2)
            margin_pct = round(margin_abs / v_now, 4) if v_now else None
            floor_value = landed / (1 - policy["margin_floor"]) if policy["margin_floor"] < 1 else None
            if floor_value and v_now > floor_value > 0:
                days_to_floor = round(math.log(v_now / floor_value) * 30.0 / lam, 1)
            elif floor_value:
                days_to_floor = 0.0

        listed_price = float(listed_price) if listed_price else None
        signal, action = _decide(status, days_held, days_listed, listed_price,
                                 margin_pct, v_now, salvage, policy)
        out.append(UnitEconomics(unit_id, cid, model_id, grade, status, days_held, days_listed,
                                 listed_price, landed, v_intake, v_now, decay, burn,
                                 margin_abs, margin_pct, days_to_floor, salvage, signal, action))
    return out


def _decide(status, days_held, days_listed, listed_price, margin_pct, value,
            salvage, policy) -> tuple[str, str]:
    expected = policy["default_expected_days_to_sell"]
    if value is None:
        return "no valuation", "review - no comparable evidence"
    if value < salvage and policy["part_out_if_parts_value_exceeds_unit_value"]:
        return f"parts £{salvage:.0f} > whole £{value:.0f}", "part out"
    if margin_pct is not None and margin_pct < policy["trade_out_if_margin_below"]:
        return "value now below landed cost", f"trade out at £{value:.0f}"
    if days_listed and days_listed > expected * policy["reprice_if_days_listed_over_expected"]:
        over = f" at £{listed_price:.0f} vs market £{value:.0f}" if listed_price else ""
        return f"listed {days_listed}d vs {expected}d typical{over}", (
            f"reprice £{listed_price:.0f} -> £{value:.0f}" if listed_price else f"reprice to £{value:.0f}")
    if margin_pct is not None and margin_pct < policy["margin_floor"]:
        return f"margin {margin_pct:.0%} below {policy['margin_floor']:.0%} floor", "review price or costs"
    if status == "awaiting_parts":
        return "blocked on parts, still depreciating", "expedite part"
    return "on track", "hold"


def ageing(con, as_of: dt.date | None = None) -> dict:
    econ = economics(con, as_of)
    buckets = cfg()["stock"]["ageing_buckets"]
    labels = [f"0-{buckets[0]} days"] + \
             [f"{buckets[i]+1}-{buckets[i+1]} days" for i in range(len(buckets) - 1)] + \
             [f"{buckets[-1]}+ days"]
    states = cfg()["stock"]["bucket_state"]

    rows = []
    for idx, label in enumerate(labels):
        lo = 0 if idx == 0 else buckets[idx - 1] + 1
        hi = buckets[idx] if idx < len(buckets) else 10**9
        units = [e for e in econ if lo <= e.days_held <= hi]
        rows.append({
            "bucket": label, "state": states.get(idx, "act_now"), "units": len(units),
            "cost": round(sum(e.cost_landed for e in units), 2),
            "value": round(sum(e.value_current or 0 for e in units), 2),
            "margin": round(sum((e.margin_abs or 0) for e in units), 2),
            "burn": round(sum((e.burn_per_month or 0) for e in units), 2),
        })
    return {
        "as_of": str(as_of or dt.date.today()),
        "buckets": rows,
        "totals": {
            "units": len(econ),
            "cost": round(sum(e.cost_landed for e in econ), 2),
            "value": round(sum(e.value_current or 0 for e in econ), 2),
            "margin": round(sum((e.margin_abs or 0) for e in econ), 2),
            "burn": round(sum((e.burn_per_month or 0) for e in econ), 2),
        },
    }


def action_queue(con, as_of: dt.date | None = None) -> list[UnitEconomics]:
    priority = {"part out": 0, "trade out": 1, "reprice": 2, "expedite part": 3,
                "review": 4, "hold": 9}
    def rank(e):
        for key, p in priority.items():
            if e.action.startswith(key):
                return p
        return 5
    return sorted([e for e in economics(con, as_of) if not e.action.startswith("hold")],
                  key=lambda e: (rank(e), -e.days_held))
