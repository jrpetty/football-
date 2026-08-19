"""L1 - parsing. Rules and gazetteers first; they are debuggable and reproducible.

An LLM fallback belongs behind this, for the titles the rules cannot crack -
not in front of it (design doc s04).
"""
from __future__ import annotations

import functools
import re
from dataclasses import dataclass, field, asdict

from .config import catalog

# Families and their trade abbreviations, used to un-glue tokens like "LAT5420".
ABBREV = {
    "LAT": ["LATITUDE"], "TP": ["THINKPAD"], "EB": ["ELITEBOOK"], "PB": ["PROBOOK"],
    "MBA": ["MACBOOK", "AIR"], "MBP": ["MACBOOK", "PRO"], "INSP": ["INSPIRON"],
}
FAMILY_TOKENS = {
    "LATITUDE", "INSPIRON", "XPS", "PRECISION", "VOSTRO", "ELITEBOOK", "PROBOOK",
    "ZBOOK", "SPECTRE", "ENVY", "PAVILION", "THINKPAD", "LEGION", "IDEAPAD", "YOGA",
    "MACBOOK", "ZENBOOK", "VIVOBOOK", "ASPIRE", "SWIFT", "NITRO", "SURFACE",
}
BRANDS = {
    "DELL": "Dell", "HP": "HP", "HEWLETT": "HP", "LENOVO": "Lenovo", "APPLE": "Apple",
    "ASUS": "Asus", "ACER": "Acer", "MSI": "MSI", "MICROSOFT": "Microsoft",
    "RAZER": "Razer", "SAMSUNG": "Samsung", "TOSHIBA": "Toshiba", "FUJITSU": "Fujitsu",
}

RAM_VALUES = {2, 4, 6, 8, 12, 16, 20, 24, 32, 48, 64, 96}
_GB = re.compile(r"(\d{1,5})\s*(GB|TB)\b", re.I)
_PAIR = re.compile(r"\b(\d{1,3})\s*(?:GB)?\s*/\s*(\d{3,4})\s*(?:GB)?\b", re.I)
_PAIR_TB = re.compile(r"\b(\d{1,3})\s*(?:GB)?\s*/\s*(\d)\s*TB\b", re.I)
_EXPLICIT_RAM = re.compile(r"(?:(\d{1,3})\s*GB\s*(?:RAM|DDR\d?|MEMORY)|RAM\s*(\d{1,3})\s*GB)", re.I)
_SCREEN = re.compile(r"\b(1[0-9](?:\.\d)?)\s*(?:\"|''|IN\b|INCH|INCHES)", re.I)
_PANEL = [
    ("oled", re.compile(r"\bOLED\b", re.I)),
    ("uhd", re.compile(r"\b(UHD|4K|3840\s*X\s*2160)\b", re.I)),
    ("qhd", re.compile(r"\b(QHD|WQXGA|2560\s*X\s*1440|1440P)\b", re.I)),
    ("fhd", re.compile(r"\b(FHD|FULL\s*HD|1920\s*X\s*1080|1080P)\b", re.I)),
]
_TOUCH = re.compile(r"\bTOUCH(SCREEN)?\b(?!\s*PAD)", re.I)
_NOT_TOUCH = re.compile(r"\bNON[- ]?TOUCH\b", re.I)


def tokens(text: str) -> list[str]:
    """Normalise to a comparable token list.

    Handles the two conventions that break naive matching: G7 vs "Gen 7",
    and glued trade shorthand like LAT5420.
    """
    out: list[str] = []
    for tok in re.sub(r"[^A-Za-z0-9]+", " ", (text or "").upper()).split():
        m = re.fullmatch(r"([A-Z]{2,8})(\d{3,4})", tok)
        if m and (m.group(1) in FAMILY_TOKENS or m.group(1) in ABBREV):
            tok_parts = ABBREV.get(m.group(1), [m.group(1)]) + [m.group(2)]
            out += tok_parts
            continue
        if re.fullmatch(r"G\d{1,2}", tok):          # G7 -> GEN 7
            out += ["GEN", tok[1:]]
        elif tok in ABBREV:
            out += ABBREV[tok]
        else:
            out.append(tok)
    return out


@functools.lru_cache(maxsize=4096)
def _tokens_cached(text: str) -> tuple[str, ...]:
    """Gazetteer entries are constant, so tokenise each one once."""
    return tuple(tokens(text))


@functools.lru_cache(maxsize=1)
def _gazetteer(kind: str = "all"):
    """Pre-tokenised aliases, longest first so the specific beats the vague."""
    cat = catalog()
    out = {}
    for key in ("cpus", "gpus"):
        entries = []
        for row_id, row in cat[key].items():
            for alias in [row["canonical"], *row["aliases"]]:
                toks = _tokens_cached(alias)
                if toks:
                    entries.append((len(toks), toks, row_id))
        entries.sort(key=lambda e: -e[0])
        out[key] = entries
    models = []
    for model_id, model in cat["models"].items():
        toks = _tokens_cached(model["model_name"])
        models.append((len(toks), toks, model_id, model["brand"]))
    models.sort(key=lambda e: -e[0])
    out["models"] = models
    return out


def contains_sequence(hay, needle) -> bool:
    """True if `needle` appears as a contiguous run inside `hay`.

    Both are normalised to tuples: the gazetteer caches tuples while callers
    pass lists, and a list slice never compares equal to a tuple.
    """
    hay, needle = tuple(hay), tuple(needle)
    if not needle or len(needle) > len(hay):
        return False
    first, n = needle[0], len(needle)
    for i in range(len(hay) - n + 1):
        if hay[i] == first and hay[i:i + n] == needle:
            return True
    return False


@dataclass
class SpecVector:
    raw_title: str = ""
    brand: str | None = None
    model_id: str | None = None
    cpu_id: str | None = None
    gpu_id: str | None = None
    ram_gb: int | None = None
    storage_gb: int | None = None
    screen_in: float | None = None
    panel: str | None = None
    touch: bool | None = None
    confidence: float = 0.0
    unresolved: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return asdict(self)


def _ram_storage(title: str) -> tuple[int | None, int | None]:
    if m := _PAIR_TB.search(title):
        return int(m.group(1)), int(m.group(2)) * 1024
    if m := _PAIR.search(title):
        return int(m.group(1)), int(m.group(2))

    ram = None
    if m := _EXPLICIT_RAM.search(title):
        ram = int(m.group(1) or m.group(2))

    values = []
    for m in _GB.finditer(title):
        v = int(m.group(1))
        values.append(v * 1024 if m.group(2).upper() == "TB" else v)
    if not values:
        return ram, None

    storage = max(values) if max(values) >= 128 else None
    if ram is None:
        small = [v for v in values if v in RAM_VALUES and (storage is None or v < storage)]
        ram = min(small) if small else None
    return ram, storage


def _match_gazetteer(tok: list[str], kind: str) -> str | None:
    """Longest alias wins, so i5-1145G7 beats the generation-median 'i5 11th Gen'."""
    for _length, alias_tokens, row_id in _gazetteer()[kind]:
        if contains_sequence(tok, alias_tokens):
            return row_id
    return None


def parse_title(title: str) -> SpecVector:
    tok = tokens(title)
    spec = SpecVector(raw_title=title)

    for t in tok:
        if t in BRANDS:
            spec.brand = BRANDS[t]
            break

    # Model: every token of the model name must be present; most specific wins.
    for _length, keys, model_id, brand in _gazetteer()["models"]:
        if spec.brand and brand != spec.brand:
            continue
        if contains_sequence(tok, keys):
            spec.model_id = model_id
            break

    spec.cpu_id = _match_gazetteer(tok, "cpus")
    spec.gpu_id = _match_gazetteer(tok, "gpus")
    spec.ram_gb, spec.storage_gb = _ram_storage(title)

    if m := _SCREEN.search(title):
        spec.screen_in = float(m.group(1))
    for name, rx in _PANEL:
        if rx.search(title):
            spec.panel = name
            break
    spec.touch = bool(_TOUCH.search(title)) and not _NOT_TOUCH.search(title)

    # Confidence: weighted completeness of the fields that matter for identity.
    weights = {"model_id": 0.40, "cpu_id": 0.25, "ram_gb": 0.15, "storage_gb": 0.15, "brand": 0.05}
    spec.confidence = round(sum(w for f, w in weights.items() if getattr(spec, f)), 3)
    spec.unresolved = [f for f in weights if not getattr(spec, f)]
    return spec
