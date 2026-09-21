"""Proveedores de generación de recomendaciones.

- OpenRouterProvider: llama al modelo real (por defecto moonshotai/kimi-k2.6) de
  forma **asíncrona** (httpx.AsyncClient) para no bloquear el event loop.
- MockProvider: avanzado y funcional, determinista (heurística sobre el DTO) para
  que la demo/tests funcionen sin red ni API key.
"""
from __future__ import annotations

import json
import logging
from decimal import Decimal

import httpx

from .models import AiRecommendationRequest, AiRecommendationResponse, TxnContext

logger = logging.getLogger(__name__)

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
BIG_THRESHOLD_USD = Decimal("2500")

SYSTEM_PROMPT = (
    "You are SmartBancs, the financial assistant. You receive a JSON transaction "
    "context (a DTO) and must decide a personalized recommendation for the customer. "
    "Respond ONLY with a single JSON object matching this schema (no markdown): "
    '{"category": string, "message": string, "priority": "LOW"|"MEDIUM"|"HIGH", '
    '"insights": [string, ...]}. '
    "Consider the transaction type, amount, currency and balances to give useful, "
    "specific and risk-aware advice for a Peruvian customer (PEN/USD)."
)


def _mock_decision(context: TxnContext) -> dict:
    amount = context.amount
    high_amount = amount >= BIG_THRESHOLD_USD if context.currency == "USD" else amount >= BIG_THRESHOLD_USD * Decimal("3.75")
    if context.type in ("WITHDRAWAL", "PAYMENT"):
        return {
            "category": "spending",
            "priority": "HIGH" if high_amount else "MEDIUM",
            "message": f"Detectamos un {_spanish_type(context.type)} de {amount:,.2f} {context.currency}. "
                       f"Revisa tus notificaciones en tiempo real para verificar que fuiste tú.",
            "insights": [f"Move of {amount} {context.currency}; verify with 2FA", "Suggested: enable instant alerts"],
        }
    if context.type == "TRANSFER":
        return {
            "category": "transfer",
            "priority": "LOW",
            "message": f"Transferencia de {amount:,.2f} {context.currency} registrada. "
                       f"Puedes programar un ahorro automático del 5% para llegar a meta.",
            "insights": ["P2P movement", "Saving suggestion: 5% autosave"],
        }
    return {
        "category": "savings",
        "priority": "MEDIUM",
        "message": f"Abonaste {amount:,.2f} {context.currency}. Conserva el hábito: "
                   f"estás a 18% de tu meta dormir tranquilo con un colchón de emergencia.",
        "insights": ["Incoming credit", "Emergency-fund progress +18%"],
    }


def _spanish_type(tx_type: str) -> str:
    return {"WITHDRAWAL": "retiro", "PAYMENT": "pago", "TRANSFER": "transferencia", "DEPOSIT": "abono"}.get(tx_type, tx_type.lower())


def _extract_json(text: str) -> dict:
    snippet = text.strip()
    start, end = snippet.find("{"), snippet.rfind("}")
    if start >= 0 and end > start:
        snippet = snippet[start:end + 1]
    return json.loads(snippet)


class Options:
    def __init__(self, api_key: str | None, model: str, timeout_seconds: float = 30.0) -> None:
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds


class RecommendationProvider:
    def __init__(self, options: Options) -> None:
        self._options = options
        self._client = httpx.AsyncClient(
            timeout=options.timeout_seconds,
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def generate(self, request: AiRecommendationRequest) -> AiRecommendationResponse:
        if not self._options.api_key:
            return self._fallback(request, reason="no OPENROUTER_API_KEY configured")
        try:
            data = await self._call_openrouter(request)
            source, model = "openrouter", self._options.model
            logger.info("ai_recommendation source='openrouter' model='%s' category='%s'", model, data.get("category"))
        except Exception as exc:  # noqa: BLE001 — el agente nunca debe tumbar la transacción
            logger.warning("ai_recommendation fallback_to_mock error='%s'", exc)
            return self._fallback(request, reason=str(exc))
        return self._to_response(request, data, source, model)

    def _fallback(self, request: AiRecommendationRequest, reason: str) -> AiRecommendationResponse:
        data = _mock_decision(request.context)
        logger.info("ai_recommendation source='mock' reason='%s'", reason)
        return self._to_response(request, data, "mock", "heuristic-mock-v1")

    async def _call_openrouter(self, request: AiRecommendationRequest) -> dict:
        payload = request.context.model_dump_json()
        body = {
            "model": self._options.model,
            "temperature": 0.3,
            "stream": False,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"Decision context (DTO): {payload}"},
            ],
        }
        resp = await self._client.post(
            OPENROUTER_URL,
            headers={"Authorization": f"Bearer {self._options.api_key}", "Content-Type": "application/json"},
            json=body,
        )
        resp.raise_for_status()
        content = resp.json()["choices"][0]["message"]["content"]
        return _extract_json(content)

    @staticmethod
    def _to_response(request: AiRecommendationRequest, data: dict, source: str, model: str) -> AiRecommendationResponse:
        return AiRecommendationResponse(
            customer_id=request.context.customer_id,
            category=str(data.get("category", "generic"))[:50],
            message=str(data.get("message", "")),
            priority=str(data.get("priority", "MEDIUM")).upper(),
            insights=[str(i) for i in data.get("insights", [])][:10],
            model=model,
            source=source,
        )