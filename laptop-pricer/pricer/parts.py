"""Per-component valuation: what each part of a machine is worth on its own.

Drives three decisions: the salvage floor under every working-unit estimate,
the part-out versus sell-whole call, and what a harvested component should be
carried at when it goes into the parts register.
"""
from __future__ import annotations

import csv
import functools
from dataclasses import dataclass

from .config import ROOT, catalog, cfg


@dataclass
class Part:
    component: str
    label: str
    value: float
    basis: str
    source: str            # "your data" | "seed formula" | "blocked"
    recoverable: bool = True


@dataclass
class Teardown:
    model_id: str
    grade: str
    parts: list[Part]
    gross: float
    scarcity: float
    labour: float
    weee: float
    net: float

    def line_items(self) -> list[Part]:
        return self.parts


@functools.lru_cache(maxsize=None)
def overrides() -> dict[str, tuple[float, str]]:
    """Your own realised parts prices, keyed most-specific-first."""
    path = ROOT / "catalog" / "parts_prices.csv"
    if not path.exists():
        return {}
    out = {}
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            key = (row.get("key") or "").strip()
            if not key or key.startswith("#"):
                continue
            try:
                out[key] = (float(row["value"]), row.get("note", "") or "your data")
            except (TypeError, ValueError):
                continue
    return out


def _override(*keys: str) -> tuple[float, str] | None:
    table = overrides()
    for key in keys:
        if key in table:
            return table[key]
    return None


def _size_band(screen_in: float | None) -> int:
    if not screen_in:
        return 14
    return min([13, 14, 15, 16, 17], key=lambda b: abs(b - screen_in))


def teardown(model_id: str, config: dict, grade: str = "C",
             battery_health_pct: int | None = None, has_charger: bool = True,
             defects: tuple[str, ...] = ()) -> Teardown:
    """Value every component of one machine."""
    spec = cfg()["parts"]
    bench = cfg()["bench"]
    model = catalog()["models"][model_id]
    comps = spec["components"]
    parts: list[Part] = []

    def blocked(name: str) -> bool:
        return any(d in defects for d in comps[name].get("blocked_by", []))

    # ---- display panel: size band, then panel class
    size = _size_band(config.get("screen_in"))
    panel_class = config.get("panel") or "ips"
    c = comps["panel"]
    if blocked("panel"):
        parts.append(Part("panel", c["label"], 0.0, "screen failed intake", "blocked", False))
    else:
        hit = _override(f"panel:{size}:{panel_class}", f"panel:{size}", "panel")
        if hit:
            parts.append(Part("panel", c["label"], hit[0], f'{size}" {panel_class}', hit[1]))
        else:
            base = c["base_by_size"].get(size, c["base_by_size"][14])
            mult = c["class_multiplier"].get(panel_class, 1.0)
            parts.append(Part("panel", c["label"], base * mult,
                              f'{size}" {panel_class} — £{base:.0f} x {mult}', "seed formula"))

    # ---- motherboard, priced off the CPU that is soldered to it
    c = comps["board"]
    cpu = catalog()["cpus"].get(config.get("cpu_id")) or {}
    if blocked("board"):
        parts.append(Part("board", c["label"], 0.0, "board fault or BIOS lock", "blocked", False))
    else:
        hit = _override(f"board:{config.get('cpu_id')}", f"board:{model_id}", "board")
        if hit:
            parts.append(Part("board", c["label"], hit[0], cpu.get("canonical", "?"), hit[1]))
        else:
            value = max(cpu.get("bench_score", 0) / 1000.0 * c["per_1000_bench_score"], c["floor"])
            parts.append(Part("board", c["label"], value,
                              f"{cpu.get('canonical', 'unknown CPU')} — {cpu.get('bench_score', 0)} bench",
                              "seed formula"))

    # ---- battery, scaled by measured health
    c = comps["battery"]
    hit = _override(f"battery:{model_id}", "battery")
    base, source = (hit[0], hit[1]) if hit else (c["base"], "seed formula")
    health_mult, band = 1.0, "health not measured"
    if battery_health_pct is not None:
        for threshold in sorted(c["health_multiplier"], key=int, reverse=True):
            if battery_health_pct >= int(threshold):
                health_mult = c["health_multiplier"][threshold]
                band = f"{battery_health_pct}% health x {health_mult}"
                break
    parts.append(Part("battery", c["label"], base * health_mult, band,
                      source if health_mult else "blocked", health_mult > 0))

    # ---- memory and storage: only separable if the chassis allows it
    for name, key, per, unit in (("ram", "ram_gb", "per_8gb", 8),
                                 ("storage", "storage_gb", "per_256gb", 256)):
        c = comps[name]
        upgradeable = model["ram_upgradeable" if name == "ram" else "storage_upgradeable"]
        amount = config.get(key) or 0
        if c.get("requires_upgradeable") and not upgradeable:
            parts.append(Part(name, c["label"], 0.0,
                              "soldered — recovered as part of the board", "seed formula", False))
            continue
        hit = _override(f"{name}:{amount}", name)
        if hit:
            parts.append(Part(name, c["label"], hit[0], f"{amount}GB", hit[1]))
        else:
            parts.append(Part(name, c["label"], amount / unit * c[per],
                              f"{amount}GB at £{c[per]:.2f}/{unit}GB", "seed formula"))

    # ---- cosmetic parts, worth what the grade allows
    c = comps["chassis"]
    hit = _override(f"chassis:{model_id}:{grade}", f"chassis:{grade}", "chassis")
    value = hit[0] if hit else c["by_grade"].get(grade, 0.0)
    parts.append(Part("chassis", c["label"], value, f"grade {grade}",
                      hit[1] if hit else "seed formula", value > 0))

    c = comps["keyboard"]
    if blocked("keyboard"):
        parts.append(Part("keyboard", c["label"], 0.0, "keys missing", "blocked", False))
    else:
        hit = _override(f"keyboard:{model_id}", "keyboard")
        base, source = (hit[0], hit[1]) if hit else (c["base"], "seed formula")
        mult = c["by_grade"].get(grade, 0.0)
        parts.append(Part("keyboard", c["label"], base * mult, f"grade {grade} x {mult}",
                          source, mult > 0))

    c = comps["charger"]
    if has_charger:
        hit = _override(f"charger:{model_id}", "charger")
        parts.append(Part("charger", c["label"], hit[0] if hit else c["base"], "included",
                          hit[1] if hit else "seed formula"))
    else:
        parts.append(Part("charger", c["label"], 0.0, "not supplied", "seed formula", False))

    scarcity = spec["scarcity_multiplier"].get(model.get("parts_availability"), 1.0)
    gross = sum(p.value for p in parts) * scarcity
    job = bench["standard_jobs"][spec["teardown_job"]]
    labour = job["minutes"] / 60.0 * bench["labour_rate_per_hour"]
    weee = bench["weee_levy_per_unit"] if spec.get("apply_weee_levy") else 0.0

    return Teardown(model_id=model_id, grade=grade, parts=parts,
                    gross=round(gross, 2), scarcity=scarcity,
                    labour=round(labour, 2), weee=round(weee, 2),
                    net=round(gross - labour - weee, 2))


def net_recovery(model_id: str, config: dict, grade: str = "C", **kw) -> float:
    return teardown(model_id, config, grade, **kw).net
