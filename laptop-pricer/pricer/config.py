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
    with open(ROOT / "catalog" / name, newline="") as fh:
        return list(csv.DictReader(fh))


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


def grade_multiplier(grade: str) -> float | None:
    return cfg()["grades"]["ladder"].get(grade, {}).get("multiplier")


def grade_rank(grade: str) -> int:
    return cfg()["grades"]["ladder"].get(grade, {}).get("rank", 0)


def channel_multiplier(channel: str) -> float:
    ladder = cfg()["channels"]["ladder"]
    if channel not in ladder:
        raise KeyError(f"unknown channel {channel!r} - add it to config/channels.yml")
    return ladder[channel]["multiplier"]


def lambda_for(model: dict | None) -> float:
    """Monthly depreciation rate for a model's build class."""
    dep = cfg()["depreciation"]
    if not model:
        return dep["global_lambda"]
    fitted = (dep.get("fitted") or {}).get(model["model_id"])
    if fitted:
        return float(fitted)
    return dep["by_build_class"].get(model["build_class"], dep["global_lambda"])
