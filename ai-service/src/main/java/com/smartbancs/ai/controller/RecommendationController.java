package com.smartbancs.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Recommendations", description = "Generación de recomendaciones personalizadas (asíncrono).")
@RestController
@RequestMapping("/internal/recommendations")
public class RecommendationController {

    @Operation(summary = "Generar recomendación (stub)",
            description = "Pendiente de implementar: devuelve 501 hasta integrar el proveedor de IA.")
    @ApiResponse(responseCode = "501", description = "Not Implemented — servicio de IA todavía en stub.")
    @PostMapping
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, String> generate(@Schema(description = "Finalidad del usuario y contexto transaccional", example = "{\"customerId\":\"c0000000-0000-0000-0000-000000000001\"}")
                                        @RequestBody String payload) {
        return Map.of("status", "not_implemented");
    }
}