"""L3 - comparability. Push every observation to one reference basis:
net realised, reference grade, reference channel, ex-VAT, base currency.

Time drift is NOT applied here - it depends on the date you are asking about,
so the estimator applies it at query time (design doc s05).
"""
from __future__ import annotations

from dataclasses import dataclass

from .config import cfg, channel_multiplier, grade_multiplier, grade_rank

FX = {"GBP": 1.0, "EUR": 0.855, "USD": 0.792}   # placeholder: real system looks up by date


@dataclass
class NormalisedObs:
    price_raw: float
    price_net: float
    price_norm: float
    grade_obs: str
    channel: str
    w_trust: float
    w_grade: float
    w_qty: float
    notes: list[str]


def net_realised(gross: float, profile: dict, shipping: float = 0.0) -> float:
    """Cash actually received, ex-VAT, in base currency."""
    price = profile.get("price", {})
    vat_rate = cfg()["business"]["vat_rate"]

    revenue = gross / (1 + vat_rate) if price.get("vat_treatment") == "inclusive" else gross

    fees = price.get("fees") or {}
    revenue -= gross * float(fees.get("rate", 0.0)) + float(fees.get("fixed", 0.0))
    revenue -= shipping

    if profile.get("observation_type") == "ask":
        revenue *= float(profile.get("ask_haircut", 1.0))
    elif profile.get("observation_type") == "offer":
        revenue *= float(profile.get("offer_uplift", 1.0))

    return revenue * FX.get(profile.get("currency", "GBP"), 1.0)


def to_reference(net: float, grade_obs: str, channel: str) -> tuple[float, list[str]]:
    """Convert a net price at (grade, channel) to the reference grade and channel.

    The grade ladder stores 'value at grade G divided by value at grade B', so
    converting an observation TO grade B divides by it. The channel ladder is
    already expressed in the to-reference direction, so it multiplies.
    """
    notes = []
    gmul = grade_multiplier(grade_obs)
    if not gmul:
        raise ValueError(f"grade {grade_obs!r} has no multiplier (salvage routes to the parts model)")
    value = net / gmul
    if grade_obs != cfg()["grades"]["reference"]:
        notes.append(f"grade {grade_obs}->{cfg()['grades']['reference']} /{gmul}")

    cmul = channel_multiplier(channel)
    value *= cmul
    if cmul != 1.0:
        notes.append(f"channel {channel} x{cmul}")
    return value, notes


def grade_match_weight(grade_obs: str, grade_target: str) -> float:
    """Adjusted evidence is still evidence, just weaker."""
    table = cfg()["grades"]["match_weight_by_rank_distance"]
    d = abs(grade_rank(grade_obs) - grade_rank(grade_target))
    return table[min(d, len(table) - 1)]


def quantity_weight(qty: int | None) -> float:
    import math
    if not qty or qty <= 1:
        return 1.0
    return min(1.0 + math.log(qty) / 8.0, 1.3)


def normalise(gross: float, profile: dict, grade_obs: str, grade_target: str,
              quantity: int | None = 1, shipping: float = 0.0) -> NormalisedObs:
    net = net_realised(gross, profile, shipping)
    norm, notes = to_reference(net, grade_obs, profile["channel"])
    return NormalisedObs(
        price_raw=gross, price_net=round(net, 4), price_norm=round(norm, 4),
        grade_obs=grade_obs, channel=profile["channel"],
        w_trust=float(profile.get("trust", 0.5)),
        w_grade=grade_match_weight(grade_obs, grade_target),
        w_qty=quantity_weight(quantity),
        notes=notes,
    )
