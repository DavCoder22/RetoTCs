package com.smartbancs.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TransactionBatchRequest(
        @NotBlank @Schema(description = "Identificador del lote aportado por el emisor (p. ej. nombre del CSV crudo).")
        String batchId,
        @NotEmpty @Size(max = 1000, message = "batch items must not exceed 1000")
        @Schema(description = "Lote de transacciones ya limpias y homologadas por el proceso ETL.")
        List<@Valid BatchTransactionItem> items) {
}