"""DuckDB warehouse. Raw data is never mutated - each layer appends a new table,
so any bad decision can be re-run without a re-import (design doc s13)."""
from __future__ import annotations

import os
from pathlib import Path

import duckdb

from .config import ROOT

DB_PATH = Path(os.environ.get("PRICER_DB", ROOT / "data" / "warehouse.duckdb"))

SCHEMA = """
CREATE SEQUENCE IF NOT EXISTS seq_obs START 1;
CREATE SEQUENCE IF NOT EXISTS seq_unit START 1;
CREATE SEQUENCE IF NOT EXISTS seq_event START 1;
CREATE SEQUENCE IF NOT EXISTS seq_lot START 1;
CREATE SEQUENCE IF NOT EXISTS seq_review START 1;

-- configurations, discovered from data and from intake
CREATE TABLE IF NOT EXISTS configs (
    config_id   VARCHAR PRIMARY KEY,
    model_id    VARCHAR, cpu_id VARCHAR, gpu_id VARCHAR,
    ram_gb      INTEGER, storage_gb INTEGER,
    panel       VARCHAR, touch BOOLEAN,
    first_seen  DATE
);

-- L0: exactly as it arrived, plus a hash so re-importing a file is a no-op
CREATE TABLE IF NOT EXISTS raw_observations (
    obs_id BIGINT PRIMARY KEY, source_id VARCHAR, batch VARCHAR,
    payload JSON, row_hash VARCHAR UNIQUE, ingested_at TIMESTAMP
);

-- L1-L2: parsed then resolved
CREATE TABLE IF NOT EXISTS matched_listings (
    obs_id BIGINT PRIMARY KEY, config_id VARCHAR, spec JSON,
    parse_confidence DOUBLE, match_confidence DOUBLE, match_method VARCHAR
);

-- L3: pushed to the reference basis, with its weight components
CREATE TABLE IF NOT EXISTS normalised_obs (
    obs_id BIGINT PRIMARY KEY, source_id VARCHAR, config_id VARCHAR,
    observation_type VARCHAR, sold_at DATE, quantity INTEGER,
    price_raw DOUBLE, price_net DOUBLE, price_norm DOUBLE,
    grade_obs VARCHAR, channel VARCHAR,
    w_trust DOUBLE, w_time DOUBLE, w_grade DOUBLE, w_qty DOUBLE
);

CREATE TABLE IF NOT EXISTS review_queue (
    id BIGINT PRIMARY KEY, stage VARCHAR, reason VARCHAR,
    payload JSON, candidates JSON, status VARCHAR DEFAULT 'open', created_at TIMESTAMP
);

-- stock book
CREATE TABLE IF NOT EXISTS purchase_lots (
    lot_id VARCHAR PRIMARY KEY, supplier VARCHAR, reference VARCHAR,
    acquired_at DATE, total_cost DOUBLE, freight DOUBLE DEFAULT 0, fees DOUBLE DEFAULT 0,
    allocation_method VARCHAR DEFAULT 'value_weighted', closed_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS units (
    unit_id VARCHAR PRIMARY KEY, config_id VARCHAR, serial VARCHAR, service_tag VARCHAR,
    lot_id VARCHAR, status VARCHAR,
    grade_intake VARCHAR, grade_current VARCHAR,
    battery_health_pct INTEGER, has_charger BOOLEAN DEFAULT TRUE,
    cost_allocated DOUBLE DEFAULT 0, cost_parts DOUBLE DEFAULT 0, cost_labour DOUBLE DEFAULT 0,
    value_at_intake DOUBLE, value_current DOUBLE, revalued_at DATE,
    location VARCHAR, listed_price DOUBLE, listed_channel VARCHAR,
    acquired_at DATE, ready_at DATE, listed_at DATE, sold_at DATE,
    sold_price DOUBLE, sold_channel VARCHAR, notes VARCHAR
);

CREATE TABLE IF NOT EXISTS unit_events (
    event_id BIGINT PRIMARY KEY, unit_id VARCHAR, event_type VARCHAR,
    from_status VARCHAR, to_status VARCHAR, actor VARCHAR,
    payload JSON, occurred_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quotes (
    quote_id VARCHAR PRIMARY KEY, config_id VARCHAR, grade VARCHAR, channel VARCHAR,
    estimate DOUBLE, p25 DOUBLE, p75 DOUBLE, confidence DOUBLE, tier VARCHAR,
    n_eff DOUBLE, created_at TIMESTAMP, actual_sale_price DOUBLE
);
"""


def connect(path: str | Path | None = None):
    p = Path(path) if path else DB_PATH
    p.parent.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect(str(p))
    con.execute(SCHEMA)
    return con


def reset(path: str | Path | None = None):
    p = Path(path) if path else DB_PATH
    if p.exists():
        p.unlink()
    return connect(p)
