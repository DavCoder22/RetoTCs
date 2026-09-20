package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateAccountRequest(
        @NotNull @Schema(example = "c0000000-0000-0000-0000-000000000001", description = "Cliente dueño de la cuenta (id seed funcional; usa el de tu POST /customers para el journey completo).")
        UUID customerId,
        @NotBlank @Size(max = 34) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "4651DEMO00000000001", description = "Número de cuenta único (cámbialo si haces otro run).")
        String accountNumber,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "PEN", description = "Moneda ISO 4217 (PEN | USD).")
        String currency,
        @NotNull @DecimalMin(value = "0.00") @Schema(example = "0", description = "Debe ser 0: el saldo nace con un DEPOSIT (de lo contrario 422).")
        BigDecimal balance,
        @NotNull @DecimalMin(value = "0.00") @Schema(example = "10000", description = "Límite de transferencia diaria.")
        BigDecimal dailyTransferLimit,
        @NotNull @Schema(example = "ACTIVE", description = "Estado: ACTIVE | FROZEN.")
        AccountStatus status) {
}