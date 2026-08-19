"""Back Market connector.

Back Market publishes a merchant API for managing your own listings and orders.
It does not publish a market-data API: there is no supported way to read what
other merchants sold an item for. So this connector covers your own sales, which
is the part that is both available and most valuable.

For Back Market as a price signal on machines you do not sell, the honest
options are your own historical sales on the platform, or nothing.
"""
from __future__ import annotations

import base64
import datetime as dt

from .base import Connector, ConnectorError, RateLimiter, require

HOSTS = {"uk": "https://www.backmarket.co.uk", "fr": "https://www.backmarket.fr",
         "de": "https://www.backmarket.de", "us": "https://www.backmarket.com"}
ORDERS_PATH = "/ws/orders"


class BackMarketOrders(Connector):
    id = "backmarket_orders"
    label = "Back Market - your own sales"
    channel = "back_market"
    observation_type = "sold"
    trust = 1.00
    columns = ["date", "title", "price", "currency", "condition", "quantity", "order_id"]

    def __init__(self, transport=None, region: str = "uk"):
        super().__init__(transport)
        self.host = HOSTS.get(region, HOSTS["uk"])
        self.limiter = RateLimiter("backmarket", per_day=5000, min_interval_s=0.3)

    def headers(self) -> dict:
        creds = require("backmarket", "token")
        scheme = creds.get("scheme", "Basic")
        token = creds["token"]
        if scheme.lower() == "basic" and ":" in token:
            token = base64.b64encode(token.encode()).decode()
        return {"Authorization": f"{scheme} {token}", "Accept": "application/json"}

    def fetch(self, since_days: int = 90, max_pages: int = 20, **_) -> list[dict]:
        since = (dt.date.today() - dt.timedelta(days=since_days)).isoformat()
        headers, out = self.headers(), []
        for page in range(1, max_pages + 1):
            self.limiter.take()
            payload = self.transport.request(
                "GET", self.host + ORDERS_PATH, headers=headers,
                params={"page": page, "date_creation": since})
            results = payload.get("results") or []
            out.extend(results)
            if not payload.get("next") or not results:
                break
        return out

    def to_rows(self, payload: list[dict]) -> list[dict]:
        rows = []
        for order in payload:
            for line in (order.get("orderlines") or []):
                price = line.get("price")
                if price is None:
                    continue
                rows.append({
                    "date": (order.get("date_creation") or "")[:10],
                    "title": line.get("product") or line.get("listing", ""),
                    "price": price, "currency": order.get("currency", "GBP"),
                    "condition": str(line.get("state", "USED")).upper(),
                    "quantity": line.get("quantity", 1),
                    "order_id": str(order.get("order_id", "")),
                })
        return rows

    def notes(self) -> list[str]:
        return ["Back Market has no public market-data API - this is your own sales only.",
                "Verify the orders path and auth scheme against your merchant "
                "documentation; both vary by account age and region."]
