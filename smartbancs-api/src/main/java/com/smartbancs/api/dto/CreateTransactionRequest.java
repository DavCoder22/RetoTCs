package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull @Schema(example = "DEPOSIT", description = "DEPOSIT | WITHDRAWAL | TRANSFER.")
        TransactionType type,
        @NotNull @Positive @Schema(example = "1500", description = "Monto en la moneda de la cuenta.")
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "PEN")
        String currency,
        @Schema(description = "DEPOSIT/WITHDRAWAL usan una sola cuenta; TRANSFER marca el débito aquí.")
        UUID debitAccountId,
        @Schema(example = "a0000000-0000-0000-0000-000000000001", description = "Id de la cuenta destino/acreditada (seed funcional; reemplázalo por el account_id de tu journey).")
        UUID creditAccountId,
        @Schema(example = "demo-2026-01", description = "Idempotencia: mismo key = mismo resultado, no duplica.")
        String idempotencyKey,
        @Schema(example = "Depósito inicial", description = "Referencia libre del movimiento.")
        String reference) {
}