"""Laptop pricing and stock engine.

Two halves that feed each other:
  L0-L6  pricing pipeline   market data -> value
  U1-U5  stock pipeline     physical machine -> tracked, costed, live-valued unit

They join on exactly one field: units.config_id.
"""
__version__ = "0.1.0"
