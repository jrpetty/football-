"""Load the human-editable YAML config and CSV catalogue (design doc s14)."""
from __future__ import annotations

import csv
import functools
import os
from pathlib import Path

import yaml

ROOT = Path(os.environ.get("PRICER_ROOT", Path(__file__).resolve().parent.parent))


def _yaml(name: str) -> dict:
    with open(ROOT / "config" / name) as fh:
        return yaml.safe_load(fh)


def _csv(name: str) -> list[dict]:
    """Catalogue CSVs may carry '#' comment lines above the header."""
    with open(ROOT / "catalog" / name, newline="") as fh:
        lines = [ln for ln in fh if not ln.lstrip().startswith("#")]
    return list(csv.DictReader(lines))


@functools.lru_cache(maxsize=None)
def cfg() -> dict:
    """All config files, keyed by filename stem."""
    return {
        "business": _yaml("business.yml"),
        "grades": _yaml("grades.yml"),
        "channels": _yaml("channels.yml"),
        "depreciation": _yaml("depreciation.yml"),
        "spec_deltas": _yaml("spec_deltas.yml"),
        "guardrails": _yaml("guardrails.yml"),
        "grading": _yaml("grading_checklist.yml"),
        "stock": _yaml("stock_policy.yml"),
        "bench": _yaml("bench.yml"),
        "parts": _yaml("parts_recovery.yml"),
    }


@functools.lru_cache(maxsize=None)
def sources() -> dict[str, dict]:
    """One profile per pricing file you own. Drop a new .yml in to add a source."""
    out = {}
    for path in sorted((ROOT / "sources").glob("*.yml")):
        with open(path) as fh:
            prof = yaml.safe_load(fh)
        out[prof["id"]] = prof
    return out


def _to_int(value, default=None):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


@functools.lru_cache(maxsize=None)
def catalog() -> dict:
    models, cpus, gpus = _csv("models.csv"), _csv("cpus.csv"), _csv("gpus.csv")
    for m in models:
        m["launch_rrp"] = float(m["launch_rrp"]) if m["launch_rrp"] else None
        m["ram_upgradeable"] = m["ram_upgradeable"] == "1"
        m["storage_upgradeable"] = m["storage_upgradeable"] == "1"
    for c in cpus:
        c["aliases"] = [a for a in c["aliases"].split("|") if a]
        c["bench_score"] = _to_int(c["bench_score"], 0)
        c["launch_year"] = _to_int(c["launch_year"])
    for g in gpus:
        g["aliases"] = [a for a in g["aliases"].split("|") if a]
        g["tier_index"] = _to_int(g["tier_index"], 0)
    return {
        "models": {m["model_id"]: m for m in models},
        "cpus": {c["cpu_id"]: c for c in cpus},
        "gpus": {g["gpu_id"]: g for g in gpus},
    }


_FITTED_OVERRIDE: dict | None = None


def use_fitted(values: dict | None) -> None:
    """Force a parameter set. Pass {} to run on seed priors only, None to
    restore normal behaviour. Used by tests to pin documented figures."""
    global _FITTED_OVERRIDE
    _FITTED_OVERRIDE = values
    _load_fitted.cache_clear()


@functools.lru_cache(maxsize=None)
def _load_fitted() -> dict:
    path = ROOT / "config" / "fitted.yml"
    if not path.exists():
        return {}
    with open(path) as fh:
        return yaml.safe_load(fh) or {}


def fitted() -> dict:
    """Values fitted from YOUR data by `pricer calibrate`. Empty until you run it."""
    return _FITTED_OVERRIDE if _FITTED_OVERRIDE is not None else _load_fitted()


def _resolve(section: str, key, seed):
    """Prefer a value fitted from your data; fall back to the seed prior.

    Returns (value, provenance) so every number that moves a price can say
    where it came from.
    """
    entry = (fitted().get(section) or {}).get(key)
    if isinstance(entry, dict) and entry.get("value") is not None:
        if entry.get("fitted") is None:
            return float(entry["value"]), f"seed (no fit: {entry.get('note') or 'insufficient data'})"
        return float(entry["value"]), f"fitted n={entry.get('n', '?')}"
    return seed, "seed"


def strict_mode() -> bool:
    """When true, refuse to quote using any parameter not fitted from your data."""
    return not cfg()["business"].get("parameters", {}).get("allow_seed_fallback", True)


def grade_multiplier(grade: str, with_provenance: bool = False):
    seed = cfg()["grades"]["ladder"].get(grade, {}).get("multiplier")
    if seed is None:
        return (None, "unknown grade") if with_provenance else None
    value, prov = _resolve("grades", grade, seed)
    return (value, prov) if with_provenance else value


def grade_rank(grade: str) -> int:
    return cfg()["grades"]["ladder"].get(grade, {}).get("rank", 0)


def channel_multiplier(channel: str, with_provenance: bool = False):
    ladder = cfg()["channels"]["ladder"]
    if channel not in ladder:
        raise KeyError(f"unknown channel {channel!r} - add it to config/channels.yml")
    value, prov = _resolve("channels", channel, ladder[channel]["multiplier"])
    return (value, prov) if with_provenance else value


def lambda_for(model: dict | None, with_provenance: bool = False):
    """Monthly depreciation rate for a model's build class."""
    dep = cfg()["depreciation"]
    if not model:
        return (dep["global_lambda"], "seed (global)") if with_provenance else dep["global_lambda"]
    bc = model["build_class"]
    seed = dep["by_build_class"].get(bc, dep["global_lambda"])
    value, prov = _resolve("depreciation", bc, seed)
    return (value, prov) if with_provenance else value


def spec_delta_table(kind: str, group: str) -> tuple[dict, str]:
    """RAM or storage ladder for an upgradeable or soldered chassis."""
    seed = cfg()["spec_deltas"][kind][group]
    entry = ((fitted().get("spec_deltas") or {}).get(kind) or {}).get(group)
    if not entry:
        return {int(k): float(v) for k, v in seed.items()}, "seed"
    table, any_fit = {}, False
    for level, meta in entry.items():
        table[int(level)] = float(meta["value"])
        any_fit = any_fit or meta.get("fitted") is not None
    note = entry[next(iter(entry))].get("note", "")
    return table, (f"fitted from {entry[next(iter(entry))].get('n', 0)} config pairs"
                   if any_fit else f"seed ({note})")
