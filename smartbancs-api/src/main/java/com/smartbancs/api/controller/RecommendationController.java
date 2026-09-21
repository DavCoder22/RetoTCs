package com.smartbancs.api.controller;

import com.smartbancs.api.dto.RecommendationResponse;
import com.smartbancs.api.service.RecommendationService;
import com.smartbancs.infra.repository.CustomerJpaRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Tag(name = "Recommendations", description = "Recomendaciones IA personalizadas (generadas de forma asíncrona).")
@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final CustomerJpaRepository customerRepository;

    public RecommendationController(RecommendationService recommendationService,
                                    CustomerJpaRepository customerRepository) {
        this.recommendationService = recommendationService;
        this.customerRepository = customerRepository;
    }

    @Operation(summary = "Listar recomendaciones de un cliente",
            description = "Devuelve las recomendaciones que el agente de IA (Python/OpenRouter) generó "
                    + "de forma asíncrona tras sus transacciones.")
    @ApiResponse(responseCode = "200", description = "Lista de recomendaciones (vacía si aún no hay).")
    @ApiResponse(responseCode = "404", description = "El cliente no existe.")
    @GetMapping
    public List<RecommendationResponse> list(
            @Parameter(description = "Id del cliente")
            @RequestParam UUID customerId,
            @Schema(description = "Máximo de recomendaciones", defaultValue = "20")
            @RequestParam(defaultValue = "20") int limit) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "customer not found");
        }
        int capped = Math.min(Math.max(limit, 1), 100);
        return recommendationService.listByCustomer(customerId, capped);
    }
}