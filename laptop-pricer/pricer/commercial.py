"""L5 - commercial layer. Reapply grade and channel, add VAT, apply margin
policy, refurb cost, holding cost and guardrails (design doc s05, s12)."""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from .config import (catalog, cfg, channel_multiplier, grade_multiplier,
                     lambda_for, strict_mode)



class UncalibratedParameter(Exception):
    """Raised in strict mode when a price would rely on a seed prior."""


def _round(value: float, rule: dict) -> float:
    mode, to = rule.get("mode", "down"), rule.get("to", 1)
    if mode == "down":
        return math.floor(value / to) * to
    if mode == "up":
        return math.ceil(value / to) * to
    if mode == "charm":                       # 310.80 -> 309
        return max(math.floor(value / to) * to - 1, 0)
    return round(value / to) * to


@dataclass
class Pricing:
    market_value_ex_vat: float
    list_price_ex_vat: float
    list_price_inc_vat: float
    buy_price_max: float
    parts_value: float
    grade: str
    channel: str
    margin_target: float
    refurb_expected: float
    holding_cost: float
    risk_premium: float
    band_ex_vat: dict
    band_inc_vat: dict
    parameters: list = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    waterfall: list[tuple[str, float]] = field(default_factory=list)


def at_grade_and_channel(reference_value: float, grade: str, channel: str) -> float:
    """Reference value (grade B, reference channel) -> a specific grade and channel."""
    gm = grade_multiplier(grade)
    if gm is None:
        raise ValueError(f"grade {grade!r} has no multiplier")
    return reference_value * gm / channel_multiplier(channel)


def parts_value(model: dict, config: dict, grade: str = "C") -> float:
    """Net recovery from a teardown - the floor under any working-unit estimate.

    Delegates to the component model so the floor and the itemised breakdown in
    `pricer parts` can never drift apart.
    """
    from .parts import net_recovery
    return net_recovery(model["model_id"], config, grade)


def price(reference_value: float, model_id: str, config: dict, grade: str = "B",
          channel: str | None = None, confidence: float = 0.8,
          expected_days_to_sell: float | None = None, band: dict | None = None) -> Pricing:
    biz = cfg()["business"]
    model = catalog()["models"][model_id]
    build_class = model["build_class"]
    channel = channel or biz["reference"]["channel"]
    vat = biz["vat_rate"]
    warnings: list[str] = []

    gmul, gprov = grade_multiplier(grade, with_provenance=True)
    cmul, cprov = channel_multiplier(channel, with_provenance=True)
    lam, lprov = lambda_for(model, with_provenance=True)
    parameters = [("grade multiplier", grade, gmul, gprov),
                  ("channel multiplier", channel, cmul, cprov),
                  ("depreciation lambda", build_class, lam, lprov)]

    value = at_grade_and_channel(reference_value, grade, channel)
    waterfall = [("reference value (grade B, own retail, ex-VAT)", round(reference_value, 2))]
    if grade != biz["reference"]["grade"]:
        waterfall.append((f"grade -> {grade} (x{grade_multiplier(grade)})", round(value, 2)))
    if channel != biz["reference"]["channel"]:
        waterfall.append((f"channel -> {channel} (/{channel_multiplier(channel)})", round(value, 2)))

    margin = biz["target_margin"].get(build_class, biz["target_margin"]["default"])
    refurb = biz["refurb_expected"].get(build_class, biz["refurb_expected"]["default"])
    days = expected_days_to_sell or cfg()["stock"]["default_expected_days_to_sell"]

    # Holding cost: this machine depreciates on the shelf for as long as this
    # configuration actually takes to shift (design doc s12).
    holding = value * (1 - math.exp(-lam * days / 30.0))
    risk = value * biz["risk_premium_max_frac"] * (1 - min(max(confidence, 0.0), 1.0))

    buy = value * (1 - margin) - refurb - holding - risk
    waterfall += [
        (f"less target margin {margin:.0%}", round(value * (1 - margin), 2)),
        (f"less expected refurb", round(value * (1 - margin) - refurb, 2)),
        (f"less holding cost ({days:.0f}d at {lam:.1%}/mo)", round(value * (1 - margin) - refurb - holding, 2)),
        (f"less risk premium (confidence {confidence:.2f})", round(buy, 2)),
    ]

    salvage = parts_value(model, config, grade)
    if value < salvage:
        warnings.append(f"worth more in parts (£{salvage:.2f}) than whole (£{value:.2f}) - consider part-out")
    if buy <= 0:
        warnings.append("no viable buy price at current policy - decline or renegotiate")

    guard = cfg()["guardrails"]
    if value < guard["min_price_gbp"]:
        warnings.append(f"below minimum sane price £{guard['min_price_gbp']}")

    seeded = [f"{name} ({key})" for name, key, _v, prov in parameters if prov.startswith("seed")]
    if seeded and strict_mode():
        raise UncalibratedParameter(
            "strict mode is on and these adjustments are not fitted from your data: "
            + ", ".join(seeded) + ". Run `pricer calibrate --write`, or set "
            "parameters.allow_seed_fallback: true in config/business.yml.")
    if seeded:
        warnings.append("using seed priors, not your data, for: " + ", ".join(seeded))

    # The estimator works at the reference grade and channel, so the band has to
    # take the same trip as the point estimate or the two sit on different scales.
    band_ex = {k: round(at_grade_and_channel(v, grade, channel), 2)
               for k, v in (band or {}).items()}
    band_inc = {k: round(v * (1 + vat), 2) for k, v in band_ex.items()}

    return Pricing(
        market_value_ex_vat=round(value, 2),
        list_price_ex_vat=round(value, 2),
        list_price_inc_vat=_round(value * (1 + vat), biz["rounding"]["list_price"]),
        buy_price_max=_round(max(buy, 0.0), biz["rounding"]["buy_price"]),
        parts_value=salvage, grade=grade, channel=channel,
        margin_target=margin, refurb_expected=refurb,
        holding_cost=round(holding, 2), risk_premium=round(risk, 2),
        band_ex_vat=band_ex, band_inc_vat=band_inc, parameters=parameters,
        warnings=warnings, waterfall=waterfall,
    )
