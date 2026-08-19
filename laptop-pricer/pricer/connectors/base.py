"""Marketplace connectors.

Deliberate architecture: a connector's only job is to fetch and write a file.
Everything downstream - parsing, identity, normalisation, pricing - reads that
file through the existing L0 source profiles and stays completely offline.

That matters for three reasons. Pricing never blocks on a rate limit or an
outage. Every valuation stays reproducible, because the raw response that
produced it is on disk. And the whole pipeline below the connector keeps its
existing tests, unchanged.

    network  │  fetch  →  data/raw/<source>/<date>.json   (raw, never edited)
             │            data/incoming/<source>_<date>.csv
    offline  │  ingest → parse → resolve → normalise → estimate
"""
from __future__ import annotations

import datetime as dt
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from ..config import ROOT


class ConnectorError(Exception):
    pass


class MissingCredentials(ConnectorError):
    """Raised with instructions rather than a stack trace."""


# ---------------------------------------------------------------- credentials

def secrets() -> dict:
    """Credentials from the environment first, then a gitignored file.

    Never committed, never logged, never written into a raw dump.
    """
    path = ROOT / "config" / "secrets.yml"
    data = {}
    if path.exists():
        with open(path) as fh:
            data = yaml.safe_load(fh) or {}
    for key, value in os.environ.items():
        if key.startswith("PRICER_"):
            parts = key[len("PRICER_"):].lower().split("__", 1)
            if len(parts) == 2:
                data.setdefault(parts[0], {})[parts[1]] = value
    return data


def require(source: str, *keys: str) -> dict:
    creds = secrets().get(source, {})
    missing = [k for k in keys if not creds.get(k)]
    if missing:
        raise MissingCredentials(
            f"{source}: missing {', '.join(missing)}.\n"
            f"  Put them in config/secrets.yml (gitignored):\n"
            f"    {source}:\n" + "".join(f"      {k}: ...\n" for k in keys) +
            f"  or export " + ", ".join(f"PRICER_{source.upper()}__{k.upper()}" for k in keys))
    return creds


# ---------------------------------------------------------------- rate limits

@dataclass
class RateLimiter:
    """Token bucket persisted to disk, so a daily cap survives process restarts.

    eBay's Browse API allows 5,000 calls a day. Losing count across runs and
    getting the application throttled is a self-inflicted outage.
    """
    name: str
    per_day: int
    min_interval_s: float = 0.0
    _state: Path = field(init=False)

    def __post_init__(self):
        self._state = ROOT / "data" / "raw" / f".ratelimit_{self.name}.json"
        self._state.parent.mkdir(parents=True, exist_ok=True)

    def _read(self) -> dict:
        if self._state.exists():
            try:
                return json.loads(self._state.read_text())
            except json.JSONDecodeError:
                pass
        return {"day": "", "used": 0, "last": 0.0}

    def remaining(self, today: str | None = None) -> int:
        today = today or dt.date.today().isoformat()
        s = self._read()
        return self.per_day if s["day"] != today else max(self.per_day - s["used"], 0)

    def take(self, n: int = 1, today: str | None = None) -> None:
        today = today or dt.date.today().isoformat()
        s = self._read()
        if s["day"] != today:
            s = {"day": today, "used": 0, "last": 0.0}
        if s["used"] + n > self.per_day:
            raise ConnectorError(
                f"{self.name}: daily limit of {self.per_day} calls reached "
                f"({s['used']} used). Resets tomorrow; cached data still prices normally.")
        if self.min_interval_s:
            wait = self.min_interval_s - (time.time() - s["last"])
            if wait > 0:
                time.sleep(wait)
        s["used"] += n
        s["last"] = time.time()
        self._state.write_text(json.dumps(s))


# ---------------------------------------------------------------- tokens

@dataclass
class Token:
    """An access token and when it stops working.

    Marketplace access tokens are short-lived - eBay user tokens last two hours.
    Caching one for the life of the process works right up until a long sync,
    then fails halfway through.
    """
    value: str
    expires_at: float
    SKEW = 120.0                       # refresh early rather than race the clock

    def valid(self) -> bool:
        return bool(self.value) and time.time() < self.expires_at - self.SKEW

    @classmethod
    def lasting(cls, value: str, seconds: float) -> "Token":
        return cls(value, time.time() + float(seconds or 0))


# ---------------------------------------------------------------- transport

class Transport:
    """HTTP, isolated behind one seam so every connector is testable offline."""

    RETRY_ON = {429, 500, 502, 503, 504}
    MAX_ATTEMPTS = 4

    def request(self, method: str, url: str, headers: dict | None = None,
                params: dict | None = None, body=None, timeout: float = 30.0) -> dict:
        import urllib.error
        import urllib.parse
        import urllib.request

        if params:
            url = f"{url}?{urllib.parse.urlencode(params, doseq=True)}"
        data = None
        if body is not None:
            form = (headers or {}).get("Content-Type", "").startswith("application/x-www-form")
            data = (urllib.parse.urlencode(body).encode() if form and isinstance(body, dict)
                    else json.dumps(body).encode())

        last = None
        for attempt in range(self.MAX_ATTEMPTS):
            req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    return json.loads(resp.read().decode() or "{}")
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode(errors="replace")[:400]
                last = ConnectorError(f"{method} {url.split('?')[0]} -> HTTP {exc.code}: {detail}")
                if exc.code not in self.RETRY_ON or attempt == self.MAX_ATTEMPTS - 1:
                    raise last from exc
                # honour Retry-After when the server sets it, else back off
                wait = float(exc.headers.get("Retry-After") or 0) or 2.0 ** attempt
                time.sleep(min(wait, 30.0))
            except urllib.error.URLError as exc:
                last = ConnectorError(f"cannot reach {url.split('?')[0]}: {exc.reason}")
                if attempt == self.MAX_ATTEMPTS - 1:
                    raise last from exc
                time.sleep(2.0 ** attempt)
        raise last


class ReplayTransport(Transport):
    """Serves recorded responses. Used by the tests and by `--replay`, so a
    connector can be exercised end to end without credentials or network."""

    def __init__(self, responses: dict[str, object]):
        self.responses = responses
        self.calls: list[tuple[str, str, dict]] = []

    def request(self, method, url, headers=None, params=None, body=None, timeout=30.0):
        self.calls.append((method, url, params or {}))
        for key, payload in self.responses.items():
            if key in url:
                return payload() if callable(payload) else payload
        raise ConnectorError(f"no recorded response for {url}")


# ---------------------------------------------------------------- connector

@dataclass
class SyncResult:
    source: str
    fetched: int
    raw_path: str | None
    csv_path: str | None
    notes: list[str] = field(default_factory=list)


class Connector:
    """Base class. Subclasses implement `fetch` and `to_rows`."""

    id: str = ""
    label: str = ""
    channel: str = ""
    observation_type: str = "sold"
    trust: float = 0.5
    columns: list[str] = []

    def __init__(self, transport: Transport | None = None):
        self.transport = transport or Transport()

    def fetch(self, **kw) -> list[dict]:
        raise NotImplementedError

    def to_rows(self, payload: list[dict]) -> list[dict]:
        raise NotImplementedError

    # -- shared plumbing ------------------------------------------------
    def sync(self, as_of: dt.date | None = None, **kw) -> SyncResult:
        import csv as _csv
        as_of = as_of or dt.date.today()
        payload = self.fetch(**kw)

        raw_dir = ROOT / "data" / "raw" / self.id
        raw_dir.mkdir(parents=True, exist_ok=True)
        raw_path = raw_dir / f"{as_of.isoformat()}.json"
        raw_path.write_text(json.dumps(payload, indent=1, default=str))

        rows = self.to_rows(payload)
        csv_path = None
        if rows:
            csv_path = ROOT / "data" / "incoming" / f"{self.id}_{as_of.isoformat()}.csv"
            csv_path.parent.mkdir(parents=True, exist_ok=True)
            with open(csv_path, "w", newline="") as fh:
                writer = _csv.DictWriter(fh, fieldnames=self.columns)
                writer.writeheader()
                writer.writerows(rows)

        return SyncResult(self.id, len(rows), str(raw_path),
                          str(csv_path) if csv_path else None, self.notes())

    def notes(self) -> list[str]:
        return []

    def profile(self) -> dict:
        """The source profile this connector's output is read through."""
        return {
            "id": self.id, "label": self.label, "channel": self.channel,
            "currency": "GBP", "observation_type": self.observation_type,
            "trust": self.trust, "file_glob": f"{self.id}_*.csv",
            "price": {"vat_treatment": "inclusive", "fees": {"rate": 0.0, "fixed": 0.0}},
            "columns": {"raw_title": "title", "price_gross": "price",
                        "sold_at": "date", "quantity": "quantity", "grade_raw": "condition"},
            "date_format": "%Y-%m-%d",
            "grade_map": {
                # eBay
                "NEW": "A_PLUS", "NEW_OTHER": "A_PLUS", "LIKE_NEW": "A_PLUS",
                "EXCELLENT_-_REFURBISHED": "A", "EXCELLENT": "A", "VERY_GOOD": "A",
                "VERY_GOOD_-_REFURBISHED": "A", "GOOD": "B", "GOOD_-_REFURBISHED": "B",
                "USED": "B", "SELLER_REFURBISHED": "B", "ACCEPTABLE": "C", "FAIR": "C",
                "FOR_PARTS_OR_NOT_WORKING": "SALVAGE",
                # Amazon condition ids
                "USEDLIKENEW": "A_PLUS", "USEDVERYGOOD": "A", "USEDGOOD": "B",
                "USEDACCEPTABLE": "C", "REFURBISHED": "A", "CLUB": "B",
                # Back Market
                "PREMIUM": "A_PLUS", "STALLONE": "A", "FAIR_CONDITION": "C"},
            "exclude_if": [{"title_matches": r"(?i)\b(job ?lot|bundle|spares|faulty|pallet)\b"},
                           {"price_gross_below": 20}],
        }
