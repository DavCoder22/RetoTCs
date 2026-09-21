package com.smartbancs.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.smartbancs.domain.enums.RecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record RecommendationResponse(
        UUID id,
        UUID customerId,
        @Schema(description = "Tipo de recomendación (p. ej. AI).")
        String type,
        @Schema(description = "Payload JSON con la recomendación generada.")
        String payload,
        RecommendationStatus status,
        Instant createdAt,
        Instant updatedAt) {
}