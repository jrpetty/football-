"""L4 - the estimator. Four-tier evidence cascade with shrinkage toward the
parent, so 400 comps and 1 comp both produce a sensible answer with no
special-casing (design doc s06)."""
from __future__ import annotations

import datetime as dt
import math
import statistics
from dataclasses import dataclass, field

from .config import catalog, cfg, lambda_for
from .match import config_key, cpu_compatible
from .normalise import grade_match_weight

MIN_PREDICTIVE_CV = 0.06     # a suspiciously tight cluster still gets an honest band


@dataclass
class Comparable:
    obs_id: int
    source_id: str
    config_id: str
    sold_at: dt.date
    value: float            # drifted to as_of, at reference grade and channel
    weight: float
    grade_obs: str
    channel: str
    parts: dict = field(default_factory=dict)


@dataclass
class Estimate:
    config_id: str
    grade: str
    as_of: dt.date
    value: float | None
    tier: str
    n_eff: float
    alpha: float
    sigma: float
    confidence: float
    comparables: list[Comparable]
    band: dict
    warnings: list[str]
    tier_values: dict


# ---------- robust statistics ----------

def weighted_median(pairs: list[tuple[float, float]]) -> float:
    pairs = sorted(pairs)
    total = sum(w for _, w in pairs)
    acc = 0.0
    for value, w in pairs:
        acc += w
        if acc >= total / 2:
            return value
    return pairs[-1][0]


def mad_sigma(values: list[float]) -> float:
    """Median absolute deviation, scaled to a normal-equivalent sigma."""
    if len(values) < 2:
        return 0.0
    med = statistics.median(values)
    mad = statistics.median([abs(v - med) for v in values])
    return 1.4826 * mad


def reject_outliers(comps: list[Comparable], threshold: float) -> tuple[list[Comparable], list[Comparable]]:
    """MAD-based, on the log scale. MAD is used rather than standard deviation
    precisely because it is not dragged around by the outliers being hunted."""
    if len(comps) < 4:
        return comps, []
    logs = [math.log(c.value) for c in comps]
    med = statistics.median(logs)
    sigma = mad_sigma(logs)
    if sigma <= 0:
        return comps, []
    keep, drop = [], []
    for c, lv in zip(comps, logs):
        (keep if abs(0.6745 * (lv - med) / sigma) <= threshold else drop).append(c)
    return keep, drop


def effective_n(weights: list[float]) -> float:
    total = sum(weights)
    sq = sum(w * w for w in weights)
    return (total * total / sq) if sq else 0.0


# ---------- spec deltas ----------

def _ladder_value(table: dict, key: int) -> float:
    if key in table:
        return float(table[key])
    nearest = min(table, key=lambda k: abs(int(k) - key))
    return float(table[nearest])


def spec_delta(model: dict, frm: tuple[int, int], to: tuple[int, int]) -> float:
    """Multiplier converting a value at RAM/storage `frm` to RAM/storage `to`."""
    sd = cfg()["spec_deltas"]
    ram_tbl = sd["ram_gb"]["upgradeable" if model["ram_upgradeable"] else "soldered"]
    sto_tbl = sd["storage_gb"]["upgradeable" if model["storage_upgradeable"] else "soldered"]
    ram = _ladder_value(ram_tbl, to[0]) / _ladder_value(ram_tbl, frm[0])
    sto = _ladder_value(sto_tbl, to[1]) / _ladder_value(sto_tbl, frm[1])
    return ram * sto


# ---------- gathering evidence ----------

def _drift(value: float, days: float, lam: float) -> float:
    """Bring an old price forward into today's money."""
    return value * math.exp(-lam * days / 30.0)


def _time_weight(days: float) -> float:
    half_life = cfg()["depreciation"]["half_life_days"]
    return math.exp(-math.log(2) * days / half_life)


def gather(con, model_id: str, cpu_id: str, ram: int, storage: int, grade: str,
           as_of: dt.date, lam: float, same_config_only: bool) -> list[Comparable]:
    rows = con.execute("""
        SELECT n.obs_id, n.source_id, n.config_id, n.sold_at, n.price_norm,
               n.grade_obs, n.channel, n.w_trust, n.w_qty,
               c.model_id, c.cpu_id, c.ram_gb, c.storage_gb
        FROM normalised_obs n JOIN configs c USING (config_id)
        WHERE c.model_id = ? AND n.sold_at IS NOT NULL
    """, [model_id]).fetchall()

    model = catalog()["models"][model_id]
    out: list[Comparable] = []
    for (obs_id, src, cid, sold_at, price, grade_obs, channel,
         w_trust, w_qty, _m, obs_cpu, obs_ram, obs_sto) in rows:
        w_cpu = cpu_compatible(obs_cpu, cpu_id)
        if w_cpu <= 0:
            continue
        same_config = (obs_ram == ram and obs_sto == storage)
        if same_config_only and not same_config:
            continue

        value = float(price)
        if not same_config:
            value *= spec_delta(model, (obs_ram, obs_sto), (ram, storage))

        days = (as_of - sold_at).days
        if days < 0:
            continue
        value = _drift(value, days, lam)

        w_spec = 1.0 if same_config else 0.75
        weight = (float(w_trust) * _time_weight(days) *
                  grade_match_weight(grade_obs, grade) * float(w_qty) * w_cpu * w_spec)
        if weight <= 0:
            continue
        out.append(Comparable(
            obs_id=obs_id, source_id=src, config_id=cid, sold_at=sold_at,
            value=round(value, 4), weight=round(weight, 6),
            grade_obs=grade_obs, channel=channel,
            parts={"trust": w_trust, "time": round(_time_weight(days), 4),
                   "grade": grade_match_weight(grade_obs, grade), "qty": w_qty,
                   "cpu": w_cpu, "spec": w_spec, "days": days},
        ))
    return out


def _aggregate(comps: list[Comparable]) -> tuple[float, float, float]:
    value = weighted_median([(c.value, c.weight) for c in comps])
    n_eff = effective_n([c.weight for c in comps])
    sigma = mad_sigma([c.value for c in comps])
    return value, n_eff, sigma


def _confidence(n_eff: float, sigma: float, value: float, newest_days: int,
                tier: str, sources_used: int) -> float:
    tier_score = {"config_exact": 1.0, "model_hedonic": 0.75, "family": 0.5, "global": 0.3}[tier]
    cv = (sigma / value) if value else 1.0
    parts = [
        (0.30, min(n_eff / 8.0, 1.0)),
        (0.25, max(0.0, 1.0 - cv / 0.25)),
        (0.20, math.exp(-newest_days / 120.0)),
        (0.15, tier_score),
        (0.10, min(sources_used / 3.0, 1.0)),
    ]
    return round(sum(w * s for w, s in parts), 3)


def estimate(con, model_id: str, cpu_id: str, ram: int, storage: int,
             grade: str = "B", as_of: dt.date | None = None) -> Estimate:
    as_of = as_of or dt.date.today()
    guard = cfg()["guardrails"]
    model = catalog()["models"][model_id]
    lam = lambda_for(model)
    cid = config_key(model_id, cpu_id, ram, storage)
    warnings: list[str] = []
    tier_values: dict = {}

    # Tier 1 - exact configuration
    t1 = gather(con, model_id, cpu_id, ram, storage, grade, as_of, lam, same_config_only=True)
    t1, dropped = reject_outliers(t1, guard["outlier_modified_z"])
    if dropped:
        warnings.append(f"{len(dropped)} outlier(s) rejected (modified z > {guard['outlier_modified_z']})")

    # Tier 2 - same model, other configurations, bridged by hedonic spec deltas
    t2 = [c for c in gather(con, model_id, cpu_id, ram, storage, grade, as_of, lam,
                            same_config_only=False)
          if c.obs_id not in {c1.obs_id for c1 in t1}]

    if t1 and len(t1) >= guard["min_observations_for_tier1"]:
        local, n_eff, sigma = _aggregate(t1)
        tier, comps = "config_exact", t1
        tier_values["config_exact"] = round(local, 2)
        if t2:
            parent, _, _ = _aggregate(t2)
            tier_values["model_hedonic"] = round(parent, 2)
            alpha = n_eff / (n_eff + guard["shrinkage_k"])
            value = alpha * local + (1 - alpha) * parent
        else:
            alpha, value = 1.0, local
            warnings.append("no parent tier available - estimate is unshrunk")
    elif t2 or t1:
        comps = t1 + t2
        value, n_eff, sigma = _aggregate(comps)
        tier, alpha = "model_hedonic", 1.0
        tier_values["model_hedonic"] = round(value, 2)
        warnings.append("fell back to model-level evidence with spec deltas")
    else:
        return Estimate(cid, grade, as_of, None, "global", 0.0, 0.0, 0.0, 0.0, [],
                        {}, ["no comparable evidence - Tier 4 global model not yet built (Phase 5)"], {})

    newest = min((as_of - c.sold_at).days for c in comps)
    conf = _confidence(n_eff, sigma, value, newest, tier, len({c.source_id for c in comps}))

    # Predictive interval for a SINGLE unit - not the standard error of the mean.
    sigma_pred = max(sigma, value * MIN_PREDICTIVE_CV)
    if alpha < 1.0:
        sigma_pred = math.hypot(sigma_pred, (1 - alpha) * abs(tier_values.get("model_hedonic", value) - value))
    band = {
        "p10": round(value - 1.2816 * sigma_pred, 2), "p25": round(value - 0.6745 * sigma_pred, 2),
        "p50": round(value, 2),
        "p75": round(value + 0.6745 * sigma_pred, 2), "p90": round(value + 1.2816 * sigma_pred, 2),
    }

    if newest > guard["warn_if_newest_comp_older_than_days"]:
        warnings.append(f"newest comparable is {newest} days old")
    if conf < guard["warn_if_confidence_below"]:
        warnings.append(f"low confidence ({conf})")
    rrp = model.get("launch_rrp")
    if rrp and value > rrp * guard["max_value_vs_launch_rrp"]:
        warnings.append(f"value is {value/rrp:.0%} of launch RRP - check for a parsing error")

    return Estimate(cid, grade, as_of, round(value, 2), tier, round(n_eff, 3), round(alpha, 4),
                    round(sigma, 3), conf, comps, band, warnings, tier_values)
