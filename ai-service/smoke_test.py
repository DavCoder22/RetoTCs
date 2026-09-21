"""Smoke test del contrato de decisión del agente de IA.

Verifica lo que el reto espera que la IA conteste: una **decisión financiera
personalizada** (DTO de salida `AiRecommendationResponse`) a partir del
contexto transaccional (DTO de entrada), generada sin bloquear nada.

Se ejecuta contra un servidor activo (uvicorn) y en CI (GitHub Actions):
    EXPECTED_SOURCE=openrouter  -> usa el secret OPENROUTER_API_KEY del repo
    EXPECTED_SOURCE=mock        -> sin API key, valida el fallback funcional
"""
import json
import os
import sys
import urllib.request

BASE = os.getenv("AI_BASE_URL", "http://127.0.0.1:18081").rstrip("/")
EXPECTED_SOURCE = os.getenv("EXPECTED_SOURCE", "mock")

CONTEXT = {
    "context": {
        "transactionId": "00000000-0000-0000-0000-000000000010",
        "customerId": "c0000000-0000-0000-0000-000000000001",
        "customerSegment": "RETAIL",
        "accountAgeDays": 730,
        "type": "DEPOSIT",
        "amount": 500.00,
        "currency": "PEN",
        "creditAccountNumber": "4651001245879632145210",
        "balanceAfterCredit": 6400.50,
        "reference": "smoke-gha",
        "createdAt": "2026-09-21T02:00:00Z",
    }
}

REQUIRED = {
    "recommendationId",
    "customerId",
    "category",
    "message",
    "priority",
    "insights",
    "actions",
    "model",
    "source",
    "generatedAt",
}

CATEGORIES = {"savings", "spending", "transfer", "risk", "generic"}
PRIORITIES = {"LOW", "MEDIUM", "HIGH"}


def main() -> int:
    request = urllib.request.Request(
        f"{BASE}/internal/recommendations",
        data=json.dumps(CONTEXT).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as resp:
            body = json.loads(resp.read())
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL request: {exc}")
        return 1

    missing = REQUIRED - set(body)
    if missing:
        print(f"FAIL schema: {sorted(missing)}")
        return 1
    if body["category"] not in CATEGORIES:
        print(f"FAIL category={body['category']!r}")
        return 1
    if body["priority"] not in PRIORITIES:
        print(f"FAIL priority={body['priority']!r}")
        return 1
    if not body["message"]:
        print("FAIL message vacío")
        return 1
    if not body["actions"] or len(body["actions"]) > 5:
        print(f"FAIL actions inválidas: {body['actions']!r}")
        return 1
    if body["source"] != EXPECTED_SOURCE:
        print(f"FAIL source={body['source']!r} esperado={EXPECTED_SOURCE!r}")
        return 1

    print("OK decision de IA:", json.dumps(body, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())