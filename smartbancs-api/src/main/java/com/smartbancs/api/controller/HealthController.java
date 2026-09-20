package com.smartbancs.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@Tag(name = "Health", description = "Probe de disponibilidad del servicio.")
@RestController
public class HealthController {

    @Operation(summary = "Estado del servicio", description = "Confirma que la API está disponible.")
    @ApiResponse(responseCode = "200", description = "Servicio en línea (status UP).")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "smartbancs-api",
                "timestamp", Instant.now().toString()
        );
    }
}