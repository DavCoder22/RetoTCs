package com.smartbancs.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record TransactionBatchResponse(
        @Schema(description = "Identificador del lote procesado.")
        String batchId,
        int received,
        int accepted,
        int rejected,
        @Schema(description = "Detalle por ítem: la ingesta ETL tolera errores parciales y los reporta.")
        List<BatchItemResult> results) {
}