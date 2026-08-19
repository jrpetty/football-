"""L2 - identity resolution. Blocking, then scored matching, with a review queue
for anything below threshold. Nothing is dropped silently (design doc s04)."""
from __future__ import annotations

from .config import catalog
from .parse import SpecVector

AUTO_ACCEPT = 0.92
FLAG_FLOOR = 0.75

# A generation-median CPU is real evidence for a specific CPU of that generation,
# just weaker. This is what `spec_match` in the weight formula is for.
GENERATION_PENALTY = 0.85


def config_key(model_id: str, cpu_id: str, ram_gb: int, storage_gb: int) -> str:
    """Deterministic identity for a configuration.

    Phase 1 deliberately keys on the four fields that are reliably present in
    real listings. Panel and touch are carried as attributes and applied as
    hedonic deltas instead, because keying on them fragments the comps pool
    whenever a title omits them - which is most of the time.
    """
    return f"{model_id}|{cpu_id}|{ram_gb}|{storage_gb}"


def is_generation_median(cpu_id: str | None) -> bool:
    return bool(cpu_id) and cpu_id.endswith("-median")


def cpu_generation(cpu_id: str | None) -> tuple | None:
    """(brand, tier, year) used to decide whether a median CPU can stand in."""
    if not cpu_id:
        return None
    cpu = catalog()["cpus"].get(cpu_id)
    if not cpu:
        return None
    parts = cpu_id.split("-")
    tier = parts[1] if len(parts) > 1 else ""
    tier = tier.replace("gen10", "").replace("gen11", "") or tier
    return (parts[0], tier[:2], cpu["launch_year"])


def cpu_compatible(observed: str | None, target: str | None) -> float:
    """Weight multiplier for using `observed` as evidence about `target`."""
    if observed == target:
        return 1.0
    if not observed or not target:
        return 0.0
    if is_generation_median(observed) or is_generation_median(target):
        a, b = catalog()["cpus"].get(observed), catalog()["cpus"].get(target)
        if a and b and a["launch_year"] == b["launch_year"]:
            # same generation and same core tier (i5 vs i5)
            if _tier(observed) == _tier(target):
                return GENERATION_PENALTY
    return 0.0


def _tier(cpu_id: str) -> str:
    for t in ("i3", "i5", "i7", "i9", "m1", "m2", "ryzen3", "ryzen5", "ryzen7"):
        if t in cpu_id:
            return t
    return "?"


def resolve(spec: SpecVector) -> tuple[str | None, float, str]:
    """Return (config_id, confidence, method)."""
    if not spec.model_id:
        return None, 0.0, "no_model"
    if not (spec.ram_gb and spec.storage_gb):
        return None, spec.confidence * 0.5, "incomplete_spec"
    if not spec.cpu_id:
        return None, spec.confidence * 0.6, "no_cpu"

    cid = config_key(spec.model_id, spec.cpu_id, spec.ram_gb, spec.storage_gb)
    method = "generation_median" if is_generation_median(spec.cpu_id) else "exact"
    conf = spec.confidence * (0.9 if method == "generation_median" else 1.0)
    return cid, round(conf, 3), method


def verdict(confidence: float) -> str:
    if confidence >= AUTO_ACCEPT:
        return "auto"
    if confidence >= FLAG_FLOOR:
        return "flagged"
    return "review"
