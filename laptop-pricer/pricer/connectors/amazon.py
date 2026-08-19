"""Amazon Selling Partner API connectors.

What Amazon will and will not give you:

  AmazonCompetitive — current offers on an ASIN: buy-box price, lowest priced
                      offers, and reference prices. The competitive summary also
                      exposes an average selling price derived from what buyers
                      actually paid over the trailing 60 days, which IS
                      transaction-derived rather than an asking price - the only
                      such signal available here without being the seller.

  AmazonOrders      — YOUR OWN sales. Real prices, real dates, trust 1.0.
                      This is the most valuable thing on this list.

Amazon does not expose other sellers' sold-price history. Anything presented as
"Amazon sold prices" from a third party is inferred, not sourced.

Endpoints are held as constants because Amazon revises paths and versions;
check them against the current SP-API documentation before first use.
"""
from __future__ import annotations

import datetime as dt

from .base import Connector, ConnectorError, RateLimiter, Token, require

LWA_TOKEN_URL = "https://api.amazon.com/auth/o2/token"
ENDPOINTS = {"eu": "https://sellingpartnerapi-eu.amazon.com",
             "na": "https://sellingpartnerapi-na.amazon.com",
             "fe": "https://sellingpartnerapi-fe.amazon.com"}
COMPETITIVE_PATH = "/batches/products/pricing/2022-05-01/items/competitiveSummary"
ORDERS_PATH = "/orders/v0/orders"
ORDER_ITEMS_PATH = "/orders/v0/orders/{order_id}/orderItems"


class _AmazonBase(Connector):
    region = "eu"

    def __init__(self, transport=None, region: str | None = None):
        super().__init__(transport)
        self.region = region or self.region
        self.host = ENDPOINTS[self.region]
        self.limiter = RateLimiter(f"amazon_{self.id}", per_day=7200, min_interval_s=0.5)
        self._token = None

    def token(self) -> str:
        if self._token and self._token.valid():
            return self._token.value
        creds = require("amazon", "refresh_token", "client_id", "client_secret")
        payload = self.transport.request(
            "POST", LWA_TOKEN_URL,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            body={"grant_type": "refresh_token", "refresh_token": creds["refresh_token"],
                  "client_id": creds["client_id"], "client_secret": creds["client_secret"]})
        if "access_token" not in payload:
            raise ConnectorError(f"Amazon did not return a token: {payload}")
        self._token = Token.lasting(payload["access_token"], payload.get("expires_in", 3600))
        return self._token.value

    def headers(self) -> dict:
        # Since October 2023 SP-API needs only the LWA bearer token; AWS IAM and
        # Signature Version 4 are no longer required.
        return {"x-amz-access-token": self.token(), "Content-Type": "application/json"}


class AmazonCompetitive(_AmazonBase):
    id = "amazon_competitive"
    label = "Amazon competitive pricing (offers + 60-day average selling price)"
    channel = "amazon_renewed"
    observation_type = "ask"          # offers are asks; the average is handled below
    trust = 0.45
    columns = ["date", "title", "price", "currency", "condition", "quantity", "asin", "kind"]
    BATCH = 20                         # documented maximum ASINs per call

    def fetch(self, asins: list[str] | None = None, marketplace_id: str = "A1F83G8C2ARO7P",
              **_) -> list[dict]:
        asins = asins or []
        if not asins:
            raise ConnectorError("give at least one ASIN, e.g. --asin B08N5W4NNB")
        out = []
        for i in range(0, len(asins), self.BATCH):
            batch = asins[i:i + self.BATCH]
            self.limiter.take()
            payload = self.transport.request(
                "POST", self.host + COMPETITIVE_PATH, headers=self.headers(),
                body={"requests": [
                    {"uri": "/products/pricing/2022-05-01/items/competitiveSummary",
                     "method": "GET", "asin": a, "marketplaceId": marketplace_id,
                     "includedData": ["featuredBuyingOptions", "lowestPricedOffers",
                                      "referencePrices"]} for a in batch]})
            out.extend(payload.get("responses") or [])
        return out

    def to_rows(self, payload: list[dict]) -> list[dict]:
        today = dt.date.today().isoformat()
        rows = []
        for response in payload:
            body = response.get("body") or {}
            asin = body.get("asin", "")
            title = body.get("title") or asin
            for offer in (body.get("lowestPricedOffers") or []):
                listing = (offer.get("listingPrice") or {})
                if listing.get("amount") is None:
                    continue
                rows.append({"date": today, "title": title, "price": listing["amount"],
                             "currency": listing.get("currencyCode", "GBP"),
                             "condition": (offer.get("condition") or "USED").upper(),
                             "quantity": 1, "asin": asin, "kind": "offer"})
            # transaction-derived, so it is worth far more than the offers above
            for ref in (body.get("referencePrices") or []):
                if ref.get("name") == "CompetitivePriceThreshold":
                    continue
                price = (ref.get("price") or {})
                if price.get("amount") is None:
                    continue
                rows.append({"date": today, "title": title, "price": price["amount"],
                             "currency": price.get("currencyCode", "GBP"),
                             "condition": "USED", "quantity": 1, "asin": asin,
                             "kind": ref.get("name", "reference")})
        return rows

    def notes(self) -> list[str]:
        return [
            "Offers are ASKING prices. Rows marked kind=AverageSellingPrice are "
            "derived from actual purchases over 60 days and are worth much more - "
            "split them into their own source profile with a higher trust.",
            "Amazon does not publish other sellers' sold-price history.",
        ]


class AmazonOrders(_AmazonBase):
    id = "amazon_orders"
    label = "Amazon - your own sales"
    channel = "amazon_renewed"
    observation_type = "sold"
    trust = 1.00
    columns = ["date", "title", "price", "currency", "condition", "quantity", "order_id"]

    def fetch(self, since_days: int = 90, marketplace_id: str = "A1F83G8C2ARO7P", **_) -> list[dict]:
        require("amazon", "refresh_token", "client_id", "client_secret")
        since = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=since_days)).isoformat()
        out, token = [], None
        while True:
            self.limiter.take()
            params = {"MarketplaceIds": marketplace_id, "CreatedAfter": since,
                      "OrderStatuses": "Shipped"}
            if token:
                params = {"NextToken": token, "MarketplaceIds": marketplace_id}
            payload = self.transport.request("GET", self.host + ORDERS_PATH,
                                             headers=self.headers(), params=params)
            result = payload.get("payload") or payload
            out.extend(result.get("Orders") or [])
            token = result.get("NextToken")
            if not token:
                break

        # An order total carries no item title, so on its own it cannot be
        # matched to a machine. Pull the line items and price those instead.
        for order in out:
            order_id = order.get("AmazonOrderId")
            if not order_id:
                continue
            self.limiter.take()
            items = self.transport.request(
                "GET", self.host + ORDER_ITEMS_PATH.format(order_id=order_id),
                headers=self.headers())
            body = items.get("payload") or items
            order["_items"] = body.get("OrderItems") or []
        return out

    columns = ["date", "title", "price", "currency", "condition", "quantity",
               "order_id", "sku", "asin"]

    def to_rows(self, payload: list[dict]) -> list[dict]:
        """One row per line item. An order containing three laptops must not
        become one laptop at three times the price."""
        rows = []
        for order in payload:
            date = (order.get("PurchaseDate") or "")[:10]
            currency = (order.get("OrderTotal") or {}).get("CurrencyCode", "GBP")
            for item in order.get("_items", []):
                price = (item.get("ItemPrice") or {})
                amount, quantity = price.get("Amount"), int(item.get("QuantityOrdered") or 1)
                if amount is None or quantity < 1:
                    continue
                rows.append({
                    "date": date,
                    "title": item.get("Title", ""),
                    "price": round(float(amount) / quantity, 2),   # ItemPrice covers the whole line
                    "currency": price.get("CurrencyCode", currency),
                    "condition": (item.get("ConditionId") or "USED").upper(),
                    "quantity": quantity,
                    "order_id": order.get("AmazonOrderId", ""),
                    "sku": item.get("SellerSKU", ""),
                    "asin": item.get("ASIN", ""),
                })
        return rows

    def notes(self) -> list[str]:
        return ["Your own sales at trust 1.0.",
                "One row per line item, with ItemPrice divided by quantity to get a "
                "unit price - SP-API reports the line total, not the unit.",
                "Amazon condition ids are coarse; if you record grade in the SKU, "
                "map it in the source profile."]
