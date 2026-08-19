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

from .base import Connector, ConnectorError, RateLimiter, Token, require

OAUTH_URL = "https://api.ebay.com/identity/v1/oauth2/token"
CONSENT_URL = "https://auth.ebay.com/oauth2/authorize"
ORDERS_URL = "https://api.ebay.com/sell/fulfillment/v1/order"
SELL_SCOPES = ["https://api.ebay.com/oauth/api_scope/sell.fulfillment.readonly"]
BROWSE_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search"
INSIGHTS_URL = "https://api.ebay.com/buy/marketplace_insights/v1_beta/item_sales/search"


class EbayAuth:
    """eBay has two token flows and they are not interchangeable.

    Client credentials gives an APPLICATION token, which reads public data -
    Browse works with it. Seller data does not: getOrders requires a USER token
    obtained through the authorization code grant, and eBay rejects an
    application token outright. User tokens last two hours; the refresh token
    issued alongside them is long-lived, so consent is a one-off.
    """

    def __init__(self, transport):
        self.transport = transport
        self._app: Token | None = None
        self._user: Token | None = None

    def _basic(self) -> tuple[str, dict]:
        creds = require("ebay", "client_id", "client_secret")
        blob = base64.b64encode(
            f"{creds['client_id']}:{creds['client_secret']}".encode()).decode()
        return blob, creds

    def app_token(self) -> str:
        if self._app and self._app.valid():
            return self._app.value
        blob, _ = self._basic()
        payload = self.transport.request(
            "POST", OAUTH_URL,
            headers={"Authorization": f"Basic {blob}",
                     "Content-Type": "application/x-www-form-urlencoded"},
            body={"grant_type": "client_credentials",
                  "scope": "https://api.ebay.com/oauth/api_scope"})
        if "access_token" not in payload:
            raise ConnectorError(f"eBay did not return an application token: {payload}")
        self._app = Token.lasting(payload["access_token"], payload.get("expires_in", 7200))
        return self._app.value

    def user_token(self) -> str:
        if self._user and self._user.valid():
            return self._user.value
        blob, creds = self._basic()
        refresh = creds.get("refresh_token")
        if not refresh:
            raise ConnectorError(
                "eBay seller data needs a USER token, which an application token cannot "
                "substitute for.\n  Run:  pricer authorize ebay\n"
                "  That prints a consent URL; sign in as the seller, approve, then paste the\n"
                "  code back. The refresh token it stores is long-lived, so this is a one-off.")
        payload = self.transport.request(
            "POST", OAUTH_URL,
            headers={"Authorization": f"Basic {blob}",
                     "Content-Type": "application/x-www-form-urlencoded"},
            body={"grant_type": "refresh_token", "refresh_token": refresh,
                  "scope": " ".join(SELL_SCOPES)})
        if "access_token" not in payload:
            raise ConnectorError(
                f"eBay refused the refresh token: {payload}. Re-run `pricer authorize ebay`.")
        self._user = Token.lasting(payload["access_token"], payload.get("expires_in", 7200))
        return self._user.value

    # -- one-off consent ------------------------------------------------
    def consent_url(self) -> str:
        import urllib.parse
        creds = require("ebay", "client_id", "redirect_uri")
        return CONSENT_URL + "?" + urllib.parse.urlencode({
            "client_id": creds["client_id"], "response_type": "code",
            "redirect_uri": creds["redirect_uri"], "scope": " ".join(SELL_SCOPES)})

    def exchange_code(self, code: str) -> dict:
        import urllib.parse
        blob, creds = self._basic()
        if not creds.get("redirect_uri"):
            raise ConnectorError("ebay.redirect_uri (your RuName) is required in secrets.yml")
        payload = self.transport.request(
            "POST", OAUTH_URL,
            headers={"Authorization": f"Basic {blob}",
                     "Content-Type": "application/x-www-form-urlencoded"},
            body={"grant_type": "authorization_code",
                  "code": urllib.parse.unquote(code),
                  "redirect_uri": creds["redirect_uri"]})
        if "refresh_token" not in payload:
            raise ConnectorError(f"eBay did not return a refresh token: {payload}")
        return payload


class EbaySellOrders(Connector):
    """Your own eBay sales. Real prices, real dates, trust 1.0 - the single most
    valuable feed on the list, and the only eBay sold data not behind a gate."""

    id = "ebay_orders"
    label = "eBay - your own sales"
    channel = "ebay_bin"
    observation_type = "sold"
    trust = 1.00
    columns = ["date", "title", "price", "currency", "condition", "quantity",
               "order_id", "sku"]

    def __init__(self, transport=None):
        super().__init__(transport)
        self.auth = EbayAuth(self.transport)
        self.limiter = RateLimiter("ebay_orders", per_day=5000, min_interval_s=0.1)

    def fetch(self, since_days: int = 90, max_pages: int = 50, **_) -> list[dict]:
        since = (dt.datetime.now(dt.timezone.utc)
                 - dt.timedelta(days=since_days)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        headers = {"Authorization": f"Bearer {self.auth.user_token()}",
                   "Content-Type": "application/json"}
        out, offset = [], 0
        for _ in range(max_pages):
            self.limiter.take()
            payload = self.transport.request(
                "GET", ORDERS_URL, headers=headers,
                params={"filter": f"creationdate:[{since}..]", "limit": 200, "offset": offset})
            orders = payload.get("orders") or []
            out.extend(orders)
            if len(orders) < 200:
                break
            offset += 200
        return out

    def to_rows(self, payload: list[dict]) -> list[dict]:
        """One row per line item: an order may contain several machines, and
        pricing the order total as a single laptop would be badly wrong."""
        rows = []
        for order in payload:
            date = (order.get("creationDate") or "")[:10]
            for line in (order.get("lineItems") or []):
                cost = (line.get("lineItemCost") or {})
                if cost.get("value") is None:
                    continue
                quantity = int(line.get("quantity") or 1)
                rows.append({
                    "date": date,
                    "title": line.get("title", ""),
                    # lineItemCost is the unit price; total is unit x quantity
                    "price": cost["value"],
                    "currency": cost.get("currency", "GBP"),
                    "condition": "USED",
                    "quantity": quantity,
                    "order_id": order.get("orderId", ""),
                    "sku": line.get("sku", ""),
                })
        return rows

    def notes(self) -> list[str]:
        return ["Your own sales at trust 1.0 - these anchor every other source.",
                "One row per line item, so a multi-machine order does not become "
                "one very expensive laptop.",
                "Condition is not on the order; if you record grade in the SKU, map it "
                "in the source profile rather than letting everything default to B."]


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
        self.auth = EbayAuth(self.transport)

    def token(self) -> str:
        return self.auth.app_token()

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
