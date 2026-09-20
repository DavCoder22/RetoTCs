package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.CustomerSegment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 150) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "María Presentación Gómez", description = "Nombre completo del cliente.")
        String fullName,
        @NotBlank @Email @Size(max = 255) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "maria.demo@example.com", description = "Correo único del cliente.")
        String email,
        @NotNull @Schema(example = "PREMIUM", description = "Segmento: RETAIL | PREMIUM | CORPORATE.")
        CustomerSegment segment) {
}