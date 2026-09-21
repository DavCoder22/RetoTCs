"""Métricas Prometheus en formato texto (sin dependencias extra).

Expone contadores con el prefijo `smartbancs_ai_*` para que el stack de
observabilidad existente (prometheus + grafana) siga funcionando igual.
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict
from datetime import datetime, timezone

_COUNTERS: "defaultdict[str, float]" = defaultdict(float)
_LOCK = threading.Lock()


def inc(name: str, value: float = 1.0) -> None:
    with _LOCK:
        _COUNTERS[name] += value


def observe(name: str, seconds: float) -> None:
    with _LOCK:
        _COUNTERS[name] += seconds
        _COUNTERS[name + "_count"] += 1


def render() -> str:
    lines = ["# TYPE smartbancs_ai_requests_total counter",
             "# HELP smartbancs_ai_requests_total Requests received by the AI agent"]
    with _LOCK:
        for key in sorted(_COUNTERS):
            lines.append(f"{key} {_COUNTERS[key]:.9f}")
    lines.append(f"smartbancs_ai_up{{ts=\"{datetime.now(timezone.utc).isoformat()}\"}} 1")
    return "\n".join(lines) + "\n"