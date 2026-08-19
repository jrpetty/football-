"""Marketplace connectors. See base.py for the fetch-to-file architecture."""
from .base import (Connector, ConnectorError, MissingCredentials, RateLimiter,
                   ReplayTransport, SyncResult, Transport, secrets)
from .amazon import AmazonCompetitive, AmazonOrders
from .backmarket import BackMarketOrders
from .ebay import EbayAuth, EbayBrowse, EbaySellOrders, EbaySold

REGISTRY = {c.id: c for c in (EbayBrowse, EbaySellOrders, EbaySold,
                             AmazonCompetitive, AmazonOrders, BackMarketOrders)}


def get(name: str):
    if name not in REGISTRY:
        raise ConnectorError(f"unknown connector {name!r}. Available: {', '.join(sorted(REGISTRY))}")
    return REGISTRY[name]
