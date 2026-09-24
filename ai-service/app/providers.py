"""Proveedores de generación de recomendaciones (contrato estandarizado).

- OpenRouterProvider: llama al modelo real (por defecto moonshotai/kimi-k2.6) de
  forma **asíncrona** (httpx.AsyncClient) para no bloquear el event loop.
- MockProvider: avanzado y funcional, determinista (heurística sobre el DTO) para
  que la demo/tests funcionen sin red ni API key.

Ambos proveedores pasan por `_to_response`, que **normaliza** la salida contra el
contrato estandarizado: la API siempre contesta el mismo esquema con `category`
∈ {savings, spending, transfer, risk, generic}, `priority` ∈ {LOW, MEDIUM, HIGH},
`message` no vacío, `insights` ≤ 10 y `actions` 1..5 — sea la respuesta real del
modelo o el mock. Así el consumo de la API es óptimo y predecible.
"""
from __future__ import annotations

import asyncio
import json
import logging
from decimal import Decimal

import httpx

from .models import AiRecommendationRequest, AiRecommendationResponse, TxnContext

logger = logging.getLogger(__name__)

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
BIG_THRESHOLD_USD = Decimal("2500")
CATEGORIES = {"savings", "spending", "transfer", "risk", "generic"}
PRIORITIES = {"LOW", "MEDIUM", "HIGH"}
SEGMENT_SAVINGS_TARGET = {"RETAIL": "20000", "PREMIUM": "60000", "CORPORATE": "200000"}  # usado por el mock

SYSTEM_PROMPT = (
    "You are SmartBancs, the AI of a bank advising its customer with the next best "
    "action. You receive a JSON transaction context (a DTO) that includes the "
    "customer's general information (customerSegment, accountAgeDays) plus the "
    "movement data (type, amount, currency, balances after the operation). Decide "
    "what the customer should do, as the bank would. "
    "Respond ONLY with a single JSON object (no markdown, no extra text) matching "
    "exactly this schema: "
    '{"category": "savings|spending|transfer|risk|generic", '
    '"message": string, "priority": "LOW|MEDIUM|HIGH", '
    '"insights": [string, ...], "actions": [string, ...]}. '
    "Rules: message in Spanish, concise, specific and risk-aware for a Peruvian "
    "customer (PEN/USD). category: savings for incoming money, spending for small "
    "outflows, risk for large or unusual outflows, transfer for P2P movements, "
    "generic otherwise. insights: up to 4 short reasons. actions: 2-4 concrete "
    "recommended actions."
)


def _spanish_type(tx_type: str) -> str:
    return {"WITHDRAWAL": "retiro", "PAYMENT": "pago", "TRANSFER": "transferencia", "DEPOSIT": "abono"}.get(
        tx_type, tx_type.lower()
    )


def _mock_decision(context: TxnContext) -> dict:
    """Decisión determinista usando información general del cliente (segmento y
    antigüedad de cuenta) además del movimiento: cantidad, moneda y saldos."""
    amount = context.amount
    segment = (context.customer_segment or "RETAIL").upper()
    age_days = context.account_age_days if context.account_age_days is not None else 365
    target = SEGMENT_SAVINGS_TARGET.get(segment, SEGMENT_SAVINGS_TARGET["RETAIL"])
    high_threshold = BIG_THRESHOLD_USD if context.currency == "USD" else BIG_THRESHOLD_USD * Decimal("3.75")
    is_high = amount >= high_threshold
    is_new = age_days < 90
    profile = [
        f"Customer segment: {segment}",
        f"Account age: {age_days} days",
    ]
    if is_new:
        profile.append("New account: strengthen adoption")

    if context.type in ("WITHDRAWAL", "PAYMENT"):
        category = "risk" if is_high else "spending"
        priority = "HIGH" if is_high else "MEDIUM"
        message = (
            f"Detectamos un {_spanish_type(context.type)} de {amount:,.2f} {context.currency}."
            f"{' Es un monto alto: verifica que fuiste tú antes de continuar.' if is_high else ''} "
            f"Revisa tus notificaciones en tiempo real para confirmar la operación."
        )
        insights = [f"Outflow of {amount} {context.currency}", *profile[:2]]
        if is_high:
            insights.append("Amount above bank threshold; verify with 2FA")
        actions = [
            "Activar alertas de seguridad en tiempo real",
            "Confirmar operaciones con token/huella",
            "Revisar el estado de cuenta semanalmente",
        ]
    elif context.type == "TRANSFER":
        category = "transfer"
        priority = "MEDIUM" if is_high else "LOW"
        message = (
            f"Transferencia de {amount:,.2f} {context.currency} registrada. "
            f"Puedes programar un ahorro automático del 5% para llegar a tu meta."
        )
        insights = ["P2P movement", *profile[:2]]
        if is_high:
            insights.append("P2P amount above usual range; confirm beneficiary")
        actions = [
            "Programar ahorro automático del 5%",
            "Verificar que los beneficiarios registrados son conocidos",
        ]
    else:  # DEPOSIT (y cualquier entrada de dinero)
        category = "risk" if segment in ("PREMIUM", "CORPORATE") and is_high else "savings"
        priority = "MEDIUM"
        message = (
            f"Abonaste {amount:,.2f} {context.currency}. "
            f"Conserva el hábito: estás cerca de tu meta de ahorro colchón de emergencia."
        )
        insights = ["Incoming credit", f"Emergency-fund target: {target} {context.currency}", *profile[:1]]
        actions = [
            f"Destinar el 20% al fondo de emergencia (meta {target} {context.currency})",
            "Configurar una transferencia automática a la cuenta de ahorro",
        ]
        if segment in ("PREMIUM", "CORPORATE"):
            actions.append("Revisar alternativas de inversión: plazo fijo o fondos mutuos")

    return {
        "category": category,
        "priority": priority,
        "message": message,
        "insights": insights,
        "actions": actions,
    }


def _extract_json(text: str) -> dict:
    snippet = text.strip()
    start, end = snippet.find("{"), snippet.rfind("}")
    if start >= 0 and end > start:
        snippet = snippet[start:end + 1]
    return json.loads(snippet)


def _normalize(data: dict) -> dict:
    """Aplica el contrato estandarizado a cualquier salida (modelo o mock)."""
    category = str(data.get("category", "generic")).strip().lower().replace(" ", "_")
    if category not in CATEGORIES:
        category = "generic"
    priority = str(data.get("priority", "MEDIUM")).strip().upper()
    if priority not in PRIORITIES:
        priority = "MEDIUM"
    message = str(data.get("message", "")).strip()
    if not message:
        message = "Revisa tu estado financiero; contáctanos si necesitas ayuda personalizada."
    message = message[:400]
    insights = [str(i).strip()[:120] for i in data.get("insights", []) if str(i).strip()]
    actions = [str(a).strip()[:120] for a in data.get("actions", []) if str(a).strip()]
    if not actions:
        actions = ["Revisar el estado de la cuenta", "Configurar alertas de notificación"]
    return {
        "category": category,
        "priority": priority,
        "message": message,
        "insights": insights[:10],
        "actions": actions[:5],
    }


class Options:
    def __init__(self, api_key: str | None, model: str, timeout_seconds: float = 30.0,
                 connect_timeout_seconds: float = 8.0, request_budget_seconds: float = 15.0) -> None:
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.connect_timeout_seconds = connect_timeout_seconds
        self.request_budget_seconds = request_budget_seconds


class RecommendationProvider:
    def __init__(self, options: Options) -> None:
        self._options = options
        # Timeouts por fase: la CONEXIÓN falla rápido (si OpenRouter no responde,
        # el agente cae a mock en segundos), mientras la LECTURA mantiene un
        # margen realista para el modelo (generación de texto ~segundos).
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(
                connect=options.connect_timeout_seconds,
                write=options.timeout_seconds,
                read=options.timeout_seconds,
                pool=options.timeout_seconds,
            ),
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def generate(self, request: AiRecommendationRequest) -> AiRecommendationResponse:
        if not self._options.api_key:
            return self._fallback(request, reason="no OPENROUTER_API_KEY configured")
        try:
            # Presupuesto TOTAL de la llamada: si el proveedor no responde en
            # `request_budget_seconds`, caemos a mock. Garantiza que el endpoint
            # SIEMPRE conteste en un tiempo acotado (el flujo transaccional es
            # asíncrono y jamás debe quedarse colgado esperando al modelo).
            data = await asyncio.wait_for(
                self._call_openrouter(request), timeout=self._options.request_budget_seconds
            )
            source, model = "openrouter", self._options.model
            logger.info("ai_recommendation source='openrouter' model='%s' category='%s'", model, data.get("category"))
        except (asyncio.TimeoutError, TimeoutError) as exc:
            logger.warning("ai_recommendation timeout_after=%.0fs fallback_to_mock", self._options.request_budget_seconds)
            return self._fallback(request, reason=f"openrouter timeout after {self._options.request_budget_seconds:g}s")
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
            "temperature": 0.2,
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
        normalized = _normalize(data)
        return AiRecommendationResponse(
            customer_id=request.context.customer_id,
            category=normalized["category"],
            message=normalized["message"],
            priority=normalized["priority"],
            insights=normalized["insights"],
            actions=normalized["actions"],
            model=model,
            source=source,
        )