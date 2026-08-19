"""eBay connectors.

Two very different things, and the difference decides how much they are worth:

  EbayBrowse  — ACTIVE listings. Open access, client-credentials token,
                5,000 calls/day. These are ASKING prices, not sales. They tell
                you what sellers hope for, which is a ceiling and a movement
                signal, not what the market paid. Ingested at observation_type
                "ask" with the haircut and low trust that implies.

  EbaySold    — realised sold prices, via the Marketplace Insights API. That
                API is a Limited Release and eBay's own documentation states it
                is closed to new applicants. This class exists so the plumbing
                is ready if access is ever granted; until then it refuses
                clearly rather than pretending. Use a Terapeak export through
                the file pipeline instead.
"""
from __future__ import annotations

import base64
import datetime as dt

from .base import Connector, ConnectorError, RateLimiter, require

OAUTH_URL = "https://api.ebay.com/identity/v1/oauth2/token"
BROWSE_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search"
INSIGHTS_URL = "https://api.ebay.com/buy/marketplace_insights/v1_beta/item_sales/search"


class EbayBrowse(Connector):
    id = "ebay_browse"
    label = "eBay active listings (asking prices)"
    channel = "ebay_bin"
    observation_type = "ask"
    trust = 0.40
    columns = ["date", "title", "price", "currency", "condition", "quantity",
               "item_id", "seller", "buying_option"]

    MARKETPLACE = "EBAY_GB"
    PAGE = 200                      # the documented maximum per search call

    def __init__(self, transport=None, marketplace: str | None = None):
        super().__init__(transport)
        self.marketplace = marketplace or self.MARKETPLACE
        self.limiter = RateLimiter("ebay_browse", per_day=5000, min_interval_s=0.1)
        self._token = None

    def token(self) -> str:
        if self._token:
            return self._token
        creds = require("ebay", "client_id", "client_secret")
        basic = base64.b64encode(
            f"{creds['client_id']}:{creds['client_secret']}".encode()).decode()
        payload = self.transport.request(
            "POST", OAUTH_URL,
            headers={"Authorization": f"Basic {basic}",
                     "Content-Type": "application/x-www-form-urlencoded"},
            body={"grant_type": "client_credentials",
                  "scope": "https://api.ebay.com/oauth/api_scope"})
        if "access_token" not in payload:
            raise ConnectorError(f"eBay did not return a token: {payload}")
        self._token = payload["access_token"]
        return self._token

    def fetch(self, queries: list[str] | None = None, max_pages: int = 3,
              category_ids: str = "177", **_) -> list[dict]:
        """One search per query. Category 177 is PC Laptops & Netbooks."""
        queries = queries or []
        if not queries:
            raise ConnectorError("give at least one search query, e.g. --query 'Dell Latitude 5420'")
        headers = {"Authorization": f"Bearer {self.token()}",
                   "X-EBAY-C-MARKETPLACE-ID": self.marketplace,
                   "Content-Type": "application/json"}
        out: list[dict] = []
        for query in queries:
            for page in range(max_pages):
                self.limiter.take()
                payload = self.transport.request(
                    "GET", BROWSE_URL, headers=headers,
                    params={"q": query, "limit": self.PAGE,
                            "offset": page * self.PAGE,
                            "category_ids": category_ids,
                            "filter": "buyingOptions:{FIXED_PRICE}"})
                items = payload.get("itemSummaries") or []
                for item in items:
                    item["_query"] = query
                out.extend(items)
                if len(items) < self.PAGE:
                    break
        return out

    def to_rows(self, payload: list[dict]) -> list[dict]:
        today = dt.date.today().isoformat()
        rows = []
        for item in payload:
            price = (item.get("price") or {})
            value = price.get("value")
            if value is None:
                continue
            rows.append({
                "date": (item.get("itemCreationDate") or today)[:10],
                "title": item.get("title", ""),
                "price": value,
                "currency": price.get("currency", "GBP"),
                "condition": (item.get("condition") or "").upper().replace(" ", "_"),
                "quantity": 1,
                "item_id": item.get("itemId", ""),
                "seller": (item.get("seller") or {}).get("username", ""),
                "buying_option": ",".join(item.get("buyingOptions") or []),
            })
        return rows

    def notes(self) -> list[str]:
        return [
            "These are ACTIVE listings - asking prices, not sales. Ingested as "
            "observation_type 'ask' at trust 0.40 with an ask haircut.",
            f"{self.limiter.remaining()} of 5000 daily calls remaining.",
        ]

    def profile(self) -> dict:
        p = super().profile()
        p["ask_haircut"] = 0.96
        p["price"]["vat_treatment"] = "inclusive"
        return p


class EbaySold(Connector):
    id = "ebay_sold"
    label = "eBay sold items (Marketplace Insights - restricted)"
    channel = "ebay_bin"
    observation_type = "sold"
    trust = 0.90
    columns = ["date", "title", "price", "currency", "condition", "quantity", "item_id"]

    def __init__(self, transport=None, marketplace: str = "EBAY_GB"):
        super().__init__(transport)
        self.marketplace = marketplace
        self.limiter = RateLimiter("ebay_sold", per_day=5000, min_interval_s=0.2)

    def fetch(self, queries: list[str] | None = None, days: int = 90, **_) -> list[dict]:
        creds = require("ebay", "client_id", "client_secret")
        if not creds.get("insights_approved"):
            raise ConnectorError(
                "eBay Marketplace Insights is a Limited Release API and eBay's documentation "
                "states it is closed to new applicants.\n"
                "  If your application HAS been granted the buy.marketplace.insights scope, set\n"
                "    ebay:\n      insights_approved: true\n"
                "  in config/secrets.yml and this will work.\n"
                "  Otherwise export sold data from Terapeak (included with an eBay shop) and\n"
                "  drop the CSV into data/incoming - `pricer inspect` will map it.")

        browse = EbayBrowse(self.transport, self.marketplace)
        headers = {"Authorization": f"Bearer {browse.token()}",
                   "X-EBAY-C-MARKETPLACE-ID": self.marketplace}
        since = (dt.date.today() - dt.timedelta(days=min(days, 90))).isoformat()
        out: list[dict] = []
        for query in (queries or []):
            self.limiter.take()
            payload = self.transport.request(
                "GET", INSIGHTS_URL, headers=headers,
                params={"q": query, "limit": 200,
                        "filter": f"lastSoldDate:[{since}T00:00:00Z..]"})
            out.extend(payload.get("itemSales") or [])
        return out

    def to_rows(self, payload: list[dict]) -> list[dict]:
        rows = []
        for item in payload:
            price = (item.get("lastSoldPrice") or {})
            if price.get("value") is None:
                continue
            rows.append({
                "date": (item.get("lastSoldDate") or "")[:10],
                "title": item.get("title", ""),
                "price": price["value"],
                "currency": price.get("currency", "GBP"),
                "condition": (item.get("condition") or "").upper().replace(" ", "_"),
                "quantity": item.get("quantitySold", 1),
                "item_id": item.get("itemId", ""),
            })
        return rows
