package com.smartbancs.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiRecommendationResponse(
        UUID recommendationId,
        UUID customerId,
        @Schema(description = "Categoría normalizada: savings | spending | transfer | risk | generic.")
        String category,
        @Schema(description = "Texto legible de la recomendación (qué hacer, como el banco).")
        String message,
        @Schema(description = "LOW | MEDIUM | HIGH.")
        String priority,
        List<String> insights,
        @Schema(description = "Acciones concretas sugeridas para el cliente (≤5).")
        List<String> actions,
        @Schema(description = "Modelo o heurística que generó la recomendación.")
        String model,
        @Schema(description = "openrouter | mock.")
        String source,
        Instant generatedAt) {
}