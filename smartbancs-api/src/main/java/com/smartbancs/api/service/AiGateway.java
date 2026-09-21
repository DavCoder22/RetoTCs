package com.smartbancs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartbancs.api.dto.AiRecommendationContext;
import com.smartbancs.api.dto.AiRecommendationRequest;
import com.smartbancs.api.dto.AiRecommendationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final String endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long timeoutMillis;

    public AiGateway(@Value("${smartbancs.ai.base-url:http://localhost:8081}") String baseUrl,
                     @Value("${smartbancs.ai.timeout-ms:30000}") long timeoutMs,
                     ObjectMapper objectMapper) {
        this.endpoint = baseUrl + "/internal/recommendations";
        this.timeoutMillis = timeoutMs;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public AiRecommendationResponse recommend(AiRecommendationContext context) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(new AiRecommendationRequest(context));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("ai_service http {} -> {}", response.statusCode(), response.body());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "ai service error: " + response.statusCode() + " " + response.body());
            }
            return objectMapper.readValue(response.body(), AiRecommendationResponse.class);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("ai_service interrupted: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ai service interrupted: " + ex.getMessage());
        } catch (Exception ex) {
            log.warn("ai_service unreachable: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ai service unreachable: " + ex.getMessage());
        }
    }
}