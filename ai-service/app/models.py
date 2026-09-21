"""SmartBancs AI — contrato de datos (DTOs) para la toma de decisiones.

Estos modelos son el *contrato* que el microservicio principal (smartbancs-api,
Java) envía y recibe. Coinciden 1:1 con los DTOs del API para que el agente de IA
tome decisiones sobre el contexto transaccional sin acoplarse al dominio JVM.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_serializer, field_validator
from pydantic.alias_generators import to_camel

CAMEL_ALIASES = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class TxnContext(BaseModel):
    """Contexto transaccional (DTO) usado por el agente para decidir."""

    model_config = ConfigDict(extra="allow", **CAMEL_ALIASES)

    transaction_id: Optional[uuid.UUID] = Field(default=None, description="Id de la transacción asentada")
    customer_id: uuid.UUID = Field(description="Cliente (cédula lógica) asociado a la operación")
    type: str = Field(description="DEPOSIT | WITHDRAWAL | TRANSFER | PAYMENT")
    amount: Decimal = Field(gt=0, description="Monto en la moneda de la cuenta")
    currency: str = Field(min_length=3, max_length=3, description="ISO-4217 (PEN/USD)")
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
    """Solicitud de generación de recomendación (DTO de entrada)."""

    model_config = CAMEL_ALIASES

    context: TxnContext = Field(description="Contexto transaccional homogeneizado")


class AiRecommendationResponse(BaseModel):
    """Recomendación generada por el agente (DTO de salida)."""

    model_config = CAMEL_ALIASES

    recommendation_id: uuid.UUID = Field(default_factory=uuid.uuid4)
    customer_id: uuid.UUID
    category: str = Field(description="Categoría de la recomendación, p. ej. savings | risk | transfer | generic")
    message: str = Field(description="Texto legible para el cliente")
    priority: str = Field(description="LOW | MEDIUM | HIGH", pattern="^(LOW|MEDIUM|HIGH)$")
    insights: list[str] = Field(default_factory=list, description="Razonamiento corto de apoyo")
    model: str = Field(description="Modelo que generó la recomendación (proveedor) o 'mock'")
    source: str = Field(default="mock", description="openrouter | mock")
    generated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @field_serializer("generated_at")
    def _serialize_iso_utc(self, dt: datetime) -> str:
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")