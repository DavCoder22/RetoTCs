"""SmartBancs AI service — agente de recomendaciones (FastAPI + OpenRouter).

Servicio **independiente** consumido por smartbancs-api de forma asíncrona
(outbox + worker). No bloquea jamás el flujo transaccional:
- los endpoints son async (no saturan el event loop);
- el proveedor de OpenRouter usa httpx.AsyncClient (I/O no bloqueante);
- sin API key (o ante error del proveedor) cae a un mock avanzado y funcional.
"""
from __future__ import annotations

import logging
import os
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import PlainTextResponse
from pydantic import ValidationError

from .import metrics
from .models import AiRecommendationRequest, AiRecommendationResponse
from .providers import Options, RecommendationProvider

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("smartbancs-ai")

API_KEY = os.getenv("OPENROUTER_API_KEY", "").strip()
MODEL = os.getenv("AI_MODEL", "moonshotai/kimi-k2.6").strip() or "moonshotai/kimi-k2.6"
TIMEOUT = float(os.getenv("AI_HTTP_TIMEOUT", "30"))

app = FastAPI(
    title="SmartBancs AI Service",
    description="Agente de IA (Python/FastAPI) para recomendaciones no bloqueantes.",
    version="1.0.0",
)

provider = RecommendationProvider(Options(api_key=API_KEY, model=MODEL, timeout_seconds=TIMEOUT))


@app.on_event("startup")
async def _startup() -> None:
    source = "openrouter" if API_KEY else "mock"
    logger.info("smartbancs-ai ready model='%s' source='%s'", MODEL, source)


@app.on_event("shutdown")
async def _shutdown() -> None:
    await provider.aclose()


@app.get("/")
async def info() -> dict:
    return {
        "service": "smartbancs-ai",
        "model": MODEL,
        "provider": "openrouter" if API_KEY else "mock",
        "docs": "/docs",
        "health": "/health",
        "metrics": "/metrics",
    }


@app.get("/health")
async def health() -> dict:
    return {"status": "UP", "model": MODEL}


@app.get("/metrics", response_class=PlainTextResponse)
async def metrics_endpoint() -> str:
    return metrics.render()


@app.post("/internal/recommendations", response_model=AiRecommendationResponse)
async def recommendations(request: Request) -> AiRecommendationResponse:
    started = time.perf_counter()
    try:
        body = await request.json()
        payload = AiRecommendationRequest.model_validate(body)
        result = await provider.generate(payload)
        metrics.inc("smartbancs_ai_requests_total")
        metrics.inc(f"smartbancs_ai_recommendations_total{{source=\"{result.source}\",category=\"{result.category}\"}}")
    except ValidationError as exc:
        metrics.inc("smartbancs_ai_validation_errors_total")
        raise HTTPException(status_code=422, detail=exc.errors()) from exc
    except Exception as exc:  # noqa: BLE001
        metrics.inc("smartbancs_ai_errors_total")
        logger.exception("ai_request_failed")
        raise HTTPException(status_code=500, detail=str(exc)[:200]) from exc
    finally:
        metrics.observe("smartbancs_ai_duration_seconds_total", time.perf_counter() - started)
    return result