"""SmartBancs AI — contrato de datos (DTOs) para la toma de decisiones.

Estos modelos son el *contrato* que el microservicio principal (smartbancs-api,
Java) envía y recibe. Coinciden 1:1 con los DTOs del API para que el agente de IA
tome decisiones sobre el contexto transaccional sin acoplarse al dominio JVM.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Annotated, Optional

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_serializer, field_validator, model_validator
from pydantic.alias_generators import to_camel

CAMEL_ALIASES = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class TxnContext(BaseModel):
    """Contexto transaccional (DTO) usado por el agente para decidir."""

    model_config = ConfigDict(extra="allow", **CAMEL_ALIASES)

    transaction_id: Optional[uuid.UUID] = Field(default=None, description="Id de la transacción asentada")
    customer_id: uuid.UUID = Field(description="Cliente (cédula lógica) asociado a la operación")
    customer_segment: Optional[str] = Field(default=None, description="RETAIL | PREMIUM | CORPORATE (info general del cliente)")
    account_age_days: Optional[int] = Field(default=None, ge=0, description="Antigüedad de la cuenta primaria en días")
    type: str = Field(default="TRANSFER", description="DEPOSIT | WITHDRAWAL | TRANSFER | PAYMENT (default TRANSFER)")
    amount: Decimal = Field(default=Decimal("100.0"), gt=0, description="Monto en la moneda de la cuenta (default 100.00)")
    currency: str = Field(default="PEN", min_length=3, max_length=3, description="ISO-4217 (PEN/USD, default PEN)")
    debit_account_number: Optional[str] = Field(default=None, description="Cuenta débito (número de negocio)")
    credit_account_number: Optional[str] = Field(default=None, description="Cuenta crédito (número de negocio)")
    balance_after_debit: Optional[Decimal] = Field(default=None, description="Saldo posterior de la cuenta débito")
    balance_after_credit: Optional[Decimal] = Field(default=None, description="Saldo posterior de la cuenta crédito")
    reference: Optional[str] = Field(default=None, description="Referencia libre del movimiento")
    created_at: Optional[datetime] = Field(default=None, description="Momento de asentamiento")

    @field_validator("currency")
    @classmethod
    def _currency_iso(cls, v: str) -> str:
        if not v.isalpha():
            raise ValueError("currency must be 3-letter ISO code")
        return v.upper()


class AiRecommendationRequest(BaseModel):
    """Solicitud de generación de recomendación (DTO de entrada).

    Acepta el contrato oficial del smartbancs-api (envuelto en ``context``)
    o el mismo contexto de forma plana (compatibilidad con pruebas directas
    y/o clientes que serialicen el TxnContext sin el wrapper).
    """

    model_config = ConfigDict(
        **CAMEL_ALIASES,
        json_schema_extra={
            "example": {
                "customerId": "9e8f7d6c-5b4a-4a3b-9c2d-1e0f3a5b7c9d",
            }
        },
    )

    context: TxnContext = Field(description="Contexto transaccional homogeneizado (solo customerId es obligatorio)")

    @model_validator(mode="before")
    @classmethod
    def _accept_wrapped_or_flat(cls, data):
        """Normaliza la entrada: envuelve el contexto plano en ``context``.

        - ``{"context": {...}}``  -> pase directo (contrato del api Java).
        - ``{...cliente plano...}`` -> se envuelve para no romper llamadas
          directas con la misma forma del AiRecommendationContext.
        """
        if isinstance(data, dict) and "context" not in data:
            return {"context": data}
        return data


class AiRecommendationResponse(BaseModel):
    """Recomendación generada por el agente (DTO de salida estandarizado)."""

    model_config = CAMEL_ALIASES

    recommendation_id: uuid.UUID = Field(default_factory=uuid.uuid4)
    customer_id: uuid.UUID
    category: str = Field(
        description="Categoría normalizada: savings | spending | transfer | risk | generic",
        pattern="^(savings|spending|transfer|risk|generic)$",
    )
    message: str = Field(min_length=1, max_length=400, description="Qué hacer, como lo diría la entidad bancaria")
    priority: str = Field(description="LOW | MEDIUM | HIGH", pattern="^(LOW|MEDIUM|HIGH)$")
    insights: list[Annotated[str, StringConstraints(max_length=120)]] = Field(
        default_factory=list, max_length=10, description="Razonamiento corto de apoyo"
    )
    actions: list[Annotated[str, StringConstraints(max_length=120)]] = Field(
        default_factory=list, max_length=5, description="Acciones concretas sugeridas para el cliente"
    )
    model: str = Field(description="Modelo que generó la recomendación (proveedor) o 'mock'")
    source: str = Field(default="mock", description="openrouter | mock")
    generated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @field_serializer("generated_at")
    def _serialize_iso_utc(self, dt: datetime) -> str:
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")