package com.smartbancs.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record BatchItemResult(
        @Schema(description = "Posición 1-based dentro del lote.")
        int index,
        @Schema(description = "true si el ítem se asentó; false si fue rechazado por una regla de negocio o dato inválido.")
        boolean accepted,
        @Schema(description = "Id de la transacción asentada (solo si accepted=true).")
        UUID transactionId,
        @Schema(description = "Motivo del rechazo (solo si accepted=false).")
        String error) {
}