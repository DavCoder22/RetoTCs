package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record BatchTransactionItem(
        @NotNull @Schema(example = "DEPOSIT", description = "DEPOSIT | WITHDRAWAL | TRANSFER | PAYMENT.")
        TransactionType type,
        @NotNull @Positive @Schema(example = "2500.00", description = "Monto en la moneda de la cuenta (ya normalizado por el ETL).")
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "PEN")
        String currency,
        @Schema(description = "Número de cuenta débito (clave de negocio, no UUID). El batch resuelve el accountId interno.")
        String debitAccountNumber,
        @Schema(example = "4651001245879632145203", description = "Número de cuenta crédito/acreditada (clave de negocio).")
        String creditAccountNumber,
        @Schema(description = "Idempotencia: si se repite en el lote, la segunda ocurrencia no duplica.")
        String idempotencyKey,
        @Schema(example = "Abono nómina", description = "Referencia libre del movimiento.")
        String reference) {
}