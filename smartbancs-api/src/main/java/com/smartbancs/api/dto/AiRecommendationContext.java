package com.smartbancs.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AiRecommendationContext(
        @Schema(description = "Id de la transacción asentada.")
        UUID transactionId,
        @Schema(description = "Cliente asociado a la operación (cuenta débito, si no, crédito).")
        UUID customerId,
        @Schema(example = "RETAIL", description = "Segmento del cliente: RETAIL | PREMIUM | CORPORATE.")
        String customerSegment,
        @Schema(description = "Antigüedad de la cuenta primaria en días (info general del cliente).")
        Long accountAgeDays,
        @Schema(description = "DEPOSIT | WITHDRAWAL | TRANSFER | PAYMENT.")
        String type,
        @Schema(example = "2500.00", description = "Monto en la moneda de la cuenta.")
        BigDecimal amount,
        @Schema(example = "PEN", description = "ISO-4217.")
        String currency,
        String debitAccountNumber,
        String creditAccountNumber,
        @Schema(description = "Saldo posterior a la operación de la cuenta débito.")
        BigDecimal balanceAfterDebit,
        @Schema(description = "Saldo posterior a la operación de la cuenta crédito.")
        BigDecimal balanceAfterCredit,
        String reference,
        Instant createdAt) {
}