"""Fit the pricing parameters from YOUR data instead of using seed priors.

Every number that moves a price - depreciation, grade multipliers, channel
multipliers, spec deltas - starts life as a seed prior in config/. This module
replaces each one with a value estimated from your own observations, shrunk
toward the seed in proportion to how much evidence there is, and records the
provenance so you can always see which is which.

Method: robust alternating-median backfitting on log price. Medians rather than
means because sold data contains typos and bundle lots; alternating rather than
a single linear solve because it needs no linear algebra dependency and is
trivially inspectable.

    ln(price_net) = mu[config] + lambda[build_class]*days_ago
                    + gamma[grade] + delta[channel] + residual

Stage A fits the four blocks above, using a fully saturated per-configuration
baseline so that everything about the machine itself is absorbed. That cleanly
identifies the parameters that vary WITHIN a configuration.

Stage B then takes those fitted per-configuration baselines and, within each
model, regresses them on RAM and storage to recover the spec deltas.
"""
from __future__ import annotations

import datetime as dt
import math
import statistics
from collections import defaultdict
from dataclasses import dataclass, field

import yaml

from .config import ROOT, catalog, cfg

ITERATIONS = 15
REFERENCE_RAM = 16
REFERENCE_STORAGE = 512


@dataclass
class Fitted:
    """One fitted parameter, with everything needed to judge whether to trust it."""
    key: str
    seed: float
    fitted: float | None
    n: int
    shrunk: float
    note: str = ""

    def as_dict(self) -> dict:
        return {"seed": round(self.seed, 5),
                "fitted": None if self.fitted is None else round(self.fitted, 5),
                "n": self.n, "value": round(self.shrunk, 5), "note": self.note}


@dataclass
class Calibration:
    as_of: str
    observations: int
    depreciation: dict = field(default_factory=dict)
    grades: dict = field(default_factory=dict)
    channels: dict = field(default_factory=dict)
    spec_ram: dict = field(default_factory=dict)
    spec_storage: dict = field(default_factory=dict)
    diagnostics: list[str] = field(default_factory=list)


def _wmedian(pairs: list[tuple[float, float]]) -> float:
    """Weighted median. Falls back to the plain median if weights are absent."""
    pairs = sorted(pairs)
    total = sum(w for _, w in pairs)
    if total <= 0:
        return statistics.median([v for v, _ in pairs])
    acc = 0.0
    for value, w in pairs:
        acc += w
        if acc >= total / 2:
            return value
    return pairs[-1][0]


def _wslope(points: list[tuple[float, float, float]]) -> float | None:
    """Weighted least-squares slope of y on x. Points are (x, y, weight)."""
    total = sum(w for _, _, w in points)
    if total <= 0 or len(points) < 8:
        return None
    xbar = sum(w * x for x, _, w in points) / total
    ybar = sum(w * y for _, y, w in points) / total
    sxx = sum(w * (x - xbar) ** 2 for x, _, w in points)
    sxy = sum(w * (x - xbar) * (y - ybar) for x, y, w in points)
    return sxy / sxx if sxx > 0 else None


def _shrink(seed: float, fitted: float | None, n: int, k: float) -> float:
    """Trust the fit in proportion to the evidence behind it."""
    if fitted is None or n <= 0:
        return seed
    w = n / (n + k)
    return w * fitted + (1 - w) * seed


def _load(con, as_of: dt.date) -> list[dict]:
    rows = con.execute("""
        SELECT n.obs_id, n.source_id, n.config_id, n.observation_type, n.sold_at,
               n.price_net, n.grade_obs, n.channel, n.w_trust, n.w_qty,
               c.model_id, c.cpu_id, c.ram_gb, c.storage_gb
        FROM normalised_obs n JOIN configs c USING (config_id)
        WHERE n.sold_at IS NOT NULL AND n.price_net > 0
    """).fetchall()
    models = catalog()["models"]
    out = []
    for (obs_id, src, cid, otype, sold_at, net, grade, channel,
         w_trust, w_qty, model_id, cpu_id, ram, storage) in rows:
        days = (as_of - sold_at).days
        if days < 0 or model_id not in models:
            continue
        out.append({
            "obs_id": obs_id, "source": src, "config": cid, "type": otype,
            "days": float(days), "y": math.log(float(net)), "grade": grade,
            "channel": channel, "model": model_id, "ram": ram, "storage": storage,
            "build_class": models[model_id]["build_class"],
            "w": float(w_trust) * float(w_qty),
        })
    return out


def calibrate(con, as_of: dt.date | None = None) -> Calibration:
    as_of = as_of or dt.date.today()
    data = _load(con, as_of)
    guard = cfg()["guardrails"]
    dep_cfg, grade_cfg, chan_cfg = cfg()["depreciation"], cfg()["grades"], cfg()["channels"]
    ref_grade, ref_channel = grade_cfg["reference"], chan_cfg["reference"]
    cal = Calibration(as_of=str(as_of), observations=len(data))

    if len(data) < 30:
        cal.diagnostics.append(
            f"only {len(data)} usable observations - too few to fit anything; seeds retained")
        return cal

    # ---- identifiability: a channel served by a single source cannot have its
    # channel effect separated from that source's own bias. Say so rather than
    # silently attributing one to the other.
    by_channel = defaultdict(set)
    for r in data:
        by_channel[r["channel"]].add(r["source"])
    for channel, srcs in sorted(by_channel.items()):
        if len(srcs) == 1 and channel != ref_channel:
            cal.diagnostics.append(
                f"channel '{channel}' has one source ({next(iter(srcs))}) - its fitted "
                f"multiplier absorbs that source's own bias; they cannot be separated")

    # ---- Stage A: alternating medians ----
    mu = defaultdict(float)
    lam = {bc: dep_cfg["by_build_class"].get(bc, dep_cfg["global_lambda"]) / 30.0
           for bc in {r["build_class"] for r in data}}
    gamma = defaultdict(float)
    delta = defaultdict(float)

    for _ in range(ITERATIONS):
        # configuration baseline absorbs everything about the machine itself
        groups = defaultdict(list)
        for r in data:
            resid = r["y"] - (lam[r["build_class"]] * r["days"] + gamma[r["grade"]] + delta[r["channel"]])
            groups[r["config"]].append((resid, r["w"]))
        mu = {k: _wmedian(v) for k, v in groups.items()}

        # depreciation, per build class
        slopes = defaultdict(list)
        for r in data:
            resid = r["y"] - (mu[r["config"]] + gamma[r["grade"]] + delta[r["channel"]])
            slopes[r["build_class"]].append((r["days"], resid, r["w"]))
        for bc, pts in slopes.items():
            if (s := _wslope(pts)) is not None:
                lam[bc] = max(s, 0.0)          # prices do not appreciate with age

        # grade, centred on the reference grade
        groups = defaultdict(list)
        for r in data:
            resid = r["y"] - (mu[r["config"]] + lam[r["build_class"]] * r["days"] + delta[r["channel"]])
            groups[r["grade"]].append((resid, r["w"]))
        gamma = {k: _wmedian(v) for k, v in groups.items()}
        base = gamma.get(ref_grade, 0.0)
        gamma = defaultdict(float, {k: v - base for k, v in gamma.items()})

        # channel, centred on the reference channel
        groups = defaultdict(list)
        for r in data:
            resid = r["y"] - (mu[r["config"]] + lam[r["build_class"]] * r["days"] + gamma[r["grade"]])
            groups[r["channel"]].append((resid, r["w"]))
        delta = {k: _wmedian(v) for k, v in groups.items()}
        base = delta.get(ref_channel, 0.0)
        delta = defaultdict(float, {k: v - base for k, v in delta.items()})

    counts_bc = defaultdict(int)
    counts_grade = defaultdict(int)
    counts_chan = defaultdict(int)
    for r in data:
        counts_bc[r["build_class"]] += 1
        counts_grade[r["grade"]] += 1
        counts_chan[r["channel"]] += 1

    k = guard["shrinkage_k"] * 5      # parameters need more evidence than a single quote

    for bc, daily in lam.items():
        seed = dep_cfg["by_build_class"].get(bc, dep_cfg["global_lambda"])
        n = counts_bc[bc]
        monthly = daily * 30.0
        cal.depreciation[bc] = Fitted(bc, seed, monthly, n, _shrink(seed, monthly, n, k)).as_dict()

    for grade, meta in grade_cfg["ladder"].items():
        seed = meta.get("multiplier")
        if seed is None:
            continue
        n = counts_grade.get(grade, 0)
        fitted = math.exp(gamma[grade]) if n >= 5 else None
        note = "" if n >= 5 else "too few observations at this grade"
        cal.grades[grade] = Fitted(grade, seed, fitted, n, _shrink(seed, fitted, n, k), note).as_dict()

    for channel, meta in chan_cfg["ladder"].items():
        seed = meta["multiplier"]
        n = counts_chan.get(channel, 0)
        # delta is how far this channel sits ABOVE the reference in logs, so the
        # to-reference multiplier is its inverse
        fitted = math.exp(-delta[channel]) if n >= 5 else None
        note = "" if n >= 5 else "not present in your data"
        if len(by_channel.get(channel, ())) == 1 and channel != ref_channel:
            note = (note + "; " if note else "") + "absorbs single source's bias"
        cal.channels[channel] = Fitted(channel, seed, fitted, n,
                                       _shrink(seed, fitted, n, k), note).as_dict()

    # ---- Stage B: spec deltas from the fitted configuration baselines ----
    cal.spec_ram, cal.spec_storage = _fit_spec_deltas(data, mu, k)
    return cal


def _fit_spec_deltas(data: list[dict], mu: dict, k: float) -> tuple[dict, dict]:
    """Recover RAM and storage elasticities from within-model, same-CPU pairs.

    The obvious approach - hold storage fixed and vary RAM - almost never works
    on real data, because vendors ship RAM and storage as bundles (8/256,
    16/512, 32/1TB) so the two move together. Instead every pair of
    configurations of the same model AND the same CPU contributes one
    observation to a two-variable regression:

        mu[i] - mu[j] = beta_ram * dln(ram) + beta_storage * dln(storage)

    Pairs are restricted to the same CPU because the configuration baseline
    already contains the CPU's contribution; comparing across CPUs would charge
    the processor's value to the memory.

    The pair set is identified only if some pairs move one dimension without
    the other. If every pair moves both by the same ratio the design is
    collinear, and that is reported rather than papered over.
    """
    models = catalog()["models"]
    sd = cfg()["spec_deltas"]

    configs_by_model: dict[str, dict] = defaultdict(dict)
    for r in data:
        configs_by_model[r["model"]][r["config"]] = (r["ram"], r["storage"], r["config"].split("|")[1])

    pairs = {"upgradeable": [], "soldered": []}
    for model_id, configs in configs_by_model.items():
        model = models[model_id]
        group = "upgradeable" if model["ram_upgradeable"] else "soldered"
        items = list(configs.items())
        for a in range(len(items)):
            for b in range(a + 1, len(items)):
                cid_i, (ram_i, sto_i, cpu_i) = items[a]
                cid_j, (ram_j, sto_j, cpu_j) = items[b]
                if cpu_i != cpu_j or (ram_i == ram_j and sto_i == sto_j):
                    continue
                if not (ram_i and ram_j and sto_i and sto_j):
                    continue
                pairs[group].append((math.log(ram_i / ram_j), math.log(sto_i / sto_j),
                                     mu[cid_i] - mu[cid_j]))

    elasticity = {}
    for group, pts in pairs.items():
        elasticity[group] = _solve_two_var(pts)

    def build(seed_table, reference, index):
        out = {}
        for group in ("upgradeable", "soldered"):
            seeds = seed_table[group]
            beta, npairs, note = elasticity[group][index], elasticity[group][2], elasticity[group][3]
            out[group] = {}
            for level, seed in seeds.items():
                level = int(level)
                if level == reference:
                    out[group][level] = Fitted(str(level), float(seed), None, npairs,
                                               float(seed), "reference level").as_dict()
                    continue
                fitted = None
                if beta is not None:
                    fitted = float(seeds[reference]) * (level / reference) ** beta
                out[group][level] = Fitted(str(level), float(seed), fitted, npairs,
                                           _shrink(float(seed), fitted, npairs, k),
                                           note).as_dict()
        return out

    return (build(sd["ram_gb"], REFERENCE_RAM, 0),
            build(sd["storage_gb"], REFERENCE_STORAGE, 1))


def _solve_two_var(points: list[tuple[float, float, float]]):
    """Least squares through the origin for y = b1*x1 + b2*x2.

    Returns (beta_ram, beta_storage, n_pairs, note). Both betas are None when
    the design is rank deficient - which happens whenever RAM and storage only
    ever move together.
    """
    if len(points) < 3:
        return (None, None, len(points), "too few within-model, same-CPU pairs")

    # drop pairs whose implied jump is wild, before fitting
    diffs = [y for _, _, y in points]
    med = statistics.median(diffs)
    spread = statistics.median([abs(d - med) for d in diffs]) * 1.4826 or 1e-9
    clean = [p for p in points if abs(0.6745 * (p[2] - med) / spread) <= 3.5]
    if len(clean) < 3:
        clean = points

    s11 = sum(x1 * x1 for x1, _, _ in clean)
    s12 = sum(x1 * x2 for x1, x2, _ in clean)
    s22 = sum(x2 * x2 for _, x2, _ in clean)
    t1 = sum(x1 * y for x1, _, y in clean)
    t2 = sum(x2 * y for _, x2, y in clean)
    det = s11 * s22 - s12 * s12

    # scale-free collinearity check: |cos| between the two regressor columns
    denom = math.sqrt(s11 * s22)
    if denom <= 0 or abs(s12) / denom > 0.995:
        return (None, None, len(clean),
                "RAM and storage always move together in your data - "
                "cannot separate them; seeds retained")
    if abs(det) < 1e-9:
        return (None, None, len(clean), "rank deficient")

    return ((s22 * t1 - s12 * t2) / det, (s11 * t2 - s12 * t1) / det, len(clean), "")


HEADER = """# WRITTEN BY `pricer calibrate` - do not hand-edit; your edits belong in the
# seed files, which this shrinks toward when evidence is thin.
#
# Each entry records:  seed (the prior)  fitted (from your data)
#                      n (observations)  value (what the engine actually uses)
"""


def write(cal: Calibration, path=None) -> str:
    path = path or ROOT / "config" / "fitted.yml"
    payload = {
        "fitted_at": cal.as_of, "observations": cal.observations,
        "diagnostics": cal.diagnostics,
        "depreciation": cal.depreciation, "grades": cal.grades,
        "channels": cal.channels,
        "spec_deltas": {"ram_gb": cal.spec_ram, "storage_gb": cal.spec_storage},
    }
    with open(path, "w") as fh:
        fh.write(HEADER)
        yaml.safe_dump(payload, fh, sort_keys=False, default_flow_style=False)
    return str(path)
